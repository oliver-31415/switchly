# APK Variants
Switchly can be published as multiple APK variants plus a Google Play App Bundle.

All variants use the same package name:
```text
at.saltyy.switchly
```

Only one variant should be installed at a time.

---

# Variants
| Variant          | Gradle flavor          | Description                                                                        |
| ---------------- | ---------------------- | ---------------------------------------------------------------------------------- |
| `full`           | `fullRelease`          | Google sign-in, Firebase auth, cloud backup, file backup, Google Play Billing      |
| `firebase-email` | `firebaseEmailRelease` | Firebase email/password auth, cloud backup, file backup, external checkout support |
| `offline`        | `offlineRelease`       | Local-only build without Firebase initialization or Premium purchase flows         |
| `full-playstore` | `fullRelease` AAB      | Google Play Store release bundle                                                   |

---

# Offline flavor behavior
The offline flavor disables Firebase functionality at runtime using:
```text
BuildConfig.SWITCHLY_FIREBASE_ENABLED=false
```

This variant:
* does not initialize Firebase
* disables Premium purchase/restore/unlock
* keeps local JSON backup/restore available

Some shared Google/Firebase dependencies may still exist because common source files reference them.

A fully dependency-free FOSS flavor would require moving implementations into flavor-specific source sets.

---

# Build all release artifacts
Build all APKs plus the Play Store bundle:
```bash
./gradlew :app:release-apk
```

Equivalent alias:
```bash
./gradlew :app:releaseApk
```

Outputs are written to:
```text
dist/
```

---

# Individual Gradle tasks
```bash
./gradlew :app:assembleFullRelease
./gradlew :app:assembleFirebaseEmailRelease
./gradlew :app:assembleOfflineRelease
./gradlew :app:bundleFullRelease
```

---

# Firebase configuration
Firebase-enabled builds require:
```text
app/google-services.json
```

Or an external path via:

```properties
GOOGLE_SERVICES_JSON_PATH=/path/to/google-services.json
```

The file is copied into the app module during Gradle configuration and should not be committed.

---

# Optional signing.properties values
```properties
GOOGLE_WEB_CLIENT_ID=
MAPS_API_KEY=

SWITCHLY_RELEASE_STORE_FILE=
SWITCHLY_RELEASE_STORE_PASSWORD=
SWITCHLY_RELEASE_KEY_ALIAS=
SWITCHLY_RELEASE_KEY_PASSWORD=

SWITCHLY_EXTERNAL_PAYMENT_PROVIDER=stripe
SWITCHLY_EXTERNAL_CHECKOUT_URL=https://example.com/checkout/
SWITCHLY_EXTERNAL_CUSTOMER_PORTAL_URL=https://example.com/customer-portal/
```

These values may also be provided through:

* environment variables
* Gradle properties
* `-P...` command line arguments

---

# Output names

Generated release artifacts:

```text
Switchly-<version>-full.apk
Switchly-<version>-firebase-email.apk
Switchly-<version>-offline.apk
Switchly-<version>-full-playstore.aab
```

Example:
```text
Switchly-x.x.x-full.apk
Switchly-x.x.x-firebase-email.apk
Switchly-x.x.x-offline.apk
Switchly-x.x.x-full-playstore.aab
```
Use the `.aab` file for the Google Play Console.

---

# External checkout support

The `firebase-email` flavor can optionally support external Premium checkout systems.

Example configuration:
```properties
SWITCHLY_EXTERNAL_PAYMENT_PROVIDER=stripe
SWITCHLY_EXTERNAL_CHECKOUT_URL=https://example.com/checkout/
SWITCHLY_EXTERNAL_CUSTOMER_PORTAL_URL=https://example.com/customer-portal/
```

Public checkout URLs are safe to compile into release builds.

Server-side secrets should remain private and must never be committed into the repository.
