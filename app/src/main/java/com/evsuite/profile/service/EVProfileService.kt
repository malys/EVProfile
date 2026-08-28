package com.evsuite.profile.service

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.evsuite.profile.MainActivity
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.Toast
import com.evsuite.profile.util.LocaleHelper
import com.evsuite.profile.R
import com.evsuite.profile.automation.AutomationDecision
import com.evsuite.profile.automation.AutomationSettings
import com.evsuite.profile.bluetooth.BluetoothProfileManager
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.EVHardware.AebMode
import com.evsuite.hardware.EVHardware.Swi68Mode
import com.evsuite.hardware.model.RegenLevel
import com.evsuite.profile.profile.ProfileApplier
import com.evsuite.profile.profile.ProfileManager
import com.evsuite.profile.shortcut.ShortcutAction
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.PhysicalButtonEventDecoder
import com.evsuite.hardware.VehicleWriteGate
import com.evsuite.hardware.saic.SaicHub
import com.evsuite.profile.util.ThemeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EVProfileService : Service() {

    companion object {
        private const val TAG          = "EV_SVC"
        private const val CHANNEL_ID   = "ev_control_channel"
        private const val NOTIF_ID     = 1
        private const val PREFS_SHORTCUTS = "ev_shortcuts"



        // Intent action broadcast by the SAIC system for physical keys
        private const val HARDKEY_ACTION   = "com.saic.keyevent.hardkey.report"

        /**
         * [T-902] Permission required from the broadcast hardkey TRANSMITTER. Declared in
         * protectionLevel="signature" in the Manifest: only an app signed with the key
         * ROM platform gets it, which excludes any third-party apps that attempt to
         * forge the action to control the shortcuts (and therefore the state of the vehicle).
         */
        const val HARDKEY_PERMISSION = "com.evsuite.profile.permission.RECEIVE_HARDKEY"

        /**
         * One-shot flag: The profile is only applied once per process session.
         * Avoid double-apply when MainActivity and BootReceiver start the service.
         */
        @Volatile private var profileScheduled = false

    }

    // ── Hardkey receiver ─────────────────────────────────────────────────────

    private var hardkeyReceiver: BroadcastReceiver? = null

    // Kept only for source compatibility with the removed Bluetooth automation code.
    // It is never registered, so EVProfile no longer observes Bluetooth connections.
    private var btAclReceiver: BroadcastReceiver? = null

    // ── Receiver sync launcher theme ──────────────────── ─────────────────────
    private var skinChangeReceiver: BroadcastReceiver? = null

    // ── Listener de cycle d'allumage (Katman5) ──────────────────────────────
    private var vehicleConditionListener: ((Int) -> Unit)? = null

    // Button identity and short/long distinction: decoded by the library, which knows
    // keycode aliases per firmware and emits a long press only once per physical press.
    private val buttonDecoder = PhysicalButtonEventDecoder()

    // Toggle states in memory — reset each time the service is started (= car restart)
    // Avoid the first press bug: if you use SharedPrefs, the persisted state may not correspond
    // to the actual state of the car after a restart, causing it to toggle in the wrong direction.
    private val toggleStates = mutableMapOf<String, Boolean>()

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "onCreate")
        startInForeground()
        // Feed the shared gate EVProfile's localized refusal strings (mg4-hardware itself
        // has no app resources; the module falls back to English when no provider is set).
        // The application context, voluntarily: `getString` would resolve on the Service,
        // and messageProvider is a singleton field in the shared library — it
        // would retain this Service, and everything it references, for the duration of the process.
        val strings = applicationContext
        VehicleWriteGate.messageProvider = { decision ->
            when (decision) {
                VehicleWriteGate.Decision.REFUSED_MOVING        -> strings.getString(R.string.write_refused_moving)
                VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED -> strings.getString(R.string.write_refused_unknown_speed)
                VehicleWriteGate.Decision.ALLOWED               -> null
            }
        }
        EVHardware.init(applicationContext)
        // One bind, for one reason: when the speed property answers nothing the gate refuses
        // every road-behaviour write, and the gear — which the vendor service on this hub
        // reports — says whether the car is in park. Without the bind that fallback is silent
        // and a profile is refused with nothing wrong with it. Asynchronous, idempotent, and
        // it holds no reference to this Service.
        SaicHub.connect(applicationContext)
        registerHardkeyReceiver()
        registerSkinChangeReceiver()   // [THEME-AUTO]
        registerIgnitionListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        vehicleConditionListener?.let { EVHardware.unregisterVehicleConditionListener(it) }
        vehicleConditionListener = null
        hardkeyReceiver?.let { unregisterReceiver(it) }
        hardkeyReceiver = null
        skinChangeReceiver?.let { unregisterReceiver(it) } // [THEME-AUTO]
        skinChangeReceiver = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(TAG, "onStartCommand")
        // Re-asserted here: MainActivity starts the service again once Bluetooth access is
        // granted, and that grant is what decides which foreground type the service may hold.
        startInForeground()
        scheduleDefaultProfileOnce()
        return START_STICKY
    }

    /**
     * Enter foreground with the type of service that the app actually has.
     *
     * Since API 34 each declared type is validated against the permissions held at that
     * instant, and `connectedDevice` requires BLUETOOTH_CONNECT — a runtime permission that
     * the application declared without ever asking for it. On a car where it is not
     * granted, the appeal to two arguments ("all types of the manifest") raises a
     * SecurityException and the service dies in its own onCreate, taking away the application
     * profiles with him. Without permission, the service runs without type: profiles
     * apply, only the detection of the paired phone remains silent.
     */
    private fun startInForeground() {
        val type = if (hasBluetoothConnect()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun hasBluetoothConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Dynamic receiver registration ────────────────────────────────────────

    private fun registerHardkeyReceiver() {
        hardkeyReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                handleHardkeyIntent(intent)
            }
        }
        // The issuer must hold HARDKEY_PERMISSION (signature): a broadcast forged by
        // a third-party app never reaches the receiver. EXPORTED remains necessary, the transmitter
        // legitimate being an external system app.
        ContextCompat.registerReceiver(
            this, hardkeyReceiver, IntentFilter(HARDKEY_ACTION),
            HARDKEY_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED
        )
        AppLogger.i(TAG, "HardkeyReceiver registered → $HARDKEY_ACTION (permission $HARDKEY_PERMISSION)")
    }

    // ── Traitement d'un event hardkey ────────────────────────────────────────

    private fun handleHardkeyIntent(intent: Intent) {
        val prefs = getSharedPreferences(PREFS_SHORTCUTS, MODE_PRIVATE)

        // Shortcuts disabled globally → we let the launcher manage
        if (!prefs.getBoolean("shortcut_enabled", false)) return

        // Reading the keycode (several extra names depending on the firmware)
        val keycode = intent.getIntExtra("android.intent.extra.hardkey.keycode", -1)
            .takeIf { it >= 0 }
            ?: intent.getIntExtra("keycode", -1).takeIf { it >= 0 }
            ?: intent.getIntExtra("keyCode", -1).takeIf { it >= 0 }
            ?: return

        val isDown = intent.getBooleanExtra("android.intent.extra.hardkey.down", false)
                     || intent.getBooleanExtra("down", false)
        val isLong = intent.getBooleanExtra("android.intent.extra.hardkey.longpress", false)
                     || intent.getBooleanExtra("longpress", false)

        AppLogger.i(TAG, "HARDKEY keycode=$keycode down=$isDown long=$isLong")

        val event = buttonDecoder.accept(keycode, isDown, isLong) ?: return

        // EVProfile only wires the two star keys; the rest of the steering wheel belongs
        // to the vehicle.
        val slot = when (event.button) {
            PhysicalButtonEventDecoder.Button.STAR_LEFT  -> "btn1"
            PhysicalButtonEventDecoder.Button.STAR_RIGHT -> "btn2"
            else -> return
        }

        val pressKey = when (event.press) {
            PhysicalButtonEventDecoder.Press.LONG  -> "${slot}_long"
            PhysicalButtonEventDecoder.Press.SHORT -> "${slot}_single"
        }
        val action = ShortcutAction.fromId(prefs.getInt("shortcut_$pressKey", 0))
        if (action != ShortcutAction.NONE) executeToggle(action, pressKey)
    }

    // ── Running the toggle ───────────────────────── ─────────────────────────

    private fun executeToggle(action: ShortcutAction, pressKey: String = "") {
        val prefs = getSharedPreferences(PREFS_SHORTCUTS, MODE_PRIVATE)

        // PROFILE_PICKER: overlay floating above the launcher — no status toggle
        if (action == ShortcutAction.PROFILE_PICKER) {
            Handler(Looper.getMainLooper()).post {
                ProfilePickerOverlay.show(this@EVProfileService)
            }
            return
        }

        // APPLY_PROFILE: direct action — no status toggle, each press applies the profile
        if (action == ShortcutAction.APPLY_PROFILE) {
            val profileId = prefs.getString("shortcut_${pressKey}_profile_id", null) ?: return
            CoroutineScope(Dispatchers.IO).launch {
                val profile = ProfileManager(applicationContext).getById(profileId)
                if (profile == null) {
                    prefs.edit().putInt("shortcut_$pressKey", ShortcutAction.NONE.id).apply()
                    AppLogger.i(TAG, "SHORTCUT APPLY_PROFILE — profile $profileId not found, reset to NONE")
                } else {
                    AppLogger.i(TAG, "SHORTCUT APPLY_PROFILE — applying '${profile.name}'")
                    ProfileApplier.apply(profile)
                }
            }
            return
        }

        // VEHICLE_POWER_OFF : check P → confirmation (overlay) → extinction. Sinon message "en P".
        if (action == ShortcutAction.VEHICLE_POWER_OFF) {
            showVehiclePowerOffConfirm()
            return
        }

        // For all other toggles: state in memory (reset when the service starts)
        // Avoids the 1st press bug caused by an out of sync SharedPrefs state after restart.
        val newState = !(toggleStates[action.name] ?: false)
        toggleStates[action.name] = newState

        AppLogger.i(TAG, "SHORTCUT ${action.name} → ${if (newState) "ON/A" else "OFF/B"}")

        CoroutineScope(Dispatchers.IO).launch {
            when (action) {
                ShortcutAction.ONE_PEDAL -> {
                    if (newState) {
                        EVHardware.setRegenLevel(RegenLevel.ONE_PEDAL)
                    } else {
                        val fallback = RegenLevel.fromValue(
                            prefs.getInt("shortcut_one_pedal_fallback", RegenLevel.HIGH.value)
                        )
                        EVHardware.setRegenLevel(fallback)
                    }
                }
                ShortcutAction.AEB_CYCLE -> {
                    val mode = if (newState)
                        prefs.getInt("shortcut_aeb_mode_a", AebMode.ALARM)
                    else
                        prefs.getInt("shortcut_aeb_mode_b", AebMode.ALARM_BRAKE)
                    EVHardware.setAebMode(mode)
                }
                ShortcutAction.SOUND_WARNING    -> EVHardware.setSoundWarning(newState)
                ShortcutAction.OVERSPEED_ALARM  -> EVHardware.setOverspeedAlarm(newState)
                ShortcutAction.SPEED_LIMIT_TONE -> EVHardware.setSpeedLimitTone(newState)
                ShortcutAction.ADAS_CYCLE -> {
                    // All known firmware stores indices 0-4 (Off/Lim.Manuel/Lim.Auto/ACC/ICA|TJA)
                    val modeA = prefs.getInt("shortcut_adas_mode_a", 3)
                    val modeB = prefs.getInt("shortcut_adas_mode_b", 0)
                    val mode  = if (newState) modeA else modeB
                    if (FirmwareInfo.isVsmBased()) {
                        // VSM (SWI68/69/131/132/165) : index → mode ACC/TJA (setAccTjaMode)
                        // + speed limiter (setSpeedLimiterMode), exclusive settings.
                        when (mode) {
                            1 -> { EVHardware.setSpeedLimiterMode(EVHardware.SasMode.MANUEL);      EVHardware.setAccTjaMode(Swi68Mode.OFF) }
                            2 -> { EVHardware.setSpeedLimiterMode(EVHardware.SasMode.INTELLIGENT); EVHardware.setAccTjaMode(Swi68Mode.OFF) }
                            3 -> { EVHardware.setAccTjaMode(Swi68Mode.ACC); EVHardware.setSpeedLimiterMode(EVHardware.SasMode.OFF) }
                            4 -> { EVHardware.setAccTjaMode(Swi68Mode.TJA); EVHardware.setSpeedLimiterMode(EVHardware.SasMode.OFF) }
                            else -> { EVHardware.setAccTjaMode(Swi68Mode.OFF); EVHardware.setSpeedLimiterMode(EVHardware.SasMode.OFF) }
                        }
                    } else {
                        // SWI133: Direct VPM (the index is also the mixedIntelligentDrive value)
                        EVHardware.setMixedIntelligentDrive(mode)
                    }
                }
                ShortcutAction.ENERGY_SAVING_TOGGLE -> EVHardware.setEnergySavingMode(newState)
                ShortcutAction.TSR_TOGGLE           -> EVHardware.setTsrMode(newState)
                ShortcutAction.OPEN_APP -> {
                    val intent = Intent(this@EVProfileService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                }
                ShortcutAction.OPEN_CUSTOM_APP -> {
                    val pkg = prefs.getString("shortcut_${pressKey}_custom_app", null)
                    if (pkg != null) {
                        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                        }
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * “Turn off car” shortcut: first checks the P (read gear) position, then displays
     * the SAME confirmation dialog as the Settings, in the overlay window (triggered from the
     * service). If not in P → Toast. `vehiclePowerOff()` re-checks the P at send time.
     */
    private fun showVehiclePowerOffConfirm() {
        CoroutineScope(Dispatchers.IO).launch {
            val inPark = EVHardware.isVehicleInPark()
            Handler(Looper.getMainLooper()).post {
                if (inPark == true) {
                    val themed = ContextThemeWrapper(LocaleHelper.applyLocale(this@EVProfileService), R.style.Theme_EVProfile)
                    val dialog = MaterialAlertDialogBuilder(themed)
                        .setTitle(R.string.vehicle_power_dialog_title)
                        .setMessage(R.string.vehicle_power_dialog_msg)
                        .setNegativeButton(R.string.vehicle_power_dialog_cancel, null)
                        .setPositiveButton(R.string.vehicle_power_dialog_confirm) { _, _ ->
                            CoroutineScope(Dispatchers.IO).launch {
                                val ok = EVHardware.vehiclePowerOff()
                                AppLogger.i(TAG, "SHORTCUT VEHICLE_POWER_OFF confirmed → $ok")
                            }
                        }
                        .create()
                    dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    dialog.show()
                } else {
                    Toast.makeText(this@EVProfileService, R.string.vehicle_power_need_park, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Schedules the default profile to be applied at process start (one-shot). */
    private fun scheduleDefaultProfileOnce() {
        if (profileScheduled) {
            AppLogger.i(TAG, "Profile already scheduled — skipping")
            return
        }
        profileScheduled = true

        val prefs = getSharedPreferences("ev_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("auto_apply_profile", true)) {
            AppLogger.i(TAG, "auto_apply_profile disabled — skipping")
            return
        }

        applyConfiguredDefaultProfile("Service startup")
    }

    /** BT resolution (+ HFP fallback) → fault at service startup (historical body, unchanged). */
    private fun resolveBtOrDefaultOnSchedule() {
        val pm = ProfileManager(applicationContext)

        // [BT-PROFILES] Searches for all BT profiles among devices already known in memory
        val btProfiles = BluetoothProfileManager.getConnectedMacs()
            .mapNotNull { mac -> pm.getProfileForBtDevice(mac) }
            .distinctBy { it.id }

        when {
            btProfiles.size >= 2 -> {
                // BT conflict: several devices have an associated profile → selection popup
                AppLogger.i(TAG, "[BT] ${btProfiles.size} conflicting Bluetooth profiles — showing picker")
                EVHardware.whenKatman1Ready {
                    ProfilePickerOverlay.show(
                        context      = applicationContext,
                        profiles     = btProfiles,
                        onAutoDismiss = {
                            // Timeout without selection → applies the 1st profile (historical behavior)
                            CoroutineScope(Dispatchers.IO).launch {
                                AppLogger.i(TAG, "[BT] Timeout → fallback profile '${btProfiles[0].name}'")
                                ProfileApplier.apply(btProfiles[0], autoStart = true) { ok ->
                                    AppLogger.i(TAG, "[BT] Fallback '${btProfiles[0].name}' — ok=$ok")
                                }
                            }
                        }
                    )
                }
                return
            }
            btProfiles.size == 1 -> {
                AppLogger.i(TAG, "[BT] Profile '${btProfiles[0].name}' found at startup — waiting for Katman1")
                EVHardware.whenKatman1Ready {
                    ProfileApplier.apply(btProfiles[0], autoStart = true) { ok ->
                        AppLogger.i(TAG, "[BT] Profile '${btProfiles[0].name}' applied — ok=$ok")
                    }
                }
                return
            }
        }

        // [BT-PROFILES] Fallback: async HFP request (case phone connected before service start)
        BluetoothProfileManager.checkConnectedHfpDevices(applicationContext) { devices ->
            val hfpProfiles = devices.mapNotNull { dev -> pm.getProfileForBtDevice(dev.address) }
                .distinctBy { it.id }

            when {
                hfpProfiles.size >= 2 -> {
                    AppLogger.i(TAG, "[BT-HFP] ${hfpProfiles.size} conflicting profiles — showing picker")
                    EVHardware.whenKatman1Ready {
                        ProfilePickerOverlay.show(
                            context       = applicationContext,
                            profiles      = hfpProfiles,
                            onAutoDismiss = {
                                CoroutineScope(Dispatchers.IO).launch {
                                    AppLogger.i(TAG, "[BT-HFP] Timeout → fallback '${hfpProfiles[0].name}'")
                                    ProfileApplier.apply(hfpProfiles[0], autoStart = true) { ok ->
                                        AppLogger.i(TAG, "[BT-HFP] Fallback applied — ok=$ok")
                                    }
                                }
                            }
                        )
                    }
                }
                hfpProfiles.size == 1 -> {
                    AppLogger.i(TAG, "[BT-HFP] Profile '${hfpProfiles[0].name}' found through HFP — waiting for Katman1")
                    EVHardware.whenKatman1Ready {
                        ProfileApplier.apply(hfpProfiles[0], autoStart = true) { ok ->
                            AppLogger.i(TAG, "[BT-HFP] Profile '${hfpProfiles[0].name}' applied — ok=$ok")
                        }
                    }
                }
                else -> {
                    // No BT match → default profile
                    val defaultProfile = pm.getDefaultProfile()
                    if (defaultProfile == null) {
                        AppLogger.i(TAG, "No default profile configured — skipping")
                        return@checkConnectedHfpDevices
                    }
                    AppLogger.i(TAG, "Default profile '${defaultProfile.name}' — waiting for Katman1")
                    EVHardware.whenKatman1Ready {
                        AppLogger.i(TAG, "Hardware ready → applying profile '${defaultProfile.name}'")
                        ProfileApplier.apply(defaultProfile, autoStart = true) { ok ->
                            AppLogger.i(TAG, "Profile '${defaultProfile.name}' applied — ok=$ok")
                        }
                    }
                }
            }
        }
    }

    // ── Listener IGNITION_STATE (Katman5) ────────────────────────────────────

    /**
     * Registers the Katman5 listener on power state changes.
     * At each RUN (0x2), applies the default profile.
     */
    private fun registerIgnitionListener() {
        val vcListener: (Int) -> Unit = { state ->
            when (state) {
                EVHardware.CarIgnitionItem.RUN -> {
                    AppLogger.i(TAG, "Katman5 IGNITION_RUN → applying profile")
                    Handler(Looper.getMainLooper()).postDelayed({
                        applyDefaultProfileOnIgnition()
                    }, 500L)
                }
                EVHardware.CarIgnitionItem.OFF -> {
                    // Extinguishing → we forget the manual choice: the next cycle starts again on the fault/BT
                    if (ProfileApplier.lastManualProfileId != null) {
                        AppLogger.i(TAG, "Katman5 IGNITION_OFF → resetting manual selection")
                        ProfileApplier.lastManualProfileId = null
                    }
                }
            }
        }
        vehicleConditionListener = vcListener
        EVHardware.registerVehicleConditionListener(vcListener)
        AppLogger.i(TAG, "Katman5 listener registered")
    }

    /**
     * [BT-PROFILES] Registers ACL Bluetooth receivers to maintain
     * the list of connected devices in BluetoothProfileManager.
     */
    private fun registerBtAclReceiver() {
        btAclReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    ?: return
                val mac = device.address ?: return
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        BluetoothProfileManager.onDeviceConnected(mac)
                        AppLogger.i(TAG, "[BT] Device connected: $mac")
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        BluetoothProfileManager.onDeviceDisconnected(mac)
                        AppLogger.i(TAG, "[BT] Device disconnected: $mac")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // Protected system broadcasts (only the system can broadcast them): no permission
        // additional to require, but the export is made explicit.
        ContextCompat.registerReceiver(
            this, btAclReceiver, filter, ContextCompat.RECEIVER_EXPORTED
        )
        AppLogger.i(TAG, "[BT] BtAclReceiver registered")
    }

    /**
     * Temperature automation — precedence: after manual choice, before BT/default.
     * Not applicable (disabled / unreadable temp / < threshold / profile absent) => [onFallback].
     * Applicable => direct application (box checked) or confirmation popup
     * (NON/timeout => [onFallback]).
     */
    private fun tryTemperatureAutomation(onFallback: () -> Unit) {
        val ctx = applicationContext
        val cfg = AutomationSettings.read(ctx)
        // Silent output = impossible to diagnose remotely: we ALWAYS trace
        // the config, including when the feature is simply disabled.
        if (!cfg.enabled) {
            AppLogger.i(TAG, "Temperature automation disabled in Settings → Bluetooth/default fallback")
            onFallback(); return
        }
        val profile = cfg.profileId.takeIf { it.isNotEmpty() }?.let { ProfileManager(ctx).getById(it) }

        EVHardware.whenKatman1Ready {
            val temp = EVHardware.getOutsideTempCelsius()
            val outcome = AutomationDecision.evaluate(cfg.enabled, temp, cfg.threshold, cfg.direction, profile != null)
            AppLogger.i(TAG, "Auto temp: config → dir=${cfg.direction} seuil=${cfg.threshold}°C " +
                "profile='${profile?.name ?: "NONE"}' auto=${cfg.autoExecute} | temperature=${temp ?: "unreadable"} → $outcome")
            if (outcome != AutomationDecision.Outcome.APPLY || profile == null || temp == null) {
                AppLogger.i(TAG, "Temperature automation not applicable → Bluetooth/default fallback")
                onFallback(); return@whenKatman1Ready
            }
            if (cfg.autoExecute) {
                AppLogger.i(TAG, "Temperature automation → applying '${profile.name}' directly (temp=$temp direction=${cfg.direction} threshold=${cfg.threshold})")
                ProfileApplier.apply(profile, autoStart = true) { ok -> AppLogger.i(TAG, "Temperature automation applied — ok=$ok") }
            } else {
                AppLogger.i(TAG, "Auto temp → popup confirmation '${profile.name}'")
                ProfileConfirmOverlay.show(
                    context     = ctx,
                    profile     = profile,
                    threshold   = cfg.threshold,
                    currentTemp = temp,
                    direction   = cfg.direction,
                    onConfirmed = {
                        CoroutineScope(Dispatchers.IO).launch {
                            ProfileApplier.apply(profile, autoStart = true) { ok -> AppLogger.i(TAG, "Auto temp OUI '${profile.name}' — ok=$ok") }
                        }
                    },
                    onDeclined  = { onFallback() }
                )
            }
        }
    }

    /**
     * Applies the appropriate profile following an IGNITION_STATE=RUN event.
     * Priority: recent manual choice (popup/app) → default profile.
     */
    private fun applyDefaultProfileOnIgnition() {
        val prefs = getSharedPreferences("ev_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("auto_apply_profile", true)) {
            AppLogger.i(TAG, "IGNITION → auto_apply_profile disabled, skipping")
            return
        }

        val pm = ProfileManager(applicationContext)

        // Recent manual choice (steering wheel popup / app) → priority on BT and default.
        // The user has explicitly selected a profile since startup: we respect it.
        val manualId = ProfileApplier.lastManualProfileId
        if (manualId != null) {
            val manualProfile = pm.getById(manualId)
            if (manualProfile != null) {
                AppLogger.i(TAG, "IGNITION → preserving manual selection: '${manualProfile.name}'")
                EVHardware.whenKatman1Ready {
                    ProfileApplier.apply(manualProfile, autoStart = true) { ok ->
                        AppLogger.i(TAG, "IGNITION → manual profile '${manualProfile.name}' reapplied — ok=$ok")
                    }
                }
                return
            } else {
                // Profile deleted in the meantime → we forget the choice and we fall back on the default/BT
                AppLogger.i(TAG, "IGNITION → manual selection not found (id=$manualId), using default/Bluetooth fallback")
                ProfileApplier.lastManualProfileId = null
            }
        }

        applyConfiguredDefaultProfile("IGNITION")
    }

    /**
     * The only automation retained in EVProfile: the profile explicitly defined by default.
     * The temperature and Bluetooth triggers now belong to EVTasker.
     */
    private fun applyConfiguredDefaultProfile(source: String) {
        val profile = ProfileManager(applicationContext).getDefaultProfile() ?: run {
            AppLogger.i(TAG, "$source → no default profile, skipping")
            return
        }
        AppLogger.i(TAG, "$source → applying default profile '${profile.name}'")
        EVHardware.whenKatman1Ready {
            ProfileApplier.apply(profile, autoStart = true) { ok ->
                AppLogger.i(TAG, "$source → default profile '${profile.name}' applied — ok=$ok")
            }
        }
    }

    /** BT resolution → fault on RUN transition (historical body, unchanged). */
    private fun resolveBtOrDefaultOnIgnition() {
        val pm = ProfileManager(applicationContext)
        // [BT-PROFILES] Searches all BT profiles among connected devices
        val btProfiles = BluetoothProfileManager.getConnectedMacs()
            .mapNotNull { mac -> pm.getProfileForBtDevice(mac) }
            .distinctBy { it.id }

        when {
            btProfiles.size >= 2 -> {
                AppLogger.i(TAG, "IGNITION [BT] → ${btProfiles.size} conflicting profiles — showing picker")
                EVHardware.whenKatman1Ready {
                    ProfilePickerOverlay.show(
                        context       = applicationContext,
                        profiles      = btProfiles,
                        onAutoDismiss = {
                            CoroutineScope(Dispatchers.IO).launch {
                                AppLogger.i(TAG, "IGNITION [BT] Timeout → fallback '${btProfiles[0].name}'")
                                ProfileApplier.apply(btProfiles[0], autoStart = true) { ok ->
                                    AppLogger.i(TAG, "IGNITION [BT] Fallback applied — ok=$ok")
                                }
                            }
                        }
                    )
                }
            }
            btProfiles.size == 1 -> {
                AppLogger.i(TAG, "IGNITION [BT] → applying profile '${btProfiles[0].name}'")
                EVHardware.whenKatman1Ready {
                    ProfileApplier.apply(btProfiles[0], autoStart = true) { ok ->
                        AppLogger.i(TAG, "IGNITION [BT] → profile '${btProfiles[0].name}' applied — ok=$ok")
                    }
                }
            }
            else -> {
                // No BT match → default profile
                val defaultProfile = pm.getDefaultProfile() ?: run {
                    AppLogger.i(TAG, "IGNITION → no default profile, skipping")
                    return
                }
                AppLogger.i(TAG, "IGNITION → applying default profile '${defaultProfile.name}'")
                EVHardware.whenKatman1Ready {
                    ProfileApplier.apply(defaultProfile, autoStart = true) { ok ->
                        AppLogger.i(TAG, "IGNITION → profile '${defaultProfile.name}' applied — ok=$ok")
                    }
                }
            }
        }
    }

    // ── Receiver sync theme launcher (SWI69 / SWI131 / SWI132) ─────────────

    /**
     * Listen to the broadcast "com.saicmotor.changeSkin" emitted by the MG launcher
     * when user changes theme (dark ↔ light).
     * Does nothing if the firmware does not expose SKIN_THEME_CONFIG or if
     * the user has chosen a manual theme (mode ≠ “auto”).
     */
    private fun registerSkinChangeReceiver() {
        if (!ThemeHelper.hasSkinThemeConfig(this)) {
            // SWI133/68: MODE_NIGHT_FOLLOW_SYSTEM manages sync automatically
            AppLogger.i(TAG, "[THEME] SKIN_THEME_CONFIG absent — FOLLOW_SYSTEM active, broadcast not required")
            return
        }
        skinChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val prefs = getSharedPreferences("ev_settings", MODE_PRIVATE)
                if (prefs.getString(ThemeHelper.PREF_THEME_MODE, "dark") != "auto") return

                val nightMode = ThemeHelper.getLauncherNightMode(ctx)
                Handler(Looper.getMainLooper()).post {
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
                    ThemeHelper.notifyThemeChanged()
                }
                AppLogger.i(TAG, "[THEME] changeSkin received → nightMode=$nightMode")
            }
        }
        // Issued by the SAIC launcher (external app): explicit export. Do not write anything in the
        // vehicle — a forged broadcast can only change the theme of the app.
        ContextCompat.registerReceiver(
            this, skinChangeReceiver, IntentFilter(ThemeHelper.ACTION_SKIN_CHANGE),
            ContextCompat.RECEIVER_EXPORTED
        )
        AppLogger.i(TAG, "[THEME] SkinChangeReceiver registered")
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "EVProfile", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("EVProfile")
            .setContentText("Service active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}
