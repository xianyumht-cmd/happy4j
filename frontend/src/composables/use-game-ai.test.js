import { describe, expect, it } from 'vitest'
import { canRefreshWallet, tableFingerprint } from './use-game-ai.js'

const card = (code) => ({ code })

describe('AI 牌桌快照指纹', () => {
  it('历史达到上限后仍按最后出牌区分新回合，避免重复动作被误判', () => {
    const base = {
      sceneId: 1260,
      roomId: 0,
      tableId: 88,
      stage: 'PLAYING',
      turnSeatId: 0,
      landlordSeatId: 1,
      multiplier: 30,
      trusted: false,
      availableActions: ['PLAY', 'PASS'],
      hand: [card(3), card(16)],
      actionHistory: Array.from({ length: 49 }, (_, index) => ({
        occurredAt: `2026-08-28T05:00:${String(index).padStart(2, '0')}Z`,
        seatId: index % 3,
        action: '不出',
        cards: [],
      })),
      settlement: { players: [] },
    }
    const first = {
      ...base,
      lastPlaySeatId: 1,
      lastPlayType: 1,
      lastPlayRank: 5,
      lastPlay: [card(7)],
      actionHistory: [
        ...base.actionHistory,
        { occurredAt: '2026-08-28T05:01:00Z', seatId: 1, action: '出牌', cards: [card(7)] },
      ],
    }
    const second = {
      ...base,
      lastPlaySeatId: 2,
      lastPlayType: 3,
      lastPlayRank: 8,
      lastPlay: [card(10), card(23)],
      actionHistory: [
        ...base.actionHistory,
        { occurredAt: '2026-08-28T05:02:00Z', seatId: 2, action: '出牌', cards: [card(10), card(23)] },
      ],
    }

    expect(first.actionHistory).toHaveLength(second.actionHistory.length)
    expect(tableFingerprint(first, 5000)).not.toBe(tableFingerprint(second, 5000))
  })
})

describe('大厅状态与余额刷新', () => {
  it('只在大厅和有效游戏会话中允许余额查询', () => {
    expect(canRefreshWallet({ phase: 'HALL_CONNECTED' })).toBe(true)
    expect(canRefreshWallet({ phase: 'AT_TABLE' })).toBe(true)
    expect(canRefreshWallet({ phase: 'PLAYING' })).toBe(true)
    expect(canRefreshWallet({ phase: 'DISCONNECTED' })).toBe(false)
    expect(canRefreshWallet({ phase: 'FAILED' })).toBe(false)
    expect(canRefreshWallet({ phase: 'GAME_AUTHENTICATING' })).toBe(false)
    expect(canRefreshWallet(null)).toBe(false)
  })
})
