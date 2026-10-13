// A legal move that empties the local hand wins the round regardless of role.
// It should not be displaced by a speculative model preference to pass.
export function immediateWinningMove(table, candidates) {
  if (table?.stage !== 'PLAYING' || Number(table.turnSeatId) !== Number(table.seatId)) return null
  if (!table.availableActions?.includes('PLAY') || !table.hand?.length) return null
  const hand = table.hand.map((card) => card.code).sort((a, b) => a - b).join(',')
  return (
    candidates.find(
      (candidate) =>
        candidate.length === table.hand.length &&
        candidate.map((card) => card.code).sort((a, b) => a - b).join(',') === hand,
    ) || null
  )
}
