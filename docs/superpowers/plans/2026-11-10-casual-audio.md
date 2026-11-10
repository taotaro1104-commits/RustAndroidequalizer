# Casual audio UI implementation plan

> **For agentic workers:** Execute inline with the executing-plans workflow. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the native Android EQ casual, visual and easy to navigate while retaining all existing operations.

**Architecture:** Keep PlayerController and the Rust/JNI audio path. MainActivity owns pickers and screen navigation; reusable theme, icons and visual sound components live under `dev.rusteq.ui`. No new runtime dependencies.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose Material 3, native Canvas/vector paths, Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-11-10-casual-audio-design.md`.

## Global constraints

- Android API 29 minimum; retain the 16 presets and 10/31-band editing.
- Retain save/delete, JSON/PK import, JSON export, CSV fitting, spatial imports, external EQ and metrics.
- Use 48 dp touch targets, accessible names and scrollable responsive content.
- Keep previous uncommitted DSP changes; do not commit or publish automatically.

## Review focus

- Navigation must preserve configuration and each page's scroll position.
- Long filenames and 200% font scale must remain readable and operable.
- EQ bypass and enabled effect states must be visible without relying only on color.
- Import and custom-preset actions must remain reachable alongside factory presets.
- The curve and its precise sliders must both remain usable; decorative graphics must not imply live measurement.

## Task 1: Navigation and visual sound controls

**Files:** Modify `android/app/src/main/java/dev/rusteq/MainActivity.kt`; create `ui/AudioTheme.kt`, `ui/AudioVisuals.kt`; add `android/app/src/androidTest/java/dev/rusteq/DesignNavigationTest.kt`.

- [x] Add an instrumentation test that navigates Sound / Space / Tools, selects Vocal and verifies the config survives navigation.
- [x] Run the test against the old UI and observe the missing navigation failure.
- [x] Implement the theme, icons, player, sound/preset/bass view, spatial view and tools view using the existing controller methods.
- [x] Build debug + test APKs and run the instrumentation test and existing tests.

## Task 2: Visual verification and documentation

- [x] Inspect native emulator screenshots at phone/tablet widths and large font scale; correct clipping and hierarchy.
- [x] Run Android Lint and Rust workspace tests; review the final diff.
- [x] Save representative screenshots and document validation results.

## Execution record

- Continued in the existing shared workspace, retaining prior DSP edits, under the user's instruction to proceed without confirmation. No branch, commit, deployment or dependency installation was needed.
- Code review found two regressions during implementation: unloaded queue playback and disposed page-local state. Both were fixed and added to the instrumentation coverage.
- Debug and release builds pass for both supported Android ABIs; Rust 35/35 and Lint pass. Android instrumentation 13/13 passes, including the independent review fixes.
- Visual verification includes actual native phone, narrow/large-font and tablet screenshots. See `docs/ui-design-validation.md`.
