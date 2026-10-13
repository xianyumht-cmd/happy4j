import { describe, expect, it } from 'vitest'
import { chooseActiveSession, hallReady, sessionStatus } from './game-session-state.js'

describe('game hall readiness', () => {
  it('distinguishes QQ or WeChat confirmation from successful hall connection', () => {
    for (const phase of [
      'CREATED',
      'QR_READY',
      'QR_SCANNED',
      'PLATFORM_AUTHENTICATED',
      'GAME_AUTHENTICATING',
      'DISCONNECTED',
      'FAILED',
      'EVICTED',
    ]) {
      expect(hallReady({ phase, uin: 288 })).toBe(false)
    }
    expect(hallReady({ phase: 'HALL_CONNECTED', uin: 0 })).toBe(false)
    for (const phase of ['HALL_CONNECTED', 'ENTERING_ROOM', 'AT_TABLE', 'READY', 'PLAYING']) {
      expect(hallReady({ phase, uin: 288 })).toBe(true)
    }
  })

  it('reports recognizable login stages without claiming a false success', () => {
    expect(sessionStatus({ phase: 'PLATFORM_AUTHENTICATED', uin: 0 })).toContain('正在验证')
    expect(sessionStatus({ phase: 'GAME_AUTHENTICATING', uin: 288 })).toContain('正在')
    expect(sessionStatus({ phase: 'HALL_CONNECTED', uin: 288 })).toBe('游戏大厅已连接')
    expect(sessionStatus({ phase: 'HALL_CONNECTED', uin: 288 }, false)).toContain('网页连接中断')
    expect(sessionStatus({ phase: 'FAILED', uin: 288 })).toContain('登录失败')
  })
})

describe('active login session selection', () => {
  const sessions = {
    stale: { id: 'stale', phase: 'FAILED', uin: 333 },
    healthy: { id: 'healthy', phase: 'HALL_CONNECTED', uin: 444 },
    newQr: { id: 'newQr', phase: 'QR_READY', uin: 0 },
  }

  it('prefers an actually connected session instead of a stale QR login', () => {
    expect(chooseActiveSession(sessions, 'stale')).toBe('healthy')
    expect(chooseActiveSession(sessions, null)).toBe('healthy')
    expect(chooseActiveSession({ stale: sessions.stale }, null)).toBe('stale')
  })

  it('respects the explicit scan target even if another session is connected', () => {
    expect(chooseActiveSession(sessions, 'healthy', 'newQr')).toBe('newQr')
    expect(chooseActiveSession(sessions, 'healthy', 'absent')).toBe('healthy')
  })

  it('does not unexpectedly switch away from a healthy current account', () => {
    const multiple = {
      older: { id: 'older', phase: 'PLAYING', uin: 100 },
      newer: { id: 'newer', phase: 'HALL_CONNECTED', uin: 200 },
    }
    expect(chooseActiveSession(multiple, 'older')).toBe('older')
  })
})
