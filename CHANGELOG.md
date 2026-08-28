# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.0.2] - 2026-08-26

### Fixed

- **`applyProfile` no longer reports success when the gateway refused the request.** The
  service returned `ok = true` with a `REFUSED_MOVING` or `REFUSED_UNKNOWN_SPEED` verdict,
  so the same EVTasker history entry said both "applied" and "refused." The settings sent
  through the gateway were not written, and callers that only read the success flag could
  not detect the refusal. `ok` now follows the verdict.

## [3.0.1] - 2026-08-25

### Fixed

- **The service remains running when the vehicle has not granted Bluetooth access.** The
  service declares the `connectedDevice` foreground-service type, which requires
  `BLUETOOTH_CONNECT` at the exact moment the service enters the foreground on Android 14
  and later. The app declared that permission but never requested it. The service now claims
  the type only while the supporting permission is held and reasserts it as soon as the
  permission is granted, without waiting for the next startup.
- **The paired-device list is no longer empty without explanation.** `BLUETOOTH_CONNECT` is
  now requested when the app opens; denying it only disables phone detection, as before.
- **The service notification is visible.** `POST_NOTIFICATIONS` was missing, so Android 13
  and later silently removed the only visible indication that EVProfile was running.

## [3.0.0] - 2026-08-15

### Changed

- **The application ID changes from `com.mg4.control` to `com.evsuite.profile`.** Android
  therefore treats this release as a different app: it does not update an existing install,
  it is added next to it, and it starts with no profiles, no permissions and no settings.
  Back up your profiles from the old app, install this one, check that it works on the
  vehicle, and only then uninstall the old app. The same applies to anyone coming from the
  former `.offline` package.
- Replaced the historical online/offline flavors with two suite-standard channels:
  `stable` is offline and manually updated, while `unstable` is a co-installable rolling
  pre-release with certificate-verified OTA download. Updater code and network permissions
  are physically absent from stable builds.
- The former online application ID becomes stable in place. Users of the former `.offline`
  package must use the profile backup/restore path before removing that legacy installation.
- Declared the version-appropriate Bluetooth permissions used by automatic profile matching
  (`BLUETOOTH` through Android 11, `BLUETOOTH_CONNECT` from Android 12), resolving the
  blocking `MissingPermission` lint findings without changing Bluetooth behaviour.

## [2.7.0] - 2026-08-10

### Added

- **An Automation button appears in the top bar when EVTasker is installed.** EVProfile
  configures the vehicle manually while EVTasker automates those settings; previously the
  launcher was the only way to move between them. The button appears only when the app is
  present, and its visibility is reevaluated on every resume so installing EVTasker while
  EVProfile is running does not require a restart. The click validates the intent again in
  case the app was removed after the button appeared.
- **The profile picker is exposed to other EVSuite apps** through `showProfilePicker()` on
  `IProfileControl`, still protected by the signature permission. EVTasker can ask EVProfile
  to show the driver the profile list instead of choosing on their behalf. Like applying a
  profile, this is refused while moving and the refusal is reported explicitly; callers can
  distinguish a moving vehicle from an empty profile list.
- **Diagnostic sharing.** The Diagnostic dialog can send the complete report to a PrivateBin
  instance (paste.chapril.org). It is encrypted, password-protected, expires after one hour,
  and the server never receives the key. Every upload requires confirmation because the
  report leaves the vehicle. The link and password are written to the log so they remain
  available after the toast disappears. Sharing entries are removed from later reports so a
  second upload does not disclose the first. This capability is absent from stable builds,
  which do not declare the `INTERNET` permission.
- **Three taps on the version in About open Diagnostics.** This is intentionally hidden: the
  report contains firmware information and logs and should not be exposed as a normal action
  to passengers exploring the screen.

### Changed

- **Steering-wheel ★ button events now come from EVHardware** through
  `PhysicalButtonEventDecoder`, which knows each firmware generation's key-code aliases. A
  held long press now triggers the action once instead of repeatedly, and a release without
  a preceding press no longer triggers anything.
- **The firmware version shown in About is read by the library.** `FirmwareHelper`
  duplicated the system-property reads already used for generation detection and has been
  removed.
- **Crash reporting now comes from EVHardware** (`com.evsuite.hardware.diag.CrashLogger`)
  instead of being carried here. The report gains the full stack trace as the platform
  prints it, an atomic write so a second failure mid-write cannot destroy the previous
  report, and the previous handler is chained rather than replaced. The file is now
  `filesDir/last_crash.txt`; a report left by an older build under `crash_log.txt` is no
  longer read. Nothing changes in the Diagnostic dialog.
- Adopted the EVSuite design system. Colour, spacing, type and component styles now
  come from shared tokens (`values/colors.xml`, `values/dimens.xml`,
  `values/styles_ev.xml`), generated by `tools/sync-tokens.mjs` and specified in
  [DESIGN.md](DESIGN.md).
- Theme is now `Theme.Material3.DayNight.NoActionBar`: the app follows the vehicle's
  day/night setting, and the light palette is held to the same 7:1 contrast floor as
  the dark one.
- `ev_outline` raised from `#4A525B` to `#7A8492`. The old value was 2.25:1 against
  `ev_surface`, below the 3:1 floor for non-text UI, so the card border it was meant
  to provide effectively was not there.
- Launcher icon replaced with the EVSuite adaptive icon: charcoal tile, white glyph
  with a single red accent, product caption. Vector only — the five legacy density
  bitmap buckets are gone.
- README restructured to the shared EVSuite skeleton; table of contents is now
  generated by `tools/sync-docs.mjs`.

### Changed

- **The dashboard tab row moved into the top bar.** Its two destinations cost a row of
  their own — 72dp of buttons plus 32dp of margins — on a 720dp-tall panel. They are now
  buttons in the top bar, shown only while the dashboard is the current destination, and
  the pager fills the fragment. Both labels were shortened (`tab_dashboard_main`,
  `tab_dashboard_elk`): the card titles they reused would have pushed the navigation
  buttons off-screen. Navigation stays explicit and touchable — the swipe is still never
  the only path.
- **Firmware generation moved from the top bar to Settings › Vehicle.** It spanned the
  width of every screen for something you read once, in chips a few dp tall. It is now a
  row of 72dp buttons next to the detected firmware string, clickable only when the
  generation is unknown or already forced. The top bar loses 8dp with it — it cannot go
  below 80dp while its navigation buttons hold the 72dp floor.
- **About is a Settings category, not a window.** It was a dialog behind a button in the
  same screen; it is now the last entry in the rail. Its firmware line is gone, the
  Vehicle category owns that information. The three-taps-on-version gesture that opens
  the diagnostic report is unchanged.
- **Shortcuts follows the same list/detail shape.** It showed four unrelated subjects at
  once — both steering-wheel star buttons, the one-pedal fallback, the ADAS cycle. Each
  is a rail entry now; the ADAS entry disappears when the firmware is unrecognised, like
  the section it opens. The global on/off switch stays above the rail: it gates all four.
- The About credit read "Maintained by malys". This repository is a fork; the line now
  reads as a contribution, and `info_based_on` keeps naming SliDeeN as the origin.
- **Profile editor and Settings are now list/detail screens.** Both stacked their
  controls in a single scroll — the editor in three columns of 38-40dp buttons, Settings
  as one column of cards — and neither showed what it contained without scrolling. Each
  now has a persistent category rail on the left and one category at a time on the
  right, with every control at the suite's 72dp target and 8dp between targets.
- Important windows are full-screen rather than floating cards: the profile-application
  confirmation (it writes to the car), the language picker and the About sheet, with
  their actions grouped top right. Remaining dialogs moved to
  `MaterialAlertDialogBuilder` so they pick up `ThemeOverlay.EV.Dialog` — dialog
  buttons were the last controls below the 72dp floor, because the framework builder
  ignores the overlay.
- The console follows DayNight like the rest of the app. It had a fixed dark palette and
  hardcoded log-level colours; both now come from named tokens.

### Fixed

- **Selection is exposed, not just drawn.** Button groups (drive mode, regen, ADAS,
  seats, language, navigation) only changed background colour, so TalkBack announced
  nothing about the current value. They now set `isSelected`, and the dashboard
  announces drive mode and regen level when the value actually changes.
- The two write overlays dropped `FLAG_NOT_FOCUSABLE`, which kept them out of the focus
  order. BACK now closes them, as a tap on the backdrop does.
- Remaining sub-72dp targets raised: shortcut spinners (36dp) and the update button row
  (44dp). No layout keeps a text size below 16sp or a target below 72dp.
- The delete confirmation had a hardcoded French title; it is now translated in the six
  locales, like the new category labels.

- Outlined buttons now use `Widget.EV.Button.Outlined`, giving them the suite's 72dp
  minimum touch target. They previously used the Material default, which is smaller
  than anything else in the app.

## Released versions

Release notes for tagged versions live on the
[GitHub releases page](https://github.com/malys/EVProfile/releases). Tags to date:

- `v2.6.4` — 2026-07-13
- `v2.6.3` — 2026-06-25
- `v2.6.2-rc1` — 2026-06-23
- `v2.6.2` — 2026-06-12
- `v2.6.1` — 2026-06-10
- `v2.5.2` — 2026-04-19
- `v2.5.1` — 2026-04-16
- `v2.5.0` — 2026-04-12
- `v2.4.0` — 2026-04-10
- `v2.3.0` — 2026-04-01
- `v2.2.0` — 2026-03-29
- `v2.1.2` — 2026-03-28
- `v2.1.1` — 2026-03-27
- `v2.1` — 2026-03-27
- `v2.0` — 2026-03-27
