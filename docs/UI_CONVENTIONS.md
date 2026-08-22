# Switchly UI and interaction conventions
This document records the visual and interaction rules used across Switchly. Follow these conventions when adding or changing screens so navigation, actions, accessibility, and security behavior remain predictable.

## Core principles
- Prefer existing Switchly components and patterns over introducing screen-specific styling.
- Keep navigation indicators semantic. An icon should describe what happens after the row is pressed.
- Keep primary actions obvious, but do not color every button as a primary action.
- Preserve access to intended unlock methods while Switchly is active, but do not expose tools that can create or reveal a bypass.
- Keep layouts usable with large system fonts, translated text, and narrow screens.
- Avoid unrelated cleanup in feature or bug-fix changes.

## Navigation and trailing icons
Use the trailing icon according to the destination or action.
| Behavior | Trailing indicator | Existing resource / pattern |
| --- | --- | --- |
| Opens another screen, submenu, or editor inside Switchly | Right chevron | `@drawable/keyboard_arrow_right_24` |
| Opens Android system UI, Android settings, another app, or an external website | Open externally | `@drawable/open_in_new_24` |
| Selects one option in the current popup or list | Checkmark, radio state, or selected styling | Do not use a chevron |
| Performs an immediate action such as copy, delete, pause, or refresh | Action-specific icon | Do not add a navigation chevron |
| Displays information only | No trailing navigation icon | Use status text or a status icon only when useful |

Do not use `open_in_new` for ordinary in-app navigation. Do not use a chevron for a row that immediately changes state.

### Display & Shortcuts examples
- Quick Settings tile rows open Android's system UI, so they use `open_in_new`.
- Widget rows keep a right chevron when they open another Switchly configuration or explanation screen.
- If a widget row directly launches Android's widget picker instead of an in-app screen, use `open_in_new` there as well.
- A popup that chooses a tile or widget action should show the feature icon on the left and selected state on the right, not navigation arrows.

## Leading icons
- Interactive rows should normally have a meaningful leading icon when neighboring rows use icons.
- Do not leave one option without an icon while equivalent options have one.
- Reuse an existing drawable when its meaning matches; do not duplicate Material icons under multiple names.
- Use `pin_24` for PIN setup or Emergency PIN, `fingerprint_24` for biometric access, and `list_alt_24` for Activity History.
- Use `check_circle_24` for a completed state such as an already-added Quick Settings tile; do not use a navigation icon for completed states.
- Use `visibility_off_24` for Hidden apps entries; use `apps_24` only for ordinary app selection or app lists.
- System UI may render an icon outside the app theme. Icons passed to Quick Settings or other Android system surfaces must be self-contained, monochrome, and must not depend on theme attributes such as `?attr/colorOnSurface`.
- Decorative icons use `android:contentDescription="@null"`. Standalone icon buttons require a translated content description.
- Icon-only `MaterialButton` controls use empty text, centered gravity, equal horizontal padding, and `ICON_GRAVITY_TEXT_START` / `app:iconGravity="textStart"`; the default start icon gravity leaves standalone icons visibly off-center.

## Rows and actions
- The whole row should be clickable when it represents one clear destination or action.
- Avoid placing a trailing chevron next to a separate clickable button unless the two controls clearly perform different actions.
- Use a switch only for a persistent on/off setting that changes immediately.
- Use a checkbox or checkmark for multi-selection.
- Use radio/selected styling for single selection.
- Use an overflow menu only for secondary actions that would clutter the main row.
- Destructive actions such as delete or reset must be visually distinct and require confirmation when data loss or lockout is possible.

## Buttons
- Use one clear accent-filled primary action per screen or dialog when possible.
- Secondary actions should use outlined, tonal, text, or neutral styling rather than duplicating the primary accent treatment.
- `Cancel`, `Close`, and `Not now` are neutral actions.
- Destructive confirmation actions use the established destructive styling.
- Button text must describe the action, not a vague result such as `OK`, unless the dialog is purely informational.

## Segmented controls
- Use `SegmentedToggleUi` for the shared two-option segmented pattern.
- Prefer `wrap_content` with a minimum touch height of 48dp. Do not force a fixed 40dp layout height.
- Use `app:singleSelection="true"` and `app:selectionRequired="true"` when one mode must always remain selected.
- Keep labels short enough for German and large-font layouts.
- Do not manually recreate tint, border, corner, icon, and alpha behavior in each Activity.
- A segmented control changes the current view or mode directly; it does not use trailing chevrons.

## Touch targets and text scaling
- Interactive controls should provide at least a 48dp touch target.
- Prefer `wrap_content` plus `minHeight` over fixed heights for controls containing text.
- Test important screens with increased Android font size.
- Avoid single-line constraints where translated or accessibility-sized text may need to wrap.
- Do not communicate state through color alone. Pair color with text, selection state, an icon, or another visual cue.

## Dialogs and popups
- Dialog titles should state the decision or subject clearly.
- Keep the primary action on the positive side and the neutral/cancel action on the negative side, following the existing Switchly dialog helpers.
- Selection dialogs show selected state with a checkmark, checkbox, radio state, or highlighted row.
- Rows that open another in-app dialog or page may use a chevron.
- Rows that leave Switchly for Android settings or another app use `open_in_new`.
- Do not expose raw QR, barcode, NFC, deep-link, or command payloads in an informational popup while Switchly is active if they could be used to bypass protection.

## Active-state and security-sensitive UI
When Switchly is active:
- Existing NFC, QR, and barcode scanning must remain available when it is an intended unlock/control method.
- Creating, editing, copying, sharing, or revealing disable and temporary-disable payloads must be blocked unless the user passes the established authentication flow.
- Apply the same protection to direct Activity entry points, not only visible menu rows.
- Recheck active state in `onResume()` for management screens that may remain open while Switchly becomes active.
- Do not add a fallback path that opens an editor merely because setup is incomplete.
- Emergency Unlock and available control channels are global safety/runtime settings, not profile-specific rule settings.

## Profiles and rule scopes
- Profiles define what is blocked or allowed and which limits/rules apply.
- App, Website, and In-app rule modes may be independently profile-scoped.
- Global control methods such as NFC, QR, barcode, button, tile, and Emergency Unlock should remain predictable across profile changes.
- UI text must make the scope clear when a setting is profile-specific.

## Screen, class, and resource naming
- User-facing titles describe the content of a screen, not the Android component type. Do not expose terms such as `Activity`, `Fragment`, `Dashboard`, or `Stats` unless they are part of the actual product language.
- Activity classes use the clearest feature name followed by `Activity`, for example `ActiveTimeActivity` and `AppWebsiteUsageActivity`.
- Use `Overview` for a summarized collection of related values and `History` for a chronological event list. Do not use the two terms interchangeably.
- Detail Activities identify the subject explicitly, for example `AppUsageDetailActivity` and `WebsiteUsageDetailActivity`.
- Repository and parser objects should be named after their responsibility rather than a screen title, for example `ActivityHistoryRepository`.
- Resource names describe their location and purpose. Activity landing-page labels use the `activity_entry_*` prefix rather than obsolete feature names such as `insights_*`.
- Keep class names, filenames, Manifest declarations, imports, Intent targets, view IDs, and user-facing strings aligned when renaming a feature.

## Strings and localization
- All user-facing text belongs in Android string resources.
- Keep English and German keys aligned.
- Use formatted resources instead of Kotlin string concatenation.
- Content descriptions, Toasts, dialog text, error messages, and empty states are also user-facing strings.
- When behavior changes, update Help/FAQ, support diagnostics, changelog, and related descriptions where necessary.
- Do not mention unsupported device categories or behavior in help text.

## Kotlin documentation and comments
- Every Kotlin source file keeps the Switchly GPL license header.
- XML and other resource files do not use the Kotlin license header.
- Use KDoc for meaningful public, architectural, or non-obvious contracts.
- Do not add one-line KDoc such as `/** Does something. */`; use `//` for a short implementation note.
- Do not place an empty line directly between KDoc and the declaration it documents.
- Comments should explain why behavior exists, not restate the code.
- Avoid reflowing unrelated comments in functional commits.

## XML and resource organization
- Keep XML indentation consistent with neighboring resources.
- Standard 24dp icon vectors use a `960 × 960` viewport and the shared attribute order: width, height, viewport width, viewport height, then path data and fill color.
- Preserve intentional dimensions for non-icon assets such as widget previews. A preview is not forced into the 24dp icon template merely for visual consistency in source code.
- Preserve semantic colors: ordinary in-app icons generally use `?attr/colorOnSurface`, system-surface icons must be self-contained, and official brand colors remain unchanged.
- Keep `android:visibility="gone"` only for views that are conditionally shown at runtime. Remove a permanently hidden view together with its unused Kotlin references, IDs, and strings.
- Avoid oversized separator banners or repeated decorative comment blocks.
- Group related strings and drawables logically.
- Remove resources only after confirming they are unused across all flavors and generated references.
- Reuse shared styles and helpers instead of adding screen-local duplicates.

## Release-sensitive UI checklist
For UI or navigation changes, verify at least:
- English and German text
- light and dark theme
- increased system font size
- narrow phone layout
- leading and trailing icon meaning
- 48dp touch targets
- internal versus external navigation indicator
- active and inactive Switchly states
- direct Activity/deep-link entry where applicable
- back navigation and returning through `onResume()`
- no new bypass through NFC, QR, barcode, schedules, or Emergency Unlock
- screenshots for the merge request when the visual result changes

### Activity overview metrics
- App usage rows show **Opens** and **Blocks** for the selected range when data exists.
- Switchly overview uses the short labels **Opens** and **Blocks** consistently.
- Related destinations may be grouped under concise section labels such as **Statistics & events** and **Review**.
- Website-rule entry points use `language_24` so website and domain destinations share the same globe metaphor.

### XML resource formatting
- Layout roots keep the first Android namespace on the opening line; remaining namespaces and attributes use one line each.
- Layout attributes use a stable order: ID/style, size, layout constraints and margins, behaviour, content, then app/tools attributes.
- Screen section headings use `@style/Switchly.SectionHeader`; use 24dp before a new section and 10dp between its heading and first card.
- Standard UI vector icons use 24dp with a 960 × 960 viewport and `?attr/colorOnSurface`. Only path data should normally differ.
- Intentional vector exceptions are brand-colour icons, Quick Settings bitmap-source icons, widget-colour icons, icon-only white blocker assets, stroked artwork, and non-square previews.
- Drawable files omit the XML declaration and use the same attribute order: size/viewport, then `pathData`, fill and optional stroke attributes.
