# Google sign-in release certificates
Google sign-in verifies both the Android package name and the SHA-1 fingerprint of the certificate that signed the installed APK.

Switchly can be installed with two different official certificates:
- The direct-download `full` APK is signed with the Switchly release keystore.
- The Play Store build is signed with the Google Play App Signing certificate.

Both SHA-1 fingerprints must be registered for the Android app `at.saltyy.switchly` in Firebase/Google Cloud. After changing fingerprints, download the updated `google-services.json` and rebuild the release artifacts.

## Direct-download APK
The `release-apk` task now reads the configured release keystore and verifies that its SHA-1 exists as an Android OAuth client in `app/google-services.json`. The build fails before packaging if the direct-download certificate is missing.

You can also inspect a built APK with Android Build Tools:
```text
apksigner verify --print-certs Switchly-2.2.3-full.apk
```

## Play Store build
Copy the SHA-1 from Google Play Console → App integrity → App signing key certificate and register it in the same Firebase Android app. This certificate differs from the local upload/release key when Play App Signing is enabled.
