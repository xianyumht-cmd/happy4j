import { describe, expect, it } from 'vitest'
import { settlementIdentity, settlementOutcome } from './settlement.js'

const end = { action: '本局结束', occurredAt: '2026-10-13T03:15:00Z' }
const table = (overrides = {}) => ({
  sceneId: 1260,
  roomId: 12,
  tableId: 88,
  stage: 'SETTLED',
  seatId: 0,
  landlordSeatId: 1,
  actionHistory: [end],
  settlement: {
    players: [
      { seatId: 0, beanChange: -100, remainingCards: [] },
      { seatId: 1, beanChange: 200, remainingCards: [4, 5] },
      { seatId: 2, beanChange: -100, remainingCards: [8] },
    ],
  },
  ...overrides,
})

describe('audited round statistics', () => {
  it('uses a stable end marker instead of action-history length', () => {
    const base = table()
    const updated = table({
      actionHistory: [...base.actionHistory, { action: '对手已准备', occurredAt: '2026-10-13T03:15:01Z' }],
    })
    expect(settlementIdentity(base, 'session-1')).toBe(settlementIdentity(updated, 'session-1'))
    expect(settlementIdentity(base, 'session-2')).not.toBe(settlementIdentity(base, 'session-1'))
    expect(settlementIdentity(table({ actionHistory: [] }), 'session-1')).toBeNull()
  })

  it('attributes farmer and landlord wins from explicit remaining cards', () => {
    expect(settlementOutcome(table())).toBe(true)
    expect(settlementOutcome(table({ seatId: 1 }))).toBe(false)
    expect(settlementOutcome(table({ seatId: 2 }))).toBe(true)
    expect(
      settlementOutcome(
        table({
          settlement: {
            players: [
              { seatId: 0, remainingCards: [3] },
              { seatId: 1, remainingCards: [] },
              { seatId: 2, remainingCards: [10] },
            ],
          },
        }),
      ),
    ).toBe(false)
  })

  it('keeps ambiguous or incomplete results unknown, even with positive bean changes', () => {
    expect(settlementOutcome(table({ landlordSeatId: -1 }))).toBeNull()
    expect(settlementOutcome(table({ settlement: { players: [] } }))).toBeNull()
    expect(
      settlementOutcome(
        table({
          settlement: {
            players: [
              { seatId: 0, beanChange: 300, remainingCards: [] },
              { seatId: 1, beanChange: -150, remainingCards: [] },
              { seatId: 2, beanChange: -150, remainingCards: [] },
            ],
          },
        }),
      ),
    ).toBeNull()
    expect(settlementOutcome(table({ settlement: { players: [{ seatId: 0, remainingCards: [] }] } }))).toBeNull()
  })
})
