import { computed, reactive, ref } from 'vue'
import { authRequest, authToken, currentUser } from '@/services/api.js'
import { GameSocket } from '@/services/game-socket.js'
import { useToasts } from './use-toasts.js'
import { playerMessage } from '@/utils/player-message.js'
import { chooseActiveSession, hallReady, sessionStatus } from '@/utils/game-session-state.js'

const state = reactive({
  user: null,
  connected: false,
  loading: true,
  modes: [],
  sessions: {},
  activeSessionId: null,
  provider: 'qq',
  preview: false,
  evicted: false,
  aiConfig: null,
})

const screen = ref('lobby')
const lobbyTab = ref('modes')
const qr = reactive({
  open: false,
  loading: false,
  image: '',
  status: '请选择登录方式',
  sessionId: null,
  completed: false,
})
let socket = null
let qrCompletionTimer = null
const { push } = useToasts()

export function useGameClient() {
  const session = computed(() => state.sessions[state.activeSessionId] || null)
  const table = computed(() => session.value?.table || null)
  const authenticated = computed(() => state.preview || Boolean(state.user && authToken.get()))

  function syncQrProgress(snapshot) {
    if (!qr.sessionId || snapshot.id !== qr.sessionId) return
    if (hallReady(snapshot)) {
      qr.status = '游戏账号已连接大厅'
      if (!qr.completed) {
        qr.completed = true
        push('游戏账号已连接大厅')
        const completedSessionId = snapshot.id
        clearTimeout(qrCompletionTimer)
        qrCompletionTimer = setTimeout(() => {
          if (qr.sessionId === completedSessionId && hallReady(state.sessions[completedSessionId]))
            qr.open = false
        }, 800)
      }
      return
    }
    if (qr.completed) return
    qr.status =
      snapshot.phase === 'FAILED'
        ? `游戏登录失败：${playerMessage(snapshot.detail)}`
        : sessionStatus(snapshot)
  }

  function updateSession(snapshot, preferred = false) {
    if (!snapshot?.id) return
    state.sessions[snapshot.id] = snapshot
    const qrTarget = !qr.completed && qr.sessionId && state.sessions[qr.sessionId] ? qr.sessionId : null
    state.activeSessionId = chooseActiveSession(
      state.sessions,
      state.activeSessionId,
      preferred ? snapshot.id : qrTarget,
    )
    syncQrProgress(snapshot)
    const active = state.sessions[state.activeSessionId]
    if (active?.table?.sceneId > 0 && active.table.stage !== 'IDLE') screen.value = 'room'
    if (!active?.table?.sceneId || active.table.stage === 'IDLE') screen.value = 'lobby'
    state.evicted = active?.phase === 'EVICTED'
  }

  async function initialize() {
    const preview = import.meta.env.DEV ? new URLSearchParams(location.search).get('preview') : null
    if (preview) {
      const { previewState } = await import('@/dev/preview-data.js')
      const data = previewState(preview)
      state.preview = true
      state.user = data.user
      state.connected = true
      state.modes = data.modes
      state.sessions = { [data.session.id]: data.session }
      state.activeSessionId = data.session.id
      state.evicted = preview === 'evicted'
      state.aiConfig = data.aiConfig || null
      screen.value = ['lobby', 'evicted', 'ai-config'].includes(preview) ? 'lobby' : 'room'
      state.loading = false
      return
    }
    state.user = await currentUser()
    state.loading = false
    if (state.user) connect()
  }

  async function authenticate(path, values) {
    const result = await authRequest(path, values)
    authToken.set(result.token)
    state.user = result.user
    connect()
  }

  function connect() {
    socket?.close()
    socket = new GameSocket(handleEnvelope, (connected) => {
      state.connected = connected
    })
    socket.connect()
  }

  async function handleEnvelope(envelope) {
    if (envelope.type === 'RESULT' && envelope.payload?.command === 'BOOT') {
      const data = envelope.payload.data
      state.user = data.user
      state.modes = data.modes || []
      state.aiConfig = data.aiConfig || null
      state.sessions = Object.fromEntries((data.sessions || []).map((item) => [item.id, item]))
      state.activeSessionId = chooseActiveSession(
        state.sessions,
        state.activeSessionId,
        qr.completed ? null : qr.sessionId,
      )
      if (!state.activeSessionId) {
        try {
          updateSession(await socket.command('SESSION_RESUME'), true)
        } catch {
          try {
            updateSession(await socket.command('SESSION_CREATE'), true)
          } catch (error) {
            push(playerMessage(error.message), 'error')
          }
        }
      } else updateSession(state.sessions[state.activeSessionId])
      return
    }
    if (envelope.type === 'SNAPSHOT') updateSession(envelope.payload)
    if (envelope.type === 'QR_STATUS') {
      const result = envelope.payload
      if (result?.sessionId !== qr.sessionId || qr.completed) return
      if (result.status === 'CONFIRMED')
        qr.status = '扫码已确认，正在验证游戏账号并连接大厅'
      else qr.status = playerMessage(result.message)
    }
    if (envelope.type === 'ERROR' && !envelope.requestId)
      push(playerMessage(envelope.payload.message), 'error')
  }

  async function command(name, extra = {}, successMessage = '') {
    const userCommands = new Set([
      'SESSION_CREATE',
      'SESSION_RESUME',
      'AI_CONFIG_GET',
      'AI_CONFIG_SAVE',
      'AI_PROVIDER_TEST',
    ])
    if (!state.activeSessionId && !userCommands.has(name)) {
      throw new Error('请先建立游戏会话')
    }
    try {
      const payload = userCommands.has(name) ? extra : { sessionId: state.activeSessionId, ...extra }
      const result = await socket.command(name, payload)
      if (result?.id && result?.phase)
        updateSession(result, name === 'SESSION_CREATE' || name === 'SESSION_RESUME')
      if (name === 'AI_CONFIG_GET' || name === 'AI_CONFIG_SAVE') state.aiConfig = result
      if (successMessage) push(successMessage)
      return result
    } catch (error) {
      const message = playerMessage(error.message)
      push(message, 'error')
      throw new Error(message, { cause: error })
    }
  }

  async function startQr(provider) {
    if (qr.loading) return
    clearTimeout(qrCompletionTimer)
    qr.loading = true
    qr.completed = false
    qr.sessionId = state.activeSessionId
    qr.image = ''
    qr.status = `正在生成${provider === 'wechat' ? '微信' : 'QQ'}二维码`
    state.provider = provider
    try {
      const result = await command('QR_START', { provider })
      qr.sessionId = result.sessionId
      qr.image = result.image
      if (qr.status.startsWith('正在生成'))
        qr.status = `请使用${provider === 'wechat' ? '微信' : 'QQ'}扫码并确认登录`
    } catch (error) {
      qr.status = `二维码生成失败：${playerMessage(error.message)}`
    } finally {
      qr.loading = false
    }
  }

  function openQr() {
    qr.open = true
  }

  function logout() {
    socket?.close()
    socket = null
    authToken.clear()
    state.user = null
    state.sessions = {}
    state.activeSessionId = null
    state.aiConfig = null
    state.connected = false
    qr.open = false
    qr.sessionId = null
    qr.completed = false
    clearTimeout(qrCompletionTimer)
    screen.value = 'lobby'
  }

  return {
    state,
    screen,
    lobbyTab,
    qr,
    session,
    table,
    authenticated,
    initialize,
    authenticate,
    command,
    startQr,
    openQr,
    logout,
    updateSession,
  }
}
