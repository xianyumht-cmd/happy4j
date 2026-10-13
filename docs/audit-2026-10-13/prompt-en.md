# Happy4J — Reliability & Usability Audit Mandate

Repository: https://github.com/xianyumht-cmd/happy4j

Baseline: `main`, commit `37be0f1a39da66eedad8026dfa1dad8ca8caa424`.

## Professional English prompt

Act as a senior software reliability engineer, production-readiness reviewer, and user experience quality auditor. Audit the Happy4J repository end to end with one overriding objective: **the application must work reliably and be straightforward, predictable, and pleasant for ordinary Windows users to operate.** Prioritize real-life functionality and safe recovery over code aesthetics, AI-generated enhancements, and isolated model tactics.

Trace and inspect the complete user journey: Windows installation, portable Java and MySQL startup, configuration and migrations, website authentication, QQ/WeChat QR generation and confirmation, game TSDK SafeLogin, Lotus TCP hall establishment and health, scene selection and QuickGame routing, game-server connections, seating, ready/bidding/doubling, legal move execution, original DouZero FullAuto and optional compatible API inference, settlement, repeated rounds, account switching, unexpected socket disconnections, process restarts, upgrades, shutdown and rollback.

Evaluate functional correctness, phase transitions, race conditions, transport ownership, liveness and timeouts, retry budgets, recovery behavior, resource/memory leaks, UX messaging, safe credentials and data persistence, Windows portability, observability, documentation and test coverage. Do not conflate a QR scan confirmation with a usable game lobby connection. Distinguish browser WebSocket connectivity from a real Tencent Lotus connection.

Each finding must contain severity (P0 blocker, P1 major, P2 improvement), exact file/line evidence, user impact, root cause versus hypothesis, the safest minimal solution, automated regression scenarios, and concrete Windows/live-game acceptance tests. Compare historical experimental patches against the untouched `main`; do not reuse a patch merely because its CI passed if a real QR-login regression was observed.

Preserve unmodified DouZero weights, legal move generation, original control-path heuristics, API model strategy and game protocol until reliable functional evidence supports a change. Do not tune AI behavior based on a single lost game. Do not merge to main, distribute updates, delete any user's data, rotate encryption keys or reconfigure databases during the audit.

Deliver: (1) a production-readiness verdict with stated confidence, (2) a severity-ranked issue ledger, (3) a dependency-aware correction plan, (4) deterministic regression tests and observable telemetry requirements, (5) manual real-Windows smoke-test scripts for QQ and WeChat, and (6) precise go/no-go release gates. State clearly anything that cannot be demonstrated without the user's Windows environment and real game credentials.

## Execution notes

This instruction has been executed as a static review of the pinned `main`, compared with previously failing experimental branch context and historical user-provided runtime diagnostics. See [findings](./findings.md). It does **not** claim to have executed a real game-account login.
