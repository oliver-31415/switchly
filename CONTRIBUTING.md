# Contributing to Switchly

Thanks for considering a contribution!

## Quick start

1. Install Android Studio (latest stable recommended) and JDK 17.
2. Clone the repo.
3. Build:

```bash
./gradlew :app:assembleDebug
```

### Firebase (optional)
The project can build **without** Firebase configured.

If you want to work on cloud features (Auth / Sync / Crashlytics):
1. Create a Firebase project.
2. Add an Android app with applicationId `at.saltyy.switchly`.
3. Download `google-services.json` and place it at `app/google-services.json`.

`google-services.json` is intentionally **gitignored**.

## Development guidelines

- Keep changes focused and easy to review.
- Prefer small PRs over big rewrites.
- Avoid committing generated files (build/ outputs).
- If you touch user-visible strings, consider updating `values-de/strings.xml` too.

## Testing

Run unit tests:
```bash
./gradlew testDebugUnitTest
```

Run lint:
```bash
./gradlew :app:lintDebug
```

## Pull requests

- Describe **what** you changed and **why**.
- Include screenshots or screen recordings for UI changes.
- Mention any behavior changes (especially around blocking, schedules, or permissions).
