import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const mocked = vi.hoisted(() => ({ sockets: [], notices: [] }))

vi.mock('@/services/api.js', () => ({
  authToken: { get: () => 'test-token', set: vi.fn(), clear: vi.fn() },
  authRequest: vi.fn(),
  currentUser: vi.fn(async () => ({ id: 'site-user' })),
}))

vi.mock('@/services/game-socket.js', () => ({
  GameSocket: class {
    constructor(onEnvelope, onConnection) {
      this.onEnvelope = onEnvelope
      this.onConnection = onConnection
      mocked.sockets.push(this)
    }

    connect() {
      this.onConnection(true)
    }

    close() {
      this.onConnection(false)
    }

    async command(name, payload = {}) {
      if (name === 'SESSION_RESUME') throw new Error('no saved session')
      if (name === 'SESSION_CREATE') return { id: 'created', phase: 'CREATED', uin: 0 }
      if (name === 'QR_START')
        return { sessionId: payload.sessionId, image: 'data:image/png;base64,preview' }
      return null
    }

    emit(envelope) {
      return this.onEnvelope(envelope)
    }
  },
}))

vi.mock('./use-toasts.js', () => ({
  useToasts: () => ({ push: (message) => mocked.notices.push(message) }),
}))

import { useGameClient } from './use-game-client.js'

describe('QR confirmation and account selection', () => {
  const client = useGameClient()
  const boot = (sessions = []) => ({
    type: 'RESULT',
    payload: {
      command: 'BOOT',
      data: { user: { id: 'site-user' }, modes: [], aiConfig: null, sessions },
    },
  })

  beforeEach(async () => {
    vi.stubGlobal('location', { search: '' })
    vi.useFakeTimers()
    client.logout()
    client.state.loading = true
    mocked.notices.length = 0
    mocked.sockets.length = 0
    await client.initialize()
  })

  afterEach(() => {
    client.logout()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('does not announce login success on QR confirmation alone', async () => {
    const socket = mocked.sockets.at(-1)
    await socket.emit(boot())
    expect(client.state.activeSessionId).toBe('created')
    client.openQr()
    await client.startQr('qq')

    await socket.emit({
      type: 'QR_STATUS',
      payload: { sessionId: 'created', status: 'CONFIRMED', message: 'confirmed' },
    })
    expect(client.qr.status).toContain('正在验证')
    expect(client.qr.open).toBe(true)
    expect(mocked.notices).not.toContain('游戏账号已连接大厅')

    await socket.emit({
      type: 'SNAPSHOT',
      payload: { id: 'created', uin: 321, phase: 'GAME_AUTHENTICATING' },
    })
    expect(client.qr.open).toBe(true)
    expect(mocked.notices).not.toContain('游戏账号已连接大厅')

    await socket.emit({
      type: 'SNAPSHOT',
      payload: { id: 'created', uin: 321, phase: 'HALL_CONNECTED' },
    })
    expect(client.qr.completed).toBe(true)
    expect(mocked.notices.filter((message) => message === '游戏账号已连接大厅')).toHaveLength(1)
    expect(client.state.activeSessionId).toBe('created')

    await socket.emit({
      type: 'QR_STATUS',
      payload: { sessionId: 'created', status: 'CONFIRMED', message: 'confirmed' },
    })
    expect(client.qr.status).toBe('游戏账号已连接大厅')
    vi.advanceTimersByTime(800)
    expect(client.qr.open).toBe(false)
  })

  it('picks a healthy session on reconnect instead of stale failed login', async () => {
    const socket = mocked.sockets.at(-1)
    await socket.emit(
      boot([
        { id: 'failed', phase: 'FAILED', uin: 500 },
        { id: 'ready', phase: 'HALL_CONNECTED', uin: 777 },
      ]),
    )
    expect(client.state.activeSessionId).toBe('ready')
    expect(client.session.value.uin).toBe(777)

    await socket.emit({
      type: 'SNAPSHOT',
      payload: { id: 'failed', phase: 'QR_READY', uin: 500 },
    })
    expect(client.state.activeSessionId).toBe('ready')
  })

  it('keeps the scanned account selected while other session snapshots arrive', async () => {
    const socket = mocked.sockets.at(-1)
    await socket.emit(boot([{ id: 'scanned', phase: 'CREATED', uin: 0 }]))
    await client.startQr('wechat')
    expect(client.qr.sessionId).toBe('scanned')

    await socket.emit({
      type: 'SNAPSHOT',
      payload: { id: 'unrelated', phase: 'HALL_CONNECTED', uin: 999 },
    })
    expect(client.state.activeSessionId).toBe('scanned')

    await socket.emit({
      type: 'SNAPSHOT',
      payload: { id: 'scanned', phase: 'HALL_CONNECTED', uin: 123 },
    })
    expect(client.state.activeSessionId).toBe('scanned')
    expect(client.session.value.uin).toBe(123)
  })

  it('leaves the QR dialog open and reports a failed game login', async () => {
    const socket = mocked.sockets.at(-1)
    await socket.emit(boot([{ id: 'failed-scan', phase: 'CREATED', uin: 0 }]))
    client.openQr()
    await client.startQr('qq')
    await socket.emit({
      type: 'QR_STATUS',
      payload: { sessionId: 'failed-scan', status: 'CONFIRMED', message: 'confirmed' },
    })
    await socket.emit({
      type: 'SNAPSHOT',
      payload: {
        id: 'failed-scan',
        phase: 'FAILED',
        uin: 321,
        detail: '当前账号已有活跃游戏连接',
      },
    })
    expect(client.qr.open).toBe(true)
    expect(client.qr.completed).toBe(false)
    expect(client.qr.status).toContain('当前账号已有活跃游戏连接')
    expect(mocked.notices).not.toContain('游戏账号已连接大厅')
  })
})
