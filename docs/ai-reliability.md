# AI decision reliability and settlement accounting

This document describes the audit performed against fork `main` at `37be0f1`.
That commit was identical to upstream at the time of review. Local PowerShell
patches in `D:\Happy4J` were **not** present in the GitHub repository and were
not available for comparison. No claims are made about the current Windows
installation.

## Source of truth

- Backend round reduction: `application/DdzTableStateMachine.java`.
- Model request/response: `application/OpenAiDecisionService.java`.
- Browser orchestration: `frontend/src/composables/use-game-ai.js`.
- Statistics: `frontend/src/ai/settlement.js`.
- Local baseline: `frontend/src/ai/douzero-fullauto.js`.
- Server events: `domain/ClientSession.java`, `application/GameEventPersistenceWriter.java`.
- The database already has a unique key on `(session_id, event_index)` in V1.
  This **does not** by itself deduplicate two distinct settlement notifications.

## Verified code defects and mitigations

1. Both GameSo `SETTLEMENT` (5) and `ROUND_END` (102) appended
   `本局结束` regardless of current stage. Repeated messages can now update
   settlement detail without appending another round-end marker.
2. A new round could inherit the previous round's public action history.
   `GAME_START`, `DEAL_START` and `INITIAL_HAND` now clear it.
3. Stats formerly counted any positive bean delta as a win and keyed rounds by
   mutable history length. The new audit requires an end marker scoped to the
   session and table, and a detailed settlement with three distinct seats and
   exactly one explicit empty hand. Ambiguous results remain **unknown**.
   `resultFlag` and monetary deltas are not interpreted as a victory flag.
4. The OpenAI-compatible path formerly called the provider even when there was
   only one validated legal action. The rule engine now returns that choice
   directly. Invalid seat/landlord state is rejected before multi-choice
   LLM decisions.
5. The model prompt now identifies the local role and teammate seats explicitly.
   The completion budget defaults to 1024 tokens and is configurable via
   `HAPPY4J_AI_MAX_OUTPUT_TOKENS` (clamped between 256 and 4096).
   A provider's `finish_reason` is exported in the decision diagnostics.
6. The browser now labels decision provenance and fallback errors. It rate-limits
   initial wallet refresh retries and prevents blind resend of non-idempotent
   game commands following ambiguous transport failures.

## Statistics limitations

The browser keeps only the most recent 300 log entries. Its displayed counts are
**not** a database-backed historical win rate. Old logs based on bean changes
are intentionally excluded from the audited counter. The last settlement key
is stored locally to avoid recounting the same snapshot after a page refresh.
An interrupted round without an end marker cannot be assigned a reliable ID.
If the protocol omits remaining-hand data, win/loss remains unknown.

To establish durable all-time statistics, a future migration should introduce
a protocol-backed per-round identity and persist the result transactionally.
Do not infer such an identity from table ID, room ID, or wall-clock time alone.

## Testing

From a clean checkout with Java 17 and Node.js 24:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat bootJar --no-daemon
```

Or on Linux use `./gradlew test --no-daemon`.
Gradle runs Prettier, ESLint and Vitest as part of the test task.
The PR workflow runs the same checks with Java 17 / Node 24.
Do not interpret an unrun or failing check as a pass.

The following still require live, consented games or representative,
sanitized captured fixtures before they can be declared resolved:

- Luna strategic pass behavior versus DouZero-only on comparable states.
- Endgame search and forced-win detection.
- Stale reconnection snapshots and lobby/GameSvr recovery.
- Server-specific winner semantics on incomplete settlements.
- Real-game win rate, latency and token cost at adequate sample sizes.
- Duplicate command suppression after a transport timeout where the server
  accepted a request but its acknowledgement was lost.

## Windows portable installation (after PR review only)

This branch is **not** installed or released automatically.

1. Back up `D:\Happy4J`, especially portable MySQL data and local configuration.
2. Check out the approved commit into a **separate** folder, not the existing
   installation; use Java 17 and Node.js 24 to run both test and bootJar tasks.
3. Stop the existing Java process before replacing the application JAR.
   Keep the existing database and user configuration intact.
4. Start the approved JAR with the same environment and review logs, model
   diagnostics and settlement counters before enabling autoplay.
5. Restore the backed-up JAR if errors occur; do not delete or reinitialize
   the portable MySQL data.

No database migrations are added in this change set. There is no Docker
requirement for this workflow.
