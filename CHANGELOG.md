# Changelog

## Unreleased — AI decision reliability branch (2026-10-13)

### Fixed
- Stop adding duplicate round-end markers for repeated GameSo settlement/round-end events.
- Clear public action history at the start of a new round, preventing invalid DouZero state carryover.
- Replace bean-change-based victory attribution with conservative result validation from explicit settlement hands.
- Use a round-end event identity rather than mutable action-history length for browser round counters.
- Track ambiguous outcomes separately and exclude historical unverified logs from revised statistics.
- Check selected LLM cards against the supplied legal candidate set before transmission.
- Suppress automatic retransmission of an action when its acknowledgement might have been lost.
- Limit unsynchronized wallet retries after repeated lobby failures.

### Improved
- Keep OpenRouter `openai/gpt-6-luna` and local DouZero FullAuto models.
- Skip LLM network calls for a sole legal choice and prioritize validated actions that immediately empty the hand.
- Make output-token budget configurable (`HAPPY4J_AI_MAX_OUTPUT_TOKENS`, default 1024).
- Include role, teammate seats, response finish reason and decision provenance in decision handling.
- Distinguish verified wins, losses, and unknown results in the UI.
- Add focused JUnit/Vitest regressions, a PR CI check, and [reliability notes](docs/ai-reliability.md).

### Not yet verified
- True winner semantics for incomplete live settlement payloads.
- Live win-rate improvements versus Luna-only and DouZero-only; long-horizon endgame policy quality.
- Full GameSvr/lobby reconnect recovery and Windows portable integration testing.

This is a development branch only: no database migration, no merge, no release,
and no changes to an installed Windows portable environment.
