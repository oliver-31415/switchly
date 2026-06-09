# APK variants
Switchly can be published as three direct-download APK options plus one Play Store Android App Bundle. All variants use the same package name (`at.saltyy.switchly`), so users should install only one variant at a time.

## Variants
| Variant | Gradle flavor | User-facing behavior |
| ------- | ------------- | -------------------- |
| `full` | `fullRelease` | Google sign-in, Firebase email/password sign-in, Firebase cloud backup, local file backup/restore, and Google Play Billing only |
| `firebase-email` | `firebaseEmailRelease` | Firebase email/password sign-in, Firebase cloud backup, local file backup/restore, no Google sign-in, external checkout URL support |
| `offline` | `offlineRelease` | No Google sign-in, no Firebase cloud backup, no Premium purchase/restore/unlock; local JSON file backup/restore remains available |

The offline flavor sets `BuildConfig.SWITCHLY_FIREBASE_ENABLED=false` and disables both Play Billing and external payments. Premium is intentionally unavailable in this build, even if old local Premium flags exist from another variant. Users who want Premium should install the `firebase-email` APK or the full Play Store build.

Note: current shared source files still reference Firebase/Google Play Services APIs, so the APK is runtime-offline but not yet dependency-free. A true FOSS/no-Play-Services flavor requires moving those implementations into flavor-specific source sets and using flavor-specific dependencies.

## Build APK options and Play Store bundle
Build all direct-download APKs and the full Play Store AAB with Gradle:
```bash
./gradlew :app:release-apk
```

Equivalent alias:
```bash
./gradlew :app:releaseApk
```

The Gradle task creates the three APKs and the full Play Store AAB in `dist/`.

## Individual Gradle tasks

```bash
./gradlew :app:assembleFullRelease
./gradlew :app:assembleFirebaseEmailRelease
./gradlew :app:assembleOfflineRelease
./gradlew :app:bundleFullRelease
```

## Required/optional local config
Firebase release builds require `app/google-services.json`. You can also keep the file outside the repo and point Gradle to it from `signing.properties`; Gradle loads this file automatically when it exists:

```properties
GOOGLE_SERVICES_JSON_PATH=/path/to/google-services.json
GOOGLE_WEB_CLIENT_ID=
MAPS_API_KEY=your-maps-api-key
SWITCHLY_RELEASE_STORE_FILE=/path/to/switchly-release.jks
SWITCHLY_RELEASE_STORE_PASSWORD=...
SWITCHLY_RELEASE_KEY_ALIAS=...
SWITCHLY_RELEASE_KEY_PASSWORD=...
SWITCHLY_EXTERNAL_PAYMENT_PROVIDER=stripe
SWITCHLY_EXTERNAL_CHECKOUT_URL=https://your-domain.example/pages/pay/checkout/
SWITCHLY_EXTERNAL_CUSTOMER_PORTAL_URL=https://your-domain.example/pages/pay/customer-portal/
```

For Google Sign-In, Gradle reads the Web client ID from `google-services.json` automatically. You can override it with `GOOGLE_WEB_CLIENT_ID` in `signing.properties` if needed.

The same values can also come from `~/.gradle/gradle.properties`, `-P...`, or environment variables.

`google-services.json` is copied to `app/google-services.json` during Gradle configuration when `GOOGLE_SERVICES_JSON_PATH` is set, because the Google Services Gradle plugin expects the file inside the app module. `app/google-services.json` is git-ignored and should not be committed.

`MAPS_API_KEY` should not live in committed `gradle.properties` or `local.properties`; keep it in `signing.properties`, user-level Gradle properties, or environment variables. External payment URLs are also loaded from `signing.properties`, user-level Gradle properties, or environment variables. See `docs/EXTERNAL_PAYMENTS_STRIPE.md` for the Stripe setup.

## Output names

The Gradle release task creates these files in `dist/`:
```text
Switchly-<version>-full.apk
Switchly-<version>-firebase-email.apk
Switchly-<version>-offline.apk
Switchly-<version>-full-playstore.aab
```

For version `2.1.0`, the expected files are:
```text
Switchly-2.1.0-full.apk
Switchly-2.1.0-firebase-email.apk
Switchly-2.1.0-offline.apk
Switchly-2.1.0-full-playstore.aab
```

Use `Switchly-<version>-full-playstore.aab` for the Google Play Console. Copy only the APK files into the website folder:
```text
pages/download/apk/
```

The download page automatically shows only variants whose APK files exist.

## Switchly external checkout configuration
For the `firebaseEmail` APK, configure the hosted checkout endpoints in `signing.properties` or via Gradle properties. Open-source/fork builds can leave these blank, but external Premium checkout will then be unavailable:
```properties
SWITCHLY_EXTERNAL_PAYMENT_PROVIDER=stripe
SWITCHLY_EXTERNAL_CHECKOUT_URL=https://your-domain.example/pages/pay/checkout/
SWITCHLY_EXTERNAL_CUSTOMER_PORTAL_URL=https://your-domain.example/pages/pay/customer-portal/
```

These URLs are public and safe to compile into the APK. Stripe/Firebase secrets stay only on the website/server in `.env`.
