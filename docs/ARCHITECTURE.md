# Switchly architecture notes
Switchly is an Android app for profile-based blocking. The app is intentionally split into feature, runtime, platform receiver, data/prefs, premium, security and UI areas.

## Important packages
- `at.saltyy.switchly.blocking` — Accessibility and blocking runtime logic
- `at.saltyy.switchly.data.prefs` — local stores and schedule/profile settings
- `at.saltyy.switchly.data.sync` — cloud and local backup/restore runtime
- `at.saltyy.switchly.feature.*` — screens and feature-specific UI
- `at.saltyy.switchly.nfc` — NFC/deep-link command schema and tag entry handling
- `at.saltyy.switchly.platform.receiver.*` — Android receivers/services for schedule, Wi-Fi, Bluetooth, location and system events
- `at.saltyy.switchly.premium` — Play Billing, external/direct payment and redeem-code handling
- `at.saltyy.switchly.security` — app lock and related safety helpers

## Flavor boundaries
Runtime behavior is controlled by flavor BuildConfig flags:
- `full` — Firebase + Google Sign-In + Google Play Billing
- `firebaseEmail` — Firebase email/password + external/direct Premium + online redeem codes
- `offline` — no Firebase initialization, no online purchase/restore, local offline redeem codes

Do not enable external/direct payment UI in the Google Play build.

## Release-sensitive checks
Before release, validate all flavors, run lint, verify Premium source labels, test backup/restore, and test exported NFC/QR/barcode action paths with malformed inputs.
