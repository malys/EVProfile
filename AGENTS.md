# AGENTS.md — MG4 Control

Context for AI agents working in this repository. The workspace-level file one directory
up covers the vehicle platform and the OEM interfaces; this file covers what is specific
to MG4 Control.

## What this app is

A drive-profile manager for the MG4 Electric. It reads and writes vehicle settings —
drive mode, regeneration, steering, ADAS — and applies saved profiles. It is the only
app in the suite that writes to the car; MG4 Tasker and the launchers go through it or
do not write at all.

It is the reference implementation. When another MG4 project and this one disagree on
how something is done, this one wins.

## The one rule that shapes everything

**A write is a physical act.** Every code path that reaches the vehicle is gated on
speed, serialised against every other write, and reports refusal to the user. There is
no fast path, no "just this one setting", and no silent failure.

## Privilege

The app carries `sharedUserId="android.uid.system"` and is signed with the platform key.
That contradicts the workspace's "minimal rights" constraint and is a known, measured
debt — see `analysis/T-905_privilege_spike.md`.

Summary: platform *signature* alone grants every Katman1 write. Dropping `sharedUserId`
would cost only Katman2, the raw `ServiceManager.getService("vehiclesetting")` binder.
The migration is blocked on one measurement — per firmware generation, does any write
succeed **only** through the Katman2 fallback? Until that is answered from on-vehicle
`MG4_HW` logs:

- do not refactor the privilege model, and
- do not write new code that assumes system UID is permanent.

The change is install-breaking (a UID change forces uninstall/reinstall); profiles
survive via the shared-storage backup file.

## Firmware is a branch, never an assumption

Generations differ (SWI68 / SWI69 / SWI131 / SWI132 / SWI133 / SWI165) and dispatch
through `FirmwareInfo`. Any new vehicle call is branched per generation. Confirmed
vendor property ids and binder transaction codes are documented inline in the header of
`app/src/main/java/com/mg4/control/hardware/MG4Hardware.kt` — that header is the record,
keep it accurate.

On AAOS 9 the `android.car` classes are not in the compile SDK, so access is by
reflection. Cache reflected `Method` objects; never call `getMethod()` per read.

## Unreadable is not false

If a precondition cannot be read, refuse. The single exception is the park-state rescue:
when speed is unreadable but the gear reads park, the write proceeds. That exception is
narrow, deliberate, and covered by tests. Do not widen it.

## Writes are serialised

Multi-step vehicle sequences (for example `setFcwAutoBrakeMode` then `setFcwState`) must
be atomic against each other — all vehicle writes go behind a single mutex. Concurrent
profile application otherwise interleaves and leaves indeterminate ADAS state.

## Flavors isolate capability

The `offline` flavor declares no network permission at all. `INTERNET`,
`INSTALL_PACKAGES`, the FileProvider and the OTA code live only in the `online` source
set. Removing a capability by construction beats guarding it at runtime — keep it that
way when adding anything that touches the network.

## User interface

The interface follows [DESIGN.md](DESIGN.md), shared by the whole suite.

- Colour, spacing, type and component styles come from generated token files:
  `values/colors.xml`, `values-night/colors.xml`, `values/dimens.xml`,
  `values/styles_mg4.xml`. Edit `tools/tokens/` in the workspace root and run
  `node tools/sync-tokens.mjs`. Editing the copies is pointless — they get overwritten.
- `values/colors_app.xml` maps this app's historical colour names (`dash_*`, `bg_*`,
  `text_*`) onto suite tokens. New layouts use `mg4_*` directly.
- Drive-mode and regeneration fill hues are product semantics, identical in both themes,
  and carry a white label. They are not theme surfaces; do not alias them.
- Minimum touch target 72dp, minimum text size 16sp, 7:1 contrast in both themes.
  Verify a new colour numerically.

## Testing

```bash
./gradlew :app:test
./gradlew :app:assembleDebug
```

Anything touching the vehicle layer needs a statement in the pull request of what was
verified on a car and what was not. There is no emulator for a drivetrain.
