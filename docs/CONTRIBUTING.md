# Contributing to Switchly
Thanks for your interest in contributing to Switchly.

## Repository scope
This repository contains the public release code of Switchly.

Development may sometimes continue in the private test repository before release-ready changes are synced here.
To avoid overlapping work and unnecessary merge conflicts, please check in first before starting larger changes.

## Getting started
1. Install the latest stable version of Android Studio
2. Use JDK 17
3. Clone the repository
4. Build the app:

```bash
./gradlew :app:assembleDebug
```

## Firebase (optional)
Switchly builds without Firebase.

Firebase is only needed if you want to work on features related to:
- Auth
- Sync
- Crashlytics

To set it up:
1. Create a Firebase project
2. Add an Android app with the application ID `at.saltyy.switchly`
3. Download `google-services.json`
4. Either place it in `app/google-services.json` locally, or keep it outside the repo and set `GOOGLE_SERVICES_JSON_PATH` in `signing.properties`

`google-services.json` and `signing.properties` are intentionally git-ignored and should not be committed.

## Guidelines
- Keep changes focused and easy to review
- Prefer small merge requests over large rewrites
- Avoid unrelated cleanup in the same merge request
- Do not commit generated files such as `build/` outputs
- Keep user-facing strings in resources
- Keep English and German translations aligned when possible
- Try to keep file structure and naming consistent with the existing project

## Naming
- `*Activity`, `*Fragment`: screen entry points
- `*Adapter`: list or RecyclerView adapters
- `*Store`: key-value persistence only
- `*Repository`: grouped feature data access
- `*Runtime`: runtime or system state handling
- `*Mapper`: mapping between models or UI data
- `*Formatter`: display formatting
- `*Validator`: validation and rule checks

## Merge requests
Please include:
- what you changed
- why you changed it
- screenshots or screen recordings for UI changes, if relevant
- notes about behavior changes, especially around blocking, schedules, permissions, NFC, or profiles

