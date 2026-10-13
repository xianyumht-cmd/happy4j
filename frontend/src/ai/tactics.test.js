import { describe, expect, it } from 'vitest'
import { immediateWinningMove, preserveLandlordSingleControl, urgentLandlordResponse } from './tactics.js'

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

describe('urgent landlord defence', () => {
  const danger = {
    stage: 'PLAYING',
    seatId: 0,
    turnSeatId: 0,
    landlordSeatId: 2,
    lastPlaySeatId: 2,
    lastPlay: [card(8)],
    availableActions: ['PLAY', 'PASS'],
    hand: [card(10), card(11), card(24), card(53), card(54)],
    players: [
      { seatId: 0, cardCount: 5 },
      { seatId: 1, cardCount: 7 },
      { seatId: 2, cardCount: 3 },
    ],
  }
  it('overrides a speculative pass when landlord has only three cards and can be beaten', () => {
    const response = [card(10)]
    const stronger = [card(11)]
    const rocket = [card(53), card(54)]
    expect(urgentLandlordResponse(danger, [[], stronger, rocket, response], [])).toBe(response)
  })

  it('does not override teammates or incomplete and out-of-turn states', () => {
    const moves = [[], [card(10)]]
    expect(urgentLandlordResponse({ ...danger, lastPlaySeatId: 1 }, moves, [])).toBeNull()
    expect(urgentLandlordResponse({ ...danger, turnSeatId: 1 }, moves, [])).toBeNull()
    expect(urgentLandlordResponse({ ...danger, players: [] }, moves, [])).toBeNull()
    expect(urgentLandlordResponse({ ...danger, landlordSeatId: 0 }, moves, [])).toBeNull()
    expect(
      urgentLandlordResponse({ ...danger, players: [{ seatId: 2, cardCount: 6 }] }, moves, []),
    ).toBeNull()
  })

  it('preserves model-selected legal plays and never invents a response', () => {
    const response = [card(10)]
    expect(urgentLandlordResponse(danger, [[], response], response)).toBeNull()
    expect(urgentLandlordResponse(danger, [[]], [])).toBeNull()
    expect(urgentLandlordResponse(danger, [], [])).toBeNull()
  })
})

describe('landlord isolated-single endgame', () => {
  const lonely = {
    stage: 'PLAYING',
    seatId: 2,
    turnSeatId: 2,
    landlordSeatId: 2,
    lastPlay: [],
    availableActions: ['PLAY', 'TRUST'],
    hand: [card(3), card(5), card(10), card(13)],
    players: [
      { seatId: 0, cardCount: 7 },
      { seatId: 1, cardCount: 9 },
      { seatId: 2, cardCount: 4 },
    ],
  }
  const moves = lonely.hand.map((item) => [item])

  it('does not waste the highest single when free-leading four isolated singles', () => {
    expect(preserveLandlordSingleControl(lonely, moves, [card(13)])).toEqual([card(3)])
  })

  it('does not override a lower single, a response, or an imminent opponent finish', () => {
    expect(preserveLandlordSingleControl(lonely, moves, [card(5)])).toBeNull()
    expect(
      preserveLandlordSingleControl({ ...lonely, lastPlay: [card(9)], lastPlaySeatId: 0 }, moves, [card(13)]),
    ).toBeNull()
    expect(
      preserveLandlordSingleControl(
        { ...lonely, players: [{ seatId: 0, cardCount: 1 }, ...lonely.players.slice(1)] },
        moves,
        [card(13)],
      ),
    ).toBeNull()
  })

  it('leaves full-hand finishes, compound moves and other roles to the core strategy', () => {
    expect(preserveLandlordSingleControl(lonely, [[card(3), card(5)]], [card(13)])).toBeNull()
    expect(preserveLandlordSingleControl({ ...lonely, seatId: 1 }, moves, [card(13)])).toBeNull()
    expect(preserveLandlordSingleControl({ ...lonely, turnSeatId: 0 }, moves, [card(13)])).toBeNull()
  })
})
