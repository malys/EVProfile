# T-905 — Can MG4Control drop `sharedUserId=android.uid.system`?

**Status: spike concluded. Recommendation: yes for `sharedUserId`, no for platform
signing — but the migration is install-breaking and needs one on-vehicle
measurement first (see "Blocking measurement").**

This is a decision document. No production code was changed for it.

## The question

`AndroidManifest.xml:3` declares `android:sharedUserId="android.uid.system"`,
which runs the app with the full system UID — maximum privilege — while
AGENTS.md requires minimal rights. Nothing recorded *why*.

## Vehicle-service integration contract

The project has a vehicle-settings integration path that needs no client-side privileged
permission:

- The client binds by explicit package + action:
  `intent.setPackage("com.saicmotor.service.vehicle")`,
  `intent.setAction("com.saicmotor.service.vehicle.VehicleService")`,
  `bindService(intent, conn, BIND_AUTO_CREATE)`, with a 1 s retry loop and
  rebind on disconnect.
- No permission is declared by the client for that bind. Service-side export and caller
  authentication remain runtime assumptions and require on-vehicle verification.

This is the "Katman3" path that `MG4Hardware.kt:27` names and deliberately
skips ("not needed for our use case").

Caveat: whether `VehicleService` is `exported` must be settled by an on-vehicle
measurement. That is
the blocking measurement below.

## What MG4Control uses instead, and what each path costs

| Capability | Mechanism | Privilege actually required |
|---|---|---|
| Drive mode, regen, one-pedal | `CarPropertyManager` vendor props `0x2140a17c/191/193` | `CAR_VENDOR_EXTENSION` — **signature\|privileged** |
| Seat + steering heat | `CarHvacManager` props `0x154025xx` | `CONTROL_CAR_CLIMATE` — privileged |
| AEB, ELK, TSR, ACC/TJA, SAS, energy saving, audible alerts | VSM/VPM SDK clients | none beyond reaching the service |
| Same, fallback path | `ServiceManager.getService("vehiclesetting")` raw binder (Katman2) | **system UID** — SELinux confines this to the system domain |
| Screen brightness | `Settings.System` | `WRITE_SETTINGS` (app-op, grantable) |
| Profile picker overlay | `TYPE_APPLICATION_OVERLAY` | `SYSTEM_ALERT_WINDOW` (app-op, grantable) |
| OTA install | `/system/bin/pm install` in `ApkInstaller` | system UID — **but this code has no callers**; the live path is DownloadManager + a manual user install (see T-901) |

## Finding

**`sharedUserId=android.uid.system` and platform signing are two different
things, and only the first is dispensable.**

`CAR_VENDOR_EXTENSION` and the car permissions are `signature|privileged`. The
platform *signature* alone satisfies them — the app does not need to share the
system UID to hold them. So dropping `sharedUserId` while keeping the platform
signing key preserves:

- every `CarPropertyManager` / `CarHvacManager` write (drive mode, regen,
  one-pedal, seat and steering heat),
- every ADAS write that goes through the VSM/VPM SDK clients,
- brightness and overlay, which are app-ops and unaffected.

What it would lose:

1. **Katman2**, the raw `ServiceManager.getService("vehiclesetting")` binder.
   SELinux confines that to the system domain, so a non-system UID cannot use
   it. It is a *fallback*, not the primary path — but see below.
2. `/system/bin/pm install`, which only `ApkInstaller` uses, and `ApkInstaller`
   is currently unreachable code.

Note that "become a privileged app instead" is **not** an option: privileged
status means living in `/system/priv-app` with a ROM permission allowlist
entry. MG4Control is distributed as an APK that users install themselves, so
that route is closed. Platform signing is the only mechanism available, and it
is already in place.

## Blocking measurement

The decision turns on one number nobody has yet: **how often is Katman2 the
path that actually succeeds?**

`setDriveMode` (`MG4Hardware.kt:1574`) tries `setIntPropertyCPM` first and only
falls back to `binderTransact` when it fails. If CPM succeeds on every
supported firmware, Katman2 is dead weight and `sharedUserId` can go. If some
firmware generation only works through the raw binder, system UID is load-
bearing for that generation and must stay.

To measure, on each firmware generation (SWI68, SWI69, SWI131, SWI132, SWI133,
SWI165), at 0 km/h:

1. Apply a profile that exercises drive mode, regen, one-pedal, seat heat, AEB,
   ELK, TSR and ACC/TJA.
2. Capture `MG4_HW` logs and record, per write, whether the CPM/VSM/VPM path
   returned true or the binder fallback was reached.

Any generation where a write only succeeds via `binderTransact` blocks the
migration for that generation.

## Migration consequence if it goes ahead

Changing `sharedUserId` changes the app's UID, which Android does not allow as
an in-place update: users must uninstall and reinstall, and app-private data is
erased. This is survivable here because `ProfileBackupManager` already keeps
profiles in shared car storage that outlives uninstall — but the release notes
must say so explicitly, and the restore path must be tested before shipping.

## Recommendation

1. Run the measurement above. It is a log-reading exercise, not a code change.
2. If Katman2 turns out to be unused in practice, drop
   `sharedUserId=android.uid.system`, keep platform signing, and delete the
   unreachable `ApkInstaller` `pm install` path (T-907 territory).
3. If any generation depends on Katman2, keep system UID for now and record
   that generation as the written justification AGENTS.md is missing.

Until the measurement exists, **do not refactor** — this is what the task
instructed, and the evidence supports it.
