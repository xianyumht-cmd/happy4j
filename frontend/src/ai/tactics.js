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
