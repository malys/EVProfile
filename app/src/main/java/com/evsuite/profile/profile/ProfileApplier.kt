package com.evsuite.profile.profile

import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.EVHardware.Swi68Mode
import com.evsuite.hardware.model.DrivingProfile
import com.evsuite.hardware.FirmwareInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ProfileApplier {

    private const val TAG = "EV_PROFILE"

    /**
     * Single, supervised scope for all profile applications. Replaces
     * GlobalScope: One application that fails does not affect subsequent ones.
     */
    private val applierScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Serializes profile applications. Steering wheel button, Bluetooth connection and pass
     * in READY can be triggered simultaneously; without this lock the writes
     * interleave and multi-step AEB sequences (setFcwAutoBrakeMode then
     * setFcwState) can be interrupted by a concurrent profile, leaving ADAS in an
     * indeterminate state.
     */
    private val applyMutex = Mutex()

    /** Application in progress: canceled as soon as a new one is requested. */
    @Volatile
    private var currentApply: Job? = null

    /**
     * ID of the last profile applied MANUALLY (steering-wheel picker, app button, shortcut
     * APPLY_PROFILE) — i.e. with autoStart=false. Allows switching to READY to respect the
     * explicit choice of the user instead of re-applying the default profile.
     * In memory: reset when the car is turned off (IGNITION_OFF) or when the process is restarted.
     */
    @Volatile
    var lastManualProfileId: String? = null

    /**
     * Applies all [profile] settings to the vehicle asynchronously.
     * HVAC operations (seat/steering wheel) are blocking (polling up to 7s) and are
     * executed on the IO dispatcher.
     *
     * @param autoStart true if the application is triggered automatically on startup
     *   (IGNITION/boot). Enables the sound alert verification pass (SWI132/SWI133):
     *   on cold start, the firmware may re-assert OVERSPEED/SPEED_TONE after our
     *   writes, so the values are read again and corrected on mismatch. This is unnecessary
     *   for manual profile application.
     */
    fun apply(profile: DrivingProfile, autoStart: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        AppLogger.i(TAG, "Applying profile: ${profile.name} (autoStart=$autoStart)")

        // Manual application (popup flyout / app / shortcut) → we store the user's choice
        // so that switching to READY respects it instead of re-applying the default profile.
        if (!autoStart) {
            lastManualProfileId = profile.id
            AppLogger.i(TAG, "  Remembered manual selection: ${profile.name} (id=${profile.id})")
        }

        val previous = currentApply
        currentApply = applierScope.launch {
            // Cancels the previous application before taking the lock: the new one
            // authoritative request, we do not queue behind an obsolete sequence.
            previous?.cancelAndJoin()
            applyMutex.withLock { applyLocked(profile, autoStart, onComplete) }
        }
    }

    /**
     * Body of the application, executed under [applyMutex]: a single sequence of writes
     * vehicle at a time.
     */
    private fun applyLocked(profile: DrivingProfile, autoStart: Boolean, onComplete: ((Boolean) -> Unit)?) {
            var ok = true

            // Mode de conduite (rapide — binder call)
            val dmOk = EVHardware.setDriveMode(profile.driveMode)
            AppLogger.i(TAG, "  DriveMode=${profile.driveMode.label} → $dmOk")
            ok = ok && dmOk

            // Regeneration level (fast — binder call)
            val rlOk = EVHardware.setRegenLevel(profile.regenLevel)
            AppLogger.i(TAG, "  RegenLevel=${profile.regenLevel.label} → $rlOk")
            ok = ok && rlOk

            // Steering wheel + Heated seats — only SWI133 and SWI68 (SWI69/SWI131 do not have this equipment)
            if (FirmwareInfo.hasHeatFeatures()) {
                val shOk = EVHardware.setSteeringHeat(profile.steeringHeat)
                AppLogger.i(TAG, "  SteeringHeat=${profile.steeringHeat} → $shOk")
                val slOk = EVHardware.setSeatHeatLeft(profile.seatHeatLeft)
                AppLogger.i(TAG, "  SeatHeatLeft=${profile.seatHeatLeft} → $slOk")
                val srOk = EVHardware.setSeatHeatRight(profile.seatHeatRight)
                AppLogger.i(TAG, "  SeatHeatRight=${profile.seatHeatRight} → $srOk")
            }

            AppLogger.i(TAG, "Profile '${profile.name}' Katman1 completed — ok=$ok")
            onComplete?.invoke(ok)

            // ADAS (Katman4) — applied as soon as the service is ready
            EVHardware.whenKatman4Ready {
                AppLogger.i(TAG, "  Applying ADAS for profile '${profile.name}'")
                if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132) {
                    // ── SWI132 ──────────────────────────────────────────────────────────
                    //
                    // SWI132 uses CarVehicleSettingClient for ADAS (ACC/TJA)
                    // but the IVehicleSettingService direct binder for sound alerts.
                    // → setOverspeedAlarm() and setSpeedLimitTone() send the correct binder transactions.
                    //   (0x128 and 0x12a) without going through VehicleSettingManager.

                    // TSR (SLIF) via VSM — applied first as SWI133
                    val tsrOk = EVHardware.setTsrMode(profile.tsrEnabled)
                    AppLogger.i(TAG, "  TsrEnabled=${profile.tsrEnabled} → $tsrOk")

                    // Alertes sonores via VSM
                    // SWI132 firmware resets overspeed and speedTone to ON ~400ms after activation
                    // of the TSR (same behavior as SWI133). setTsrMode() returns immediately without
                    // wait for this reset. If we write alerts too early, the firmware
                    // then crushes them. We wait 450ms to let the firmware complete its
                    // reset before applying profile values.
                    if (profile.tsrEnabled) {
                        try { Thread.sleep(450) } catch (_: InterruptedException) {}
                    }
                    // Delay of 150ms between the two writes: the middleware processes the properties
                    // in a queue with debounce — two writes too fast mean that only the
                    // last one is validated. 150ms ensures that the first one is processed.
                    val oaOk = EVHardware.setOverspeedAlarm(profile.overspeedAlarm)
                    AppLogger.i(TAG, "  OverspeedAlarm=${profile.overspeedAlarm} → $oaOk")
                    try { Thread.sleep(150) } catch (_: InterruptedException) {}
                    val stOk = EVHardware.setSpeedLimitTone(profile.speedLimitTone)
                    AppLogger.i(TAG, "  SpeedLimitTone=${profile.speedLimitTone} → $stOk")

                    // Mode ACC/TJA via CarVehicleSettingClient (setAccTjaState).
                    // SHWA (old limiter coding) is no longer an ACC/TJA mode → returned to Off;
                    // the limiter is handled separately via setSasMode below.
                    val cruiseMode = if (profile.swi68AdasMode == Swi68Mode.SHWA) Swi68Mode.OFF else profile.swi68AdasMode
                    val adOk = EVHardware.setAccTjaMode(cruiseMode)
                    AppLogger.i(TAG, "  AdasMode=0x${cruiseMode.toString(16)} → $adOk")
                    // Speed ​​limiter (SAS) — applied only if the profile has configured it.
                    // Profiles created before this function (swi132LimiterConfigured=false) → not affected.
                    if (profile.swi132LimiterConfigured) {
                        EVHardware.setSpeedLimiterMode(profile.swi132SasMode)
                        AppLogger.i(TAG, "  SasMode=${profile.swi132SasMode} (0=Off 2=Manuel 3=Intelligent)")
                    }
                    applyAeb(profile.aebEnabled, profile.aebMode, profile.aebSensitivity)

                    // Energy saving — via CarVehicleSettingClient (setEnduranceMode), same path as SWI69
                    val esOk = EVHardware.setEnergySavingMode(profile.energySaving)
                    AppLogger.i(TAG, "  EnergySaving=${profile.energySaving} → $esOk")

                } else if (FirmwareInfo.isVsmBased()) {
                    // ── SWI68/SWI69/SWI131/SWI165 ──────────────────────────────────────
                    //
                    // The TSR is applied FIRST: setTsrMode() blocks 400 ms internally
                    // and restores soundWarning from preferences. We call him first
                    // to then be able to overwrite the sound alert with the profile value.
                    val tsrOk = EVHardware.setTsrMode(profile.tsrEnabled)
                    AppLogger.i(TAG, "  TsrEnabled=${profile.tsrEnabled} → $tsrOk")

                    // Audible alert — applied AFTER the TSR to overwrite its internal restore
                    val swOk = EVHardware.setSoundWarning(profile.soundWarning)
                    AppLogger.i(TAG, "  SoundWarning=${profile.soundWarning} → $swOk")

                    // ACC/TJA mode — SHWA (old limiter coding) set to Off (limiter managed separately)
                    val cruiseMode = if (profile.swi68AdasMode == Swi68Mode.SHWA) Swi68Mode.OFF else profile.swi68AdasMode
                    val adOk = EVHardware.setAccTjaMode(cruiseMode)
                    AppLogger.i(TAG, "  AdasMode=0x${cruiseMode.toString(16)} → $adOk")
                    // Speed ​​limiter — applied only if the profile has configured it.
                    // (SWI69/SWI131 → setSasMode ; SWI68/SWI165 → setSpeedAsstMode, dispatch interne)
                    if (profile.swi132LimiterConfigured) {
                        EVHardware.setSpeedLimiterMode(profile.swi132SasMode)
                        AppLogger.i(TAG, "  LimiterMode=${profile.swi132SasMode} (0=Off 2=Manuel 3=Intelligent)")
                    }
                    applyAeb(profile.aebEnabled, profile.aebMode, profile.aebSensitivity)

                    // Power saving — VSM firmwares excluding SWI132 (SWI68/SWI69/SWI131/SWI165)
                    val esOk = EVHardware.setEnergySavingMode(profile.energySaving)
                    AppLogger.i(TAG, "  EnergySaving=${profile.energySaving} → $esOk")
                } else {
                    // ── SWI133/UNKNOWN ──────────────────────────────────────────────────
                    //
                    // Same logic: activating TSR re-enables OVERSPEED_ALARM and SPEED_LIMIT_TONE.
                    // setTsrMode() restores from preferences — we call it first
                    // then we overwrite with the profile values.
                    if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133) {
                        val tsrOk = EVHardware.setTsrMode(profile.tsrEnabled)
                        AppLogger.i(TAG, "  TsrEnabled=${profile.tsrEnabled} → $tsrOk")
                    }

                    // Speed ​​alerts — applied AFTER TSR
                    // Delay of 150ms between the two writes: the vehicle middleware (VPM) processes
                    // properties in a queue with debounce — two writes too fast make
                    // that only the last one is validated. 150ms ensures that the first one is processed.
                    val oaOk = EVHardware.setOverspeedAlarm(profile.overspeedAlarm)
                    AppLogger.i(TAG, "  OverspeedAlarm=${profile.overspeedAlarm} → $oaOk")
                    try { Thread.sleep(150) } catch (_: InterruptedException) {}
                    val stOk = EVHardware.setSpeedLimitTone(profile.speedLimitTone)
                    AppLogger.i(TAG, "  SpeedLimitTone=${profile.speedLimitTone} → $stOk")

                    val adOk = EVHardware.setMixedIntelligentDrive(profile.adasMode)
                    AppLogger.i(TAG, "  AdasMode=${profile.adasMode} → $adOk")
                    applyAeb(profile.aebEnabled, profile.aebMode, profile.aebSensitivity)

                    // Energy saving — SWI133 via VPM
                    if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133) {
                        val esOk = EVHardware.setEnergySavingMode(profile.energySaving)
                        AppLogger.i(TAG, "  EnergySaving=${profile.energySaving} → $esOk")
                    }
                }
                // ELK — common to all known firmware
                applyElk(profile.elkMode, profile.elkSensitivity, profile.lasAudibleWarning, profile.lasVibrationReminder)

                // ── ADAS verification pass (self-start only) ────────────
                // On cold start, the firmware may re-assert certain settings AFTER
                // our writing (overspeed/tone alerts ~400ms after SLIF/TSR; ELK reset to
                // its default value). We reread after stabilization and we rewrite in the event of a discrepancy.
                // Concerne SWI132/SWI133 (2 alertes distinctes + ELK).
                val gen = FirmwareInfo.getGeneration()
                if (autoStart && (gen == FirmwareInfo.Gen.SWI133 || gen == FirmwareInfo.Gen.SWI132)) {
                    verifyAdasWithRetry(profile)
                }
            }
    }

    /**
     * Post-application verification pass (self-start, SWI132/SWI133). Wait for the end of
     * the firmware re-assertion window then rereads/rewrites in the event of a deviation:
     *   - alertes sonores survitesse + ton
     *   - ELK (lane departure assist) — the firmware resets it to its default value at boot
     */
    private fun verifyAdasWithRetry(profile: DrivingProfile) {
        AppLogger.i(TAG, "  [VERIFY] Checking ADAS after automatic startup — waiting 500ms")
        try { Thread.sleep(500) } catch (_: InterruptedException) {}
        verifyOneAlert("OverspeedAlarm", profile.overspeedAlarm,
            { EVHardware.overspeedAlarmOnOrNull() }, { EVHardware.setOverspeedAlarm(it) })
        verifyOneAlert("SpeedLimitTone", profile.speedLimitTone,
            { EVHardware.speedLimitToneOnOrNull() }, { EVHardware.setSpeedLimitTone(it) })
        verifyElk(profile)
    }

    /**
     * Checks ELK (Lane Departure Assist) mode after stabilization and rewrites it if it has
     * derivative (auto reactivation by firmware). Does nothing if the profile does not configure the ELK
     * (elkMode=0). Reapplies the complete ELK config (mode + sensitivity + SWI132 sound/vibration).
     */
    private fun verifyElk(profile: DrivingProfile) {
        if (profile.elkMode == 0) return   // ELK not configured in this profile → do not touch
        repeat(3) { i ->
            val actual = EVHardware.getElkMode()
            // -1 = unreadable. Without this safeguard the loop rewrites the ELK three times on a
            // firmware which never returns its mode, counting it each time as a deviation.
            if (actual < 0) {
                AppLogger.w(TAG, "  [VERIFY] ElkMode unreadable — verification stopped")
                return
            }
            if (actual == profile.elkMode) {
                AppLogger.i(TAG, "  [VERIFY] ElkMode conforme (lu=$actual" +
                    if (i > 0) ", after $i correction(s))" else ")")
                return
            }
            AppLogger.w(TAG, "  [VERIFY] ElkMode MISMATCH (read=$actual, expected=${profile.elkMode}) → rewriting (attempt ${i + 1}/3)")
            applyElk(profile.elkMode, profile.elkSensitivity, profile.lasAudibleWarning, profile.lasVibrationReminder)
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
        }
        val finalVal = EVHardware.getElkMode()
        if (finalVal == profile.elkMode)
            AppLogger.i(TAG, "  [VERIFY] ElkMode finalement conforme (lu=$finalVal)")
        else
            AppLogger.w(TAG, "  [VERIFY] ElkMode FAILED after 3 attempts (read=$finalVal, expected=${profile.elkMode})")
    }

    /**
     * Replays an alert; if it differs from the desired value, rewrite it and try again.
     * Up to 3 attempts spaced 300ms apart to cover a late firmware overwrite.
     */
    private fun verifyOneAlert(name: String, desired: Boolean, read: () -> Boolean?, write: (Boolean) -> Boolean) {
        repeat(3) { i ->
            val actual = read()
            // null = firmware did not respond. We cannot verify what we cannot
            // read: insisting three times would rewrite a setting based on a failed reading.
            if (actual == null) {
                AppLogger.w(TAG, "  [VERIFY] $name unreadable — verification stopped")
                return
            }
            if (actual == desired) {
                AppLogger.i(TAG, "  [VERIFY] $name conforme (lu=$actual" +
                    if (i > 0) ", after $i correction(s))" else ")")
                return
            }
            AppLogger.w(TAG, "  [VERIFY] $name MISMATCH (read=$actual, expected=$desired) → rewriting (attempt ${i + 1}/3)")
            write(desired)
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
        }
        val finalVal = read()
        if (finalVal == desired)
            AppLogger.i(TAG, "  [VERIFY] $name finalement conforme (lu=$finalVal)")
        else
            AppLogger.w(TAG, "  [VERIFY] $name FAILED after 3 attempts (read=$finalVal, expected=$desired)")
    }

    /**
     * Applies the ELK (lane departure assist) settings of the profile — all firmwares.
     * If elkMode=0 (default value — profile created before adding the ELK),
     * we do not touch the ELK settings to avoid unintentional modification.
     */
    private fun applyElk(elkMode: Int, elkSensitivity: Int, lasAudibleWarning: Boolean = true, lasVibrationReminder: Boolean = true) {
        if (elkMode == 0) {
            AppLogger.i(TAG, "  ELK — default values, skipping to avoid unintended changes")
            return
        }
        val modeOk = EVHardware.setElkMode(elkMode)
        AppLogger.i(TAG, "  ElkMode=$elkMode → $modeOk")
        if (elkMode != EVHardware.ElkMode.OFF && elkSensitivity > 0) {
            val senOk = EVHardware.setElkSensitivity(elkSensitivity)
            AppLogger.i(TAG, "  ElkSensitivity=$elkSensitivity → $senOk")
        }
        // SWI132: Audible alert + Vibration applied after delay
        // The firmware can reset these values ​​when changing ELK mode.
        // 300ms ensures that the firmware has completed its internal reset.
        if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 && elkMode != EVHardware.ElkMode.OFF) {
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            val soundOk = EVHardware.setLasWarningSound(lasAudibleWarning)
            AppLogger.i(TAG, "  LasAudibleWarning=$lasAudibleWarning → $soundOk")
            val vibrOk = EVHardware.setLasWarningVibration(lasVibrationReminder)
            AppLogger.i(TAG, "  LasVibrationReminder=$lasVibrationReminder → $vibrOk")
        }
    }

    /**
     * Applies the profile's AEB settings.
     * If aebEnabled=false AND aebMode=1 AND aebSensitivity=0 (default values),
     * we do not touch the AEB state of the car to avoid unintentional deactivation.
     */
    private fun applyAeb(aebEnabled: Boolean, aebMode: Int, aebSensitivity: Int = 0) {
        val isDefault = !aebEnabled && aebMode == 1 && aebSensitivity == 0
        if (isDefault) {
            AppLogger.i(TAG, "  AEB — default values, skipping to avoid unintended disable")
            return
        }
        val aebOk = EVHardware.setAebEnabled(aebEnabled)
        AppLogger.i(TAG, "  AebEnabled=$aebEnabled → $aebOk")
        if (aebEnabled) {
            val aebModeOk = EVHardware.setAebMode(aebMode)
            AppLogger.i(TAG, "  AebMode=$aebMode → $aebModeOk")
            if (aebSensitivity > 0) {
                val senOk = EVHardware.setAebSensitivity(aebSensitivity)
                AppLogger.i(TAG, "  AebSensitivity=$aebSensitivity → $senOk")
            }
        }
    }
}
