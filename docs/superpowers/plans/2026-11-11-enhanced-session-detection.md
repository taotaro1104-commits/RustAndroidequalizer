# Enhanced session detection implementation plan

> **For agentic workers:** Use executing-plans for inline implementation. The user has requested continuous execution without confirmation.

**Goal:** Discover non-announcing selected players on Android 13 and connect their real sessions to the existing platform Equalizer.

**Architecture:** An opt-in foreground-service worker reads a bounded audio-service dump with the app's explicitly granted DUMP permission. A pure parser accepts only current playback rows, resolves selected application UIDs, and reconciles discovery-owned effects separately from announced sessions. Playback callbacks plus a bounded polling interval handle already-playing apps and session changes.

**Tech Stack:** Existing Kotlin/Compose/coroutines, Android audio services, JUnit/instrumentation. No new dependencies or copied third-party code.

**Spec:** `docs/research/2026-11-11-youtube-eq-options.md` (first implementation stage only).

## Global constraints

- minSdk 29, targetSdk 35, Japanese and English; existing settings and standard broadcast mode remain supported.
- Explicit opt-in and a visible ADB permission setup instruction. No automatic grant, recording, root, global session 0, or arbitrary session guessing.
- Dumps and parsing run off the main thread with real time/output limits. Raw system dumps are not persisted.
- Selected packages only; ambiguous shared UIDs, own UID, non-media usage and invalid IDs are rejected.
- A permission failure is distinct from a successful empty scan. Stale scans cannot attach after stop, selection change or opt-out.
- Test only the private `rusteq_fix13` / emulator-5584; leave the user's emulator-5554 untouched.

## Review focus

1. Session IDs inside historical event logs must never attach effects.
2. Permission revocation and blocked reads must release discovery-only handles without blocking UI.
3. Deselection/stop during an in-flight scan cannot reattach a player.
4. Shared UIDs and one player with multiple sessions cannot cause cross-app effects.
5. Announced and discovered sources can overlap; losing one source must not close an effect still owned by the other.

## Tasks

### 1. Prove discovery parsing and process access

- [x] Add unit tests with literal Android 13 playback dump rows: multiple sessions, stopped/released rows, historical logs, invalid IDs, usage restrictions and permission-denied/unknown formats. Observe RED, then implement pure parser.
- [x] Implement a no-shell, bounded dump reader and test success, output limit, process timeout and cancellation cleanup.
- [x] Verify audio-service dump access from a normal app UID with DUMP on the private Android 13 emulator; change transport only if evidence requires it.

### 2. Wire settings, lifecycle and ownership

- [x] Add a silent fixture playback mode and an integration test that fails without discovery; assert actual Equalizer control, release, opt-out and target filtering.
- [x] Persist `enhanced_detection`, add worker/callback lifecycle and reconcile discovered versus announced session ownership. Reject stale scans.
- [x] Add explicit opt-in, permission status, copyable setup command, refresh action and actionable Japanese/English states. Keep standard operation available without DUMP.
- [x] Test saved opt-in, absent/revoked permissions, silent playback already active, multiple sessions, session replacement and stop/deselection.

### 3. Verify and deliver

- [x] Run full unit/instrumentation tests, process recreation, localization, debug/release builds and lint. Request independent code review; address important findings.
- [x] Build and sign a new release with the existing key; verify upgrade, alignment and package checksums. Record actual YouTube test scope honestly.
- [ ] Update documentation, commit/push as previously authorized, and prepare the distribution package. Stop only the private test emulator.

## Execution ledger

- Baseline: ce3815f, Rust EQ 0.4.3. Prior research document is untracked and must be preserved/included.
- Ruling: Work inline in the existing build workspace on a feature branch, retaining the SDK/native caches and private AVD. The user has opted out of confirmation; no additional worktree is needed for this single-author change.
- Ruling: Start with direct DUMP/ADB setup and no Shizuku dependency. Shizuku convenience setup and external PCM capture are separate stages, not prerequisites for discovering sessions with an already granted DUMP permission.
- Task 1: complete — observed six unit failures and the missing-connection integration failure before implementation. Added an actual Android13 inactive-row regression (RED then GREEN). Final unit suite: 9 passed, including real subprocess timeout/output bounds and child termination on cancellation.
- Task 2: feature code complete — the five discovery integration cases passed in the first full Android13 run, including normal app-UID dump access, silent multi-session playback, revocation, selection/opt-out, source overlap and late permission grant. Two UI cases were still red; do not claim a green Android suite yet.
- Ruling: a captured screenshot proved that a private-emulator System UI ANR dialog blocked later UI runs. Disable the private AVD's unneeded Google Search/Play-services packages and animations, dismiss its ANR dialog, and record this test-environment deviation in release metadata. The user's emulator remains untouched. Retain the real EQ-control assertions.
- Final review: independent reviewer inspected the source and identified the inactive-row prefix blocker; fixed and regression-tested. Follow-up found no further confirmed important issue. OEM dump differences and process-start/pipe behavior remain runtime limitations; current YouTube playback is not claimed verified.

- Final verification: Android37, unit9, Rust35 and both real-process restoration phases passed. The signed update retained settings and controlled the silent fixture. See docs/releases/0.4.4.json; latest YouTube playback remains unverified. Delivery is the remaining step.
