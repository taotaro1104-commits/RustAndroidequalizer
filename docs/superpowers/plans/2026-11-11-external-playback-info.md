# External playback information implementation plan

> **For agentic workers:** Use executing-plans inline. The user has requested continuous execution without confirmation.

**Goal:** Show selected YouTube Music/external-player track metadata and playback state in Sound and Playback details, independently from EQ attachment and local Rust DSP metrics.

**Architecture:** A user-authorized NotificationListenerService enables MediaSessionManager access. An application-owned monitor observes only selected external packages, updates metadata/state through MediaController callbacks, and clears data on deselection, session destruction or access loss. UI extrapolates position only while playing, identifies metadata access separately from DUMP/EQ, and labels local DSP metrics explicitly.

**Tech stack:** Existing Kotlin/Compose/coroutines, platform MediaSession APIs; no new libraries, capture, playback interception or remote services.

## Constraints and review focus

- Android min29/target35; English and Japanese. Existing local playback and external EQ stay intact.
- YouTube Music is `com.google.android.apps.youtube.music`, distinct from YouTube.
- Notification access is explicit Android user consent; show a settings link and Android13 restricted-settings guidance. No metadata history or notification-body processing.
- Metadata presence never implies EQ connection. Paused, buffering, remote playback, unknown title/duration and session destruction must be represented accurately.
- Reject own/unselected sessions and stale callbacks after selection or permission changes. Unregister callbacks on disconnect.
- Keep local playback controls/metrics associated with the local player. Do not populate external streams with native Rust output measurements.
- Only private emulator-5584 / rusteq_fix13 is mutable. Keep it awake and dismiss only observed stale System UI dialogs; record test-environment changes.

## Tasks and interfaces

### 1. Reproduce missing external information

- [x] Extend the separate-UID fixture with optional MediaSession metadata, playback-state updates and stop cleanup.
- [x] Run an instrumentation UI regression against existing 0.4.4: selected fixture plays a named external track, yet Playback details does not show it. Preserve RED evidence.
- [x] Add pure tests for selected-package filtering, per-app session selection, paused/buffering position, unknown metadata and clamping; observe RED before implementation.

### 2. Observe and present external media

- [x] Implement `ExternalPlaybackMonitor` / immutable `ExternalTrack` / `ExternalPlaybackState`; integrate application and listener lifecycle. Use MediaSessionManager callbacks with selected-package filtering and revoke/destroy cleanup.
- [x] Add a protected notification-listener component and foreground access refresh. Expose a permission-settings action; handle missing settings activities in localized UI.
- [x] Add the same external-playback content to Sound and expanded Playback details. Include title, artist, app, playback status, progress and separately evaluated EQ status. Show connected-app fallback when metadata access is missing.
- [x] Test metadata/title updates, pause/resume, selection changes, permission revoke/regrant, destroyed sessions and UI setup. Verify local-player diagnostics are still correctly scoped.

### 3. Validate and deliver

- [x] Run relevant/full unit and Android tests, existing Rust suite, localization, debug/release builds and lint; request an independent code review.
- [x] Sign 0.4.5 using the existing key, verify release/upgrade behavior and alignment, prepare versioned ZIP and documentation, commit/push, verify hashes and stop only the private AVD.

## Ledger

- Baseline main: 6bc3cd9, released 0.4.4. Working tree was clean.
- Root cause: `DiagnosticsPanel` consumes only `PlayerState.metrics`; neither it nor Sound subscribes to other apps' MediaSession metadata. DUMP discovery attaches EQ handles but does not supply song metadata.
- Ruling: Implement a read-only media-session display for selected apps, with explicit notification access. This solves the reported missing information without presenting metadata access as proof of EQ application.
- Ruling: Continue in the existing cached workspace on a feature branch, as authorized by the user's no-confirmation preference.

- Review: independent read-only review found no confirmed blocking defect; callback, selection, revoke/regrant and settings-flow integration tests complete the requested coverage.

- Delivery: implementation commit 781a4cab74a89cdb4f2049e835353a2d83c8001a pushed to main; versioned APK and source/license/verification ZIP verified. See docs/releases/0.4.5-delivery.json.
