# Background settings restoration and external-player diagnostics

**Goal:** Preserve the complete sound profile after background/process recreation, verify saved target-app intent on Android13, and show why a player cannot connect without reporting a false connection.

**Approach:** Reproduce persistence gaps before edits, retain the existing preferences/JSON format, persist missing presentation and spatial state together, and test external settings independently from live session handles. Keep YouTube support claims within the actual Android session API.

- [x] Add and run regression tests for preset identity, spatial state, EQ values and target selection after recreation.
- [x] Reproduce the supported-session and no-session connection paths on a private emulator, using Android13 where available.
- [x] Fix confirmed restoration gaps and make missing-session feedback actionable in Japanese and English.
- [x] Run builds/lint/regressions and create a signed update with the existing release key; record unverified device-specific behavior.

The reported device is Android13. Both sound settings and target-app settings are reported to reset. A separate existing emulator belongs to the user and is read-only during this investigation.
