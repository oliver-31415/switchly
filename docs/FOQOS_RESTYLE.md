# Foqos Restyle — Change Log for Future Merge Agents

> **Purpose:** This document tracks every intentional change made in the `foqos-ui` branch
> (Foqos-inspired Material 3 Expressive restyle of Switchly). It exists so that future agents
> (or humans) can resolve upstream merge conflicts correctly without guessing.
>
> **Upstream:** `upstream` = https://gitlab.com/Saltyy/switchly-public (public mirror, lags
> the Play Store). **Origin:** `origin` = https://github.com/oliver-31415/switchly.
> **Branch model:** `main` mirrors upstream (merge only, never rewrite). `foqos-ui` carries
> the restyle on top of `main` and is the published/installed branch.
>
> **Sync procedure:** `git fetch upstream && git checkout main && git merge upstream/main &&
> git push origin main && git checkout foqos-ui && git merge main`. Resolve conflicts using
> this document's per-file guidance. After each merge: rebuild `assembleOfflineDebug`, run
> `lintOfflineDebug`, install on test device, check logcat for `FATAL EXCEPTION`.

---

## Design direction

- Target look: [Foqos](https://github.com/awaseem/foqos) (iOS app blocker) — flat warm-neutral
  cards, hairline borders, generous corner radii, calm green accent, minimal chrome, big
  friendly typography.
- Android implementation: Material 3 Expressive via Material Components **1.13.0**
  (View/XML app — no Compose). Theme tokens in `res/values/colors_foqos.xml`.

## Ground rules (keep the merge surface small)

1. Prefer **new files** (`*_foqos.xml`) over edits to upstream-owned files.
2. Never restyle screens by rewriting Activity/layout files in place when a theme/style/token
   can do it.
3. All new colors live in `colors_foqos.xml` (+ `values-night/colors_foqos.xml`).
4. Component styles live in `res/values/layout.xml` (upstream keeps its own styles there too —
   re-parenting styles is allowed; renaming/deleting upstream styles is NOT).
5. Any behavioral code change needs a section here, with file, reason, and merge advice.

---

## Change log

### Commit 1 — `22a191d` "restyle - Material 3 Expressive theme foundation (Foqos-inspired)"

**Files:** `themes.xml`, `layout.xml`, `colors.xml`, `values-night/colors.xml`,
`colors_foqos.xml` (new), `values-night/colors_foqos.xml` (new)

| File | Change | Merge advice |
|---|---|---|
| `res/values/themes.xml` | `Theme.Switchly` + `Theme.Switchly.Blocker` re-parented `Theme.MaterialComponents.DayNight.NoActionBar` → `Theme.Material3.DayNight.NoActionBar`. Added M3 container tokens (`colorPrimaryContainer` etc.) mapped to Foqos colors, Foqos surface tokens, expressive shape ramp (`ShapeAppearance.Switchly.Small/Medium/Large`), `preferenceTheme`, and a **full M3 motion-token block (critical, see Pitfall #1)**. `ThemeOverlay.Switchly.Dialog` re-parented to `ThemeOverlay.Material3.MaterialAlertDialog`. Accent variants (`Theme.Switchly.Accent.*`) additionally override `colorPrimaryContainer`/`colorOnPrimaryContainer`/`colorSecondaryContainer`/`colorOnSecondaryContainer` per accent. | When upstream adds a new accent variant, it must ALSO get the 4 container items — copy the pattern from `Theme.Switchly.Accent.Amber`. When upstream touches `Theme.Switchly` directly, re-apply our token additions (parent stays `Theme.Material3.DayNight.NoActionBar`). |
| `res/values/layout.xml` | Component styles re-parented to M3: `Switchly.TopBar`→`Widget.Material3.Toolbar`, `Switchly.Button`→`Widget.Material3.Button` (pill, `cornerRadius 100dp`), `Switchly.OutlinedButton`→`Widget.Material3.Button.OutlinedButton` (pill), `Switchly.TextButton`→`Widget.Material3.Button.TextButton`, `Switchly.Fab`→`Widget.Material3.FloatingActionButton.Primary`, `Switchly.Card`→`Widget.Material3.CardView.Elevated` (`cardBackgroundColor @color/foqos_surface`, stroke `foqos_outline_variant`, elevation 1dp, **`android:stateListAnimator @null` — see Pitfall #2**), card shapes 28dp / tile 32dp, `Switchly.Divider`→`Widget.Material3.MaterialDivider`, `Switchly.Chip`→`Widget.Material3.Chip.Assist`, `Switchly.DayChip`→`Widget.Material3.Chip.Filter`, TextInput styles→`Widget.Material3.TextInputLayout.*` (16dp boxes). Added shape ramp styles. | Keep re-parented parents on M3 equivalents; keep `stateListAnimator @null` on card styles. If upstream adds styles, re-parent only when a stable M3 equivalent exists. |
| `res/values/colors.xml` | `switchly_bg` `#FFF3F4F6` → `#FFF7F6F2` (warm paper). | Trivial; take either side or ours. |
| `res/values-night/colors.xml` | `switchly_bg` `#FF121212` → `#FF101010`. | Trivial. |
| `res/values/colors_foqos.xml` (new) | Foqos token palette (light): surfaces (`foqos_bg/surface/surface_variant/surface_container*`), text (`foqos_on_surface*`), outlines (`foqos_outline*`), primary set, and `accent_<name>_container` / `accent_<name>_on_container` for all 9 accents. Some primary tokens marked `tools:ignore="UnusedResources"` (reserved for rollout). | Upstream never has this file — conflicts impossible. Add new accent colors BOTH here and in upstream `colors.xml` habits? No: only here + upstream `colors.xml` for the base accent itself. |
| `res/values-night/colors_foqos.xml` (new) | Dark variants of the same tokens (warm near-black, softened containers). | Same as above. |

### Commit 3 — "fix - dark-mode M3 crashes; self-contained theme" (on-device debugging)

**Files:** `themes.xml` (rewritten), `values-night/themes.xml` (new), `layout.xml`,
`values-night/colors_foqos.xml` (restored)

| Change | Reason |
|---|---|
| `Theme.Switchly` → split into `Theme.Switchly.Base` (parent `Theme.Material3.Light.NoActionBar`, **full M3 token set as direct items**, Foqos overrides) + `Theme.Switchly` (thin: status bar icons) | Pitfall #1/#1b fix |
| `Theme.Switchly.Blocker` parent → `Theme.Switchly` | inherit tokens (same bug exposure) |
| `enforceTextAppearance=false` in `Switchly.TextInputLayout` + `Switchly.DropdownLayout` | Pitfall #1 (ThemeEnforcement read path) |
| `Switchly.Card` → `android:stateListAnimator="@null"` | Pitfall #2 |
| `values-night/themes.xml`: `Theme.Switchly` override with `windowLightStatusBar=false` | dark status bar icons |

Debugging trail (for future agents, so you don't repeat it): dark-only crash at
MaterialCardView (activity_main:54) → assumed Dark-theme motion-gap, added motion tokens
(no fix) → `@null` stateListAnimator fixed the card → next crash TextInputLayout
ThemeEnforcement (activity_main:354) → `enforceTextAppearance=false` → next crash
MaterialAutoCompleteTextView `dropDownBackgroundTint=?attr/colorSurfaceContainer` →
bisected theme additions (still crashed) → tested forced Light parent (still crashed in
night) → realized MainActivity had never been reached in light (onboarding gate!) →
light reaches MainActivity fine ⇒ night-config-specific ⇒ identified
materialThemeOverlay + inherited-attr resolution bug ⇒ self-contained theme ⇒ **both
modes clean**. Lesson: always reach the actual failing screen in BOTH modes before
theorizing.

### Commit 2 — `applicationIdSuffix` for debug co-install

**Files:** `app/build.gradle.kts`

| Change | Reason | Merge advice |
|---|---|---|
| `buildTypes.debug { applicationIdSuffix = ".foqosdev" }` | Local builds co-install with the Play Store release (`at.saltyy.switchly`) instead of failing with `INSTALL_FAILED_VERSION_DOWNGRADE` / signature mismatch. Installed app id: `at.saltyy.switchly.foqosdev`. | If upstream edits `buildTypes`, keep this one-liner. Never let this leak into a release build. |

---

## Known pitfalls (verified on device: Pixel 10 Pro XL, Android 17)

### Pitfall #1 — Night-mode `?attr` resolution through Material theme overlays (CRASH)
On Android 16/17 with material 1.13.0, when the device is in **night (dark) mode**, any
`TypedArray.getXxx()` read of a `?attr/...` value **inside a style resolved through a
`materialThemeOverlay`-wrapped context** (e.g. `ThemeOverlay.Material3.AutoCompleteTextView.
OutlinedBox`, `ThemeOverlay.Material3.TextInputEditText.*`) fails with
`UnsupportedOperationException: Failed to resolve attribute at index N` — **unless the attr
is defined as a DIRECT item of the app theme style**. Attrs inherited only via the base
chain (e.g. `colorSurfaceContainer`, `textAppearanceBodySmall` from
`Base.V14.Theme.Material3.*`) do NOT resolve. Light mode resolves the same lookups fine.
Diagnostic signature: the crash dumps `theme={InheritanceMap=[...overlay styles...],
Themes=[..., Theme.Switchly, forced, ...]}`.
Same family as material-components-android issues #5029 / #5025 (open as of 2026-09).

**Fixes applied (all three required):**
1. `Theme.Switchly.Base` is **self-contained**: the complete M3 token set (252 items:
   color roles, textAppearance ramp, motion tokens, shape attrs) is carried as DIRECT
   items — copied from `Base.V14.Theme.Material3.Light` with Foqos overrides. Do not trim
   "unused" items from it; they are crash insurance for every Material widget.
2. `enforceTextAppearance=false` on `Switchly.TextInputLayout` + `Switchly.DropdownLayout`
   (the enforcement's `getResourceId` returns -1 through the broken path and throws).
3. Component styles that hit the crash use **concrete colors, not `?attr`** where practical:
   `Switchly.Card` sets `cardBackgroundColor`/`strokeColor` explicitly.

If upstream bumps `material` past the fix for #5029, all three can potentially be relaxed.

### Pitfall #1b — DayNight parent participates in the same bug (crash)
With parent `Theme.Material3.DayNight.NoActionBar` the crash occurred in night mode even
with a minimal theme, and even with the parent force-pinned to
`Theme.Material3.Light.NoActionBar` it still crashed (the bug keys on the device's night
config, not on the theme's Dark branch). The chosen architecture avoids DayNight entirely:
parent is **always** `Theme.Material3.Light.NoActionBar`; dark mode is delivered by
`values-night` variants of the `foqos_*`/`accent_*_container` colors the theme points at.
Status bar icons: `Theme.Switchly` (values) sets `android:windowLightStatusBar true`,
`values-night/themes.xml` overrides it to false. **Merge advice:** never re-parent
`Theme.Switchly.Base` to a DayNight style; never replace `foqos_*` token references with
MDC `?attr`-style indirections that don't have night variants.

### Pitfall #2 — State-list animators cannot resolve `?attr/...` (CRASH)
The framework's `AnimatorInflater.loadStateListAnimator` path constructs with `theme = null`,
so ANY `?attr/` reference inside `res/animator/*.xml` used via `android:stateListAnimator`
stays an unresolved string → `NumberFormatException: For input string: "?<attr-id>"`.
(observed: `0x7f0403ab` = `motionDurationMedium4` inside
`@animator/m3_card_elevated_state_list_anim`). Affected M3 styles: elevated cards (and
elevated chips/buttons — unused by Switchly). MaterialCardView inflates in
`MainActivity.onCreate → setContentView(activity_main line 54)`.
**Fix in place:** `Switchly.Card` sets `android:stateListAnimator="@null"` (flat Foqos cards
make press-elevation unnecessary). `Widget.Material3.CardView.Filled/Outlined` and
`m3_card_state_list_anim` have the same landmine — do not switch parents to them without
a null stateListAnimator.

### Pitfall #3 — Custom accent runtime recoloring
`CustomAccentApplier` (runs only in "Custom" accent mode) string-matches
`R.color.accent_default_green` (#2E8B57) heuristically. M3 doesn't change that behavior, but
if the restyle ever changes the default accent color, `defaultAccent` in
`CustomAccentApplier.applyIfNeeded`/`applyToView` must be updated too.

### Pitfall #4 — UiConsistency runtime normalizer
`ui/UiConsistency.kt` applies a late consistency pass over widgets. It was written against
the MaterialComponents look. If M3 widgets show doubled/odd tinting, audit that file before
changing the theme again.

---

## Restyle roadmap (update as commits land)

- [x] Theme foundation: M3 + Foqos tokens + expressive shapes (commit 1)
- [x] Debug co-install (commit 2)
- [x] Dark-mode crash fixes (commit 3)
- [ ] Toolbar: flat surface style (Foqos has no colored header) — audit screens that set
      toolbar background via `AccentColor.getToolbarColor`
- [ ] Home (`activity_main.xml`) restructure toward Foqos layout: hero status card, prominent
      primary action, profile cards, minimal bottom nav
- [ ] Typography pass (bigger display sizes on Home, tighter section headers)
- [x] Dark status bar icons (`values-night/themes.xml` overrides `windowLightStatusBar`)
- [ ] Settings/Preferences restyle pass
- [ ] Widgets restyle pass (RemoteViews use `@color/widget_*` — separate system, low priority)

## Device / install notes

- Test device: Pixel 10 Pro XL over wireless ADB (`adb devices` shows
  `192.168.1.10:45039`; re-pair via Android Studio/`adb pair` if absent).
- Install: `adb -s 192.168.1.10:45039 install -r
  app/build/outputs/apk/offline/debug/app-offline-debug.apk` (app id
  `at.saltyy.switchly.foqosdev`).
- Launch check: clear logcat, `monkey -p at.saltyy.switchly.foqosdev -c
  android.intent.category.LAUNCHER 1`, grep `FATAL EXCEPTION`.
- Screenshots: `adb shell screencap -p /dev/stdout > shot.png` (or `-d <display>`; the
  foqosdev app must be foregrounded first).
