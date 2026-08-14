# EVProfile

![image info](evprofile_github_banner.svg)

[![Security](https://github.com/malys/EVProfile/actions/workflows/security.yml/badge.svg)](https://github.com/malys/EVProfile/actions/workflows/security.yml)
[![Unstable](https://github.com/malys/EVProfile/actions/workflows/unstable.yml/badge.svg)](https://github.com/malys/EVProfile/actions/workflows/unstable.yml)
[![Release](https://github.com/malys/EVProfile/actions/workflows/release.yml/badge.svg)](https://github.com/malys/EVProfile/actions/workflows/release.yml)

> Application Android Automotive pour le contrôle avancé des paramètres de conduite du MG4 électrique.
> Android Automotive app for advanced driving settings control on the MG4 electric vehicle.

> ⚠️ Cette application modifie des réglages du véhicule. Lisez le
> [DISCLAIMER.md](DISCLAIMER.md) avant installation. Ce projet indépendant n’est ni affilié
> à SAIC Motor ou MG Motor, ni approuvé par eux. MG et MG4 sont des marques tierces utilisées
> uniquement pour indiquer la compatibilité.

> 🇬🇧 An English version of this document is available: **[README.md](README.md)**.

## Canaux de diffusion

- **Stable** (`com.evsuite.profile`) : APK hors ligne, sans code de mise à jour ni
  permission réseau. Installation et mises à jour manuelles depuis une release taguée.
- **Unstable** (`com.evsuite.profile.unstable`) : pré-release continue installable à côté
  de stable. Elle contrôle les origines HTTPS et la signature de l’APK avant de demander
  une installation manuelle explicite.

Migration : l’ancien paquet `online` devient stable sans réinstallation car son identifiant
est inchangé. L’ancien `com.evsuite.profile.offline` reste une application distincte :
sauvegardez ses profils, installez stable, restaurez-les, puis désinstallez l’ancien paquet.

## Table des matières
1. [Présentation](#présentation)
2. [Fonctionnalités](#fonctionnalités)
3. [Compatibilité](#compatibilité)
4. [Architecture](#architecture)
5. [Structure du projet](#structure-du-projet)
6. [Couches matérielles](#couches-matérielles)
7. [Système de profils](#système-de-profils)
8. [Interface utilisateur](#interface-utilisateur)
9. [Compilation et installation](#compilation-et-installation)
10. [Permissions requises](#permissions-requises)

---

## Présentation

**EVProfile** est une application système conçue pour Android Automotive OS, destinée à fonctionner sur les écrans de bord des véhicules MG4 équipés du SoC **SAIC MT2712**. Elle offre un accès direct et unifié aux réglages de conduite qui ne sont pas accessibles — ou difficilement accessibles — via l'interface constructeur.

L'application communique avec le véhicule via le SDK propriétaire SAIC, en accédant aux services Android Automotive (`CarPropertyManager`, `CarHvacManager`) ainsi qu'aux services de bas niveau exposés par le firmware du véhicule.

> **Important :** Cette application nécessite des privilèges système (`sharedUserId="android.uid.system"`) et doit être signée avec la clé de la ROM. Elle ne peut pas fonctionner sur un appareil standard débloqué.

> [!WARNING]
> **EVProfile est un projet communautaire indépendant. Il n'est en aucun cas affilié, approuvé ou soutenu par MG Motor, SAIC Motor ou l'une de leurs filiales.**
> L'utilisation de cette application se fait entièrement à vos risques. Des réglages incorrects peuvent affecter le comportement du véhicule. Procédez avec précaution.

---

## Fonctionnalités

### Paramètres de conduite
- **Mode de conduite** : ECO / NORMAL / SPORT / SNOW / CUSTOM
- **Régénération** : Off / Faible / Moyen / Fort / Adaptatif / 1 Pédale

### Climatisation
- **Volant chauffant** : On / Off
- **Sièges chauffants gauche et droit** : Off / Niveau 1 / 2 / 3

### ADAS (Assistance à la conduite)
- **SWI133** : Off / Limiteur / Auto / ACC / ICA + alertes excès de vitesse / changement de limite
- **SWI68** : Désactiver / ACC / TJA + avertissement sonore On / Off
- **SWI69 / SWI131** : Anti-collision avant (AEB) — On / Off + mode Alerte uniquement / Alerte + Freinage
- **SWI165** : Désactiver / ACC / TJA + Anti-collision avant (AEB) On/Off + mode Alerte / Alerte+Freinage + avertissement sonore

### Raccourcis volant
- Configuration des **4 boutons du volant** (boutons latéraux gauche/droit)
- Actions disponibles : Mode de conduite / Régénération / ADAS / **Ouvrir l'application**
- Activation / désactivation des raccourcis avec **dialog d'avertissement**

### Gestion de profils
- Sauvegarde jusqu'à **5 profils** personnalisés
- Application instantanée d'un profil en un clic
- Application automatique du profil par défaut **au démarrage du véhicule**

### Réglages
- Choix de la langue (Français / English)
- Activation/désactivation de l'application automatique du profil
- **Unstable uniquement — OTA** : vérification de la pré-release GitHub et téléchargement vérifié
- **Unstable uniquement — nettoyage APK** : suppression des anciens `EVProfile*.apk`
- Dialog "À propos" avec version de l'app, version firmware et QR code GitHub
- Bouton "Fermer" pour revenir directement au dashboard

### Profils
- Bouton "Fermer" pour revenir directement au dashboard

### Compatibilité firmware inconnue (UNKNOWN)
- Dialog d'avertissement au démarrage si le firmware n'est ni SWI133 ni SWI68
- L'utilisateur peut fermer l'application ou continuer
- En mode "Continuer", les chips SWI133 / SWI68 / SWI69 / SWI131 deviennent cliquables pour forcer un mode de compatibilité
- Le choix forcé est persisté en SharedPreferences et survit aux redémarrages de l'app

---

## Compatibilité

| Élément | Valeur |
|---------|--------|
| Véhicule cible | MG4 Electric (SAIC) |
| OS | Android Automotive 9+ (API 28+) |
| SoC | SAIC MT2712 |
| Résolution d'écran | 1280 × 480 (orientation paysage forcée) |
| Firmware SWI133 | Compatible ✅ |
| Firmware SWI131 | Compatible ✅ |
| Firmware SWI132 | Compatible ✅ |
| Firmware SWI68 | Compatible ✅ |
| Firmware SWI69 | Compatible ✅ |
| Firmware SWI165 | Compatible ✅ |
| Firmware UNKNOWN | Mode forcé SWI133/SWI132/SWI68/SWI69/SWI131/SWI165 disponible ⚠️ |

---

## Architecture

### Vue d'ensemble

```
┌──────────────────────────────────────────────────────┐
│                    INTERFACE                          │
│  MainActivity ─── NavController ─── Fragment Host   │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐ │
│  │  Dashboard  │  │   Profils    │  │  Réglages   │ │
│  └─────────────┘  └──────────────┘  └─────────────┘ │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│                 COUCHE MÉTIER                         │
│  ProfileManager  ─  ProfileApplier  ─  FirmwareInfo  │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│            ABSTRACTION MATÉRIELLE (EVHardware)       │
│  Katman1 (Car API) → Katman2 (Binder) → Katman4      │
│                      (ADAS / SWI133 / SWI68)          │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│              SERVICES SYSTÈME & BOOT                  │
│      EVProfileService  ─────  BootReceiver          │
└──────────────────────────────────────────────────────┘
```

### Démarrage de l'application

```
Démarrage véhicule
       │
       ▼
BootReceiver.onReceive()
       │
       ▼
EVProfileService.onCreate()
  └─ EVHardware.init()
  └─ Découverte des services Katman1 / Katman4
  └─ Application du profil par défaut (si activé)
       │
       ▼
MainActivity (IHM)
  └─ FirmwareInfo.initWithContext()     ← charge mode forcé (SharedPreferences)
  └─ Détection du firmware (SWI133 / SWI68 / UNKNOWN)
  └─ Configuration de la top bar (chips firmware)
  └─ checkUnknownFirmware()             ← dialog si UNKNOWN et non forcé
  └─ Navigation vers DashboardFragment
```

---

## Structure du projet

```
EVProfile/
├── app/src/main/
│   ├── java/com/evsuite/profile/
│   │   ├── EVApp.kt                  # Application — mode nuit, locale
│   │   ├── MainActivity.kt            # Activité principale, top bar, navigation
│   │   │
│   │   ├── model/
│   │   │   ├── DrivingProfile.kt      # Modèle de données d'un profil
│   │   │   ├── DriveMode.kt           # Enum modes de conduite (ECO/NORMAL/SPORT/SNOW/CUSTOM)
│   │   │   └── RegenLevel.kt          # Enum niveaux de régénération
│   │   │
│   │   ├── profile/
│   │   │   ├── ProfileManager.kt      # CRUD profils (SharedPreferences + Gson)
│   │   │   └── ProfileApplier.kt      # Application des réglages au véhicule (async)
│   │   │
│   │   ├── hardware/
│   │   │   └── EVHardware.kt         # Abstraction matérielle (4 couches)
│   │   │
│   │   ├── ui/
│   │   │   ├── DashboardFragment.kt   # Écran principal unifié
│   │   │   ├── ProfileFragment.kt     # Gestion des profils
│   │   │   ├── SettingsFragment.kt    # Réglages & À propos
│   │   │   ├── ProfileAdapter.kt      # Adaptateur RecyclerView profils
│   │   │   ├── ConsoleFragment.kt     # Journal de debug en temps réel
│   │   │   ├── DriveRegenFragment.kt  # Héritage (non utilisé en v2)
│   │   │   ├── ClimateFragment.kt     # Héritage (non utilisé en v2)
│   │   │   └── AdasFragment.kt        # Héritage (non utilisé en v2)
│   │   │
│   │   ├── service/
│   │   │   └── EVProfileService.kt   # Service de premier plan (boot + auto-apply)
│   │   │
│   │   ├── receiver/
│   │   │   └── BootReceiver.kt        # Récepteur de démarrage système
│   │   │
│   │   ├── util/
│   │   │   ├── FirmwareInfo.kt        # Détection firmware (SWI133/SWI68/UNKNOWN) + mode forcé
│   │   │   ├── FirmwareHelper.kt      # Lecture version firmware complète (async)
│   │   │   └── LocaleHelper.kt        # Gestion de la langue (FR / EN)
│   │   │
│   │   └── update/
│   │       ├── UpdateChecker.kt       # Vérification dernière release GitHub (API)
│   │       ├── UpdateDialogManager.kt # Dialog MAJ + DownloadManager + ouverture dossier
│   │   │
│   │   └── debug/
│   │       └── AppLogger.kt           # Buffer de logs en mémoire (400 entrées)
│   │
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml      # Top bar + NavHostFragment
│   │   │   ├── fragment_dashboard.xml # Écran principal (conduite + climat + alertes)
│   │   │   ├── fragment_profile.xml   # Liste des profils
│   │   │   ├── fragment_settings.xml  # Réglages
│   │   │   ├── item_profile.xml       # Item liste de profil
│   │   │   ├── dialog_profile_edit.xml       # Dialog création / édition de profil
│   │   │   ├── dialog_app_info.xml           # Dialog "À propos"
│   │   │   └── dialog_unknown_firmware.xml   # Dialog firmware inconnu (UNKNOWN)
│   │   ├── navigation/nav_graph.xml   # Dashboard → Profils / Réglages
│   │   ├── values/strings.xml         # Chaînes FR
│   │   ├── values-en/strings.xml      # Chaînes EN
│   │   └── values/colors.xml          # Palette dash_* (dark theme)
│   │
│   └── AndroidManifest.xml
│
└── mockup/
    └── index.html                     # Maquette interactive HTML 1280×480
```

---

## Couches matérielles

`EVHardware` est organisé en **4 couches d'accès**, du plus haut niveau au plus bas, avec repli automatique en cas d'échec.

### Katman1 — Android Automotive Car API
Couche principale. Utilise les APIs officielles Android Automotive :
- `CarPropertyManager` → modes de conduite, régénération, pédale unique
- `CarHvacManager` → siège chauffant, volant chauffant

La connexion est initialisée par réflexion sur `Car.createCar()` avec plusieurs surcharges tentées successivement. Les actions en attente sont mises en file d'attente et exécutées dès que le service est prêt.

### Katman2 — Raw Binder (fallback)
Repli sur `ServiceManager.getService("vehiclesetting")` avec appels `binderTransact()` directs. Souvent bloqué par SELinux en production.

### Katman4 — Services ADAS (firmware-specific)
Couche dédiée aux fonctions ADAS, chargée dynamiquement selon la génération de firmware :

| Firmware | Service | Mécanisme |
|----------|---------|-----------|
| **SWI133** | `VehiclePropertyManager` | Chargé depuis l'APK launcher via `ClassLoader` + réflexion sur `mIVehiclePropertyService`. Utilise `getMixProperty()` / `setMixProperty()` |
| **SWI68** | `VehicleSettingManager` | Singleton statique chargé via réflexion. Utilise `setAccTjaMode()` / `setLaneKeepingWarningSound()` |
| **SWI69 / SWI131** | `VehicleSettingManager` | Même singleton que SWI68. Utilise `setFcwState()` / `getFcwState()` / `setFcwAutoBrakeMode()` / `setFcwSensitivity()` pour l'AEB. Valeurs confirmées empiriquement sur véhicule réel : `setFcwState(1)` = DÉSACTIVER, `setFcwState(2)` = ACTIVER. |
| **SWI165** | `VehicleSettingManager` | Même SDK que SWI68 (`com.saicmotor.sdk.vehiclesettings`). ADAS via `setAccTjaMode()`. AEB via `setAutoEmergencyBraking(1/2)` comme toggle principal + `setFcwAlarmMode(1/2)` + `setFcwAutoBrakeMode(1/2)`. Modes : 1=OFF, 2=ON. |

### Détection du firmware

```kotlin
// util/FirmwareInfo.kt
FirmwareInfo.initWithContext(context)   // Charge le mode forcé depuis SharedPreferences
val gen = FirmwareInfo.getGeneration()  // Lit ro.build.mt2712.version
// → Gen.SWI133 | Gen.SWI68 | Gen.UNKNOWN

// Si firmware inconnu, l'utilisateur peut forcer un mode :
FirmwareInfo.forceGeneration(context, FirmwareInfo.Gen.SWI133)
FirmwareInfo.isForced(context)          // true si mode forcé actif
FirmwareInfo.getDetectedString()        // Ex : "SWI69-12345" (brut)
```

Le résultat est mis en cache. Si le firmware est `UNKNOWN` et aucun mode forcé, un dialog d'avertissement s'affiche au démarrage. L'utilisateur peut choisir de continuer et forcer SWI133 ou SWI68 via les chips de la top bar.

---

## Système de profils

### Modèle `DrivingProfile`

```kotlin
data class DrivingProfile(
    val id: String,             // UUID unique
    val name: String,           // Nom affiché
    val driveMode: DriveMode,   // ECO / NORMAL / SPORT / SNOW / CUSTOM
    val regenLevel: RegenLevel, // OFF / LOW / MEDIUM / HIGH / ADAPTIVE / ONE_PEDAL
    val steeringHeat: Boolean,
    val seatHeatLeft: Int,      // 0–3
    val seatHeatRight: Int,     // 0–3
    // SWI133 uniquement :
    val overspeedAlarm: Boolean,
    val speedLimitTone: Boolean,
    val adasMode: Int,          // 0=Off 1=Lim 2=Auto 3=ACC 4=ICA
    // SWI68 uniquement :
    val soundWarning: Boolean,
    val swi68AdasMode: Int      // Swi68Mode.OFF / ACC / TJA
)
```

### Persistance

Les profils sont sérialisés en JSON via **Gson** et stockés dans `SharedPreferences`. Maximum **5 profils** par appareil.

### Application d'un profil

`ProfileApplier.apply()` exécute les appels matériels dans l'ordre suivant sur `Dispatchers.IO` :
1. Mode de conduite (rapide — binder)
2. Niveau de régénération (rapide — binder)
3. Volant chauffant (~2 s — polling de confirmation d'état)
4. Siège gauche (~7 s — polling par toggle)
5. Siège droit (~7 s — polling par toggle)
6. Attente Katman4 → ADAS (selon firmware)

---

## Interface utilisateur

### Navigation
L'application utilise un **NavController** avec **3 destinations** :

```
DashboardFragment (départ)
    ├──► ProfileFragment  (bouton PROFILS — toggle)
    └──► SettingsFragment (bouton RÉGLAGES — toggle)
```

Un second appui sur PROFILS ou RÉGLAGES ferme la vue et revient au dashboard.

### Dashboard (écran principal)
Disposition en **2 rangées** (ratio 2:1) optimisée pour 1280×480 :
- **Rangée haute (2/3)** : Mode de conduite | Régénération | ADAS
- **Rangée basse (1/3)** : Climatisation (volant + sièges) | Alertes

### Dark theme — palette de couleurs

| Token | Hex | Usage |
|-------|-----|-------|
| `dash_bg` | `#0C0C0E` | Fond général |
| `dash_card` | `#141416` | Cartes |
| `dash_section` | `#1C1C1F` | Sections internes |
| `dash_border` | `#2A2A2E` | Bordures |
| `dash_accent` | `#38BDF8` | Sélection active (bleu) |
| `dash_eco` | `#22C55E` | Mode ECO (vert) |
| `dash_warn` | `#F59E0B` | Mode SPORT (orange) |
| `dash_danger` | `#F43F5E` | Suppression / danger |

---

## Compilation et installation

Vous pouvez directement télécharger la dernière version de EVProfile via les releases : https://github.com/malys/EVProfile/releases
Il ne vous faut qu'une clé USB et l'accès aux paramètres AAOS afin d'installer l'APK.


Vous pouvez aussi compiler vous même le projet :

### Prérequis
- Android Studio Hedgehog (2023.1) ou supérieur
- JDK 17+
- Android SDK API 34

### Build debug

```bash
# Avec le JDK d'Android Studio
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew assembleStableDebug
# Canal de test avec OTA :
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew assembleUnstableDebug
```

L'APK se trouve dans :
```
app/build/outputs/apk/debug/app-debug.apk
```

### Installation sur le véhicule

L'application nécessite d'être signée avec la clé système de la ROM. Sur un système de développement :

```bash
adb push app-debug.apk /sdcard/
adb shell pm install -r --system /sdcard/app-debug.apk
```

> Sur une ROM de production, l'APK doit être incluse dans le build système ou installée via un mécanisme OEM spécifique.

---

## Permissions requises

| Permission | Justification |
|-----------|---------------|
| `FOREGROUND_SERVICE` | Service de premier plan pour l'auto-apply |
| `WAKE_LOCK` | Empêche le sleep pendant l'application des réglages |
| `RECEIVE_BOOT_COMPLETED` | Démarrage automatique au boot |
| `BLUETOOTH` *(Android 11 et antérieur)* | Association d’un appareil appairé à un profil automatique |
| `BLUETOOTH_CONNECT` *(Android 12+)* | Association d’un appareil appairé à un profil automatique |
| `CAR_POWERTRAIN` | Contrôle du mode de conduite et de la régénération |
| `CONTROL_CAR_CLIMATE` | Contrôle des sièges et du volant chauffants |
| `CAR_VENDOR_EXTENSION` | Extensions propriétaires SAIC |
| `CAR_ENERGY` | Informations batterie / motorisation |
| `INTERNET` *(unstable uniquement)* | Vérification et téléchargement de la pré-release |
| `ACCESS_NETWORK_STATE` *(unstable uniquement)* | Avertissement avant un téléchargement hors Wi-Fi |
