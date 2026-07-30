# Switchly APK variants
Switchly currently uses one Android app module with product flavors for service/payment behavior.

| Flavor | Firebase | Google Sign-In | Play Billing | External/direct payment | Redeem codes | Intended use |
| --- | --- | --- | --- | --- | --- | --- |
| `full` | Yes | Yes | Yes | No | Hidden | Google Play/official full build |
| `firebaseEmail` | Yes | No | No | Yes | Online Switchly redeem codes | Direct/Firebase build |
| `offline` | No | No | No | No | Optional private local allowlist | Offline/file-backup-only build |

Public/offline validation without Firebase or signing secrets:
```bash
./gradlew :app:public-release-apk
```

Official maintainer validation and packaging of all published flavors:
```bash
./gradlew :app:release-apk
```

The official task runs release lint for every flavor and requires private Firebase and signing inputs. The public task labels unsigned output explicitly. It does not include any offline Premium codes unless a private allowlist is supplied.

The `full` AAB is the Play Store artifact. Custom Premium redeem flows should stay hidden there so Google Play Billing remains the Premium purchase/restore path.
