# Switchly
[**Switchly**](https://switchly.saltyy.at) is an Android application for **profile-based app blocking**.
A lightweight background service monitors the currently foreground app and shows a blocking overlay whenever a restricted app is opened.

Designed for focus, control, and flexibility — without unnecessary complexity.

---

## Project Structure
Source code root:
```text
app/src/main/java/at/saltyy/switchly
```

Icons are based on **Material Symbols**:
[https://fonts.google.com/icons](https://fonts.google.com/icons)

---

## Localization/i18n
All user-facing text lives in **translations**, not hard-coded in Kotlin/XML:

* Default: `app/src/main/res/values/strings.xml`
* German: `app/src/main/res/values-de/strings.xml`

Guidelines:

* Use `getString(R.string.some_key)`/`@string/some_key`
* Prefer formatted strings (`*_fmt`) over string concatenation
* Keep EN + DE keys in sync with the same key set

This includes **Toasts, dialogs, notifications, and inline UI labels**.

---

## APK Build Options
Switchly supports three direct APK configurations plus one Play Store AAB.

| Variant          | Gradle artifact        | Backup/sign-in/payment behavior                                                                   |
| ---------------- | ---------------------- | ------------------------------------------------------------------------------------------------- |
| `full`           | `fullRelease`          | Google sign-in, Firebase email/password, Firebase cloud backup, file backup, Google Play Billing  |
| `firebase-email` | `firebaseEmailRelease` | Firebase email/password, Firebase cloud backup, file backup, external checkout URL for Premium    |
| `offline`        | `offlineRelease`       | File backup only; Firebase is not initialized; Premium purchase, restore, and unlock are disabled |
| `full-playstore` | `fullRelease` AAB      | Google Play Store build using Google Play Billing                                                 |

Build all APK options plus the full Play Store AAB with Gradle:
```bash
./gradlew :app:release-apk
```

Equivalent alias:
```bash
./gradlew :app:releaseApk
```

Outputs are written to `dist/` using website-friendly names:

```text
Switchly-<version>-full.apk
Switchly-<version>-firebase-email.apk
Switchly-<version>-offline.apk
Switchly-<version>-full-playstore.aab
```

Upload `Switchly-<version>-full-playstore.aab` to the Google Play Console. Put the APK files on the website download page.

Individual release tasks:

```bash
./gradlew :app:assembleFullRelease
./gradlew :app:assembleFirebaseEmailRelease
./gradlew :app:assembleOfflineRelease
./gradlew :app:bundleFullRelease
```

See [`docs/APK_VARIANTS.md`](./docs/APK_VARIANTS.md) for build details and [`docs/EXTERNAL_PAYMENTS_STRIPE.md`](./docs/EXTERNAL_PAYMENTS_STRIPE.md) for Stripe/external payment setup.

---

## Firebase/Google Services
Firebase support is optional for local/offline builds, but the `full` and `firebaseEmail` release artifacts need a valid Firebase config.

To enable Firebase release artifacts, place your Firebase config at:
```text
app/google-services.json
```

Or keep it outside the repo and point Gradle to it from `signing.properties`:
```properties
GOOGLE_SERVICES_JSON_PATH=/path/to/google-services.json
```

For Google Sign-In, Gradle reads the Web client ID from `google-services.json` automatically. You can override it if needed:
```properties
GOOGLE_WEB_CLIENT_ID=your-web-client-id.apps.googleusercontent.com
```

Google Maps and release signing are also configured through `signing.properties`:

```properties
MAPS_API_KEY=your-maps-api-key

SWITCHLY_RELEASE_STORE_FILE=/path/to/switchly-release.jks
SWITCHLY_RELEASE_STORE_PASSWORD=...
SWITCHLY_RELEASE_KEY_ALIAS=...
SWITCHLY_RELEASE_KEY_PASSWORD=...
```

You can still override any of those via `-P...` or environment variables, for example:

```bash
./gradlew :app:release-apk -PGOOGLE_SERVICES_JSON_PATH=/path/to/google-services.json
```

Notes:
* Firebase config is not required for `assembleOfflineRelease`
* The offline flavor sets `BuildConfig.SWITCHLY_FIREBASE_ENABLED=false`, skips Firebase initialization at runtime, and disables Premium purchase, restore, and unlock completely
* Users who want Premium should install the Firebase email/password APK or the full Play Store build
* Current offline builds still include common Google/Firebase dependencies where shared source files reference them; a dependency-free FOSS flavor requires moving those implementations into flavor-specific source sets
* Google sign-in is enabled only in the `full` flavor
* Firebase email/password auth is enabled in `full` and `firebaseEmail`
* Local file backup/restore remains available in all flavors

---

## Public Links and Contact Configuration
Official builds can compile public website/contact links through `signing.properties`. Forks can leave these blank or replace them with their own URLs.
```properties
SWITCHLY_WEBSITE_URL=https://your-domain.example
SWITCHLY_DOWNLOADS_URL=https://your-domain.example/pages/download
SWITCHLY_SUPPORT_EMAIL=support@example.com
SWITCHLY_DEV_EMAIL=dev@example.com
```

These values are public and safe to compile into the APK.

---

## Switchly External Checkout Configuration
For the `firebaseEmail` APK, configure hosted checkout endpoints in `signing.properties` or via Gradle properties. Open-source/fork builds can leave these blank, but external Premium checkout will then be unavailable.

```properties
SWITCHLY_EXTERNAL_PAYMENT_PROVIDER=stripe
SWITCHLY_EXTERNAL_CHECKOUT_URL=https://your-domain.example/pages/pay/checkout/
SWITCHLY_EXTERNAL_CUSTOMER_PORTAL_URL=https://your-domain.example/pages/pay/customer-portal/
```

These URLs are public and safe to compile into the APK. Stripe/Firebase secrets stay only on the website/server in `.env`.

Payment behavior by build:
* `full`/Play Store AAB uses Google Play Billing
* `firebase-email` can use the configured external checkout URL and restores Premium through Firebase entitlements
* `offline` has no Premium purchase, restore, or unlock flow

---

## Shrinking: Unused Code and Resources
Release builds enable:
* **R8/minification** (`minifyEnabled true`)
* **Resource shrinking** (`shrinkResources true`)

This means most unused code/resources are removed automatically at build time.

To verify locally:

```bash
./gradlew :app:assembleOfflineRelease
```

---

## Supported Android Versions
| Requirement | Value                    |
| ----------- | ------------------------ |
| **Min SDK** | **Android 8.1 (API 27)** |
| Target SDK  | 36                       |
| JDK         | 17                       |
| Kotlin      | 2.2+                     |
| AGP         | 8.9+                     |

---

## Versioning
Switchly follows **MAJOR.MINOR.PATCH**.

| Type  | Example | Description                       |
| ----- | ------- | --------------------------------- |
| Patch | `1.0.1` | Bug fixes                         |
| Minor | `1.1.0` | New features, backward-compatible |
| Major | `2.0.0` | Breaking changes                  |

---

## Contributing

Before starting a contribution, please contact me first:
**[andi@saltyy.at](mailto:andi@saltyy.at)**

Please also read:
* [`CONTRIBUTING.md`](./CONTRIBUTING.md)
* [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md)

---

## Useful Commands
### Run lint
```bash
./gradlew clean lint
```

### Get info about all dependencies
```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

---

## Contributor
**Andi S.**
[https://saltyy.at](https://saltyy.at)

---

## License
Switchly is licensed under the **GNU General Public License v3.0**.

You are free to use, modify, and distribute this software, but any distributed modifications must also be licensed under **GPLv3**.

See:

* [`LICENSE`](./LICENSE)
* [`NOTICE`](./NOTICE)

---

**Made with ♥️ and 🍪 by saltyy**
