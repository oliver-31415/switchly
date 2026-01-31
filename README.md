# Switchly

Switchly is an Android app for **profile-based app blocking**.
A lightweight background service monitors the foreground app and shows a blocking overlay when a restricted app is opened.

## ✨ Features

* App blocking via profiles
* Schedules (time / Wi‑Fi / Bluetooth)
* NFC & QR actions
* Optional cloud sync / authentication (Firebase)

## 🏗 Build & Run

### 1) Clone

```bash
git clone <your-repo-url>
cd switchly
```

### 2) Build (works **without** Firebase)

If you **do not** add `app/google-services.json`, the app still builds, but Firebase features (Auth/Sync/Crashlytics) are disabled.

```bash
./gradlew :app:assembleDebug
```

### 3) Enable Firebase (optional)

If you want Google Sign‑In / Firestore sync / Crashlytics:

1. Create a Firebase project
2. Add an Android app with applicationId: `at.saltyy.switchly`
3. Download `google-services.json`
4. Put it here: `app/google-services.json`

Rebuild after adding the file.

## ✅ Tests

```bash
./gradlew testDebugUnitTest
./gradlew :app:lintDebug
```

## 🏷 Versioning

Follows **MAJOR.MINOR.PATCH**.

|  Type | Example | Description                        |
| ----: | :------ | :--------------------------------- |
| Patch | `1.0.1` | Bug fixes                          |
| Minor | `1.1.0` | New features (backward-compatible) |
| Major | `2.0.0` | Breaking changes                   |

## 📌 Supported Android Versions

| Requirement                 | Value                    |
| --------------------------- | ------------------------ |
| **Min SDK**                 | **Android 8.1 (API 27)** |
| Target / Compile SDK        | 36                       |
| JDK                         | 17                       |
| Kotlin (Gradle plugin)      | 2.2.x                    |
| Android Gradle Plugin (AGP) | 8.9+                     |

> Note: If you change the Kotlin/AGP versions in the project, keep the README in sync with the values in `gradle/libs.versions.toml` (or the project’s Gradle plugin versions).

## 🗂 Project Structure

Main code lives under:

* `app/src/main/java/at/saltyy/switchly/`

High-level modules/packages (may vary slightly):

* `app/` startup & initialization
* `blocking/` app watching + blocking/overlay logic
* `feature/` UI screens
* `data/` persistence, preferences, sync
* `platform/` receivers, tiles, services
* `nfc/` NFC schema + writer

## 🤝 Contributing

See `CONTRIBUTING.md`.

## 🔒 Security

See `SECURITY.md`.

## 🕵️ Privacy

See `PRIVACY.md`.

## ❤️ Donate / Support

If you find Switchly useful and want to support development:

* **PayPal Donate:** [https://www.paypal.com/donate/?hosted_button_id=4CMENNDQCXWZY](https://www.paypal.com/donate/?hosted_button_id=4CMENNDQCXWZY)
* **Get the official release:** buy/purchase in the Play Store (supports development)

Community:

* **Discord:** [https://discord.gg/PC5zn2NeCg](https://discord.gg/PC5zn2NeCg)
* **Website:** [https://switchly.saltyy.at](https://switchly.saltyy.at)


## 📄 License

Licensed under the **Apache License 2.0**.

See:

* [`LICENSE`](./LICENSE)
* [`NOTICE`](./NOTICE)
---

**Made with ♥️ and 🍪 by saltyy**

