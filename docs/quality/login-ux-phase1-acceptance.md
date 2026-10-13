# Login UX Phase 1 — Windows acceptance checklist

Baseline: original `main` at `37be0f1a39da66eedad8026dfa1dad8ca8caa424`.

## Scope

This patch changes only the browser's QR login reporting, session selection, and lobby connection indicator. It does **not** change QQ/WeChat authentication requests, SafeLogin, Lotus or GameServer transports, database schemas, saved credentials, DouZero models, or card tactics.

## Automated checks

Run `./gradlew clean test bootJar --no-daemon` using Java 17 and Node 24. This runs the frontend Prettier, ESLint, Vitest suites and Java tests before building the bootable JAR.

Additional Vitest checks simulate QR confirmation, delayed hall authorization, failure, stale session selection, and concurrent unrelated session snapshots.

## Manual acceptance (required before production)

Use a disposable copy of the Windows installation, preserve the original `D:\Happy4J` source/JAR and MySQL data, and test against a real Tencent account:

1. Start the existing MySQL and Java launcher; confirm the website login and WebSocket are healthy.
2. QQ QR: scan and approve on the phone. The modal must **not** say “游戏账号登录成功” solely on confirmation; show a pending game auth/hall state until `HALL_CONNECTED`.
3. When the hall actually connects, confirm the avatar's connected indicator, selected UIN, player profile, and wallet refresh. The QR dialog should close only after connection succeeded.
4. Repeat using the WeChat QR provider. Confirm no stale session/profile is shown instead of the newly scanned account.
5. Inject or observe a SafeLogin failure after successful phone QR confirmation. The QR dialog must remain open and display failure, not success.
6. Refresh the browser with an old failed/disconnected session and a newer healthy session. The healthy session should be selected without changing accounts behind the user's back.
7. With a QR attempt pending, deliver a late snapshot from another session. The pending QR target must remain selected.
8. Test QR expiry and reattempt; verify no stale QR status from a different session is shown as a current success.
9. Verify manual room entry, DouZero mode, and logout still behave as in the original release.

### Go/no-go

Do not merge or distribute as production if any actual QQ/WeChat scan cannot identify the correct account, if the lobby status is wrong, or if the original room-entry/gameplay flow regresses. Passing CI **alone** does not meet the release gate.

Follow-on scope only after actual device evidence: safely reclaiming stale Lotus connection capacity, lifecycle-aware transport callbacks, investigating observed idle disconnects, and stopping autoplay when there is no verified hall connection.
