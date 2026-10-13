import { computed, onBeforeUnmount, reactive, watch } from 'vue'
import { chooseFullAutoBid, chooseFullAutoDouble, chooseFullAutoMove } from '@/ai/douzero-fullauto.js'
import { enumerateLegalMoves } from '@/ai/legal-moves.js'
import { chooseDoubleDecision, chooseLandlordDecision } from '@/ai/policy.js'
import { settlementIdentity, settlementOutcome } from '@/ai/settlement.js'
import {
  immediateWinningMove,
  preserveLandlordSingleControl,
  urgentLandlordResponse,
} from '@/ai/tactics.js'

const SUPPORT_MISSION_ID = 1563
const SUPER_DOUBLE_ITEM_ID = 1030108
const AI_LOG_STORAGE_KEY = 'happy4j.ai.runtime.logs.v1'
const LAST_SETTLEMENT_STORAGE_KEY = 'happy4j.ai.lastSettlement.v1'
const COMMANDS_WITH_UNCERTAIN_DELIVERY = new Set(['PLAY', 'ORDER', 'MULTIPLE', 'SUPER_MULTIPLE', 'READY'])
const REASON_LABELS = Object.freeze({
  RULE_FORCED_PASS: '没有合法压制动作，按规则不出',
  WP_STRATEGIC_PASS: '欢乐斗地主 WP 模型主动选择不出',
  WP_MAX_Q: '欢乐斗地主 WP 模型选择最高评分动作',
  TACTICAL_WIN_NOW: '检测到可以立即出完，执行制胜动作',
  TACTICAL_BLOCK_LANDLORD: '地主残局警戒：有合法压制牌时不盲目过牌',
  TACTICAL_PRESERVE_SINGLE: '地主残局警戒：散单保留最大控牌',
  ONLY_LEGAL_MOVE: '当前只有一个合法出牌动作',
  MODEL_FALLBACK: '模型暂不可用，执行合法保底动作',
  INCOMPLETE_STATE_HEURISTIC: '公开牌历史不完整，使用保牌型残局策略',
  OPENAI_DECISION: 'OpenAI兼容大模型完成决策',
  FULLAUTO_RESNET_MAX_Q: 'FullAuto ResNet 选择最高评分动作',
  FULLAUTO_STRATEGIC_PASS: 'FullAuto ResNet 判断不出收益更高',
  FULLAUTO_ACTIVE_RESPONSE: 'FullAuto 主动压制策略选择第二候选',
  FULLAUTO_CONTROL_PATH: 'FullAuto 检测到连续控牌路径',
  FULLAUTO_WIN_NOW: 'FullAuto 检测到可以立即出完',
})

const WALLET_READY_PHASES = new Set(['HALL_CONNECTED', 'ENTERING_ROOM', 'AT_TABLE', 'READY', 'PLAYING'])

export function canRefreshWallet(session) {
  return WALLET_READY_PHASES.has(session?.phase)
}

export function isInsufficientBeansError(error) {
  const message = String(error?.message || error || '')
  return /10810|欢乐豆太少|欢乐豆不足/.test(message)
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

export function tableFingerprint(table, beans) {
  if (!table) return `none:${beans}`
  const historyTail = (table.actionHistory || [])
    .slice(-3)
    .map(
      (entry) =>
        `${entry.occurredAt || ''}:${entry.seatId}:${entry.action}:${(entry.cards || []).map((card) => card.code).join('.')}`,
    )
    .join('|')
  return [
    table.sceneId,
    table.roomId,
    table.tableId,
    table.stage,
    table.turnSeatId,
    table.landlordSeatId,
    table.multiplier,
    table.trusted,
    (table.availableActions || []).join(','),
    (table.hand || []).map((card) => card.code).join(','),
    table.lastPlaySeatId,
    table.lastPlayType,
    table.lastPlayRank,
    (table.lastPlay || []).map((card) => card.code).join(','),
    table.actionHistory?.length || 0,
    historyTail,
    table.settlement?.players?.length || 0,
    beans,
  ].join(':')
}

function loadLogs() {
  try {
    return JSON.parse(localStorage.getItem(AI_LOG_STORAGE_KEY) || '[]').slice(-300)
  } catch {
    return []
  }
}

function persistLogs(logs) {
  try {
    localStorage.setItem(AI_LOG_STORAGE_KEY, JSON.stringify(logs.slice(-300)))
  } catch {
    // Runtime logging continues even when storage is unavailable.
  }
}

export function useGameAi(client) {
  const initialLogs = loadLogs()
  const settlementLogs = initialLogs.filter(
    (entry) => entry.phase === 'SETTLEMENT' && entry.level === 'INFO' && entry.detail?.auditVersion === 1,
  )
  const runtime = reactive({
    active: false,
    busy: false,
    state: 'IDLE',
    modelState: 'IDLE',
    status: '配置后点击开始',
    decision: '',
    rounds: settlementLogs.length,
    wins: settlementLogs.filter((entry) => entry.detail?.won === true).length,
    losses: settlementLogs.filter((entry) => entry.detail?.won === false).length,
    unknown: settlementLogs.filter((entry) => entry.detail?.won == null).length,
    lastError: '',
    plays: initialLogs.filter((entry) => entry.phase === 'INFERENCE' && entry.level === 'PLAY').length,
    passes: initialLogs.filter((entry) => entry.phase === 'INFERENCE' && entry.level === 'PASS').length,
    strategy: 'FullAuto ResNet 高胜率策略',
    providerTesting: false,
    logs: initialLogs,
  })
  const config = computed(() => client.state.aiConfig || null)
  let timer = null
  let destroyed = false
  let lastActionKey = ''
  let missionRefreshKey = ''
  let settlementKey = (() => {
    try {
      return localStorage.getItem(LAST_SETTLEMENT_STORAGE_KEY) || ''
    } catch {
      return ''
    }
  })()
  let lastWalletAttemptAt = 0
  let lastObservedState = ''
  let logSequence = runtime.logs.at(-1)?.id || 0

  function log(level, phase, message, detail = null) {
    runtime.logs.push({
      id: ++logSequence,
      at: new Date().toISOString(),
      level,
      phase,
      message,
      detail,
    })
    if (runtime.logs.length > 300) runtime.logs.splice(0, runtime.logs.length - 300)
    persistLogs(runtime.logs)
  }

  function clearLogs() {
    runtime.logs.splice(0)
    persistLogs(runtime.logs)
    runtime.rounds = 0
    runtime.wins = 0
    runtime.losses = 0
    runtime.unknown = 0
    log('INFO', 'CONTROL', '运行日志已清空')
  }

  function setModelState(state, status) {
    if (runtime.modelState !== state || runtime.status !== status) log('INFO', 'MODEL', status, { state })
    runtime.modelState = state
    runtime.status = status
  }

  function schedule(wait = 260) {
    clearTimeout(timer)
    if (!runtime.active || destroyed) return
    timer = setTimeout(run, wait)
  }

  async function save(values) {
    const saved = await client.command('AI_CONFIG_SAVE', values)
    client.state.aiConfig = saved
    return saved
  }

  function openAiEnabled() {
    return config.value?.inferenceEngine === 'OPENAI'
  }

  async function testProvider(values) {
    runtime.providerTesting = true
    try {
      const saved = await save({ ...values, enabled: Boolean(config.value?.enabled) })
      runtime.strategy = saved.inferenceEngine === 'OPENAI' ? '大模型胜率优先' : 'FullAuto ResNet 高胜率策略'
      const result = await client.command('AI_PROVIDER_TEST')
      log('SUCCESS', 'MODEL_TEST', `大模型连接成功 · ${result.model}`, result)
      return result
    } catch (error) {
      log('ERROR', 'MODEL_TEST', error.message)
      throw error
    } finally {
      runtime.providerTesting = false
    }
  }

  async function start(values) {
    const saved = await save({ ...values, enabled: true })
    runtime.active = true
    runtime.state = 'RUNNING'
    runtime.strategy = saved.inferenceEngine === 'OPENAI' ? '大模型胜率优先' : 'FullAuto ResNet 高胜率策略'
    runtime.status = 'AI 正在接管牌局'
    runtime.lastError = ''
    lastActionKey = ''
    log('INFO', 'CONTROL', 'AI 托管已启动', {
      strategy: runtime.strategy,
      sceneId: saved.sceneId,
      autoChangeTable: saved.autoChangeTable,
      autoClaimBeans: saved.autoClaimBeans,
      stopAtBeans: saved.stopAtBeans,
      stopBelowBeans: saved.stopBelowBeans,
    })
    client.screen.value = client.table.value?.sceneId ? 'room' : 'lobby'
    schedule(50)
    return saved
  }

  async function stop(reason = 'AI 已停止', persist = true) {
    runtime.active = false
    runtime.busy = false
    runtime.state = 'STOPPED'
    runtime.status = reason
    runtime.decision = ''
    clearTimeout(timer)
    log('INFO', 'CONTROL', reason)
    if (persist && config.value?.sceneId) {
      try {
        await save({ ...config.value, enabled: false })
      } catch {
        // The local stop remains effective while the connection recovers.
      }
    }
  }

  async function act(key, command, payload, status, wait = 650) {
    if (lastActionKey === key) return false
    lastActionKey = key
    runtime.status = status
    log('SEND', command, status, payload)
    await delay(wait)
    if (!runtime.active) return false
    if (client.state.preview) {
      log('SUCCESS', command, `${status} · 预览已记录`)
      return true
    }
    try {
      await client.command(command, payload)
      log('SUCCESS', command, `${status} · 服务器已接收`)
      return true
    } catch (error) {
      // A timeout does not prove that the game server rejected the command.
      // Avoid re-sending a non-idempotent action until a new table snapshot arrives.
      const uncertain = COMMANDS_WITH_UNCERTAIN_DELIVERY.has(command)
      if (!uncertain) lastActionKey = ''
      runtime.lastError = error.message
      runtime.status = uncertain ? '操作状态未知，等待服务器更新牌桌' : '操作没有完成，正在重新同步'
      log('ERROR', command, runtime.status, { message: error.message, retrySuppressed: uncertain })
      if (['CHANGE_TABLE', 'MODE_ENTER', 'READY'].includes(command) && isInsufficientBeansError(error)) {
        log('INFO', 'LIMIT', '服务器确认欢乐豆不足，停止自动操作，避免重复请求')
        await stop('欢乐豆不足，AI 已停止，请补充欢乐豆后手动重新开始')
        return false
      }
      schedule(1800)
      return false
    }
  }

  async function refreshWallet(key) {
    return act(`${key}:wallet`, 'WALLET_REFRESH', {}, '正在同步欢乐豆余额', 100)
  }

  async function handleBeanLimits(session, selectedConfig, fingerprint) {
    const walletReady =
      Boolean(session.walletRefreshedAt) && !String(session.walletRefreshedAt).startsWith('1970-')
    if (!walletReady) {
      if (!canRefreshWallet(session)) {
        runtime.status = '大厅尚未连接，暂停余额刷新'
        return true
      }
      // Avoid hammering a disconnected lobby when the wallet query repeatedly fails.
      if (Date.now() - lastWalletAttemptAt >= 15000) {
        lastWalletAttemptAt = Date.now()
        log('INFO', 'WALLET', '余额尚未同步，先刷新欢乐豆')
        await refreshWallet(fingerprint)
      } else runtime.status = '等待大厅连接恢复并同步余额'
      return true
    }
    const beans = Number(session.beans || 0)
    const belowLimit = selectedConfig.stopBelowBeans > 0 && beans < selectedConfig.stopBelowBeans
    const support = session.missions?.find((mission) => Number(mission.missionId) === SUPPORT_MISSION_ID)
    if (selectedConfig.autoClaimBeans && beans < 2000 && (!support || belowLimit)) {
      const key = `${new Date().toISOString().slice(0, 10)}:${support?.periodFinishCount ?? 'missing'}`
      if (!support && missionRefreshKey !== key) {
        missionRefreshKey = key
        log('INFO', 'MISSION', '正在同步每日欢乐豆补助状态')
        await act(`${fingerprint}:missions`, 'MISSIONS_REFRESH', {}, '正在检查欢乐豆补助', 100)
        return true
      }
      const canClaim =
        support && Number(support.periodFinishCount || 0) < 4 && (support.claimable || support.status === 3)
      if (canClaim) {
        log('INFO', 'MISSION', '余额较低，自动领取欢乐豆补助', {
          beans,
          claimedToday: support.periodFinishCount,
        })
        await act(
          `${fingerprint}:claim:${support.periodFinishCount}`,
          'MISSION_CLAIM',
          { missionId: SUPPORT_MISSION_ID },
          '正在领取欢乐豆补助',
          250,
        )
        await refreshWallet(`${fingerprint}:claimed`)
        return true
      }
    }
    if (selectedConfig.stopAtBeans > 0 && beans >= selectedConfig.stopAtBeans) {
      log('INFO', 'LIMIT', '达到目标欢乐豆，停止自动对局', { beans, limit: selectedConfig.stopAtBeans })
      await stop(`欢乐豆已达到 ${selectedConfig.stopAtBeans.toLocaleString()}，AI 已停止`)
      return true
    }
    if (belowLimit) {
      log('INFO', 'LIMIT', '欢乐豆低于保护线，停止自动对局', { beans, limit: selectedConfig.stopBelowBeans })
      await stop(`欢乐豆少于 ${selectedConfig.stopBelowBeans.toLocaleString()}，AI 已停止`)
      return true
    }
    return false
  }

  async function decidePlay(table, fingerprint) {
    if (
      ![0, 1, 2].includes(Number(table.landlordSeatId)) ||
      ![0, 1, 2].includes(Number(table.seatId)) ||
      !table.hand?.length
    ) {
      runtime.status = '牌桌身份或手牌状态未完成同步，暂不自动出牌'
      return
    }
    const before = tableFingerprint(client.table.value, client.session.value?.beans)
    runtime.status = openAiEnabled() ? '大模型正在计算最优出牌' : 'DouZero FullAuto ResNet 正在计算最优出牌'
    const startedAt = performance.now()
    let providerFailure = null
    let result = openAiEnabled()
      ? await chooseOpenAiMove(table).catch(async (error) => {
          providerFailure = error.message
          log('ERROR', 'OPENAI', '大模型决策失败，已切换本地模型', { message: error.message })
          return chooseFullAutoMove(table, setModelState)
        })
      : await chooseFullAutoMove(table, setModelState)
    if (!runtime.active || before !== tableFingerprint(client.table.value, client.session.value?.beans)) {
      if (runtime.active) {
        log('INFO', 'INFERENCE', '等待决策期间牌局已变化，废弃过时动作', {
          elapsedMs: Math.round(performance.now() - startedAt),
          providerFailure,
        })
      }
      return
    }
    const legalMoves = enumerateLegalMoves(table)
    const urgent = urgentLandlordResponse(table, legalMoves, result.cards)
    if (urgent) {
      log('INFO', 'TACTICS', '地主即将走完，已将不出改为合法压制动作', {
        landlordCardsLeft: table.players?.find(
          (player) => Number(player.seatId) === Number(table.landlordSeatId),
        )?.cardCount,
        providerReason: result.providerReason,
        cards: urgent.map((card) => card.code),
      })
      result = { ...result, cards: urgent, reason: 'TACTICAL_BLOCK_LANDLORD', tacticalOverride: true }
    }
    const conservativeLead = preserveLandlordSingleControl(table, legalMoves, result.cards)
    if (conservativeLead) {
      log('INFO', 'TACTICS', '地主散单残局已保留最大牌，先出较小单张', {
        providerReason: result.providerReason,
        oldCards: result.cards.map((card) => card.code),
        cards: conservativeLead.map((card) => card.code),
      })
      result = {
        ...result,
        cards: conservativeLead,
        reason: 'TACTICAL_PRESERVE_SINGLE',
        tacticalOverride: true,
      }
    }
    const elapsed = Math.round(performance.now() - startedAt)
    const pass = result.cards.length === 0
    const strategy = result.fallback ? ' · 保底策略' : ''
    runtime.decision = pass
      ? `选择不出 · ${result.candidates} 个候选 · ${elapsed}ms${strategy}`
      : `选择 ${result.cards.length} 张牌 · ${result.candidates} 个候选 · ${elapsed}ms${strategy}`
    if (pass) runtime.passes += 1
    else runtime.plays += 1
    log(pass ? 'PASS' : 'PLAY', 'INFERENCE', REASON_LABELS[result.reason] || 'AI 决策完成', {
      role: result.role,
      modelFile: result.modelFile,
      reason: result.reason,
      decisionSource:
        result.tacticalOverride ||
        result.reason === 'ONLY_LEGAL_MOVE' ||
        result.reason === 'TACTICAL_WIN_NOW'
          ? 'RULES'
          : result.reason === 'OPENAI_DECISION'
            ? 'LUNA'
            : 'DOUZERO',
      providerFailure,
      tacticalOverride: Boolean(result.tacticalOverride),
      candidates: result.candidates,
      playableCandidates: result.playableCandidates,
      selectedCards: result.cards.map((card) => card.code),
      selectedLabel: result.cards
        .map((card) => card.rank)
        .filter(Boolean)
        .join(' '),
      selectedQ: result.q,
      qGap: result.qGap,
      modelError: result.errorMessage,
      stateQuality: result.stateQuality,
      elapsedMs: elapsed,
      top: result.top,
      providerModel: result.providerModel,
      providerLatencyMs: result.providerLatencyMs,
      providerTokens: result.providerTokens,
      providerReason: result.providerReason,
      providerFinishReason: result.providerFinishReason,
      confidence: result.confidence,
    })
    await act(
      `${fingerprint}:play:${result.cards.map((card) => card.code).join('-') || 'pass'}`,
      'PLAY',
      { cards: result.cards.map((card) => card.code), pass },
      pass ? '当前选择不出' : `准备打出 ${result.cards.length} 张牌`,
      420,
    )
  }

  async function chooseOpenAiMove(table) {
    const candidates = enumerateLegalMoves(table)
    if (!candidates.length) throw new Error('当前没有可提交的合法动作')
    const winningMove = immediateWinningMove(table, candidates)
    if (winningMove) {
      runtime.modelState = 'READY'
      return {
        cards: winningMove,
        candidates: candidates.length,
        playableCandidates: candidates.filter((candidate) => candidate.length).length,
        q: null,
        role: table.seatId === table.landlordSeatId ? 'landlord' : 'farmer',
        reason: 'TACTICAL_WIN_NOW',
        fallback: false,
        top: [],
        providerModel: 'RULE_ENGINE',
        providerReason: '合法动作可立即出完全部手牌',
      }
    }
    const response = await client.command('AI_DECIDE', {
      phase: 'PLAY',
      candidates: candidates.map((cards) => cards.map((card) => card.code)),
    })
    const selectedCodes = response.action === 'PASS' ? [] : response.cards || []
    const cards = selectedCodes.map((code) => table.hand.find((card) => card.code === code)).filter(Boolean)
    if (cards.length !== selectedCodes.length) throw new Error('大模型返回的手牌已发生变化')
    const selectedKey = [...selectedCodes].sort((a, b) => a - b).join(',')
    if (
      !candidates.some(
        (candidate) =>
          candidate
            .map((card) => card.code)
            .sort((a, b) => a - b)
            .join(',') === selectedKey,
      )
    )
      throw new Error('大模型返回了候选列表之外的出牌动作')
    runtime.modelState = 'READY'
    return {
      cards,
      candidates: candidates.length,
      playableCandidates: candidates.filter((candidate) => candidate.length).length,
      q: null,
      role: table.seatId === table.landlordSeatId ? 'landlord' : 'farmer',
      reason: response.model === 'RULE_ENGINE' ? 'ONLY_LEGAL_MOVE' : 'OPENAI_DECISION',
      fallback: false,
      top: [],
      providerModel: response.model,
      providerLatencyMs: response.latencyMs,
      providerTokens: response.totalTokens,
      providerReason: response.reason,
      providerFinishReason: response.finishReason,
      confidence: response.confidence,
    }
  }

  async function chooseOpenAiOrder() {
    const response = await client.command('AI_DECIDE', { phase: 'ORDER' })
    runtime.modelState = 'READY'
    log('DECISION', 'CALLING_LANDLORD', response.reason, response)
    return response.action === 'ACCEPT'
  }

  async function chooseOpenAiDouble() {
    const response = await client.command('AI_DECIDE', { phase: 'DOUBLE' })
    runtime.modelState = 'READY'
    log('DECISION', 'DOUBLING', response.reason, response)
    return response.action
  }

  async function run() {
    if (!runtime.active || runtime.busy || destroyed) return
    const selectedConfig = config.value
    const session = client.session.value
    if (!selectedConfig?.sceneId) {
      await stop('请选择 AI 要进入的游戏模式', false)
      return
    }
    if (!client.state.connected) {
      runtime.status = '连接恢复后将继续运行'
      schedule(1500)
      return
    }
    if (!session?.uin) {
      runtime.status = '请先扫码登录游戏账号'
      schedule(1500)
      return
    }
    runtime.busy = true
    runtime.state = 'RUNNING'
    runtime.lastError = ''
    try {
      const table = session.table
      const fingerprint = tableFingerprint(table, session.beans)
      const observedState = `${table?.roomId || 0}:${table?.tableId || 0}:${table?.stage || 'IDLE'}:${table?.turnSeatId ?? -1}:${table?.actionHistory?.length || 0}`
      if (observedState !== lastObservedState) {
        lastObservedState = observedState
        log('STATE', table?.stage || 'IDLE', table?.prompt || '牌桌状态已更新', {
          roomId: table?.roomId || 0,
          tableId: table?.tableId || 0,
          seatId: table?.seatId ?? -1,
          turnSeatId: table?.turnSeatId ?? -1,
          landlordSeatId: table?.landlordSeatId ?? -1,
          handCount: table?.hand?.length || 0,
          availableActions: table?.availableActions || [],
        })
      }
      if (await handleBeanLimits(session, selectedConfig, fingerprint)) return

      if (!table || table.stage === 'IDLE' || !table.sceneId) {
        client.screen.value = 'lobby'
        await act(
          `${fingerprint}:enter:${selectedConfig.sceneId}`,
          'MODE_ENTER',
          { sceneId: Number(selectedConfig.sceneId) },
          '正在自动进入所选场次',
          250,
        )
        if (runtime.active) client.screen.value = 'room'
        return
      }

      client.screen.value = 'room'
      const actions = new Set(table.availableActions || [])
      if (table.stage === 'TIMED_OUT') {
        await act(`${fingerprint}:timeout-change`, 'CHANGE_TABLE', {}, '本桌等待超时，正在自动换桌', 500)
        return
      }
      if (table.stage === 'SETTLED') {
        const currentSettlement = settlementIdentity(table, session.id || client.state.activeSessionId)
        const won = settlementOutcome(table)
        const existing = runtime.logs.find(
          (entry) => entry.phase === 'SETTLEMENT' && entry.detail?.settlementId === currentSettlement,
        )
        if (existing && existing.detail.won == null && won !== null) {
          existing.detail.won = won
          existing.message = `第 ${runtime.rounds} 局结算已补全：${won ? '胜利' : '失利'}`
          runtime.unknown = Math.max(0, runtime.unknown - 1)
          if (won) runtime.wins += 1
          else runtime.losses += 1
          persistLogs(runtime.logs)
        }
        if (currentSettlement && currentSettlement !== settlementKey && !existing) {
          settlementKey = currentSettlement
          try {
            localStorage.setItem(LAST_SETTLEMENT_STORAGE_KEY, settlementKey)
          } catch {
            // The in-memory identity still prevents duplicate accounting this session.
          }
          runtime.rounds += 1
          if (won === true) runtime.wins += 1
          else if (won === false) runtime.losses += 1
          else runtime.unknown += 1
          const localResult = table.settlement?.players?.find(
            (player) => Number(player.seatId) === Number(table.seatId),
          )
          log(
            'INFO',
            'SETTLEMENT',
            `第 ${runtime.rounds} 局${won === true ? '胜利' : won === false ? '失利' : '结果待确认'}，同步结算余额`,
            {
              auditVersion: 1,
              settlementId: currentSettlement,
              won,
              beanChange: localResult?.beanChange ?? null,
              beans: localResult?.beans ?? session.beans,
              landlordSeatId: table.landlordSeatId,
              localSeatId: table.seatId,
            },
          )
          await refreshWallet(`${fingerprint}:settlement`)
          return
        }
        if (!currentSettlement) runtime.status = '结算标识不完整，暂不计入统计'
        if (selectedConfig.autoChangeTable && actions.has('CHANGE_TABLE'))
          await act(`${fingerprint}:change`, 'CHANGE_TABLE', {}, '本局完成，正在自动换桌', 1400)
        else if (actions.has('READY'))
          await act(`${fingerprint}:ready`, 'READY', {}, '本局完成，正在准备下一局', 1100)
        return
      }
      if (table.stage === 'CALLING_LANDLORD') {
        const robbing = actions.has('ROB_LANDLORD') || actions.has('PASS_ROB')
        if (robbing || actions.has('CALL_LANDLORD') || actions.has('PASS_LANDLORD')) {
          const heuristicDecision = chooseLandlordDecision(actions, table.hand)
          let localDecision
          const getLocalDecision = async () => {
            if (localDecision) return localDecision
            localDecision = await chooseFullAutoBid(table, actions, setModelState).catch((error) => {
              log('ERROR', 'FULLAUTO_BID', '叫抢模型暂不可用，使用手牌策略', { message: error.message })
              return { ...heuristicDecision, score: null, threshold: null }
            })
            return localDecision
          }
          const accept = openAiEnabled()
            ? await chooseOpenAiOrder().catch((error) => {
                log('ERROR', 'OPENAI', '大模型叫抢决策失败，使用本地策略', { message: error.message })
                return getLocalDecision().then((decision) => decision.accept)
              })
            : (await getLocalDecision()).accept
          const resolvedLocal = await getLocalDecision()
          const decision = {
            ...resolvedLocal,
            accept,
            label: accept ? (robbing ? '抢地主' : '叫地主') : robbing ? '不抢' : '不叫',
          }
          runtime.decision = `${decision.score == null ? `手牌强度 ${decision.strength}` : `叫牌得分 ${decision.score.toFixed(3)}`} · ${decision.label}`
          log('DECISION', 'CALLING_LANDLORD', runtime.decision, {
            strength: decision.strength,
            score: decision.score,
            threshold: decision.threshold,
            modelFile: decision.modelFile,
            accept: decision.accept,
            actions: [...actions],
          })
          await act(
            `${fingerprint}:order:${decision.accept}`,
            'ORDER',
            { accept: decision.accept },
            runtime.decision,
            620,
          )
        }
        return
      }
      if (table.stage === 'DOUBLING') {
        const itemId = Number(table.superDoubleItemId || SUPER_DOUBLE_ITEM_ID)
        const itemCount = Number(
          session.inventory?.find((item) => Number(item.itemId) === itemId)?.count || 0,
        )
        if (actions.has('DOUBLE') || actions.has('NO_DOUBLE') || actions.has('SUPER_DOUBLE')) {
          const heuristicDecision = chooseDoubleDecision(actions, table.hand, itemCount)
          let localDecision
          const getLocalDecision = async () => {
            if (localDecision) return localDecision
            localDecision = await chooseFullAutoDouble(table, actions, itemCount, setModelState).catch(
              (error) => {
                log('ERROR', 'FULLAUTO_DOUBLE', '加倍模型暂不可用，使用手牌策略', {
                  message: error.message,
                })
                return { ...heuristicDecision, score: null, threshold: null }
              },
            )
            return localDecision
          }
          let decision = await getLocalDecision()
          if (openAiEnabled()) {
            const remoteAction = await chooseOpenAiDouble().catch((error) => {
              log('ERROR', 'OPENAI', '大模型加倍决策失败，使用本地策略', { message: error.message })
              return null
            })
            if (remoteAction === 'SUPER_DOUBLE')
              decision = { ...decision, command: 'SUPER_MULTIPLE', payload: {}, label: '超级加倍' }
            else if (remoteAction === 'DOUBLE')
              decision = { ...decision, command: 'MULTIPLE', payload: { accept: true }, label: '普通加倍' }
            else if (remoteAction === 'NO_DOUBLE')
              decision = { ...decision, command: 'MULTIPLE', payload: { accept: false }, label: '不加倍' }
          }
          const itemDetail = decision.command === 'SUPER_MULTIPLE' ? `（剩余 ${itemCount}）` : ''
          runtime.decision = `${decision.score == null ? `手牌强度 ${decision.strength}` : `局前得分 ${decision.score.toFixed(3)}`} · ${decision.label}${itemDetail}`
          log('DECISION', 'DOUBLING', runtime.decision, {
            strength: decision.strength,
            score: decision.score,
            threshold: decision.threshold,
            modelFile: decision.modelFile,
            itemCount,
            command: decision.command,
          })
          await act(
            `${fingerprint}:double:${decision.command}:${decision.payload.accept ?? 'super'}`,
            decision.command,
            decision.payload,
            runtime.decision,
            520,
          )
        }
        return
      }
      if (table.stage === 'PLAYING') {
        if (table.trusted && actions.has('CANCEL_TRUST')) {
          await act(`${fingerprint}:untrust`, 'TRUST', { enabled: false }, '正在取消系统托管', 250)
          return
        }
        const myTurn = Number(table.turnSeatId) === Number(table.seatId)
        if (myTurn && (actions.has('PLAY') || actions.has('PASS'))) await decidePlay(table, fingerprint)
        else runtime.status = '等待其他玩家操作'
        return
      }
      runtime.status =
        table.stage === 'SEATED' || table.stage === 'READY' || table.stage === 'DEALING'
          ? '已进入牌桌，等待发牌'
          : '正在同步牌桌状态'
    } catch (error) {
      runtime.lastError = error.message
      runtime.status = 'AI 决策暂未完成，正在重试'
      log('ERROR', 'RUNTIME', 'AI 决策暂未完成，正在重试', { message: error.message })
      lastActionKey = ''
    } finally {
      runtime.busy = false
      schedule(runtime.lastError ? 1800 : 700)
    }
  }

  watch(
    () => config.value?.enabled,
    (enabled) => {
      if (enabled && !runtime.active) {
        runtime.strategy = openAiEnabled() ? '大模型胜率优先' : 'FullAuto ResNet 高胜率策略'
        runtime.active = true
        runtime.state = 'RUNNING'
        runtime.status = '正在恢复 AI 自动对局'
        log('INFO', 'CONTROL', '正在恢复上次的 AI 自动对局')
        schedule(100)
      } else if (!enabled && runtime.active) stop('AI 已停止', false)
    },
  )
  watch(
    () => tableFingerprint(client.table.value, client.session.value?.beans),
    () => schedule(180),
  )
  watch(
    () => client.state.connected,
    () => schedule(100),
  )

  onBeforeUnmount(() => {
    destroyed = true
    clearTimeout(timer)
  })

  return { runtime, config, start, stop, save, testProvider, clearLogs }
}
