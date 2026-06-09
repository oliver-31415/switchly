# Switchly APK variants
Switchly currently uses one Android app module with product flavors for service/payment behavior.

| Flavor | Firebase | Google Sign-In | Play Billing | External/direct payment | Redeem codes | Intended use |
| --- | --- | --- | --- | --- | --- | --- |
| `full` | Yes | Yes | Yes | No | Hidden | Google Play/official full build |
| `firebaseEmail` | Yes | No | No | Yes | Online Switchly redeem codes | Direct/Firebase build |
| `offline` | No | No | No | No | Local offline allowlist | Offline/file-backup-only build |

Recommended validation before release:
```bash
./gradlew :app:lint \
  :app:assembleOfflineRelease \
  :app:assembleFirebaseEmailRelease \
  :app:assembleFullRelease \
  :app:bundleFullRelease
```

The `full` AAB is the Play Store artifact. Custom Premium redeem flows should stay hidden there so Google Play Billing remains the Premium purchase/restore path.
