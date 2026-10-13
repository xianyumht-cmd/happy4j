Happy4J portable update package
===============================

This package is built from the fix/ai-decision-reliability branch.
It is intended for Windows 10 x64 with an existing Happy4J + Java 17 +
portable MySQL 8.4 installation. No Docker is needed.

Contents:
  happy4j-0.0.1-SNAPSHOT.jar  -- Java application + built Vue frontend
  Update.cmd                  -- one-click updater
  Apply-Update.ps1            -- validated, backed-up JAR replacement
  SHA256SUMS.txt              -- checksum for distributed JAR
  BUILD-INFO.txt              -- exact commit and CI metadata

Before update:
  1. Close Happy4J normally (Java process only; do not stop MySQL if
     that would interrupt unrelated work).
  2. Back up your portable MySQL database and existing Happy4J folder
     using your normal backup process.
  3. Keep the ORIGINAL launcher and environment variables unchanged.
  4. Extract this ZIP to a separate folder, not D:\Happy4J.

Install:
  Run Update.cmd. Default installation path is D:\Happy4J.
  The updater only replaces an EXACTLY MATCHING existing JAR file.
  It refuses to guess when zero or multiple matching files exist, refuses
  an active Happy4J Java process, backs up the original JAR to
  D:\Happy4J\_upgrade_backup\<timestamp>, and verifies SHA256.

  For custom layouts, run PowerShell manually with -InstallRoot and
  -TargetJar (an existing JAR path inside the install folder).

After update:
  Restart using the ORIGINAL launcher. Check localhost:8080, lobby login,
  Luna connection test (openai/gpt-6-luna), AI play/pass diagnostics, and
  settlement statistics before auto-play. Keep real-time control available.

Rollback:
  Close Happy4J normally, then restore the backup JAR to its original
  JAR path. The updater does not touch MySQL data or configuration.
  No database schema migration is part of this update.

Limitations:
  This is an application-only upgrade. It does NOT install Java, Node,
  MySQL, or Windows services. It does NOT restart your process. Actual
  Windows/game-server integration must still be validated locally.
  UI history win/loss counters are not durable all-time statistics.

Repository: https://github.com/xianyumht-cmd/happy4j
Draft PR: https://github.com/xianyumht-cmd/happy4j/pull/1
