import { describe, expect, it } from 'vitest'
import { immediateWinningMove } from './tactics.js'

const card = (code) => ({ code })
const table = {
  stage: 'PLAYING',
  seatId: 0,
  turnSeatId: 0,
  landlordSeatId: 1,
  availableActions: ['PLAY', 'PASS'],
  hand: [card(3), card(16)],
}

describe('deterministic endgame overrides', () => {
  it('recognizes a legal play that empties the whole hand', () => {
    const win = [card(16), card(3)]
    expect(immediateWinningMove(table, [[], [card(3)], win])).toBe(win)
  })

  it('never forces an illegal, incomplete or out-of-turn play', () => {
    expect(immediateWinningMove(table, [[], [card(3)]])).toBeNull()
    expect(immediateWinningMove(table, [[card(3), card(4)]])).toBeNull()
    expect(immediateWinningMove({ ...table, turnSeatId: 1 }, [[card(3), card(16)]])).toBeNull()
    expect(immediateWinningMove({ ...table, availableActions: ['PASS'] }, [[card(3), card(16)]])).toBeNull()
  })
})
