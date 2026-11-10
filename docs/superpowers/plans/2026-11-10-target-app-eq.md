# Target-app EQ Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Persist explicitly selected apps and EQ listener preferences, offer tap-to-connect, and automatically apply device EQ to supported sessions.

**Architecture:** SharedPreferences-backed StateFlow settings, a selection-aware foreground service, lifecycle/boot restoration, and a searchable Compose app picker. No PCM interception or forced output routing is claimed.

**Tech Stack:** Kotlin, Compose, Android Equalizer / PackageManager / foreground services, existing JUnit instrumentation.

**Spec:** `docs/superpowers/specs/2026-11-10-target-app-eq.md`.

## Global Constraints

- API29+ / target35; no new dependencies, recording permissions, global session0 or root changes.
- Preserve current uncommitted work, localization, signing key and preset JSON.
- Settings keys / public APIs follow the spec. Backend resources use `external_service_`; UI uses `external_ui_`.

## Review Focus

- Unselected and deselected apps must not retain effects.
- OS recreation must not re-enable an explicitly stopped listener.
- No matching session means waiting, never a fabricated connection.
- Reconnect must retry a refused or lost effect, with bounded session resources.
- Save stable package identities; never persist ephemeral session IDs.

## Tasks

- [x] Run the real unselected-player regression against the old implementation and confirm failure.
- [x] Backend worker: settings store, service selection/filter/reconnect, sticky lifecycle, startup coordinator and boot receiver; localize backend messages.
- [x] UI worker: extract external EQ panel, add searchable picker, saved targets with connection actions and restore toggle; maintain English/Japanese.
- [x] Owner: manifest queries / boot declaration, version0.4.2 code6, expanded integration and UI tests, docs.
- [x] Build/Lint and test selected/unselected/reconnect/persistence, then review integration and boot behavior.
- [x] Sign with existing key, verify upgrade, capture UI and record precise platform limitations.
