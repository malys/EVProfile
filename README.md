# EVProfile

<p align="center"><img src="docs/logo.svg" width="440" alt="EVProfile"></p>

[![Tests](https://github.com/malys/EVProfile/actions/workflows/tests.yml/badge.svg)](https://github.com/malys/EVProfile/actions/workflows/tests.yml)
[![Security](https://github.com/malys/EVProfile/actions/workflows/security.yml/badge.svg)](https://github.com/malys/EVProfile/actions/workflows/security.yml)
[![Unstable](https://github.com/malys/EVProfile/actions/workflows/unstable.yml/badge.svg)](https://github.com/malys/EVProfile/actions/workflows/unstable.yml)
[![Release](https://github.com/malys/EVProfile/actions/workflows/release.yml/badge.svg)](https://github.com/malys/EVProfile/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE.md)

Android Automotive app for advanced driving-settings control on the MG4 electric vehicle.

> ⚠️ **This app writes vehicle settings.** Use it only while parked and at your own risk.
> Read [DISCLAIMER.md](DISCLAIMER.md) before installing. This independent community
> project is not affiliated with or approved by SAIC Motor or MG Motor. MG and MG4 are
> third-party marks used only to identify compatibility.

> 🇫🇷 Une version française de ce document est disponible : **[README.fr.md](README.fr.md)**.

## Release channels

- **Stable** (`com.evsuite.profile`): offline APK, no updater code and no network
  permission. Install and update it manually from a tagged GitHub Release.
- **Unstable** (`com.evsuite.profile.unstable`): rolling pre-release that can coexist with
  stable. It checks the `unstable` GitHub pre-release, validates HTTPS origins and the APK
  signing certificate, then requires an explicit user installation.

Migration: the former `online` package upgrades in place to stable because it used the same
application ID. The former `com.evsuite.profile.offline` package is a separate installation;
back up its profiles, install stable, restore them, then uninstall the legacy package.

## Contents

- [Release channels](#release-channels)
- [Overview](#overview)
- [Features](#features)
- [Requirements](#requirements)
- [How it works](#how-it-works)
- [Hardware Layers](#hardware-layers)
- [Profile System](#profile-system)
- [User Interface](#user-interface)
- [Required Permissions](#required-permissions)
- [Building](#building)
- [Project layout](#project-layout)
- [Project documents](#project-documents)
- [Security](#security)
- [Contributing](#contributing)
- [Legal](#legal)
- [Credits](#credits)

## Overview
**EVProfile** is a system-level application designed for Android Automotive OS, intended to run on the head unit of MG4 electric vehicles equipped with the **SAIC MT2712** SoC. It provides direct, unified access to driving settings that are unavailable — or poorly accessible — through the stock manufacturer interface.

The app communicates with the vehicle through the proprietary SAIC SDK, accessing Android Automotive services (`CarPropertyManager`, `CarHvacManager`) as well as low-level services exposed by the vehicle's firmware.

> **Important:** This application requires system privileges (`sharedUserId="android.uid.system"`) and must be signed with the ROM's platform key. It cannot run on a standard unlocked device.

> [!WARNING]
> **EVProfile is an independent community project. It is in no way affiliated with, endorsed by, or supported by MG Motor, SAIC Motor, or any of their subsidiaries.**
> Use this application entirely at your own risk. Incorrect settings may affect vehicle behaviour. Proceed with caution.

---

## Features
### Driving Settings
- **Drive mode**: ECO / NORMAL / SPORT / SNOW / CUSTOM
- **Regenerative braking**: Off / Low / Medium / High / Adaptive / One Pedal

### Climate Control
- **Heated steering wheel**: On / Off
- **Heated seats (left & right)**: Off / Level 1 / 2 / 3

### ADAS (Advanced Driver Assistance)
- **SWI133**: Off / Speed Limiter / Auto / ACC / ICA + overspeed alert / speed limit change alert
- **SWI68**: Disable / ACC / TJA + audible warning On / Off
- **SWI69 / SWI131**: Forward Collision Warning (AEB) — On / Off + mode Alert only / Alert + Emergency Braking

### Steering Wheel Shortcuts
- Configure **4 steering wheel buttons** (left/right side buttons)
- Available actions: Drive mode / Regeneration / ADAS / **Open app**
- Enable/disable shortcuts with a **warning dialog**

### Profile Management
- Save up to **5 custom profiles**
- Instant one-tap profile application
- Automatic default profile application **on vehicle startup**

### Settings
- Language selection (French / English)
- Enable/disable automatic profile application
- **Unstable only — OTA**: GitHub pre-release check + verified APK download
- **Unstable only — APK cleanup**: removes old `EVProfile*.apk` files from Downloads
- "About" dialog showing app version, firmware version, and GitHub QR code

---

## Requirements
| Item | Value |
|------|-------|
| Target vehicle | MG4 Electric (SAIC) |
| OS | Android Automotive 9+ (API 28+) |
| SoC | SAIC MT2712 |
| Screen resolution | 1280 × 480 (forced landscape) |
| Firmware SWI133 | Compatible ✅ |
| Firmware SWI131 | Compatible ✅ |
| Firmware SWI132 | Compatible ✅ |
| Firmware SWI68 | Compatible ✅ |
| Firmware SWI69 | Compatible ✅ |
| Firmware SWI165 | Compatible ✅ |
| UNKNOWN firmware | Forced SWI133/SWI132/SWI68/SWI69/SWI131/SWI165 mode available ⚠️ |

---

## How it works
### Overview

```
┌──────────────────────────────────────────────────────┐
│                      UI LAYER                         │
│  MainActivity ─── NavController ─── Fragment Host    │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐ │
│  │  Dashboard  │  │   Profiles   │  │  Settings   │ │
│  └─────────────┘  └──────────────┘  └─────────────┘ │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│                  BUSINESS LOGIC                       │
│  ProfileManager  ─  ProfileApplier  ─  FirmwareInfo  │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│           HARDWARE ABSTRACTION (EVHardware)          │
│  Katman1 (Car API) → Katman2 (Binder) → Katman4      │
│                      (ADAS / SWI133 / SWI68)          │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│               SYSTEM SERVICES & BOOT                  │
│       EVProfileService  ─────  BootReceiver         │
└──────────────────────────────────────────────────────┘
```

### Startup Sequence

```
Vehicle boot
       │
       ▼
BootReceiver.onReceive()
       │
       ▼
EVProfileService.onCreate()
  └─ EVHardware.init()
  └─ Katman1 / Katman4 service discovery
  └─ Apply default profile (if enabled)
       │
       ▼
MainActivity (UI)
  └─ Firmware detection (SWI133 / SWI68)
  └─ Top bar setup
  └─ Navigate to DashboardFragment
```

---

## Hardware Layers
`EVHardware` is organized into **4 access layers**, from highest to lowest level, with automatic fallback on failure.

### Katman1 — Android Automotive Car API
Primary layer. Uses official Android Automotive APIs:
- `CarPropertyManager` → drive modes, regeneration, one-pedal
- `CarHvacManager` → seat heating, steering wheel heating

The connection is initialized via reflection on `Car.createCar()` with multiple overloads tried in sequence. Pending actions are queued and executed once the service is ready, with exponential backoff retry (2 s → 60 s).

### Katman2 — Raw Binder (fallback)
Falls back to `ServiceManager.getService("vehiclesetting")` with direct `binderTransact()` calls. Usually blocked by SELinux in production builds.

### Katman4 — ADAS Services (firmware-specific)
Dedicated layer for ADAS functions, dynamically loaded according to the detected firmware generation:

| Firmware | Service | Mechanism |
|----------|---------|-----------|
| **SWI133** | `VehiclePropertyManager` | Loaded from the launcher APK via `ClassLoader` + reflection on `mIVehiclePropertyService`. Uses `getMixProperty()` / `setMixProperty()` |
| **SWI68** | `VehicleSettingManager` | Static singleton loaded via reflection. Uses `setAccTjaMode()` / `setLaneKeepingWarningSound()` |
| **SWI69 / SWI131** | `VehicleSettingManager` | Same singleton as SWI68. Uses `setFcwState()` / `getFcwState()` / `setFcwAutoBrakeMode()` / `setFcwSensitivity()` for AEB. Values confirmed empirically on real hardware: `setFcwState(1)` = DISABLE, `setFcwState(2)` = ENABLE. |
| **SWI165** | `VehicleSettingManager` | Same SDK as SWI68 (`com.saicmotor.sdk.vehiclesettings`). ADAS via `setAccTjaMode()`. AEB via `setAutoEmergencyBraking(1/2)` as the main toggle + `setFcwAlarmMode(1/2)` + `setFcwAutoBrakeMode(1/2)`. Values: 1=OFF, 2=ON. |

### Firmware Detection

```kotlin
// util/FirmwareInfo.kt
val gen = FirmwareInfo.getGeneration()  // Reads ro.build.mt2712.version
// → Gen.SWI133 | Gen.SWI68 | Gen.UNKNOWN
```

The result is cached and used throughout the app to branch firmware-specific code paths.

---

## Profile System
### `DrivingProfile` Model

```kotlin
data class DrivingProfile(
    val id: String,             // Unique UUID
    val name: String,           // Display name
    val driveMode: DriveMode,   // ECO / NORMAL / SPORT / SNOW / CUSTOM
    val regenLevel: RegenLevel, // OFF / LOW / MEDIUM / HIGH / ADAPTIVE / ONE_PEDAL
    val steeringHeat: Boolean,
    val seatHeatLeft: Int,      // 0–3
    val seatHeatRight: Int,     // 0–3
    // SWI133 only:
    val overspeedAlarm: Boolean,
    val speedLimitTone: Boolean,
    val adasMode: Int,          // 0=Off 1=Limiter 2=Auto 3=ACC 4=ICA
    // SWI68 only:
    val soundWarning: Boolean,
    val swi68AdasMode: Int      // Swi68Mode.OFF / ACC / TJA
)
```

### Persistence

Profiles are serialized to JSON via **Gson** and stored in `SharedPreferences`. Maximum **5 profiles** per device.

### Applying a Profile

`ProfileApplier.apply()` executes hardware calls in the following order on `Dispatchers.IO`:
1. Drive mode (fast — binder call)
2. Regen level (fast — binder call)
3. Heated steering wheel (~2 s — state confirmation polling)
4. Left seat heating (~7 s — toggle polling)
5. Right seat heating (~7 s — toggle polling)
6. Wait for Katman4 → ADAS (firmware-dependent)

---

## User Interface
### Navigation
The app uses a **NavController** with **3 destinations**:

```
DashboardFragment (start)
    ├──► ProfileFragment  (PROFILS button — toggle)
    └──► SettingsFragment (RÉGLAGES button — toggle)
```

A second press on PROFILS or RÉGLAGES closes the view and returns to the dashboard.

### Dashboard (main screen)
**2-row layout** (2:1 weight ratio) optimized for 1280×480:
- **Top row (2/3 height)**: Drive mode | Regeneration | ADAS
- **Bottom row (1/3 height)**: Climate (steering + seats) | Alerts

### Dark Theme — Color Palette

| Token | Hex | Usage |
|-------|-----|-------|
| `dash_bg` | `#0C0C0E` | App background |
| `dash_card` | `#141416` | Cards |
| `dash_section` | `#1C1C1F` | Inner sections |
| `dash_border` | `#2A2A2E` | Borders |
| `dash_accent` | `#38BDF8` | Active selection (blue) |
| `dash_eco` | `#22C55E` | ECO mode (green) |
| `dash_warn` | `#F59E0B` | SPORT mode (amber) |
| `dash_danger` | `#F43F5E` | Delete / danger actions |

---

## Required Permissions
| Permission | Reason |
|-----------|--------|
| `FOREGROUND_SERVICE` | Persistent foreground service for auto-apply |
| `WAKE_LOCK` | Prevents sleep during settings application |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on vehicle boot |
| `BLUETOOTH` *(Android 11 and below)* | Match paired devices to automatic profiles |
| `BLUETOOTH_CONNECT` *(Android 12+)* | Match paired devices to automatic profiles |
| `CAR_POWERTRAIN` | Drive mode and regeneration control |
| `CONTROL_CAR_CLIMATE` | Seat and steering wheel heating control |
| `CAR_VENDOR_EXTENSION` | SAIC proprietary extensions |
| `CAR_ENERGY` | Battery / powertrain information |
| `INTERNET` *(unstable only)* | Rolling pre-release check and verified APK download |
| `ACCESS_NETWORK_STATE` *(unstable only)* | Warn before downloading outside Wi-Fi |

---

## Building
You can download the latest version of EVProfile directly from the releases page: https://github.com/malys/EVProfile/releases
All you need is a USB drive and access to the AAOS settings to install the APK.


You can also compile the project yourself:

### Prerequisites
- Android Studio Hedgehog (2023.1) or later
- JDK 17+
- Android SDK API 34

### Debug Build

```bash
# Using Android Studio's bundled JDK
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew assembleStableDebug
# Tester channel with OTA:
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew assembleUnstableDebug
```

Output APK location:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Installing on the Vehicle

The application must be signed with the ROM's system key. On a development system:

```bash
adb push app-debug.apk /sdcard/
adb shell pm install -r --system /sdcard/app-debug.apk
```

> On a production ROM, the APK must be included in the system build or installed through an OEM-specific mechanism.

---

## Project layout
```
EVProfile/
├── app/src/main/
│   ├── java/com/evsuite/profile/
│   │   ├── EVApp.kt                  # Application — night mode, locale
│   │   ├── MainActivity.kt            # Main activity, top bar, navigation
│   │   │
│   │   ├── model/
│   │   │   ├── DrivingProfile.kt      # Profile data model
│   │   │   ├── DriveMode.kt           # Drive mode enum (ECO/NORMAL/SPORT/SNOW/CUSTOM)
│   │   │   └── RegenLevel.kt          # Regen level enum
│   │   │
│   │   ├── profile/
│   │   │   ├── ProfileManager.kt      # Profile CRUD (SharedPreferences + Gson)
│   │   │   └── ProfileApplier.kt      # Applies settings to vehicle (async)
│   │   │
│   │   ├── hardware/
│   │   │   └── EVHardware.kt         # Hardware abstraction (4 layers)
│   │   │
│   │   ├── ui/
│   │   │   ├── DashboardFragment.kt   # Unified main screen
│   │   │   ├── ProfileFragment.kt     # Profile management
│   │   │   ├── SettingsFragment.kt    # Settings & About
│   │   │   ├── ProfileAdapter.kt      # Profile list RecyclerView adapter
│   │   │   ├── ConsoleFragment.kt     # Real-time debug log viewer
│   │   │   ├── DriveRegenFragment.kt  # Legacy (unused in v2)
│   │   │   ├── ClimateFragment.kt     # Legacy (unused in v2)
│   │   │   └── AdasFragment.kt        # Legacy (unused in v2)
│   │   │
│   │   ├── service/
│   │   │   └── EVProfileService.kt   # Foreground service (boot + auto-apply)
│   │   │
│   │   ├── receiver/
│   │   │   └── BootReceiver.kt        # System boot receiver
│   │   │
│   │   ├── util/
│   │   │   ├── FirmwareInfo.kt        # Firmware generation detection (SWI133 / SWI68)
│   │   │   ├── FirmwareHelper.kt      # Full firmware version string reader (async)
│   │   │   └── LocaleHelper.kt        # Language management (FR / EN)
│   │   │
│   │   └── debug/
│   │       └── AppLogger.kt           # In-memory log ring buffer (400 entries)
│   │
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml      # Top bar + NavHostFragment
│   │   │   ├── fragment_dashboard.xml # Main screen (drive + climate + alerts)
│   │   │   ├── fragment_profile.xml   # Profile list
│   │   │   ├── fragment_settings.xml  # Settings screen
│   │   │   ├── item_profile.xml       # Profile list item
│   │   │   ├── dialog_profile_edit.xml# Profile create / edit dialog
│   │   │   └── dialog_app_info.xml    # About dialog
│   │   ├── navigation/nav_graph.xml   # Dashboard → Profiles / Settings
│   │   ├── values/strings.xml         # French strings
│   │   ├── values-en/strings.xml      # English strings
│   │   └── values/colors.xml          # dash_* color palette (dark theme)
│   │
│   └── AndroidManifest.xml
│
└── mockup/
    └── index.html                     # Interactive HTML mockup (1280×480)
```

---

## Project documents
| Document | What it covers |
|---|---|
| [DESIGN.md](DESIGN.md) | The EVSuite design system — colour, type, touch targets, icons |
| [AGENTS.md](AGENTS.md) | Context for AI agents working in this repository |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to build, test and submit a change |
| [SECURITY.md](SECURITY.md) | Threat model and vulnerability disclosure |
| [DISCLAIMER.md](DISCLAIMER.md) | Vehicle-safety disclaimer — read before installing |
| [CHANGELOG.md](CHANGELOG.md) | Release history |
| [LICENSE.md](LICENSE.md) | Licence text |

## Security
See [SECURITY.md](SECURITY.md) for the threat model and how to report a vulnerability
privately.

## Contributing
Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. In short: this
code runs in a moving vehicle, so changes stay small, carry tests, and say in the diff
what would break without them. Anything touching the interface follows
[DESIGN.md](DESIGN.md).

## Legal

Licensed under [LICENSE.md](LICENSE.md). Read [DISCLAIMER.md](DISCLAIMER.md) before
installing: this application writes to vehicle settings, and wrong settings can affect
how the car behaves. Not affiliated with SAIC Motor or MG.

## Credits
Made with ❤ by **SliDeeN** and **Claude AI**.

Based on **DriveHub Dort**, developed by **Merth4n** and **hotboy_ist**.

Special thanks to **confor1max** for extensive SWI68 firmware testing.

[![GitHub](https://img.shields.io/badge/GitHub-SliDeeN%2FEVProfile-181717?logo=github)](https://github.com/malys/EVProfile)
