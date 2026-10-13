// Settlement identity is derived from the first round-end event, not the history length.
// A late detailed settlement may update the result without counting a second round.
export function settlementIdentity(table, sessionId) {
  if (table?.stage !== 'SETTLED' || !sessionId) return null
  const end = [...(table.actionHistory || [])].reverse().find((entry) => entry.action === '本局结束')
  if (!end?.occurredAt) return null
  return [sessionId, table.sceneId, table.roomId, table.tableId, end.occurredAt].join(':')
}

// The protocol's resultFlag and bean deltas do not have verified win/loss semantics.
// Count a winner only when the settlement includes three distinct, explicit hands and
// exactly one player is out of cards; otherwise leave the result unknown.
export function settlementOutcome(table) {
  const players = table?.settlement?.players
  const landlord = Number(table?.landlordSeatId)
  const localSeat = Number(table?.seatId)
  if (!Array.isArray(players) || players.length !== 3) return null
  if (![0, 1, 2].includes(landlord) || ![0, 1, 2].includes(localSeat)) return null
  if (new Set(players.map((player) => Number(player.seatId))).size !== 3) return null
  if (!players.every((player) => [0, 1, 2].includes(Number(player.seatId)))) return null
  if (!players.every((player) => Array.isArray(player.remainingCards))) return null
  const out = players.filter((player) => player.remainingCards.length === 0)
  if (out.length !== 1) return null
  const landlordWon = Number(out[0].seatId) === landlord
  return (localSeat === landlord) === landlordWon
}
