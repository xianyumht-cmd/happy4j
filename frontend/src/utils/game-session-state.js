const HALL_READY_PHASES = new Set([
  'HALL_CONNECTED',
  'ENTERING_ROOM',
  'AT_TABLE',
  'READY',
  'PLAYING',
])
const INACTIVE_PHASES = new Set(['FAILED', 'DISCONNECTED', 'EVICTED'])

export function hallReady(session) {
  return Boolean(session?.uin && HALL_READY_PHASES.has(session.phase))
}

export function sessionStatus(session, webConnected = true) {
  if (!webConnected) return '网页连接中断，正在重连'
  if (!session) return '请先扫码登录游戏账号'
  if (hallReady(session)) return '游戏大厅已连接'

  switch (session.phase) {
    case 'QR_READY':
      return '请扫码并在手机上确认'
    case 'QR_SCANNED':
      return '已扫码，请在手机上确认'
    case 'PLATFORM_AUTHENTICATED':
      return '扫码已确认，正在验证游戏账号'
    case 'GAME_AUTHENTICATING':
      return '正在验证账号并连接游戏大厅'
    case 'DISCONNECTED':
      return '游戏大厅已断开，请重新登录'
    case 'EVICTED':
      return '账号已在其他设备登录'
    case 'FAILED':
      return '游戏登录失败，请重新扫码'
    default:
      return '等待扫码登录游戏账号'
  }
}

export function chooseActiveSession(sessions, currentId = null, preferredId = null) {
  if (preferredId && sessions[preferredId]) return preferredId

  const candidates = Object.values(sessions)
  if (!candidates.length) return null

  if (currentId && hallReady(sessions[currentId])) return currentId
  const ready = candidates.find(hallReady)
  if (ready) return ready.id

  if (currentId && sessions[currentId] && !INACTIVE_PHASES.has(sessions[currentId].phase))
    return currentId

  const available = candidates.find((session) => !INACTIVE_PHASES.has(session.phase))
  return available?.id || candidates[0].id
}
