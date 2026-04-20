# Switchly Architecture Notes

## Core idea
Switchly is an Android app for profile-based app blocking.
The long-term direction is to keep these concerns separate:

- **UI**: Activities, Fragments, adapters, widgets
- **Feature/domain logic**: rules, validation, mapping, orchestration
- **Persistence/data**: preferences, local database, sync
- **Platform/runtime**: accessibility, receivers, tiles, NFC, Android system integration

## Practical layering
```text
UI -> Feature/domain -> Data/Store -> Runtime/Platform
```

A screen class should not become the place where all feature logic accumulates.

## Preferred package direction
```text
feature/<name>/ui
feature/<name>/domain
feature/<name>/data
feature/<name>/model
```

Shared UI/system helpers should live outside feature screens in dedicated shared packages.

## Design rules
1. Activities and Fragments are entry points, not business-logic containers.
2. Stores are for persistence, not UI orchestration.
3. Runtime classes handle active system behavior.
4. Shared UI patterns should be centralized.
5. New feature code should follow existing naming and placement rules.
