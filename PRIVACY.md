# Privacy Notes
This document is a **high-level** overview of data handling in Switchly.
It is not legal advice.

## Local data
- App preferences and profiles are stored locally on the device (SharedPreferences / Room).
- App blocking decisions are made on-device.

## Optional cloud features (Firebase)
Cloud features are **disabled** unless you add `app/google-services.json` and rebuild.

When enabled:
- **Firebase Authentication** is used for sign-in.
- **Cloud Firestore** may be used to sync/backup certain settings and stats tied to the signed-in user.
- **Firebase Crashlytics** may collect crash reports to help diagnose app stability issues.

What exactly gets stored/sent depends on the feature you use and how your Firebase project is configured.

## Third-party services
If you enable Firebase, data is processed by Google/Firebase services under their terms.

## Questions / contact
See the in-app About screen for contact details.
