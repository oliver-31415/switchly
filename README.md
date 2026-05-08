# Switchly
[**Switchly**](https://switchly.saltyy.at) is an Android app for profile-based app and website blocking.

It is designed to help with focus, routines, and self-control by letting users define blocking profiles and control how those profiles can be enabled or disabled.

---

## Project Structure
Main source code:
```text
app/src/main/java/at/saltyy/switchly
````

Important docs:
```text
docs/APK_VARIANTS.md
docs/ARCHITECTURE.md
docs/EXTERNAL_PAYMENTS_STRIPE.md
CONTRIBUTING.md
PRIVACY.md
SECURITY.md
```

Icons are based on Material Symbols:
[https://fonts.google.com/icons](https://fonts.google.com/icons)

---

## APK Variants
Switchly supports multiple APK variants plus a Play Store bundle.

| Variant          | Description                                                                        |
| ---------------- | ---------------------------------------------------------------------------------- |
| `full`           | Google sign-in, Firebase auth, cloud backup, file backup, Google Play Billing      |
| `firebase-email` | Firebase email/password auth, cloud backup, file backup, external checkout support |
| `offline`        | Local-only build without Firebase initialization or Premium purchase flows         |
| `full-playstore` | Google Play Store bundle using Google Play Billing                                 |

Build all release artifacts: ```./gradlew :app:release-apk``` Outputs are written to: ```dist/```

Example output names:
```text
Switchly-<version>-full.apk
Switchly-<version>-firebase-email.apk
Switchly-<version>-offline.apk
Switchly-<version>-full-playstore.aab
```

See [`docs/APK_VARIANTS.md`](./docs/APK_VARIANTS.md) for full build and configuration details.

---

## Firebase / Google Services
Firebase is optional depending on the build variant.

Firebase-enabled builds require a valid Firebase configuration:
```text
app/google-services.json
```

Alternatively, keep the file outside the repository and point Gradle to it:
```properties
GOOGLE_SERVICES_JSON_PATH=/path/to/google-services.json
```

The offline flavor disables Firebase functionality at runtime and keeps local file backup/restore available.

---

## Local Configuration
Release signing, Google Maps, Firebase, and optional external payment URLs can be configured through `signing.properties`, user-level Gradle properties, environment variables, or `-P...` arguments.

Example:
```properties
MAPS_API_KEY=your-maps-api-key

SWITCHLY_RELEASE_STORE_FILE=/path/to/switchly-release.jks
SWITCHLY_RELEASE_STORE_PASSWORD=...
SWITCHLY_RELEASE_KEY_ALIAS=...
SWITCHLY_RELEASE_KEY_PASSWORD=...

SWITCHLY_WEBSITE_URL=https://your-domain.example
SWITCHLY_DOWNLOADS_URL=https://your-domain.example/pages/download
SWITCHLY_SUPPORT_EMAIL=support@example.com
SWITCHLY_DEV_EMAIL=dev@example.com
```

External payment configuration for supported builds:

```properties
SWITCHLY_EXTERNAL_PAYMENT_PROVIDER=stripe
SWITCHLY_EXTERNAL_CHECKOUT_URL=https://your-domain.example/pages/pay/checkout/
SWITCHLY_EXTERNAL_CUSTOMER_PORTAL_URL=https://your-domain.example/pages/pay/customer-portal/
```

Server-side secrets must not be committed.

See [`docs/EXTERNAL_PAYMENTS_STRIPE.md`](./docs/EXTERNAL_PAYMENTS_STRIPE.md) for Stripe setup details.

---

## Localization
All user-facing text should live in Android string resources.

Default strings:
```text
app/src/main/res/values/
```

German strings:
```text
app/src/main/res/values-de/
```

Guidelines:
* use `getString(R.string.some_key)` or `@string/some_key`
* avoid hard-coded UI text in Kotlin/XML
* keep English and German keys in sync
* prefer formatted strings over string concatenation

This applies to:
* labels
* dialogs
* toasts
* notifications
* inline help text

---

## Supported Android / Build Versions
| Requirement           | Value                |
| --------------------- | -------------------- |
| Min SDK               | Android 8.1 / API 27 |
| Target SDK            | 36                   |
| JDK                   | 17                   |
| Kotlin                | 2.2+                 |
| Android Gradle Plugin | 8.9+                 |

---

## Useful Commands
Run lint:
```bash
./gradlew clean lint
```

Build offline release:
```bash
./gradlew :app:assembleOfflineRelease
```

Build all APK variants and Play Store bundle:
```bash
./gradlew :app:release-apk
```

Inspect dependencies:
```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

---

## Shrinking
Release builds use:
* R8 / minification
* resource shrinking

This removes most unused code and resources from release artifacts.

---

## Versioning
Switchly follows semantic versioning:
| Type  | Example | Description                        |
| ----- | ------- | ---------------------------------- |
| Patch | `1.0.1` | Bug fixes and small improvements   |
| Minor | `1.1.0` | New backward-compatible features   |
| Major | `2.0.0` | Breaking changes or major rewrites |

---

## Contributing
Before starting a contribution, please contact me first:
[andi@saltyy.at](mailto:andi@saltyy.at)

Please also read:
* [`CONTRIBUTING.md`](./CONTRIBUTING.md)
* [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md)

---

## Security
Please report security issues privately.
See [`SECURITY.md`](./SECURITY.md).

---

## Privacy
See [`PRIVACY.md`](./PRIVACY.md).

---

## License
Switchly is licensed under the **GNU General Public License v3.0**.

You are free to use, modify, and distribute this software, but any distributed modifications must also be licensed under **GPLv3**.

See:

* [`LICENSE`](./LICENSE)
* [`NOTICE`](./NOTICE)

---

## Author
**Andi S.**
[https://saltyy.at](https://saltyy.at)

**Made with ♥️ and 🍪 by saltyy**
