# Japanese / English Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Localize the Android app for Japanese and English, keeping existing sound settings compatible.

**Architecture:** Android string resources with English fallback, Japanese overrides and platform locale configuration. Immutable `UiText` stores resource IDs / arguments in controller and session state; UI and notifications resolve text using their current context. Stable preset IDs and legacy persisted titles remain unchanged.

**Tech Stack:** Kotlin, Jetpack Compose, Android resources, JUnit instrumentation, Python resource validation.

**Spec:** `docs/superpowers/specs/2026-11-10-japanese-english.md`.

## Global Constraints

- Android 10 / API 29 minimum; compile/target SDK 35.
- No new runtime dependencies or changes to DSP / JNI / preset JSON.
- Keep all existing user changes and signing keys.
- Resource prefixes `ui_`, `player_`, `service_` separate independently owned files.

## Review Focus

- Switching locale must not leave stale translated message strings in process state.
- Names entered by the user and saved preset identities must survive language changes.
- Accessibility descriptions and notifications must follow the selected language.
- Translation placeholders must agree; non-Japanese locales fall back to English.
- English text must fit narrow screens and large fonts.

## Tasks

- [x] First run a failing supported-language / resource contract check.
- [x] Controller worker: introduce `UiText.resolve(Context)`, localize controller / session state and input errors; own `player_strings.xml` and model files.
- [x] Service worker: localize playback and external EQ notifications, channels and MainActivity fallback; own `service_strings.xml` and service files.
- [x] UI owner: extract Compose labels and semantics; localize preset presentation by stable ID; add locale configuration and bump patch version.
- [x] Add resource-selection and bilingual navigation instrumentation; update existing UI tests to use current resources.
- [x] Build / Lint, run tests in a disposable F-drive AVD, review integrated changes and sign 0.4.1 with the existing key.
- [x] Update language documentation and record exact verification / limits.
