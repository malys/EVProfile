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
import com.evsuite.hardware.EVHardware.Swi68Mode
import com.evsuite.hardware.FirmwareInfo
import kotlinx.coroutines.*

class AdasFragment : Fragment() {

    // SWI133 views
    private var switchOverspeed: Switch? = null
    private var switchSpeedTone: Switch? = null
    private var btnAdasOff: Button? = null
    private var btnAdasLimiteur: Button? = null
    private var btnAdasAuto: Button? = null
    private var btnAdasAcc: Button? = null
    private var btnAdasIca: Button? = null

    // SWI68/69/131/165 views — 5 boutons (index 0-4) : Off / Lim.Manuel / Lim.Auto / ACC / TJA
    private var switchSoundWarning: Switch? = null
    private var btnSwi68Off: Button? = null
    private var btnSwi68Lim: Button? = null
    private var btnSwi68Auto: Button? = null
    private var btnSwi68Acc: Button? = null
    private var btnSwi68Tja: Button? = null

    // AEB views (communes SWI133 + SWI68)
    private var switchAeb: Switch? = null
    private var btnAebAlarm: Button? = null
    private var btnAebAlarmBrake: Button? = null

    private val swi133Buttons get() = listOfNotNull(
        btnAdasOff, btnAdasLimiteur, btnAdasAuto, btnAdasAcc, btnAdasIca
    )

    // index 0-4 → button (Off / Lim.Manuel / Lim.Auto / ACC / TJA)
    private val swi68Buttons: Map<Int, Button?> get() = mapOf(
        0 to btnSwi68Off,
        1 to btnSwi68Lim,
        2 to btnSwi68Auto,
        3 to btnSwi68Acc,
        4 to btnSwi68Tja
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_adas, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // ── References views ──────────────────────── ────────────────────────
        switchOverspeed   = view.findViewById(R.id.switch_overspeed)
        switchSpeedTone   = view.findViewById(R.id.switch_speed_tone)
        btnAdasOff        = view.findViewById(R.id.btn_adas_off)
        btnAdasLimiteur   = view.findViewById(R.id.btn_adas_limiteur)
        btnAdasAuto       = view.findViewById(R.id.btn_adas_auto)
        btnAdasAcc        = view.findViewById(R.id.btn_adas_acc)
        btnAdasIca        = view.findViewById(R.id.btn_adas_ica)
        switchSoundWarning = view.findViewById(R.id.switch_sound_warning)
        btnSwi68Off       = view.findViewById(R.id.btn_swi68_off)
        btnSwi68Lim       = view.findViewById(R.id.btn_swi68_lim)
        btnSwi68Auto      = view.findViewById(R.id.btn_swi68_auto)
        btnSwi68Acc       = view.findViewById(R.id.btn_swi68_acc)
        btnSwi68Tja       = view.findViewById(R.id.btn_swi68_tja)
        switchAeb         = view.findViewById(R.id.switch_aeb)
        btnAebAlarm       = view.findViewById(R.id.btn_aeb_alarm)
        btnAebAlarmBrake  = view.findViewById(R.id.btn_aeb_alarm_brake)

        // ── Show correct section according to firmware ──────────────────────
        val gen        = FirmwareInfo.getGeneration()
        val isKnown    = gen != FirmwareInfo.Gen.UNKNOWN
        val isVsmBased = FirmwareInfo.isVsmBased()
        val isSWI132   = gen == FirmwareInfo.Gen.SWI132
        // SWI133 : 5 boutons ADAS (Off/Limiteur/Auto/ACC/ICA)
        // SWI132: 4 ADAS buttons (Off/Lim/ACC/ICA) — same section as SWI133, Auto button hidden
        // SWI68/SWI69/SWI131/SWI165 : 3 boutons ADAS (Off/ACC/TJA)
        view.findViewById<View>(R.id.section_swi133).visibility =
            if (!isVsmBased || isSWI132) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.section_swi68).visibility =
            if (isVsmBased && !isSWI132) View.VISIBLE else View.GONE
        // Bottom line (AEB + alerts) — available if firmware known
        view.findViewById<View>(R.id.section_bottom_row).visibility =
            if (isKnown) View.VISIBLE else View.GONE
        // Alerts: right column — SWI133 and SWI132 have 2 separate alerts (overspeed + tone)
        //                          — SWI68/SWI69/SWI131/SWI165 have one VSM audible alert.
        view.findViewById<View>(R.id.alerts_swi133).visibility =
            if (gen == FirmwareInfo.Gen.SWI133 || isSWI132) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.alerts_swi68).visibility =
            if (isVsmBased && !isSWI132) View.VISIBLE else View.GONE

        // ── Listeners SWI133 ─────────────────────────────────────────────────
        if (!isVsmBased) {
            switchOverspeed?.setOnCheckedChangeListener { _, checked ->
                if (switchOverspeed?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { EVHardware.setOverspeedAlarm(checked) }
            }
            switchSpeedTone?.setOnCheckedChangeListener { _, checked ->
                if (switchSpeedTone?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { EVHardware.setSpeedLimitTone(checked) }
            }
            swi133Buttons.forEachIndexed { index, btn ->
                btn.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        EVHardware.setMixedIntelligentDrive(index)
                        withContext(Dispatchers.Main) { if (isAdded) applySwi133ModeUI(index) }
                    }
                }
            }
        }

        // ── Listeners SWI132 — alertes + ADAS 5 modes ─────────────────────────
        // Off / Lim.Manuel / Lim.Auto(Intelligent) / ACC / ICA.
        // ACC/TJA mode (setAccTjaMode) and speed limiter (setSasMode) are two
        // independent settings: the single selector imposes exclusivity.
        if (isSWI132) {
            switchOverspeed?.setOnCheckedChangeListener { _, checked ->
                if (switchOverspeed?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { EVHardware.setOverspeedAlarm(checked) }
            }
            switchSpeedTone?.setOnCheckedChangeListener { _, checked ->
                if (switchSpeedTone?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { EVHardware.setSpeedLimitTone(checked) }
            }
            // The Auto button is available on SWI132: it selects the Intelligent limiter.
            btnAdasAuto?.visibility = View.VISIBLE
            swi133Buttons.forEachIndexed { btnIndex, btn ->
                btn.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        applyVsmAdasMode(btnIndex)
                        withContext(Dispatchers.Main) { if (isAdded) applySwi133ModeUI(btnIndex) }
                    }
                }
            }
        }

        // ── Listeners AEB (communs SWI133 + SWI68) ──────────────────────────
        if (isKnown) {
            switchAeb?.setOnCheckedChangeListener { _, checked ->
                if (switchAeb?.isPressed == true) {
                    CoroutineScope(Dispatchers.IO).launch {
                        EVHardware.setAebEnabled(checked)
                        withContext(Dispatchers.Main) { if (isAdded) applyAebModeButtonsEnabled(checked) }
                    }
                }
            }
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
        }

        // ── Listeners SWI68 / SWI69 / SWI131 / SWI165 — 5 mode selector (index 0-4) ─
        // Off / Manual Lim / Auto Lim / ACC / TJA. ACC/TJA mode + independent limiter,
        // exclusivity via the single selector (same logic as SWI132).
        if (isVsmBased && !isSWI132) {
            switchSoundWarning?.setOnCheckedChangeListener { _, checked ->
                if (switchSoundWarning?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { EVHardware.setSoundWarning(checked) }
            }
            swi68Buttons.forEach { (btnIndex, btn) ->
                btn?.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        applyVsmAdasMode(btnIndex)
                        withContext(Dispatchers.Main) { if (isAdded) applySwi68ModeUI(btnIndex) }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        EVHardware.whenKatman4Ready {
            if (isAdded) refreshState()
        }
    }

    /**
     * SWI132: applies the ADAS selector index (0-4) distinguishing between ACC/TJA mode
     * (setAccTjaMode) of the speed limiter (setSasMode). The single selector imposes
     * exclusivity: choosing one mode deactivates the other subsystem.
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

    /** SWI132: read status (ACC/TJA mode + SAS limiter) → button index (0-4). */
    private fun vsmStateToIndex(accTja: Int, sas: Int): Int = when {
        sas == EVHardware.SasMode.MANUEL      -> 1
        sas == EVHardware.SasMode.INTELLIGENT -> 2
        accTja == Swi68Mode.ACC                -> 3
        accTja == Swi68Mode.TJA                -> 4
        else                                   -> 0
    }

    private fun refreshState() {
        CoroutineScope(Dispatchers.IO).launch {
            when {
                FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 -> refreshSwi132()
                FirmwareInfo.isVsmBased()                               -> refreshSwi68()
                else                                                    -> refreshSwi133()
            }
        }
    }

    /**
     * SWI132: refreshes the ADAS status (Off/Lim/ACC/ICA mode via CarVehicleSettingClient),
     * audible alerts (through binder getter transactions 0x129/0x12b), and AEB.
     */
    private suspend fun refreshSwi132() {
        val mode      = EVHardware.getAccTjaMode()
        val sas       = EVHardware.getSpeedLimiterMode()   // limiteur : 0=Off, 2=Manuel, 3=Intelligent
        val overspeed = EVHardware.isOverspeedAlarmOn()
        val speedTone = EVHardware.isSpeedLimitToneOn()
        val aebOn     = EVHardware.isAebEnabled()
        val aebMode   = EVHardware.getAebMode()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (mode < 0) {
                view?.postDelayed({ if (isAdded) refreshState() }, 2_000)
                return@withContext
            }
            // SWI132: ACC/TJA mode + SAS limiter → button index (0-4)
            applySwi133ModeUI(vsmStateToIndex(mode, sas))
            switchOverspeed?.isChecked = overspeed
            switchSpeedTone?.isChecked = speedTone
            switchAeb?.isChecked = aebOn
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    private suspend fun refreshSwi133() {
        val adasMode  = EVHardware.getMixedIntelligentDrive()
        val overspeed = EVHardware.isOverspeedAlarmOn()
        val speedTone = EVHardware.isSpeedLimitToneOn()
        val aebOn     = EVHardware.isAebEnabled()
        val aebMode   = EVHardware.getAebMode()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (adasMode < 0) {
                view?.postDelayed({ if (isAdded) refreshState() }, 2_000)
                return@withContext
            }
            switchOverspeed?.isChecked = overspeed
            switchSpeedTone?.isChecked = speedTone
            applySwi133ModeUI(adasMode)
            switchAeb?.isChecked = aebOn
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    private suspend fun refreshSwi68() {
        val mode    = EVHardware.getAccTjaMode()
        val sas     = EVHardware.getSpeedLimiterMode()   // limiteur : 0=Off, 2=Manuel, 3=Intelligent
        val sound   = EVHardware.isSoundWarningOn()
        val aebOn   = EVHardware.isAebEnabled()
        val aebMode = EVHardware.getAebMode()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (mode < 0) {
                view?.postDelayed({ if (isAdded) refreshState() }, 2_000)
                return@withContext
            }
            switchSoundWarning?.isChecked = sound
            applySwi68ModeUI(vsmStateToIndex(mode, sas))
            switchAeb?.isChecked = aebOn
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    private fun applySwi133ModeUI(activeMode: Int) {
        val accent = requireContext().getColor(R.color.accent_eco)
        val def    = requireContext().getColor(R.color.bg_button)
        swi133Buttons.forEachIndexed { i, btn ->
            btn.backgroundTintList = ColorStateList.valueOf(if (i == activeMode) accent else def)
            btn.isSelected = i == activeMode
        }
    }

    private fun applySwi68ModeUI(activeMode: Int) {
        val accent = requireContext().getColor(R.color.accent_eco)
        val def    = requireContext().getColor(R.color.bg_button)
        swi68Buttons.forEach { (modeValue, btn) ->
            btn?.backgroundTintList = ColorStateList.valueOf(if (modeValue == activeMode) accent else def)
            btn?.isSelected = modeValue == activeMode
        }
    }

    private fun applyAebModeUI(activeMode: Int) {
        val accent = requireContext().getColor(R.color.accent_eco)
        val def    = requireContext().getColor(R.color.bg_button)
        btnAebAlarm?.backgroundTintList      = ColorStateList.valueOf(if (activeMode == AebMode.ALARM) accent else def)
        btnAebAlarm?.isSelected              = activeMode == AebMode.ALARM
        btnAebAlarmBrake?.backgroundTintList = ColorStateList.valueOf(if (activeMode == AebMode.ALARM_BRAKE) accent else def)
        btnAebAlarmBrake?.isSelected         = activeMode == AebMode.ALARM_BRAKE
    }

    private fun applyAebModeButtonsEnabled(enabled: Boolean) {
        btnAebAlarm?.isEnabled      = enabled
        btnAebAlarmBrake?.isEnabled = enabled
        btnAebAlarm?.alpha          = if (enabled) 1f else 0.35f
        btnAebAlarmBrake?.alpha     = if (enabled) 1f else 0.35f
    }
}
