# Switchly
[**Switchly**](https://switchly.saltyy.at) is an Android application for **profile-based app blocking**.
A lightweight background service monitors the currently foreground app and shows a blocking overlay whenever a restricted app is opened.

Designed for focus, control, and flexibility — without unnecessary complexity.

---

## Project Structure
Source code root:
```text
java/at/saltyy/switchly
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
* Keep EN + DE keys in sync (same key set)

This includes **Toasts, dialogs, notifications, and inline UI labels**.

---

## Firebase/Google Services
Firebase support is **optional**.

Switchly can be built **without** Firebase configured.
When Firebase is not set up, Firebase-related functionality will simply be unavailable at runtime.

To enable Firebase:

1. Place your Firebase config at:
   `app/google-services.json`
2. Enable Firebase in Gradle via a property, for example in:
   * local `gradle.properties`, or
   * `~/.gradle/gradle.properties`

```properties
switchly.firebase=true
```

Notes:

* Firebase is **not required** for general builds
* The project is set up so public builds can compile without shipping private Firebase configuration
* Google Services/Crashlytics are only enabled when Firebase is explicitly turned on

---

## Shrinking (Unused Code/Resources)
Release builds enable:

* **R8/minification** (`minifyEnabled true`)
* **Resource shrinking** (`shrinkResources true`)

This means most unused code/resources are removed automatically at build time.

To verify locally:

```bash
./gradlew :app:assembleRelease
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
Follows **MAJOR.MINOR.PATCH**.

| Type  | Example | Description                        |
| ----- | ------- | ---------------------------------- |
| Patch | `1.0.1` | Bug fixes                          |
| Minor | `1.1.0` | New features (backward-compatible) |
| Major | `2.0.0` | Breaking changes                   |

---

## Development Notes
The public Switchly repository mirrors the **publicly available releases only**. That means `main`(release) and `test`(open beta)
Because of that, it may sometimes be **behind the current private development stage**.

So code in this repository may occasionally not reflect the very latest internal work in progress.

---

## Contributing

Before starting a contribution, please contact me first:

**[andi@saltyy.at](mailto:andi@saltyy.at)**

This helps avoid duplicate work and makes it easier to coordinate changes with the current development state.

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

You are free to use, modify, and distribute this software,
but any distributed modifications must also be licensed under **GPLv3**.

See:

* [`LICENSE`](./LICENSE)
* [`NOTICE`](./NOTICE)

---

**Made with ♥️ and 🍪 by saltyy**
