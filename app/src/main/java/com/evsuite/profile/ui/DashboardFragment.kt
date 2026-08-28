package com.evsuite.profile.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import androidx.fragment.app.Fragment
import com.evsuite.profile.R
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.EVHardware.AebMode
import com.evsuite.hardware.EVHardware.AebSensitivity
import com.evsuite.hardware.EVHardware.ElkMode
import com.evsuite.hardware.EVHardware.ElkSensitivity
import com.evsuite.hardware.EVHardware.Swi68Mode
import com.evsuite.hardware.model.DriveMode
import com.evsuite.hardware.model.RegenLevel
import com.evsuite.hardware.FirmwareInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A dashboard page.
 *   [PAGE_CONTROLS]: driving parameters, climate, alerts
 *   [PAGE_ELK]      : assistant de sortie de voie (ELK) et AEB
 *
 * The fragment no longer has its own ViewPager2: the two pages are two instances, and it is
 * the pager of [com.evsuite.profile.MainActivity] which holds them at the same rank as Profiles or
 * Settings. A pager nested within a pager would not have chained the scan — the gesture would
 * would be stopped at the edge of the dashboard instead of continuing to the following screens.
 */
class DashboardFragment : Fragment() {

    /** Which of the two pages this instance displays. */
    private val page: Int
        get() = arguments?.getInt(ARG_PAGE) ?: PAGE_CONTROLS

    // ── Page 0 — Drive mode ─────────────────────────────────────────────────
    private val driveModeButtons = mutableMapOf<DriveMode, Button>()

    // ── Page 0 — Regeneration ─────────────────────── ────────────────────────
    private val regenButtons = mutableMapOf<RegenLevel, Button>()

    // ── Page 0 — ADAS SWI133 ────────────────────────────────────────────────
    private var btnAdasOff: Button?     = null
    private var btnAdasLimiteur: Button? = null
    private var btnAdasAuto: Button?    = null
    private var btnAdasAcc: Button?     = null
    private var btnAdasIca: Button?     = null
    private val swi133AdasMap: Map<Int, Button?>
        get() = mapOf(0 to btnAdasOff, 1 to btnAdasLimiteur, 2 to btnAdasAuto, 3 to btnAdasAcc, 4 to btnAdasIca)

    // ── Page 0 — ADAS SWI68/69/131/165 (5 boutons index 0-4) ────────────────
    // Off / Lim.Manuel / Lim.Auto / ACC / TJA
    private var btnSwi68Off: Button? = null
    private var btnSwi68Lim: Button? = null
    private var btnSwi68Auto: Button? = null
    private var btnSwi68Acc: Button? = null
    private var btnSwi68Tja: Button? = null
    private val swi68AdasMap: Map<Int, Button?>
        get() = mapOf(0 to btnSwi68Off, 1 to btnSwi68Lim, 2 to btnSwi68Auto, 3 to btnSwi68Acc, 4 to btnSwi68Tja)

    // ── Page 0 — Climat ─────────────────────────────────────────────────────
    private var switchSteering: Switch? = null
    private var seatLeftButtons: List<Button>? = null
    private var seatRightButtons: List<Button>? = null

    // ── Page 0 — Alertes ────────────────────────────────────────────────────
    private var switchOverspeed: Switch? = null
    private var switchSpeedTone: Switch? = null
    private var switchSoundWarning: Switch? = null
    private var alertsGroupSwi133: View? = null

    // ── Page 0 — TSR + Energy saving ───────────────────────────────────
    private var switchTsr: Switch? = null
    private var btnEnergySaving: Button? = null
    private var energySavingOn = false
    /** Last known driving mode — necessary to arbitrate SNOW / Eco energy exclusions. */
    private var currentDriveMode: DriveMode? = null
    /** Last known regen level — used to announce only real changes. */
    private var currentRegenLevel: RegenLevel? = null

    // ── AEB: page 0 for VSM-based, page 1 (SWI133) for others ───────────
    private var switchAeb: Switch? = null
    private var btnAebAlarm: Button? = null
    private var btnAebAlarmBrake: Button? = null
    private var btnAebSenLow: Button? = null
    private var btnAebSenStandard: Button? = null
    private var btnAebSenHigh: Button? = null
    private val aebSenMap: Map<Int, Button?>
        get() = mapOf(AebSensitivity.LOW to btnAebSenLow, AebSensitivity.STANDARD to btnAebSenStandard, AebSensitivity.HIGH to btnAebSenHigh)

    // ── Page 1 — ELK ────────────────────────────────────────────────────────
    private var switchElk: Switch? = null
    private var btnElkAlert: Button? = null
    private var btnElkAssist: Button? = null
    private var btnElkEmergency: Button? = null
    private var btnElkSenLow: Button? = null
    private var btnElkSenStandard: Button? = null
    private var btnElkSenHigh: Button? = null
    // SWI132 ELK extra
    private var switchElkSound: Switch? = null
    private var switchElkVibration: Switch? = null
    private val elkModeMap: Map<Int, Button?>
        get() = mapOf(ElkMode.ALERT to btnElkAlert, ElkMode.ASSIST to btnElkAssist, ElkMode.EMERGENCY to btnElkEmergency)
    private val elkSenMap: Map<Int, Button?>
        get() = mapOf(ElkSensitivity.LOW to btnElkSenLow, ElkSensitivity.STANDARD to btnElkSenStandard, ElkSensitivity.HIGH to btnElkSenHigh)

    /** True during programmatic Switch updates — blocks listeners. */
    private var isRefreshing = false
    /** Last known active ELK mode (to restore mode when toggle ON). */
    private var lastActiveElkMode = ElkMode.EMERGENCY

    // ── Colours (lazy so the context is available) ───────────────────────────
    private val colorActive   by lazy { requireContext().getColor(R.color.dash_accent_dim) }
    private val colorInactive by lazy { requireContext().getColor(R.color.dash_btn) }
    private val colorTextActive   by lazy { requireContext().getColor(R.color.dash_accent) }
    private val colorTextInactive by lazy { requireContext().getColor(R.color.text_secondary) }
    private val colorEcoBg   by lazy { requireContext().getColor(R.color.dash_eco_dim) }
    private val colorEcoText by lazy { requireContext().getColor(R.color.dash_eco) }
    private val colorWarnBg  by lazy { requireContext().getColor(R.color.dash_warn_dim) }
    private val colorWarnText by lazy { requireContext().getColor(R.color.dash_warn) }

    // ═════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(
        if (page == PAGE_ELK) R.layout.page_dashboard_elk else R.layout.page_dashboard_main,
        container,
        false
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (page == PAGE_ELK) bindElkPage(view) else bindMainPage(view)
    }

    override fun onResume() {
        super.onResume()
        refreshDriveRegen()
        refreshClimate()
        EVHardware.whenKatman4Ready {
            if (isAdded) {
                refreshAdas()
                if (FirmwareInfo.isVsmBased()) refreshElk()  // ELK uses sVsm on these firmware generations.
            }
        }
        refreshElk()  // SWI133 — sVsm133 independent of Katman4
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Page 0 — Binding, visibility, listeners  (code existant)
    // ═════════════════════════════════════════════════════════════════════════

    private fun bindMainPage(view: View) {
        bindMainViews(view)
        applyFirmwareVisibility(view)
        setupMainListeners()
        // Immediate refresh (the page has just been created)
        refreshDriveRegen()
        refreshClimate()
        EVHardware.whenKatman4Ready {
            if (isAdded) {
                refreshAdas()
                if (FirmwareInfo.isVsmBased()) refreshElk()
            }
        }
    }

    private fun bindMainViews(view: View) {
        // Drive
        driveModeButtons[DriveMode.ECO]    = view.findViewById(R.id.btn_eco)
        driveModeButtons[DriveMode.NORMAL] = view.findViewById(R.id.btn_normal)
        driveModeButtons[DriveMode.SPORT]  = view.findViewById(R.id.btn_sport)
        driveModeButtons[DriveMode.SNOW]   = view.findViewById(R.id.btn_snow)
        driveModeButtons[DriveMode.CUSTOM] = view.findViewById(R.id.btn_custom)

        // Regen
        regenButtons[RegenLevel.OFF]       = view.findViewById(R.id.btn_regen_off)
        regenButtons[RegenLevel.LOW]       = view.findViewById(R.id.btn_regen_low)
        regenButtons[RegenLevel.MEDIUM]    = view.findViewById(R.id.btn_regen_medium)
        regenButtons[RegenLevel.HIGH]      = view.findViewById(R.id.btn_regen_high)
        regenButtons[RegenLevel.ADAPTIVE]  = view.findViewById(R.id.btn_regen_adaptive)
        regenButtons[RegenLevel.ONE_PEDAL] = view.findViewById(R.id.btn_regen_one_pedal)

        // ADAS SWI133
        btnAdasOff      = view.findViewById(R.id.btn_adas_off)
        btnAdasLimiteur = view.findViewById(R.id.btn_adas_limiteur)
        btnAdasAuto     = view.findViewById(R.id.btn_adas_auto)
        btnAdasAcc      = view.findViewById(R.id.btn_adas_acc)
        btnAdasIca      = view.findViewById(R.id.btn_adas_ica)

        // ADAS SWI68
        btnSwi68Off  = view.findViewById(R.id.btn_swi68_off)
        btnSwi68Lim  = view.findViewById(R.id.btn_swi68_lim)
        btnSwi68Auto = view.findViewById(R.id.btn_swi68_auto)
        btnSwi68Acc  = view.findViewById(R.id.btn_swi68_acc)
        btnSwi68Tja  = view.findViewById(R.id.btn_swi68_tja)

        // Climat
        switchSteering   = view.findViewById(R.id.switch_steering_heat)
        seatLeftButtons  = listOf(
            R.id.btn_seat_left_0, R.id.btn_seat_left_1,
            R.id.btn_seat_left_2, R.id.btn_seat_left_3
        ).map { view.findViewById(it) }
        seatRightButtons = listOf(
            R.id.btn_seat_right_0, R.id.btn_seat_right_1,
            R.id.btn_seat_right_2, R.id.btn_seat_right_3
        ).map { view.findViewById(it) }

        // Alertes
        switchOverspeed    = view.findViewById(R.id.switch_overspeed)
        switchSpeedTone    = view.findViewById(R.id.switch_speed_tone)
        switchSoundWarning = view.findViewById(R.id.switch_sound_warning)
        alertsGroupSwi133  = view.findViewById(R.id.alerts_group_swi133)

        // TSR + Energy saving
        switchTsr       = view.findViewById(R.id.switch_tsr)
        btnEnergySaving = view.findViewById(R.id.btn_energy_saving)

        // AEB moved to page 1 for all firmwares — no binding here
    }

    private fun applyFirmwareVisibility(view: View) {
        val gen        = FirmwareInfo.getGeneration()
        val isVsmBased = FirmwareInfo.isVsmBased()
        val isSWI132   = gen == FirmwareInfo.Gen.SWI132
        val isKnown    = gen != FirmwareInfo.Gen.UNKNOWN
        val hasClimate = FirmwareInfo.hasHeatFeatures()

        // SWI132 uses the 4 Off/Lim/ACC/ICA buttons (same group as SWI133), not Off/ACC/TJA
        view.findViewById<View>(R.id.adas_group_swi133).visibility   = if (!isVsmBased || isSWI132) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.adas_group_swi68).visibility    = if (isVsmBased && !isSWI132) View.VISIBLE else View.GONE
        // SWI132 uses two separate alerts (overspeed + tone) like SWI133, not soundWarning
        view.findViewById<View>(R.id.alerts_group_swi133).visibility = if (!isVsmBased || isSWI132) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.alerts_group_swi68).visibility  = if (isVsmBased && !isSWI132) View.VISIBLE else View.GONE
        // AEB moved to page 1 for all firmwares
        view.findViewById<View>(R.id.aeb_group).visibility           = View.GONE
        view.findViewById<View>(R.id.climate_card).visibility        = if (hasClimate) View.VISIBLE else View.GONE
        // TSR + Power saving — all known firmware
        view.findViewById<View>(R.id.section_tsr).visibility    = if (isKnown) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.btn_energy_saving).visibility = if (isKnown) View.VISIBLE else View.GONE
    }

    private fun setupMainListeners() {
        val gen        = FirmwareInfo.getGeneration()
        val isVsmBased = FirmwareInfo.isVsmBased()
        val isSWI132   = gen == FirmwareInfo.Gen.SWI132
        val isKnown    = gen != FirmwareInfo.Gen.UNKNOWN
        val hasClimate = FirmwareInfo.hasHeatFeatures()

        // Drive mode
        driveModeButtons.forEach { (mode, btn) ->
            btn.setOnClickListener {
                applyDriveModeUI(mode)
                CoroutineScope(Dispatchers.IO).launch { EVHardware.setDriveMode(mode) }
            }
        }

        // Regen
        regenButtons.forEach { (level, btn) ->
            btn.setOnClickListener {
                applyRegenUI(level)
                CoroutineScope(Dispatchers.IO).launch { EVHardware.setRegenLevel(level) }
            }
        }

        // ADAS
        // SWI133/UNKNOWN : setMixedIntelligentDrive (VPM) — indices 0/1/3/4 → Off/Lim/ACC/ICA
        // SWI132: setAccTjaMode (VSM) — values 0x4/0x3/0x1/0x2 → Off/Limiter/ACC/ICA
        // SWI68/SWI69/SWI131/SWI165: setAccTjaMode (VSM) — values 0x4/0x1/0x2 → Off/ACC/TJA
        if (!isVsmBased || isSWI132) {
            swi133AdasMap.forEach { (modeIndex, btn) ->
                btn?.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        if (isSWI132) {
                            // SWI132: ACC/TJA mode (setAccTjaMode) and speed limiter (setSasMode)
                            // are two independent settings; the single selector imposes exclusivity.
                            applyVsmAdasMode(modeIndex)
                        } else {
                            EVHardware.setMixedIntelligentDrive(modeIndex)
                        }
                        withContext(Dispatchers.Main) { if (isAdded) applySwi133AdasUI(modeIndex) }
                    }
                }
            }
        } else {
            // SWI68/SWI69/SWI131/SWI165: 5 mode selector (index 0-4), same logic as SWI132
            // (ACC/TJA mode + independent speed limiter, exclusivity via the single selector).
            swi68AdasMap.forEach { (modeIndex, btn) ->
                btn?.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        applyVsmAdasMode(modeIndex)
                        withContext(Dispatchers.Main) { if (isAdded) applySwi68AdasUI(modeIndex) }
                    }
                }
            }
        }

        // Climat
        if (hasClimate) {
            switchSteering?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    CoroutineScope(Dispatchers.IO).launch { EVHardware.setSteeringHeat(checked) }
            }
            seatLeftButtons?.let { setupSeatButtons(it) { level ->
                CoroutineScope(Dispatchers.IO).launch { EVHardware.setSeatHeatLeft(level) }
            } }
            seatRightButtons?.let { setupSeatButtons(it) { level ->
                CoroutineScope(Dispatchers.IO).launch { EVHardware.setSeatHeatRight(level) }
            } }
        }

        // SWI133 + SWI132 alerts (two independent toggles: overspeed + limit tone)
        if (!isVsmBased || isSWI132) {
            switchOverspeed?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    CoroutineScope(Dispatchers.IO).launch { EVHardware.setOverspeedAlarm(checked) }
            }
            switchSpeedTone?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    CoroutineScope(Dispatchers.IO).launch { EVHardware.setSpeedLimitTone(checked) }
            }
        }

        // Alerte sonore SWI68/SWI69/SWI131/SWI165 (un seul toggle)
        if (isVsmBased && !isSWI132) {
            switchSoundWarning?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    EVHardware.whenKatman4Ready { EVHardware.setSoundWarning(checked) }
            }
        }

        // TSR — tous firmwares connus
        if (isKnown) {
            switchTsr?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing) {
                    val gen = FirmwareInfo.getGeneration()
                    val hasTwoAlerts = gen == FirmwareInfo.Gen.SWI133 || gen == FirmwareInfo.Gen.SWI132
                    // Immediate UI update — no need to wait for hardware:
                    // TSR OFF → alerts forced to OFF and grayed out section (not editable)
                    // TSR ON → section re-activated; the actual values ​​are read after the SET
                    if (hasTwoAlerts) {
                        if (!checked) {
                            isRefreshing = true
                            switchOverspeed?.isChecked = false
                            switchSpeedTone?.isChecked = false
                            isRefreshing = false
                        }
                        setAlertsSwi133Enabled(checked)
                    }
                    EVHardware.whenKatman4Ready {
                        CoroutineScope(Dispatchers.IO).launch {
                            EVHardware.setTsrMode(checked)
                            if (checked && hasTwoAlerts) {
                                when (gen) {
                                    FirmwareInfo.Gen.SWI133 -> {
                                        // The SWI133 firmware resets overspeed/speedTone to ON as soon as
                                        // TSR is activated. setTsrMode() then restores them via VPM,
                                        // but VPM writes have a propagation latency that can
                                        // exceed 500ms: reading the hardware here would still return ON.
                                        // → We directly use the values ​​saved in prefs,
                                        //   which are exactly what setTsrMode() just restored.
                                        val (overspeed, speedTone) = EVHardware.savedTsrAlerts()
                                        withContext(Dispatchers.Main) {
                                            if (!isAdded) return@withContext
                                            isRefreshing = true
                                            switchOverspeed?.isChecked = overspeed
                                            switchSpeedTone?.isChecked = speedTone
                                            isRefreshing = false
                                        }
                                    }
                                    FirmwareInfo.Gen.SWI132 -> {
                                        // SWI132: enabling TSR resets overspeed/speedTone to ON
                                        // in the car. Force toggles ON in the UI directly.
                                        withContext(Dispatchers.Main) {
                                            if (!isAdded) return@withContext
                                            isRefreshing = true
                                            switchOverspeed?.isChecked = true
                                            switchSpeedTone?.isChecked = true
                                            isRefreshing = false
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        }

        // Energy saving — all known firmware
        if (isKnown) {
            btnEnergySaving?.setOnClickListener {
                energySavingOn = !energySavingOn
                applyEnergySavingUI(energySavingOn)
                EVHardware.whenKatman4Ready {
                    CoroutineScope(Dispatchers.IO).launch { EVHardware.setEnergySavingMode(energySavingOn) }
                }
            }
        }

        // AEB listeners are on page 1 through setupAebPage2Listeners(); nothing to bind here.
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Page 1 — ELK : Binding + Listeners
    // ═════════════════════════════════════════════════════════════════════════

    private fun bindElkPage(view: View) {
        val isSWI132elk = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132
        // ELK — main switch: different IDs depending on the firmware
        if (isSWI132elk) {
            // SWI132: multi-line layout in elk_activation_swi132.
            view.findViewById<View>(R.id.elk_activation_simple).visibility = View.GONE
            view.findViewById<View>(R.id.elk_activation_swi132).visibility = View.VISIBLE
            switchElk         = view.findViewById(R.id.switch_elk_s132)
            switchElkSound    = view.findViewById(R.id.switch_elk_sound)
            switchElkVibration= view.findViewById(R.id.switch_elk_vibration)
            // SWI132: no Emergency mode — hidden
            btnElkEmergency   = view.findViewById(R.id.btn_elk_emergency)
            btnElkEmergency?.visibility = View.GONE
            // Fault SWI132: Alert (mode 2), not Emergency
            lastActiveElkMode = ElkMode.ALERT
        } else {
            switchElk         = view.findViewById(R.id.switch_elk)
            btnElkEmergency   = view.findViewById(R.id.btn_elk_emergency)
        }
        btnElkAlert       = view.findViewById(R.id.btn_elk_alert)
        btnElkAssist      = view.findViewById(R.id.btn_elk_assist)
        btnElkSenLow      = view.findViewById(R.id.btn_elk_sen_low)
        btnElkSenStandard = view.findViewById(R.id.btn_elk_sen_standard)
        btnElkSenHigh     = view.findViewById(R.id.btn_elk_sen_high)

        // AEB — page 1 for all known firmware
        val aebCard = view.findViewById<View>(R.id.aeb_card_page2)
        if (FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN) {
            aebCard.visibility    = View.VISIBLE
            switchAeb             = view.findViewById(R.id.switch_aeb_p2)
            btnAebAlarm           = view.findViewById(R.id.btn_aeb_alarm_p2)
            btnAebAlarmBrake      = view.findViewById(R.id.btn_aeb_alarm_brake_p2)
            btnAebSenLow          = view.findViewById(R.id.btn_aeb_sen_low)
            btnAebSenStandard     = view.findViewById(R.id.btn_aeb_sen_standard)
            btnAebSenHigh         = view.findViewById(R.id.btn_aeb_sen_high)
            setupAebPage2Listeners()
            EVHardware.whenKatman4Ready { if (isAdded) refreshAebPage2() }
        }

        setupElkListeners()
        refreshElk()
    }

    private fun setupElkListeners() {
        val isSWI132elk = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132
        // Toggle ON/OFF
        switchElk?.setOnCheckedChangeListener { _, checked ->
            if (!isRefreshing) {
                val mode = if (checked) lastActiveElkMode else ElkMode.OFF
                // SWI132: grays the 2 additional switches if disabled
                if (isSWI132elk) applyElkSoundVibEnabled(checked)
                CoroutineScope(Dispatchers.IO).launch {
                    EVHardware.setElkMode(mode)
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            applyElkModeUI(mode)
                            applyElkButtonsEnabled(checked)
                        }
                    }
                }
            }
        }

        // SWI132 — Alerte sonore
        if (isSWI132elk) {
            switchElkSound?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    CoroutineScope(Dispatchers.IO).launch { EVHardware.setLasWarningSound(checked) }
            }
            switchElkVibration?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    CoroutineScope(Dispatchers.IO).launch { EVHardware.setLasWarningVibration(checked) }
            }
        }

        // Mode buttons
        elkModeMap.forEach { (mode, btn) ->
            btn?.setOnClickListener {
                lastActiveElkMode = mode
                CoroutineScope(Dispatchers.IO).launch {
                    EVHardware.setElkMode(mode)
                    withContext(Dispatchers.Main) { if (isAdded) applyElkModeUI(mode) }
                }
            }
        }

        // Sensitivity buttons
        elkSenMap.forEach { (level, btn) ->
            btn?.setOnClickListener {
                CoroutineScope(Dispatchers.IO).launch {
                    EVHardware.setElkSensitivity(level)
                    withContext(Dispatchers.Main) { if (isAdded) applyElkSensitivityUI(level) }
                }
            }
        }
    }

    private fun setupAebPage2Listeners() {
        // Toggle ON/OFF
        switchAeb?.setOnCheckedChangeListener { _, checked ->
            if (!isRefreshing) {
                CoroutineScope(Dispatchers.IO).launch {
                    EVHardware.setAebEnabled(checked)
                    withContext(Dispatchers.Main) { if (isAdded) applyAebModeButtonsEnabled(checked) }
                }
            }
        }
        // Mode
        btnAebAlarm?.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                EVHardware.setAebMode(AebMode.ALARM)
                withContext(Dispatchers.Main) { if (isAdded) applyAebModeUI(AebMode.ALARM) }
            }
        }
        btnAebAlarmBrake?.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                EVHardware.setAebMode(AebMode.ALARM_BRAKE)
                withContext(Dispatchers.Main) { if (isAdded) applyAebModeUI(AebMode.ALARM_BRAKE) }
            }
        }
        // Sensitivity
        aebSenMap.forEach { (level, btn) ->
            btn?.setOnClickListener {
                CoroutineScope(Dispatchers.IO).launch {
                    EVHardware.setAebSensitivity(level)
                    withContext(Dispatchers.Main) { if (isAdded) applyAebSensitivityUI(level) }
                }
            }
        }
    }

    private fun refreshAebPage2() {
        if (switchAeb == null) return
        CoroutineScope(Dispatchers.IO).launch {
            val aebOn  = EVHardware.isAebEnabled()
            val aebMode = EVHardware.getAebMode()
            val aebSen  = EVHardware.getAebSensitivity()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                isRefreshing = true
                switchAeb?.isChecked = aebOn
                isRefreshing = false
                applyAebModeButtonsEnabled(aebOn)
                if (aebMode > 0) applyAebModeUI(aebMode)
                if (aebSen > 0) applyAebSensitivityUI(aebSen)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers UI
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * SWI132: applies the ADAS selector index distinguishing between ACC/TJA mode (setAccTjaMode)
     * speed limiter (setSasMode), two independent settings on the car. The selector
     * unique imposes exclusivity: choosing one mode deactivates the other subsystem.
     *   0=Off, 1=Lim.Manuel(SAS 2), 2=Lim.Auto/Intelligent(SAS 3), 3=ACC, 4=ICA
     */
    private fun applyVsmAdasMode(index: Int) {
        when (index) {
            1 -> { EVHardware.setSpeedLimiterMode(EVHardware.SasMode.MANUEL);      EVHardware.setAccTjaMode(Swi68Mode.OFF) }
            2 -> { EVHardware.setSpeedLimiterMode(EVHardware.SasMode.INTELLIGENT); EVHardware.setAccTjaMode(Swi68Mode.OFF) }
            3 -> { EVHardware.setAccTjaMode(Swi68Mode.ACC); EVHardware.setSpeedLimiterMode(EVHardware.SasMode.OFF) }
            4 -> { EVHardware.setAccTjaMode(Swi68Mode.TJA); EVHardware.setSpeedLimiterMode(EVHardware.SasMode.OFF) }
            else -> { EVHardware.setAccTjaMode(Swi68Mode.OFF); EVHardware.setSpeedLimiterMode(EVHardware.SasMode.OFF) }
        }
    }

    /**
     * SWI132: converts read status (ACC/TJA mode + SAS limiter) to button index (0-4).
     * Manuel→1 (Lim.Manuel), Intelligent→2 (Lim.Auto), ACC→3, TJA→4, sinon Off→0.
     */
    private fun vsmStateToIndex(accTja: Int, sas: Int): Int = when {
        sas == EVHardware.SasMode.MANUEL      -> 1
        sas == EVHardware.SasMode.INTELLIGENT -> 2
        accTja == Swi68Mode.ACC                -> 3
        accTja == Swi68Mode.TJA                -> 4
        else                                   -> 0
    }

    /**
     * SWI133 / SWI132: activates or grays out the 2 sound alerts section.
     * When the TSR (PANEL RECOGNITION) is OFF, the alerts are deactivated and cannot be modified.
     * Alpha is applied to the entire container (labels + switches) for consistent rendering.
     */
    private fun setAlertsSwi133Enabled(enabled: Boolean) {
        alertsGroupSwi133?.alpha    = if (enabled) 1f else 0.4f
        switchOverspeed?.isEnabled  = enabled
        switchSpeedTone?.isEnabled  = enabled
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Refresh from hardware
    // ═════════════════════════════════════════════════════════════════════════

    private fun refreshDriveRegen() {
        if (driveModeButtons.isEmpty()) return  // page not yet created
        CoroutineScope(Dispatchers.IO).launch {
            val mode  = EVHardware.getDriveMode()
            val regen = EVHardware.getRegenLevel()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                mode?.let  { applyDriveModeUI(it) }
                regen?.let { applyRegenUI(it) }
                if (mode == null && regen == null)
                    view?.postDelayed({ if (isAdded) refreshDriveRegen() }, 3_000)
            }
        }
    }

    private fun refreshClimate() {
        if (!FirmwareInfo.hasHeatFeatures() || switchSteering == null) return
        CoroutineScope(Dispatchers.IO).launch {
            val steeringOn = EVHardware.isSteeringHeatOn()
            val leftLevel  = EVHardware.getSeatHeatLeft()
            val rightLevel = EVHardware.getSeatHeatRight()
            val ready = EVHardware.getIntPropertyHvac(EVHardware.PROP_SEAT_HEAT_L, 0x75) >= 0
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (ready) {
                    isRefreshing = true
                    switchSteering?.isChecked = steeringOn
                    isRefreshing = false
                    seatLeftButtons?.let  { applySeatUI(it, leftLevel) }
                    seatRightButtons?.let { applySeatUI(it, rightLevel) }
                } else {
                    view?.postDelayed({ if (isAdded) refreshClimate() }, 3_000)
                }
            }
        }
    }

    private fun refreshAdas() {
        // Verifies that page 0 ADAS buttons are created (AEB is on page 1)
        // SWI132 uses swi133 buttons (Off/Lim/ACC/ICA), not swi68 buttons
        val isSWI132forGuard = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132
        if (!FirmwareInfo.isVsmBased() || isSWI132forGuard) { if (btnAdasOff == null) return }
        else { if (btnSwi68Off == null) return }
        CoroutineScope(Dispatchers.IO).launch {
            when {
                FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 -> refreshSwi132Adas()
                FirmwareInfo.isVsmBased()                               -> refreshSwi68Adas()
                else                                                    -> refreshSwi133Adas()
            }
        }
    }

    private suspend fun refreshSwi133Adas() {
        val adasMode     = EVHardware.getMixedIntelligentDrive()
        val overspeed    = EVHardware.isOverspeedAlarmOn()
        val speedTone    = EVHardware.isSpeedLimitToneOn()
        val aebOn        = EVHardware.isAebEnabled()
        val aebMode      = EVHardware.getAebMode()
        val tsrOn        = EVHardware.isTsrOn()
        val energySaving = EVHardware.isEnergySavingOn()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (adasMode < 0) {
                view?.postDelayed({ if (isAdded) refreshAdas() }, 2_000)
                return@withContext
            }
            isRefreshing = true
            switchOverspeed?.isChecked    = overspeed
            switchSpeedTone?.isChecked    = speedTone
            switchAeb?.isChecked          = aebOn
            switchTsr?.isChecked          = tsrOn
            applyEnergySavingUI(energySaving)
            isRefreshing = false
            setAlertsSwi133Enabled(tsrOn)   // grays alerts if TSR is OFF
            applySwi133AdasUI(adasMode)
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    /**
     * SWI132 : mode ACC/TJA (CarVehicleSettingClient) + alertes binder direct
     * (overspeed TX 0x129, speedTone TX 0x12b) + direct TSR binder + AEB + economy.
     */
    private suspend fun refreshSwi132Adas() {
        val mode         = EVHardware.getAccTjaMode()
        val sas          = EVHardware.getSpeedLimiterMode()   // limiteur : 0=Off, 2=Manuel, 3=Intelligent
        val overspeed    = EVHardware.isOverspeedAlarmOn()
        val speedTone    = EVHardware.isSpeedLimitToneOn()
        val aebOn        = EVHardware.isAebEnabled()
        val aebMode      = EVHardware.getAebMode()
        val tsrOn        = EVHardware.isTsrOn()
        val energySaving = EVHardware.isEnergySavingOn()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (mode < 0) {
                view?.postDelayed({ if (isAdded) refreshAdas() }, 2_000)
                return@withContext
            }
            isRefreshing = true
            switchOverspeed?.isChecked = overspeed
            switchSpeedTone?.isChecked = speedTone
            switchAeb?.isChecked       = aebOn
            switchTsr?.isChecked       = tsrOn
            applyEnergySavingUI(energySaving)
            isRefreshing = false
            setAlertsSwi133Enabled(tsrOn)   // grays alerts if TSR is OFF
            applySwi133AdasUI(vsmStateToIndex(mode, sas))  // SWI132 : Off/Limiteur/ACC/ICA
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    private suspend fun refreshSwi68Adas() {
        val mode         = EVHardware.getAccTjaMode()
        val sas          = EVHardware.getSpeedLimiterMode()   // limiteur : 0=Off, 2=Manuel, 3=Intelligent
        val sound        = EVHardware.isSoundWarningOn()
        val aebOn        = EVHardware.isAebEnabled()
        val aebMode      = EVHardware.getAebMode()
        val tsrOn        = EVHardware.isTsrOn()
        val energySaving = EVHardware.isEnergySavingOn()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (mode < 0) {
                view?.postDelayed({ if (isAdded) refreshAdas() }, 2_000)
                return@withContext
            }
            isRefreshing = true
            switchSoundWarning?.isChecked = sound
            switchAeb?.isChecked          = aebOn
            switchTsr?.isChecked          = tsrOn
            applyEnergySavingUI(energySaving)
            isRefreshing = false
            applySwi68AdasUI(vsmStateToIndex(mode, sas))
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    private fun refreshElk() {
        if (switchElk == null) return  // page not yet created
        val isSWI132elk = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132
        CoroutineScope(Dispatchers.IO).launch {
            val mode  = EVHardware.getElkMode()
            val sen   = EVHardware.getElkSensitivity()
            val sound = if (isSWI132elk) EVHardware.getLasWarningSound() else -1
            val vibr  = if (isSWI132elk) EVHardware.getLasWarningVibration() else -1
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val enabled = mode > 0 && mode != ElkMode.OFF
                if (enabled) lastActiveElkMode = mode
                isRefreshing = true
                switchElk?.isChecked = enabled
                if (isSWI132elk) {
                    if (sound >= 0) switchElkSound?.isChecked = sound == 1
                    if (vibr  >= 0) switchElkVibration?.isChecked = vibr == 1
                }
                isRefreshing = false
                applyElkModeUI(mode)
                applyElkButtonsEnabled(enabled)
                applyElkSoundVibEnabled(enabled)
                if (sen > 0) applyElkSensitivityUI(sen)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers UI — Page 0
    // ═════════════════════════════════════════════════════════════════════════

    private fun applyEnergySavingUI(active: Boolean) {
        energySavingOn = active
        btnEnergySaving?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
        btnEnergySaving?.isSelected = active
        btnEnergySaving?.setTextColor(if (active) colorTextActive else colorTextInactive)
        // Regen: unavailable if Eco active OR if SNOW selected
        setRegenEnabled(!active && currentDriveMode != DriveMode.SNOW)
    }

    private fun applyDriveModeUI(mode: DriveMode) {
        // The value change is announced once, not on each refresh
        // periodic: otherwise TalkBack says nothing at all when the mode changes.
        if (mode != currentDriveMode) {
            announceValue(R.string.card_drive, getString(mode.labelRes))
        }
        currentDriveMode = mode
        driveModeButtons.forEach { (m, btn) ->
            val (bg, text) = when {
                m != mode            -> colorInactive to colorTextInactive
                m == DriveMode.ECO   -> colorEcoBg   to colorEcoText
                m == DriveMode.SPORT -> colorWarnBg  to colorWarnText
                else                 -> colorActive   to colorTextActive
            }
            btn.backgroundTintList = ColorStateList.valueOf(bg)
            btn.setTextColor(text)
            btn.isSelected = m == mode
        }
        // Regen: unavailable if SNOW OR if Eco energy active
        setRegenEnabled(mode != DriveMode.SNOW && !energySavingOn)
        // Eco energy button: not available in SNOW mode (exclusive modes)
        val isSnow = mode == DriveMode.SNOW
        btnEnergySaving?.isEnabled = !isSnow
        btnEnergySaving?.alpha = if (isSnow) 0.35f else 1f
    }

    private fun applyRegenUI(level: RegenLevel) {
        if (level != currentRegenLevel) {
            announceValue(R.string.drive_section_regen, getString(level.labelRes))
        }
        currentRegenLevel = level
        regenButtons.forEach { (l, btn) ->
            val active = l == level
            btn.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn.isSelected = active
            btn.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    /** Announces “<setting>: <value>” to TalkBack. The color of the button is not enough. */
    private fun announceValue(labelRes: Int, value: String) {
        view?.announceForAccessibility("${getString(labelRes)} : $value")
    }

    private fun setRegenEnabled(enabled: Boolean) {
        val isSnow = currentDriveMode == DriveMode.SNOW
        regenButtons.forEach { (level, btn) ->
            // ONE_PEDAL remains accessible even when Eco energy is active,
            // except in SNOW mode where all regen levels are unavailable.
            val btnEnabled = enabled || (level == RegenLevel.ONE_PEDAL && !isSnow)
            btn.isEnabled = btnEnabled
            btn.alpha = if (btnEnabled) 1f else 0.35f
        }
    }

    private fun applySwi133AdasUI(activeMode: Int) {
        swi133AdasMap.forEach { (modeIndex, btn) ->
            val active = modeIndex == activeMode
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.isSelected = active
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun applySwi68AdasUI(activeMode: Int) {
        swi68AdasMap.forEach { (modeValue, btn) ->
            val active = modeValue == activeMode
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.isSelected = active
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun setupSeatButtons(buttons: List<Button>, onLevel: (Int) -> Unit) {
        buttons.forEachIndexed { index, btn ->
            btn.setOnClickListener {
                applySeatUI(buttons, index)
                onLevel(index)
            }
        }
    }

    private fun applyAebModeUI(activeMode: Int) {
        btnAebAlarm?.backgroundTintList      = ColorStateList.valueOf(if (activeMode == AebMode.ALARM)       colorActive else colorInactive)
        btnAebAlarm?.isSelected              = activeMode == AebMode.ALARM
        btnAebAlarm?.setTextColor(                                    if (activeMode == AebMode.ALARM)       colorTextActive else colorTextInactive)
        btnAebAlarmBrake?.backgroundTintList = ColorStateList.valueOf(if (activeMode == AebMode.ALARM_BRAKE) colorActive else colorInactive)
        btnAebAlarmBrake?.isSelected         = activeMode == AebMode.ALARM_BRAKE
        btnAebAlarmBrake?.setTextColor(                               if (activeMode == AebMode.ALARM_BRAKE) colorTextActive else colorTextInactive)
    }

    private fun applyAebModeButtonsEnabled(enabled: Boolean) {
        btnAebAlarm?.isEnabled      = enabled
        btnAebAlarmBrake?.isEnabled = enabled
        btnAebAlarm?.alpha          = if (enabled) 1f else 0.35f
        btnAebAlarmBrake?.alpha     = if (enabled) 1f else 0.35f
    }

    private fun applySeatUI(buttons: List<Button>, activeIndex: Int) {
        buttons.forEachIndexed { i, btn ->
            val active = i == activeIndex
            btn.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn.isSelected = active
            btn.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers UI — Page 1 (ELK)
    // ═════════════════════════════════════════════════════════════════════════

    private fun applyElkModeUI(activeMode: Int) {
        elkModeMap.forEach { (mode, btn) ->
            val active = mode == activeMode
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.isSelected = active
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun applyElkSensitivityUI(activeLevel: Int) {
        elkSenMap.forEach { (level, btn) ->
            val active = level == activeLevel
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.isSelected = active
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun applyAebSensitivityUI(activeLevel: Int) {
        aebSenMap.forEach { (level, btn) ->
            val active = level == activeLevel
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.isSelected = active
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun applyElkButtonsEnabled(enabled: Boolean) {
        (elkModeMap.values + elkSenMap.values).forEach { btn ->
            btn?.isEnabled = enabled
            btn?.alpha = if (enabled) 1f else 0.35f
        }
    }

    private fun applyElkSoundVibEnabled(enabled: Boolean) {
        switchElkSound?.isEnabled = enabled
        switchElkSound?.alpha = if (enabled) 1f else 0.35f
        switchElkVibration?.isEnabled = enabled
        switchElkVibration?.alpha = if (enabled) 1f else 0.35f
    }

    companion object {
        const val PAGE_CONTROLS = 0
        const val PAGE_ELK = 1

        private const val ARG_PAGE = "page"

        fun newInstance(page: Int) = DashboardFragment().apply {
            arguments = Bundle().apply { putInt(ARG_PAGE, page) }
        }
    }
}
