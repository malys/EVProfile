package com.evsuite.profile.ui

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.evsuite.profile.R
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.EVHardware.AebMode
import com.evsuite.hardware.EVHardware.AebSensitivity
import com.evsuite.hardware.EVHardware.ElkMode
import com.evsuite.hardware.EVHardware.ElkSensitivity
import com.evsuite.hardware.EVHardware.Swi68Mode
import com.evsuite.hardware.model.DriveMode
import com.evsuite.hardware.model.DrivingProfile
import com.evsuite.hardware.model.RegenLevel
import android.widget.Toast
import com.evsuite.hardware.AppLogger
import com.evsuite.profile.profile.ProfileApplier
import com.evsuite.profile.profile.ProfileManager
import com.evsuite.hardware.FirmwareInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment() {

    private lateinit var manager: ProfileManager
    private lateinit var adapter: ProfileAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        manager = ProfileManager(requireContext())

        adapter = ProfileAdapter(
            mutableListOf(),
            manager.getDefaultId(),
            onApply = { profile ->
                ProfileApplier.apply(profile) { ok ->
                    requireActivity().runOnUiThread {
                        val msg = if (ok) getString(R.string.profile_applied, profile.name)
                                  else "Profile applied (check the logs)"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSetDefault = { profile ->
                manager.setDefault(profile.id)
                refreshList()
                Toast.makeText(context, "Default profile: ${profile.name}", Toast.LENGTH_SHORT).show()
            },
            onEdit = { profile ->
                showProfileDialog(existing = profile, data = profile)
            },
            onDelete = { profile ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.profile_delete_confirm, profile.name))
                    .setPositiveButton(R.string.profile_delete) { _, _ ->
                        manager.delete(profile.id)
                        refreshList()
                    }
                    .setNegativeButton(R.string.profile_cancel, null)
                    .show()
            }
        )

        view.findViewById<RecyclerView>(R.id.recycler_profiles).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ProfileFragment.adapter
        }

        view.findViewById<View>(R.id.btn_add_profile).setOnClickListener {
            if (manager.getAll().size >= ProfileManager.MAX_PROFILES) {
                Toast.makeText(context, getString(R.string.profile_max_reached, ProfileManager.MAX_PROFILES), Toast.LENGTH_SHORT).show()
            } else {
                openNewProfileDialog()
            }
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        adapter.update(manager.getAll(), manager.getDefaultId())
    }

    // -------------------------------------------------------------------------
    // New profile: reads the current hardware state then opens the pre-filled dialog
    // -------------------------------------------------------------------------

    /**
     * Pre-populates a new profile with the current status of the car.
     *
     * Each setting goes through its `…OrNull` reader: a signal that the firmware does not render
     * is `null`, not “disabled”. The difference matters here more than elsewhere — a profile
     * saved with `aebEnabled=false` because reading failed **will turn off AEB** at
     * each application. In the absence of a “do not touch” value in the model, we keep the
     * default value and we name the unreadable signals in the dialog: it is
     * the user to adjust them knowingly, not for a failed reading to decide.
     */
    private fun openNewProfileDialog() {
        CoroutineScope(Dispatchers.IO).launch {
            val hasHeat  = FirmwareInfo.hasHeatFeatures()
            val isSWI132 = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132
            val unread = mutableListOf<String>()

            /** Remembers the setting name when the car has not responded. */
            fun <T> T?.orNoted(label: String, fallback: T): T {
                if (this == null) unread += label
                return this ?: fallback
            }
            val prefill = if (FirmwareInfo.isVsmBased()) {
                // SWI68/SWI69/SWI131/SWI132/SWI165: ADAS ACC/TJA — seats/steering wheel only on SWI68/SWI165
                val elkMode = EVHardware.getElkMode().let {
                    if (it < 1) (if (isSWI132) ElkMode.ALERT else ElkMode.EMERGENCY) else it
                }
                val elkSen  = EVHardware.getElkSensitivity().let { if (it < 1) ElkSensitivity.STANDARD else it }
                DrivingProfile(
                    name          = "",
                    driveMode     = EVHardware.getDriveMode()  ?: DriveMode.NORMAL,
                    regenLevel    = EVHardware.getRegenLevel() ?: RegenLevel.MEDIUM,
                    steeringHeat  = if (hasHeat) EVHardware.steeringHeatOnOrNull().orNoted("volant chauffant", false) else false,
                    seatHeatLeft  = if (hasHeat) EVHardware.seatHeatLeftOrNull().orNoted("left seat", 0) else 0,
                    seatHeatRight = if (hasHeat) EVHardware.seatHeatRightOrNull().orNoted("right seat", 0) else 0,
                    // SWI132: two independent alerts like SWI133 (no VSM soundWarning)
                    overspeedAlarm = if (isSWI132) EVHardware.overspeedAlarmOnOrNull().orNoted("alerte survitesse", false) else false,
                    speedLimitTone = if (isSWI132) EVHardware.speedLimitToneOnOrNull().orNoted("speed-limit tone", false) else false,
                    soundWarning   = if (!isSWI132) EVHardware.soundWarningOnOrNull().orNoted("alerte sonore ADAS", false) else false,
                    // ACC/TJA mode — SHWA (old limiter coding) set to Off (limiter managed separately)
                    swi68AdasMode  = EVHardware.getAccTjaMode().let {
                        if (it < 0 || it == Swi68Mode.SHWA) Swi68Mode.OFF else it
                    },
                    // Speed ​​limiter — captured for all VSM firmware (SWI68/69/131/132/165)
                    swi132LimiterConfigured = true,
                    swi132SasMode  = EVHardware.getSpeedLimiterMode().let { if (it < 0) 0 else it },
                    aebEnabled     = EVHardware.aebEnabledOrNull().orNoted("AEB", false),
                    aebMode        = EVHardware.aebModeOrNull().orNoted("mode AEB", AebMode.ALARM),
                    aebSensitivity = EVHardware.getAebSensitivity().let { if (it < 1) AebSensitivity.STANDARD else it },
                    elkMode        = elkMode,
                    elkSensitivity = elkSen,
                    lasAudibleWarning    = if (isSWI132) (EVHardware.getLasWarningSound() == 1) else true,
                    lasVibrationReminder = if (isSWI132) (EVHardware.getLasWarningVibration() == 1) else true,
                    energySaving   = EVHardware.energySavingOnOrNull().orNoted("energy-saving mode", false),
                    tsrEnabled     = EVHardware.tsrOnOrNull().orNoted("TSR", false)
                )
            } else {
                // SWI133/UNKNOWN: mixed ADAS, heated seats and steering wheel
                val elkMode = EVHardware.getElkMode().let { if (it < 1) ElkMode.EMERGENCY else it }
                val elkSen  = EVHardware.getElkSensitivity().let { if (it < 1) ElkSensitivity.STANDARD else it }
                val aebSen  = EVHardware.getAebSensitivity().let { if (it < 1) AebSensitivity.STANDARD else it }
                DrivingProfile(
                    name           = "",
                    driveMode      = EVHardware.getDriveMode()  ?: DriveMode.NORMAL,
                    regenLevel     = EVHardware.getRegenLevel() ?: RegenLevel.MEDIUM,
                    steeringHeat   = EVHardware.steeringHeatOnOrNull().orNoted("volant chauffant", false),
                    seatHeatLeft   = EVHardware.seatHeatLeftOrNull().orNoted("left seat", 0),
                    seatHeatRight  = EVHardware.seatHeatRightOrNull().orNoted("right seat", 0),
                    overspeedAlarm = EVHardware.overspeedAlarmOnOrNull().orNoted("alerte survitesse", false),
                    speedLimitTone = EVHardware.speedLimitToneOnOrNull().orNoted("speed-limit tone", false),
                    adasMode       = EVHardware.getMixedIntelligentDrive().coerceAtLeast(0),
                    aebEnabled     = EVHardware.aebEnabledOrNull().orNoted("AEB", false),
                    aebMode        = EVHardware.aebModeOrNull().orNoted("mode AEB", AebMode.ALARM),
                    aebSensitivity = aebSen,
                    elkMode        = elkMode,
                    elkSensitivity = elkSen,
                    energySaving   = if (FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN) EVHardware.energySavingOnOrNull().orNoted("energy-saving mode", false) else false,
                    tsrEnabled     = if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133) EVHardware.tsrOnOrNull().orNoted("TSR", false) else false
                )
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (unread.isNotEmpty()) {
                    AppLogger.w("EVProfile.Profile", "prefill: unreadable — ${unread.joinToString(", ")}")
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.profile_prefill_unread, unread.joinToString(", ")),
                        Toast.LENGTH_LONG
                    ).show()
                }
                showProfileDialog(existing = null, data = prefill)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Edit/creation dialog — dark MaterialButton style
    // -------------------------------------------------------------------------

    private fun showProfileDialog(existing: DrivingProfile?, data: DrivingProfile) {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_profile_edit, null)
        val gen = FirmwareInfo.getGeneration()

        // ── Couleurs ─────────────────────────────────────────────────────────
        fun activateBtn(btn: MaterialButton, active: Boolean) {
            btn.backgroundTintList = ColorStateList.valueOf(
                ctx.getColor(if (active) R.color.dash_accent_dim else R.color.dash_btn))
            btn.setTextColor(ctx.getColor(
                if (active) R.color.dash_accent else R.color.text_secondary))
            btn.strokeColor = ColorStateList.valueOf(
                ctx.getColor(if (active) R.color.dash_accent else R.color.dash_border))
            // TalkBack announces “selected”: the color does not carry the state alone.
            btn.isSelected = active
        }

        /** Links a group of buttons: only one active at a time. Returns a lambda to read the current value. */
        fun <T> bindGroup(pairs: List<Pair<MaterialButton, T>>, initial: T, onSelect: (T) -> Unit) {
            pairs.forEach { (btn, value) -> activateBtn(btn, value == initial) }
            pairs.forEach { (btn, value) ->
                btn.setOnClickListener {
                    pairs.forEach { (b, v) -> activateBtn(b, v == value) }
                    onSelect(value)
                }
            }
        }

        // ── Selection variables ───────────────────── ──────────────────────
        var selectedDrive   = data.driveMode
        var selectedRegen   = data.regenLevel
        var steeringOn      = data.steeringHeat
        var seatLeft        = data.seatHeatLeft
        var seatRight       = data.seatHeatRight
        var adasMode        = data.adasMode
        var swi68Mode       = data.swi68AdasMode
        var swi132SasMode   = data.swi132SasMode                       // 0=Off, 2=Manuel, 3=Intelligent
        var swi132LimiterConfigured = data.swi132LimiterConfigured
        var aebEnabledSel   = data.aebEnabled
        var aebModeSel      = data.aebMode
        var aebSenSel       = data.aebSensitivity.let { if (it == 0) AebSensitivity.STANDARD else it }
        var elkModeSel      = data.elkMode.let { if (it == 0) ElkMode.EMERGENCY else it }
        var elkSenSel       = data.elkSensitivity.let { if (it == 0) ElkSensitivity.STANDARD else it }
        var elkEnabledSel   = elkModeSel != ElkMode.OFF
        /** Last active ELK mode for restoration after toggle ON */
        var lastActiveElkModeD = if (elkModeSel != ElkMode.OFF) elkModeSel else ElkMode.EMERGENCY
        var lasAudibleWarningSel   = data.lasAudibleWarning
        var lasVibrationReminderSel = data.lasVibrationReminder
        var energySavingSel = data.energySaving
        var tsrEnabledSel   = data.tsrEnabled

        // ── Eco energy button — declared early to be accessible in binding drive mode ─
        val btnEnergy = dialogView.findViewById<MaterialButton>(R.id.btn_energy_saving_d)

        // ── Mode de conduite ─────────────────────────────────────────────────
        val drivePairs = listOf(
            dialogView.findViewById<MaterialButton>(R.id.btn_drive_eco_d)    to DriveMode.ECO,
            dialogView.findViewById<MaterialButton>(R.id.btn_drive_normal_d) to DriveMode.NORMAL,
            dialogView.findViewById<MaterialButton>(R.id.btn_drive_sport_d)  to DriveMode.SPORT,
            dialogView.findViewById<MaterialButton>(R.id.btn_drive_snow_d)   to DriveMode.SNOW,
            dialogView.findViewById<MaterialButton>(R.id.btn_drive_custom_d) to DriveMode.CUSTOM
        )
        val regenSection = dialogView.findViewById<View>(R.id.section_regen_dialog)
        val regenBtns = listOf(
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_off_d),
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_low_d),
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_medium_d),
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_high_d),
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_adaptive_d),
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_one_pedal_d)
        )

        val btnOnePedal = dialogView.findViewById<MaterialButton>(R.id.btn_regen_one_pedal_d)

        fun setRegenEnabled(enabled: Boolean) {
            val isSnow = selectedDrive == DriveMode.SNOW
            regenBtns.forEach { btn ->
                // ONE_PEDAL remains accessible even when Eco energy is active,
                // except in SNOW mode where all regen levels are unavailable.
                val btnEnabled = enabled || (btn == btnOnePedal && !isSnow)
                btn.isEnabled = btnEnabled
                btn.alpha = if (btnEnabled) 1f else 0.35f
            }
        }

        bindGroup(drivePairs, selectedDrive) { mode ->
            selectedDrive = mode
            val isSnow = mode == DriveMode.SNOW
            // Regen: unavailable if SNOW or Eco energy active (ONE_PEDAL exempt from Eco)
            setRegenEnabled(!isSnow && !energySavingSel)
            // Eco energy button: not available in SNOW mode (exclusive modes)
            if (gen != FirmwareInfo.Gen.UNKNOWN) {
                btnEnergy.isEnabled = !isSnow
                btnEnergy.alpha = if (isSnow) 0.35f else 1f
            }
        }
        // Initial state: regen unavailable if SNOW or Eco energy already active
        setRegenEnabled(data.driveMode != DriveMode.SNOW && !energySavingSel)

        // ── Regeneration ────────────────────────── ───────────────────────────
        val regenPairs = listOf(
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_off_d)       to RegenLevel.OFF,
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_low_d)       to RegenLevel.LOW,
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_medium_d)    to RegenLevel.MEDIUM,
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_high_d)      to RegenLevel.HIGH,
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_adaptive_d)  to RegenLevel.ADAPTIVE,
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_one_pedal_d) to RegenLevel.ONE_PEDAL
        )
        bindGroup(regenPairs, selectedRegen) { selectedRegen = it }

        // ── Volant chauffant ─────────────────────────────────────────────────
        val steerPairs = listOf(
            dialogView.findViewById<MaterialButton>(R.id.btn_steer_off_d) to false,
            dialogView.findViewById<MaterialButton>(R.id.btn_steer_on_d)  to true
        )
        bindGroup(steerPairs, steeringOn) { steeringOn = it }

        // ── Left seat ────────────────────────── ───────────────────────────
        val seatLeftPairs = listOf(
            dialogView.findViewById<MaterialButton>(R.id.btn_sl_0_d) to 0,
            dialogView.findViewById<MaterialButton>(R.id.btn_sl_1_d) to 1,
            dialogView.findViewById<MaterialButton>(R.id.btn_sl_2_d) to 2,
            dialogView.findViewById<MaterialButton>(R.id.btn_sl_3_d) to 3
        )
        bindGroup(seatLeftPairs, seatLeft) { seatLeft = it }

        // ── Right seat ─────────────────────────── ───────────────────────────
        val seatRightPairs = listOf(
            dialogView.findViewById<MaterialButton>(R.id.btn_sr_0_d) to 0,
            dialogView.findViewById<MaterialButton>(R.id.btn_sr_1_d) to 1,
            dialogView.findViewById<MaterialButton>(R.id.btn_sr_2_d) to 2,
            dialogView.findViewById<MaterialButton>(R.id.btn_sr_3_d) to 3
        )
        bindGroup(seatRightPairs, seatRight) { seatRight = it }

        // ── Climate Sections (Steering Wheel + Seats) — hidden if no heating (SWI69/SWI131) ─
        val hasHeat = FirmwareInfo.hasHeatFeatures()
        dialogView.findViewById<View>(R.id.section_steering_dialog)?.visibility =
            if (hasHeat) View.VISIBLE else View.GONE
        dialogView.findViewById<View>(R.id.section_seats_dialog)?.visibility =
            if (hasHeat) View.VISIBLE else View.GONE

        // ── Section AEB (commune SWI133 + SWI68 + SWI69) ────────────────────
        val sectionAeb = dialogView.findViewById<View>(R.id.adas_section_aeb)
        if (gen != FirmwareInfo.Gen.UNKNOWN) {
            sectionAeb.visibility = View.VISIBLE
            val swAeb         = dialogView.findViewById<Switch>(R.id.sw_aeb_enabled)
            val btnAebAlarmD  = dialogView.findViewById<MaterialButton>(R.id.btn_aeb_alarm_d)
            val btnAebBrakeD  = dialogView.findViewById<MaterialButton>(R.id.btn_aeb_alarm_brake_d)

            // AEB sensitivity — SWI133 only
            val aebSenSectionD = dialogView.findViewById<View>(R.id.aeb_sen_section_d)
            val btnAebSenLowD  = dialogView.findViewById<MaterialButton>(R.id.btn_aeb_sen_low_d)
            val btnAebSenStdD  = dialogView.findViewById<MaterialButton>(R.id.btn_aeb_sen_standard_d)
            val btnAebSenHighD = dialogView.findViewById<MaterialButton>(R.id.btn_aeb_sen_high_d)

            val showSensitivity = gen != FirmwareInfo.Gen.UNKNOWN
            aebSenSectionD.visibility = if (showSensitivity) View.VISIBLE else View.GONE

            fun setAebModeButtonsEnabled(enabled: Boolean) {
                listOf(btnAebAlarmD, btnAebBrakeD).forEach { btn ->
                    btn.isEnabled = enabled
                    btn.alpha     = if (enabled) 1f else 0.35f
                }
                if (showSensitivity) {
                    listOf(btnAebSenLowD, btnAebSenStdD, btnAebSenHighD).forEach { btn ->
                        btn.isEnabled = enabled
                        btn.alpha     = if (enabled) 1f else 0.35f
                    }
                }
            }

            swAeb.isChecked = aebEnabledSel
            setAebModeButtonsEnabled(aebEnabledSel)
            swAeb.setOnCheckedChangeListener { _, checked ->
                aebEnabledSel = checked
                setAebModeButtonsEnabled(checked)
            }

            val aebModePairs = listOf(btnAebAlarmD to AebMode.ALARM, btnAebBrakeD to AebMode.ALARM_BRAKE)
            bindGroup(aebModePairs, aebModeSel) { aebModeSel = it }

            if (showSensitivity) {
                val aebSenPairs = listOf(
                    btnAebSenLowD  to AebSensitivity.LOW,
                    btnAebSenStdD  to AebSensitivity.STANDARD,
                    btnAebSenHighD to AebSensitivity.HIGH
                )
                bindGroup(aebSenPairs, aebSenSel) { aebSenSel = it }
            }
        }

        // ── Section ELK (tous firmwares connus) ─────────────────────────────
        val sectionElk = dialogView.findViewById<View>(R.id.elk_section_dialog)
        if (gen != FirmwareInfo.Gen.UNKNOWN) {
            sectionElk.visibility = View.VISIBLE
            val isSWI132elk = gen == FirmwareInfo.Gen.SWI132

            val swElk           = dialogView.findViewById<Switch>(R.id.sw_elk_enabled)
            val btnElkAlertD    = dialogView.findViewById<MaterialButton>(R.id.btn_elk_alert_d)
            val btnElkAssistD   = dialogView.findViewById<MaterialButton>(R.id.btn_elk_assist_d)
            val btnElkEmergD    = dialogView.findViewById<MaterialButton>(R.id.btn_elk_emergency_d)
            val btnElkSenLowD   = dialogView.findViewById<MaterialButton>(R.id.btn_elk_sen_low_d)
            val btnElkSenStdD   = dialogView.findViewById<MaterialButton>(R.id.btn_elk_sen_standard_d)
            val btnElkSenHighD  = dialogView.findViewById<MaterialButton>(R.id.btn_elk_sen_high_d)

            // SWI132: no Emergency mode + 2 additional switches
            if (isSWI132elk) {
                btnElkEmergD?.visibility = View.GONE
                dialogView.findViewById<View>(R.id.elk_sound_row_d)?.visibility = View.VISIBLE
                dialogView.findViewById<View>(R.id.elk_vibration_row_d)?.visibility = View.VISIBLE
                if (elkModeSel == ElkMode.EMERGENCY) {
                    elkModeSel = ElkMode.ALERT
                    lastActiveElkModeD = ElkMode.ALERT
                    elkEnabledSel = true
                }
            }

            val elkModeBtns = if (isSWI132elk)
                listOf(btnElkAlertD, btnElkAssistD)
            else
                listOf(btnElkAlertD, btnElkAssistD, btnElkEmergD)
            val elkSenBtns  = listOf(btnElkSenLowD, btnElkSenStdD, btnElkSenHighD)

            val swElkSound    = dialogView.findViewById<Switch?>(R.id.sw_elk_sound_d)
            val swElkVibration= dialogView.findViewById<Switch?>(R.id.sw_elk_vibration_d)

            fun setElkButtonsEnabled(enabled: Boolean) {
                (elkModeBtns + elkSenBtns).forEach { btn ->
                    btn?.isEnabled = enabled
                    btn?.alpha     = if (enabled) 1f else 0.35f
                }
                if (isSWI132elk) {
                    swElkSound?.isEnabled = enabled
                    swElkSound?.alpha     = if (enabled) 1f else 0.35f
                    swElkVibration?.isEnabled = enabled
                    swElkVibration?.alpha     = if (enabled) 1f else 0.35f
                }
            }

            swElk.isChecked = elkEnabledSel
            setElkButtonsEnabled(elkEnabledSel)

            if (isSWI132elk) {
                swElkSound?.isChecked    = lasAudibleWarningSel
                swElkVibration?.isChecked= lasVibrationReminderSel
                swElkSound?.setOnCheckedChangeListener { _, checked -> lasAudibleWarningSel = checked }
                swElkVibration?.setOnCheckedChangeListener { _, checked -> lasVibrationReminderSel = checked }
            }

            swElk.setOnCheckedChangeListener { _, checked ->
                elkEnabledSel = checked
                elkModeSel = if (checked) lastActiveElkModeD else ElkMode.OFF
                setElkButtonsEnabled(checked)
            }

            val initialElkMode = if (isSWI132elk && elkModeSel == ElkMode.EMERGENCY) ElkMode.ALERT
                                  else if (elkEnabledSel) elkModeSel else ElkMode.ALERT
            val elkModePairs = if (isSWI132elk)
                listOf(btnElkAlertD to ElkMode.ALERT, btnElkAssistD to ElkMode.ASSIST)
            else
                listOf(btnElkAlertD to ElkMode.ALERT, btnElkAssistD to ElkMode.ASSIST, btnElkEmergD to ElkMode.EMERGENCY)
            bindGroup(elkModePairs, initialElkMode) { mode ->
                elkModeSel = mode
                lastActiveElkModeD = mode
            }

            val elkSenPairs = listOf(
                btnElkSenLowD  to ElkSensitivity.LOW,
                btnElkSenStdD  to ElkSensitivity.STANDARD,
                btnElkSenHighD to ElkSensitivity.HIGH
            )
            bindGroup(elkSenPairs, elkSenSel) { elkSenSel = it }
        }

        // ── Sections ADAS ────────────────────────────────────────────────────
        val sectionSwi133 = dialogView.findViewById<View>(R.id.adas_section_swi133)
        val sectionSwi68  = dialogView.findViewById<View>(R.id.adas_section_swi68)
        val isSWI132Profile = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132

        when {
            isSWI132Profile -> {
                // SWI132: 5 ADAS Off/Lim.Manuel/Lim.Auto/ACC/ICA buttons + separate alerts.
                // ACC/TJA mode (swi68Mode) and speed limiter (swi132SasMode) are
                // two independent settings; the single selector imposes exclusivity.
                sectionSwi133.visibility = View.VISIBLE
                sectionSwi68.visibility  = View.GONE
                dialogView.findViewById<View>(R.id.btn_adas_auto_d)?.visibility = View.VISIBLE
                dialogView.findViewById<Switch>(R.id.sw_overspeed_alarm).isChecked  = data.overspeedAlarm
                dialogView.findViewById<Switch>(R.id.sw_speed_limit_tone).isChecked = data.speedLimitTone
                val initialIdx = when {
                    data.swi132SasMode == EVHardware.SasMode.MANUEL      -> 1
                    data.swi132SasMode == EVHardware.SasMode.INTELLIGENT -> 2
                    data.swi68AdasMode == Swi68Mode.ACC                   -> 3
                    data.swi68AdasMode == Swi68Mode.TJA                   -> 4
                    else                                                  -> 0
                }
                val adasSwi132Pairs = listOf(
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_off_d)  to 0,
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_lim_d)  to 1,
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_auto_d) to 2,
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_acc_d)  to 3,
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_ica_d)  to 4
                )
                bindGroup(adasSwi132Pairs, initialIdx) { idx ->
                    swi132LimiterConfigured = true
                    when (idx) {
                        1 -> { swi68Mode = Swi68Mode.OFF; swi132SasMode = EVHardware.SasMode.MANUEL }
                        2 -> { swi68Mode = Swi68Mode.OFF; swi132SasMode = EVHardware.SasMode.INTELLIGENT }
                        3 -> { swi68Mode = Swi68Mode.ACC; swi132SasMode = EVHardware.SasMode.OFF }
                        4 -> { swi68Mode = Swi68Mode.TJA; swi132SasMode = EVHardware.SasMode.OFF }
                        else -> { swi68Mode = Swi68Mode.OFF; swi132SasMode = EVHardware.SasMode.OFF }
                    }
                }
            }
            FirmwareInfo.isVsmBased() -> {
                // SWI68/SWI69/SWI131/SWI165 : section SWI68 (5 boutons + alerte sonore).
                // Off / Lim.Manuel / Lim.Auto / ACC / TJA — ACC/TJA mode + independent limiter.
                sectionSwi68.visibility  = View.VISIBLE
                sectionSwi133.visibility = View.GONE
                dialogView.findViewById<Switch>(R.id.sw_sound_warning).isChecked = data.soundWarning
                val initialIdx = when {
                    data.swi132SasMode == EVHardware.SasMode.MANUEL      -> 1
                    data.swi132SasMode == EVHardware.SasMode.INTELLIGENT -> 2
                    data.swi68AdasMode == Swi68Mode.ACC                   -> 3
                    data.swi68AdasMode == Swi68Mode.TJA                   -> 4
                    else                                                  -> 0
                }
                val adasSwi68Pairs = listOf(
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_swi68_off_d)  to 0,
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_swi68_lim_d)  to 1,
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_swi68_auto_d) to 2,
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_swi68_acc_d)  to 3,
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_swi68_tja_d)  to 4
                )
                bindGroup(adasSwi68Pairs, initialIdx) { idx ->
                    swi132LimiterConfigured = true
                    when (idx) {
                        1 -> { swi68Mode = Swi68Mode.OFF; swi132SasMode = EVHardware.SasMode.MANUEL }
                        2 -> { swi68Mode = Swi68Mode.OFF; swi132SasMode = EVHardware.SasMode.INTELLIGENT }
                        3 -> { swi68Mode = Swi68Mode.ACC; swi132SasMode = EVHardware.SasMode.OFF }
                        4 -> { swi68Mode = Swi68Mode.TJA; swi132SasMode = EVHardware.SasMode.OFF }
                        else -> { swi68Mode = Swi68Mode.OFF; swi132SasMode = EVHardware.SasMode.OFF }
                    }
                }
            }
            else -> {
                // SWI133/UNKNOWN : section SWI133 (overspeed + speedTone + 5 boutons ADAS)
                sectionSwi133.visibility = View.VISIBLE
                sectionSwi68.visibility  = View.GONE
                val swOverspeed = dialogView.findViewById<Switch>(R.id.sw_overspeed_alarm)
                val swSpeedTone = dialogView.findViewById<Switch>(R.id.sw_speed_limit_tone)
                swOverspeed.isChecked = data.overspeedAlarm
                swSpeedTone.isChecked = data.speedLimitTone
                val adasSwi133Pairs = listOf(
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_off_d)  to 0,
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_lim_d)  to 1,
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_auto_d) to 2,
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_acc_d)  to 3,
                    dialogView.findViewById<MaterialButton>(R.id.btn_adas_ica_d)  to 4
                )
                bindGroup(adasSwi133Pairs, adasMode) { adasMode = it }
            }
        }

        // ── Energy saving + TSR (all known firmware) ───────────────
        // btn_energy_saving_d is in column 1 (drive section), section_tsr_dialog in column 2.
        val sectionTsr = dialogView.findViewById<View>(R.id.section_tsr_dialog)
        if (gen != FirmwareInfo.Gen.UNKNOWN) {
            btnEnergy.visibility = View.VISIBLE
            // Grayed out if SNOW is already selected when the dialog opens
            val initSnow = data.driveMode == DriveMode.SNOW
            btnEnergy.isEnabled = !initSnow
            btnEnergy.alpha = if (initSnow) 0.35f else 1f
            activateBtn(btnEnergy, energySavingSel)
            btnEnergy.setOnClickListener {
                energySavingSel = !energySavingSel
                activateBtn(btnEnergy, energySavingSel)
                // Regen: unavailable if Eco active or if SNOW selected
                setRegenEnabled(!energySavingSel && selectedDrive != DriveMode.SNOW)
            }
            // Note: the initial state of the regen is already managed after the bindGroup of the modes

            sectionTsr.visibility = View.VISIBLE
            val swTsr = dialogView.findViewById<Switch>(R.id.sw_tsr_d)
            swTsr.isChecked = tsrEnabledSel
            swTsr.setOnCheckedChangeListener { _, checked -> tsrEnabledSel = checked }
        }

        // ── Default profile ──────────────────────── ────────────────────────
        val swDefault = dialogView.findViewById<Switch>(R.id.sw_set_default)
        swDefault.isChecked = existing?.id == manager.getDefaultId()

        // ── Nom ──────────────────────────────────────────────────────────────
        val etName = dialogView.findViewById<EditText>(R.id.et_profile_name)
        if (existing != null) etName.setText(existing.name)

        // ── Creating the dialog without Android chrome ───────────────────────────
        val dialog = AlertDialog.Builder(ctx, R.style.Theme_EV_Picker)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Dynamic title integrated into the layout
        dialogView.findViewById<TextView>(R.id.tv_dialog_title).text =
            if (existing != null) getString(R.string.profile_edit) else getString(R.string.profile_add)

        // ── Bouton Annuler ───────────────────────────────────────────────────
        dialogView.findViewById<MaterialButton>(R.id.btn_dialog_cancel).setOnClickListener {
            dialog.dismiss()
        }

        // ── Save button: does NOT close if name is empty ────────────
        dialogView.findViewById<MaterialButton>(R.id.btn_dialog_save).setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                etName.error = getString(R.string.profile_name_required)
                etName.requestFocus()
                return@setOnClickListener
            }

            // SWI132 now uses sectionSwi133 (same IDs as SWI133 — without _d suffix)
            val overspeedAlarm = dialogView.findViewById<Switch?>(R.id.sw_overspeed_alarm)?.isChecked ?: false
            val speedLimitTone = dialogView.findViewById<Switch?>(R.id.sw_speed_limit_tone)?.isChecked ?: false
            val soundWarning   = dialogView.findViewById<Switch?>(R.id.sw_sound_warning)?.isChecked ?: false

            val profile = DrivingProfile(
                id             = existing?.id ?: java.util.UUID.randomUUID().toString(),
                name           = name,
                driveMode      = selectedDrive,
                regenLevel     = selectedRegen,
                steeringHeat   = steeringOn,
                seatHeatLeft   = seatLeft,
                seatHeatRight  = seatRight,
                overspeedAlarm = overspeedAlarm,
                speedLimitTone = speedLimitTone,
                adasMode       = adasMode,
                soundWarning   = soundWarning,
                swi68AdasMode  = swi68Mode,
                swi132LimiterConfigured = swi132LimiterConfigured,
                swi132SasMode  = swi132SasMode,
                aebEnabled     = aebEnabledSel,
                aebMode        = aebModeSel,
                aebSensitivity = aebSenSel,
                elkMode        = elkModeSel,
                elkSensitivity = elkSenSel,
                lasAudibleWarning    = lasAudibleWarningSel,
                lasVibrationReminder = lasVibrationReminderSel,
                energySaving   = energySavingSel,
                tsrEnabled     = tsrEnabledSel
            )
            manager.save(profile)
            if (swDefault.isChecked) manager.setDefault(profile.id)
            refreshList()
            dialog.dismiss()
        }

        // ── Category rail ─────────────────────── ────────────────────────
        // The right pane only displays one category at a time: the rail carries the entire
        // editor navigation, no gestures required. A category of which all
        // sections are hidden by the firmware disappears from the rail rather than opening a
        // volet vide.
        val detail = dialogView.findViewById<ViewFlipper>(R.id.profile_detail)
        val railEntries = listOf(
            R.id.rail_profile_general to emptyList<Int>(),
            R.id.rail_profile_drive   to emptyList(),
            R.id.rail_profile_comfort to listOf(R.id.section_steering_dialog, R.id.section_seats_dialog),
            R.id.rail_profile_adas    to listOf(R.id.adas_section_swi133, R.id.adas_section_swi68,
                                                R.id.section_tsr_dialog),
            R.id.rail_profile_safety  to listOf(R.id.adas_section_aeb, R.id.elk_section_dialog)
        )
        val railButtons = railEntries.map { dialogView.findViewById<MaterialButton>(it.first) }
        fun selectCategory(index: Int) {
            detail.displayedChild = index
            railButtons.forEachIndexed { i, button ->
                val current = i == index
                activateBtn(button, current)
                // TalkBack announces the current category: color does not carry it alone.
                button.isSelected = current
            }
        }
        railEntries.forEachIndexed { index, (_, sections) ->
            val button = railButtons[index]
            val hasContent = sections.isEmpty() ||
                sections.any { dialogView.findViewById<View>(it)?.visibility == View.VISIBLE }
            button.visibility = if (hasContent) View.VISIBLE else View.GONE
            button.setOnClickListener { selectCategory(index) }
        }
        selectCategory(0)

        dialog.show()

        // Full-screen editor: rail on the left, one category at a time on the right.
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
    }
}
