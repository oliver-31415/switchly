# Privacy Notes
This document provides a general overview of how Switchly handles data.
It is not a formal legal privacy policy.

## Local data
* App preferences, profiles, schedules, and configuration are primarily stored locally on the device.
* App blocking decisions are processed on-device.
* Local backups are only created when explicitly triggered by the user.

## Optional cloud features (Firebase)
Some Switchly variants optionally support Firebase-based features such as:
* Firebase Authentication
* Cloud backup/sync
* Firebase Crashlytics crash reporting

These features require a configured Firebase project and are not active in offline-only builds.

Depending on the enabled features and project configuration, limited account-related data, settings, backups, or crash diagnostics may be processed through Google Firebase services.

## Third-party services
When Firebase-based features are enabled, related data is processed through Google/Firebase services under their own terms and privacy policies.

Switchly may also support optional external payment providers depending on the APK variant.

## Open-source builds / forks
Public source releases may require developers to provide their own:
* Firebase project
* API keys
* payment configuration
* hosting configuration

Official Switchly infrastructure and credentials are not included in the repository.

## Questions / contact
See the in-app About screen or official website for contact information.
