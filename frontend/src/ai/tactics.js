import { classifyMove, nativeRank, TYPE } from './legal-moves.js'

// A legal move that empties the local hand wins the round regardless of role.
// It should not be displaced by a speculative model preference to pass.
export function immediateWinningMove(table, candidates) {
  if (table?.stage !== 'PLAYING' || Number(table.turnSeatId) !== Number(table.seatId)) return null
  if (!table.availableActions?.includes('PLAY') || !table.hand?.length) return null
  const hand = table.hand
    .map((card) => card.code)
    .sort((a, b) => a - b)
    .join(',')
  return (
    candidates.find(
      (candidate) =>
        candidate.length === table.hand.length &&
        candidate
          .map((card) => card.code)
          .sort((a, b) => a - b)
          .join(',') === hand,
    ) || null
  )
}

// When the landlord is close to going out, passing on their live play despite
// having a legal response can end the game before the teammate gets another turn.
// This overrides only a confirmed landlord lead, never a teammate's play.
export function urgentLandlordResponse(table, candidates, selectedCards) {
  if (selectedCards?.length || table?.stage !== 'PLAYING') return null
  if (Number(table.turnSeatId) !== Number(table.seatId)) return null
  if (!table.availableActions?.includes('PLAY')) return null
  const landlord = Number(table.landlordSeatId)
  const self = Number(table.seatId)
  if (![0, 1, 2].includes(landlord) || ![0, 1, 2].includes(self) || self === landlord) return null
  if (Number(table.lastPlaySeatId) !== landlord || !table.lastPlay?.length) return null
  const landlordPlayer = table.players?.find((player) => Number(player.seatId) === landlord)
  const cardsLeft = Number(landlordPlayer?.cardCount)
  if (!Number.isInteger(cardsLeft) || cardsLeft < 1 || cardsLeft > 5) return null

  const handRanks = new Map()
  for (const card of table.hand || []) {
    const rank = nativeRank(card)
    handRanks.set(rank, (handRanks.get(rank) || 0) + 1)
  }
  const options = (candidates || [])
    .filter((move) => move?.length && classifyMove(move))
    .map((cards) => {
      const pattern = classifyMove(cards)
      const moveRanks = new Map()
      for (const card of cards) {
        const rank = nativeRank(card)
        moveRanks.set(rank, (moveRanks.get(rank) || 0) + 1)
      }
      let brokenGroups = 0
      for (const [rank, amount] of moveRanks) {
        const original = handRanks.get(rank) || 0
        if (original >= 2 && amount < original) brokenGroups++
      }
      const bombPenalty = [TYPE.BOMB, TYPE.ROCKET].includes(pattern.type) ? 500 : 0
      return { cards, cost: bombPenalty + brokenGroups * 40 + pattern.mainRank + cards.length / 10 }
    })
    .sort((a, b) => a.cost - b.cost)
  return options[0]?.cards || null
}

/**
 * Four isolated singles are not a run-out plan. On a free landlord lead,
 * spending the highest singleton first removes our best chance to regain
 * control after an opponent beats the next low card.
 *
 * This is a narrow late-game guard, not a perfect-information solver.
 * It does not intervene if an opponent has two or fewer cards, if any
 * non-single move is available, or if the model did not pick the top single.
 */
export function preserveLandlordSingleControl(table, candidates, selectedCards) {
  if (table?.stage !== 'PLAYING' || Number(table.turnSeatId) !== Number(table.seatId)) return null
  if (Number(table.seatId) !== Number(table.landlordSeatId)) return null
  if (!table.availableActions?.includes('PLAY')) return null
  if (table.lastPlay?.length && Number(table.lastPlaySeatId) !== Number(table.seatId)) return null
  if (!Array.isArray(table.hand) || table.hand.length < 3 || table.hand.length > 5) return null
  if (!selectedCards || selectedCards.length !== 1) return null

  const others = (table.players || []).filter((player) => Number(player.seatId) !== Number(table.seatId))
  if (others.length !== 2 || others.some((player) => !Number.isInteger(player.cardCount) || player.cardCount <= 2))
    return null
  const moves = candidates || []
  if (moves.length !== table.hand.length || moves.some((cards) => cards.length !== 1)) return null
  const sorted = [...moves].sort((a, b) => nativeRank(a[0]) - nativeRank(b[0]))
  const weakest = sorted[0][0]
  const strongest = sorted.at(-1)[0]
  if (nativeRank(weakest) >= nativeRank(strongest)) return null
  if (nativeRank(selectedCards[0]) !== nativeRank(strongest)) return null
  return sorted[0]
}
