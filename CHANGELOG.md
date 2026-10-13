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

### Follow-up after 2026-10-13 portable smoke logs
- First post-update live log confirmed real Luna decisions and the zero-token rule engine for forced passes.
- Added a narrowly scoped farmer endgame guard: when the landlord has 1–5 cards,
  last-play belongs to the landlord, and a legal response exists, a model-selected
  pass is replaced by a legal low-cost defensive response. Never override teammate
  leads, forced passes, or stale states.
- Reduce live PLAY provider request timeout from 12 to 8 seconds, with no
  retry on PLAY, so local DouZero gets a chance before the game timer expires.
- Include `finish_reason` in invalid-JSON provider diagnostics and record stale
  inference drops when the game changes while Luna is being called.
- Extend model prompt with last-play side and landlord remaining-card count.
- Original branch remains a draft PR; each reviewed commit receives a distinct
  prerelease asset. No automatic install on a user's machine.

### Not yet verified
- True winner semantics for incomplete live settlement payloads.
- Live win-rate improvements versus Luna-only and DouZero-only; long-horizon endgame policy quality.
- Full GameSvr/lobby reconnect recovery and Windows portable integration testing.

This is a draft development PR with no database migration or merge.
A separately versioned Windows prerelease artifact may be installed manually.
No GitHub workflow changes the user's installed Windows environment.
