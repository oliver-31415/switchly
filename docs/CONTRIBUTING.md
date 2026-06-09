# Contributing to Switchly
Thanks for your interest in contributing to Switchly.

This repository contains the public release code of Switchly. Development may sometimes continue in a private test repository before release-ready changes are synced here.

Before starting larger changes, please contact the maintainer so work can be coordinated and release/policy-sensitive areas stay aligned.

## Repository scope
Switchly has multiple build variants:
- `offline`: local/offline build without Firebase or Google Play Billing
- `firebaseEmail`: direct/Firebase build
- `full`: Google Play/full build

The public repository is expected to build the Offline variant without private Firebase files.

Firebase/Google builds may require private configuration such as `google-services.json`, which is intentionally not committed.

## Getting started
1. Install the latest stable version of Android Studio
2. Use JDK 17
3. Clone the repository
4. Build the Offline variant:
```bash
./gradlew :app:assembleOfflineDebug
```

For release validation of the public/offline build:
```bash
./gradlew :app:lintOfflineRelease :app:assembleOfflineRelease
```

## Firebase and Google builds
Firebase/Google configuration is only needed for features related to:
- Auth
- Sync
- Crashlytics
- Google Play Billing
- Google Maps/location picker in configured builds

To set it up locally:
1. Create or use a Firebase project
2. Add an Android app with the application ID `at.saltyy.switchly`
3. Download `google-services.json`
4. Place it in `app/google-services.json` locally, or configure the path outside the repository if supported by your local setup

`google-services.json`, `signing.properties`, keystores, secrets, tokens, and generated build outputs must not be committed.

Maintainer/full release validation may include:
```bash
./gradlew :app:lint \
  :app:assembleOfflineRelease \
  :app:assembleFirebaseEmailRelease \
  :app:assembleFullRelease \
  :app:bundleFullRelease
```

## Translation rules
All user-facing strings must live in Android resources.

Keep English and German resource keys in sync when possible.

## Policy-sensitive areas
Be extra careful with:
- Accessibility disclosure and service behavior
- Google Play Billing and Play Store flavor behavior
- external/direct payment links in non-Play builds
- location, Wi-Fi, Bluetooth and exact-alarm scheduling
- exported NFC/QR/barcode/deep-link entry points
- backup and restore data handling
- support/debug report contents

For changes touching Accessibility, schedules, Premium, billing, backup/restore, NFC/QR/barcode actions, blocking logic, or background services, test the affected flavor on a real device when possible.

## Guidelines
- Keep changes focused and easy to review
- Prefer small merge requests over large rewrites
- Avoid unrelated cleanup in the same merge request
- Do not commit generated files such as `build/` outputs
- Do not commit APK/AAB files unless explicitly requested for a release workflow
- Keep user-facing strings in resources
- Keep English and German translations aligned when possible
- Keep file structure and naming consistent with the existing project

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
- notes about behavior changes, especially around blocking, schedules, permissions, NFC, QR, barcode, Premium, or profiles
- what you tested, including device/flavor where relevant
