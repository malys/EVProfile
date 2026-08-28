package com.evsuite.profile.ui

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Switch
import android.widget.ViewFlipper
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.evsuite.profile.R
import com.evsuite.hardware.model.RegenLevel
import com.evsuite.profile.profile.ProfileManager
import com.evsuite.profile.shortcut.ShortcutAction
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.FirmwareInfo

class ShortcutsFragment : Fragment() {

    private val PREFS = "ev_shortcuts"

    private lateinit var prefs: SharedPreferences
    private var accentColor = 0
    private var defColor    = 0

    private var switchEnabled:   Switch? = null
    private var shortcutsContent: View?  = null

    /** Items available in Spinners — calculated once according to firmware. */
    private data class ActionItem(val label: String, val action: ShortcutAction)

    /** Basic list (without custom label) — shared for all spinners. */
    private var baseActionItems: List<ActionItem> = emptyList()

    /** Keys identifying each line slot × pressure type. */
    private val slotPressList = listOf(
        "btn1_single", "btn1_long",
        "btn2_single", "btn2_long"
    )

    // ── Par-spinner : label list mutable + adapter + vue ─────────────────
    private val spinnerLabelLists = mutableMapOf<String, MutableList<String>>()
    private val spinnerAdapters   = mutableMapOf<String, ArrayAdapter<String>>()
    private val spinnerViews      = mutableMapOf<String, Spinner>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_shortcuts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs       = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        accentColor = requireContext().getColor(R.color.accent_eco)
        defColor    = requireContext().getColor(R.color.bg_button)

        switchEnabled    = view.findViewById(R.id.switch_shortcuts_enabled)
        shortcutsContent = view.findViewById(R.id.shortcuts_content)

        val gen        = FirmwareInfo.getGeneration()
        val isKnown    = gen != FirmwareInfo.Gen.UNKNOWN
        val isVsmBased = FirmwareInfo.isVsmBased()
        val isSWI132   = gen == FirmwareInfo.Gen.SWI132

        // ── Build the base items for the active firmware ──────────────────
        baseActionItems = buildList {
            add(ActionItem(getString(R.string.shortcuts_action_none),           ShortcutAction.NONE))
            add(ActionItem(getString(R.string.shortcuts_action_one_pedal),      ShortcutAction.ONE_PEDAL))
            if (isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_aeb),        ShortcutAction.AEB_CYCLE))
            }
            // SWI68/69/131/165: one VSM audible alert.
            if (isVsmBased && !isSWI132) {
                add(ActionItem(getString(R.string.shortcuts_action_sound),      ShortcutAction.SOUND_WARNING))
            }
            // SWI133 + SWI132: two independent alerts (overspeed + limit tone)
            if ((!isVsmBased || isSWI132) && isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_overspeed),  ShortcutAction.OVERSPEED_ALARM))
                add(ActionItem(getString(R.string.shortcuts_action_speed_limit),ShortcutAction.SPEED_LIMIT_TONE))
            }
            if (isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_adas),       ShortcutAction.ADAS_CYCLE))
            }
            if (isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_energy_saving), ShortcutAction.ENERGY_SAVING_TOGGLE))
            }
            if (isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_tsr), ShortcutAction.TSR_TOGGLE))
            }
            add(ActionItem(getString(R.string.shortcuts_action_apply_profile),   ShortcutAction.APPLY_PROFILE))
            add(ActionItem(getString(R.string.shortcuts_action_profile_picker), ShortcutAction.PROFILE_PICKER))
            add(ActionItem(getString(R.string.shortcuts_action_open_app),       ShortcutAction.OPEN_APP))
            add(ActionItem(getString(R.string.shortcuts_action_open_custom_app),ShortcutAction.OPEN_CUSTOM_APP))
            if (EVHardware.hasVehiclePowerOff()) {
                add(ActionItem(getString(R.string.shortcuts_action_vehicle_power_off), ShortcutAction.VEHICLE_POWER_OFF))
            }
        }

        // ── Show configuration sections for the active firmware ───────────
        // All known firmwares use the 5-mode config (Off/Lim.Manuel/Lim.Auto/ACC/ICA|TJA).
        view.findViewById<View>(R.id.config_adas_section)?.visibility = if (isKnown) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.config_adas_swi133)?.visibility  = if (isKnown) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.config_adas_swi68)?.visibility   = View.GONE

        setupShortcutsRail(view)

        setupSpinners(view)
        setupConfigListeners(view)
        restoreState()
    }

    // ── Spinners (un adapter par spinner) ────────────────────────────────

    /**
     * Category rail. The screen processed four subjects at once — the two ★ buttons,
     * the return regen, the ADAS alternation — without hierarchy between them. The pane of
     * right only shows one; the ADAS category disappears from the rail when the
     * firmware is not recognized, like the section it opens.
     */
    private fun setupShortcutsRail(view: View) {
        val detail = view.findViewById<ViewFlipper>(R.id.shortcuts_detail)
        val entries = listOf(
            R.id.rail_shortcuts_btn1  to null,
            R.id.rail_shortcuts_btn2  to null,
            R.id.rail_shortcuts_pedal to null,
            R.id.rail_shortcuts_adas  to R.id.config_adas_section
        )
        val buttons = entries.map { view.findViewById<MaterialButton>(it.first) }
        val activeText   = requireContext().getColor(R.color.dash_accent)
        val inactiveText = requireContext().getColor(R.color.text_secondary)
        val activeBg     = requireContext().getColor(R.color.dash_accent_dim)

        fun select(index: Int) {
            detail.displayedChild = index
            buttons.forEachIndexed { i, button ->
                val current = i == index
                button.backgroundTintList =
                    ColorStateList.valueOf(if (current) activeBg else defColor)
                button.setTextColor(if (current) activeText else inactiveText)
                button.isSelected = current
            }
        }

        entries.forEachIndexed { index, (_, sectionId) ->
            val button = buttons[index]
            val available = sectionId == null ||
                view.findViewById<View>(sectionId)?.visibility == View.VISIBLE
            button.visibility = if (available) View.VISIBLE else View.GONE
            button.setOnClickListener { select(index) }
        }
        select(0)
    }

    private fun setupSpinners(view: View) {
        for (slotKey in slotPressList) {
            val spinnerId = resources.getIdentifier("spinner_$slotKey", "id", requireContext().packageName)
            val spinner   = view.findViewById<Spinner>(spinnerId) ?: continue

            // Build the list of labels for this slot (OPEN_CUSTOM_APP can have a custom label)
            val labels = buildLabelsFor(slotKey)
            spinnerLabelLists[slotKey] = labels
            spinnerViews[slotKey]      = spinner

            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerAdapters[slotKey] = adapter
            spinner.adapter = adapter

            // Initial selection
            val savedAction = ShortcutAction.fromId(prefs.getInt("shortcut_$slotKey", 0))
            val position    = baseActionItems.indexOfFirst { it.action == savedAction }.coerceAtLeast(0)
            spinner.setSelection(position)

            // Listener positioned AFTER to ignore the auto callback of setSelection.
            // The `initialized` flag absorbs the first automatic onItemSelected (initial selection).
            spinner.post {
                var initialized = false
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                        val action = baseActionItems[pos].action
                        saveInt("shortcut_$slotKey", action.id)
                        if (initialized) {
                            when (action) {
                                ShortcutAction.OPEN_CUSTOM_APP -> showAppPickerDialog(slotKey)
                                ShortcutAction.APPLY_PROFILE   -> showProfilePickerDialog(slotKey)
                                else -> {}
                            }
                        }
                        initialized = true
                    }
                    override fun onNothingSelected(parent: AdapterView<*>) {}
                }
            }
        }
    }

    /**
     * Constructs the list of labels for a slot.
     * - OPEN_CUSTOM_APP: displays "Open [AppName]" if an app is saved.
     * - APPLY_PROFILE: displays “▶ [ProfileName]” if a profile is saved.
     */
    private fun buildLabelsFor(slotKey: String): MutableList<String> {
        val savedPkg = prefs.getString("shortcut_${slotKey}_custom_app", null)
        val customAppLabel = if (savedPkg != null) {
            resolveAppLabel(savedPkg) ?: getString(R.string.shortcuts_action_open_custom_app)
        } else {
            getString(R.string.shortcuts_action_open_custom_app)
        }

        val savedProfileId = prefs.getString("shortcut_${slotKey}_profile_id", null)
        val profileLabel = if (savedProfileId != null) {
            val profile = ProfileManager(requireContext()).getById(savedProfileId)
            if (profile != null) getString(R.string.shortcuts_profile_prefix) + " " + profile.name
            else getString(R.string.shortcuts_action_apply_profile)
        } else {
            getString(R.string.shortcuts_action_apply_profile)
        }

        return baseActionItems.map { item ->
            when (item.action) {
                ShortcutAction.OPEN_CUSTOM_APP -> customAppLabel
                ShortcutAction.APPLY_PROFILE   -> profileLabel
                else                           -> item.label
            }
        }.toMutableList()
    }

    /** Returns the application label (packageName) or null if not found. */
    private fun resolveAppLabel(packageName: String): String? {
        return try {
            val pm = requireContext().packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            val appName = pm.getApplicationLabel(info).toString()
            getString(R.string.shortcuts_open_custom_prefix) + " " + appName
        } catch (_: Exception) { null }
    }

    // ── Application selection dialog ────────────────────────────────

    private fun showAppPickerDialog(slotKey: String) {
        val pm = requireContext().packageManager

        // Retrieve all launchable apps, sorted by label
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveList: List<ResolveInfo> = pm.queryIntentActivities(launchIntent, 0)
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val labels   = resolveList.map { it.loadLabel(pm).toString() }.toTypedArray()
        val packages = resolveList.map { it.activityInfo.packageName }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.shortcuts_pick_app_title)
            .setItems(labels) { _, which ->
                val pkg      = packages[which]
                val appName  = labels[which]
                val newLabel = getString(R.string.shortcuts_open_custom_prefix) + " " + appName

                prefs.edit().putString("shortcut_${slotKey}_custom_app", pkg).apply()
                updateCustomAppLabel(slotKey, newLabel)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // If no app was saved → return to NONE
                if (prefs.getString("shortcut_${slotKey}_custom_app", null) == null) {
                    val spinner = spinnerViews[slotKey] ?: return@setNegativeButton
                    spinner.setSelection(0)
                    saveInt("shortcut_$slotKey", ShortcutAction.NONE.id)
                }
            }
            .show()
    }

    // ── Profile selection dialog ──────────────────── ────────────────────

    private fun showProfilePickerDialog(slotKey: String) {
        val profiles = ProfileManager(requireContext()).getAll()

        if (profiles.isEmpty()) {
            // No profile created → return to NONE
            val spinner = spinnerViews[slotKey] ?: return
            spinner.setSelection(0)
            saveInt("shortcut_$slotKey", ShortcutAction.NONE.id)
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.shortcuts_no_profiles)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val labels = profiles.map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.shortcuts_pick_profile_title)
            .setItems(labels) { _, which ->
                val profile  = profiles[which]
                val newLabel = getString(R.string.shortcuts_profile_prefix) + " " + profile.name
                prefs.edit().putString("shortcut_${slotKey}_profile_id", profile.id).apply()
                updateProfileLabel(slotKey, newLabel)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // Cancellation without previously saved profile → return to NONE
                if (prefs.getString("shortcut_${slotKey}_profile_id", null) == null) {
                    val spinner = spinnerViews[slotKey] ?: return@setNegativeButton
                    spinner.setSelection(0)
                    saveInt("shortcut_$slotKey", ShortcutAction.NONE.id)
                }
            }
            .show()
    }

    /** Updates the APPLY_PROFILE label in the adapter of the spinner concerned. */
    private fun updateProfileLabel(slotKey: String, newLabel: String) {
        val labels  = spinnerLabelLists[slotKey] ?: return
        val spinner = spinnerViews[slotKey]      ?: return
        val adapter = spinnerAdapters[slotKey]   ?: return

        val idx = baseActionItems.indexOfFirst { it.action == ShortcutAction.APPLY_PROFILE }
        if (idx < 0) return

        labels[idx] = newLabel
        adapter.notifyDataSetChanged()
        spinner.setSelection(idx)
    }

    /** Updates the OPEN_CUSTOM_APP label in the adapter of the spinner concerned. */
    private fun updateCustomAppLabel(slotKey: String, newLabel: String) {
        val labels  = spinnerLabelLists[slotKey] ?: return
        val spinner = spinnerViews[slotKey]      ?: return
        val adapter = spinnerAdapters[slotKey]   ?: return

        val idx = baseActionItems.indexOfFirst { it.action == ShortcutAction.OPEN_CUSTOM_APP }
        if (idx < 0) return

        labels[idx] = newLabel
        adapter.notifyDataSetChanged()
        // Ensure that the spinner displays the correct selected item
        spinner.setSelection(idx)
    }

    // ── Config buttons (1 Pedal / AEB / ADAS) ───────────────────────────

    private fun setupConfigListeners(view: View) {
        switchEnabled?.setOnCheckedChangeListener { _, checked ->
            if (switchEnabled?.isPressed == true) {
                saveBoolean("shortcut_enabled", checked)
                applyEnabledUI(checked)
                if (checked) showShortcutWarning()
            }
        }

        // One Pedal — regen de retour
        setupConfigRow("shortcut_one_pedal_fallback", RegenLevel.HIGH.value, view,
            R.id.sc_fallback_off      to RegenLevel.OFF.value,
            R.id.sc_fallback_low      to RegenLevel.LOW.value,
            R.id.sc_fallback_medium   to RegenLevel.MEDIUM.value,
            R.id.sc_fallback_high     to RegenLevel.HIGH.value,
            R.id.sc_fallback_adaptive to RegenLevel.ADAPTIVE.value
        )

        // ADAS — modes A and B: all known firmware uses indices 0-4
        // (Off/Lim.Manuel/Lim.Auto/ACC/ICA|TJA). The index→hardware conversion is done in the service.
        setupConfigRow("shortcut_adas_mode_a", 0, view,
            R.id.sc_adas_a_0 to 0, R.id.sc_adas_a_1 to 1, R.id.sc_adas_a_2 to 2,
            R.id.sc_adas_a_3 to 3, R.id.sc_adas_a_4 to 4
        )
        setupConfigRow("shortcut_adas_mode_b", 3, view,
            R.id.sc_adas_b_0 to 0, R.id.sc_adas_b_1 to 1, R.id.sc_adas_b_2 to 2,
            R.id.sc_adas_b_3 to 3, R.id.sc_adas_b_4 to 4
        )
    }

    private fun setupConfigRow(
        prefKey: String,
        defaultValue: Int,
        view: View,
        vararg pairs: Pair<Int, Int>
    ) {
        val buttons = pairs.associate { (resId, value) ->
            value to view.findViewById<MaterialButton>(resId)
        }
        buttons.forEach { (value, btn) ->
            btn?.setOnClickListener {
                saveInt(prefKey, value)
                highlightConfig(buttons, value)
            }
        }
        highlightConfig(buttons, prefs.getInt(prefKey, defaultValue))
    }

    // ── Restoring the state ────────────────────── ──────────────────────

    private fun restoreState() {
        val enabled = prefs.getBoolean("shortcut_enabled", false)
        switchEnabled?.isChecked = enabled
        applyEnabledUI(enabled)
    }

    // ── Helpers UI ───────────────────────────────────────────────────────

    private fun applyEnabledUI(enabled: Boolean) {
        shortcutsContent?.alpha = if (enabled) 1f else 0.35f
        setChildrenEnabled(shortcutsContent, enabled)
    }

    private fun setChildrenEnabled(v: View?, enabled: Boolean) {
        if (v == null) return
        v.isEnabled = enabled
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) setChildrenEnabled(v.getChildAt(i), enabled)
        }
    }

    private fun highlightConfig(map: Map<Int, MaterialButton?>, active: Int) {
        val activeTextColor   = requireContext().getColor(R.color.text_active)
        val inactiveTextColor = requireContext().getColor(R.color.text_secondary)
        map.forEach { (value, btn) ->
            val isActive = value == active
            btn?.backgroundTintList = ColorStateList.valueOf(if (isActive) accentColor else defColor)
            btn?.setTextColor(if (isActive) activeTextColor else inactiveTextColor)
            btn?.isSelected = isActive
        }
    }

    private fun showShortcutWarning() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.shortcuts_warning_title)
            .setMessage(R.string.shortcuts_warning_message)
            .setPositiveButton(R.string.shortcuts_warning_ok, null)
            .show()
    }

    // ── Prefs helpers ────────────────────────────────────────────────────

    private fun saveInt(key: String, value: Int)          = prefs.edit().putInt(key, value).apply()
    private fun saveBoolean(key: String, value: Boolean)  = prefs.edit().putBoolean(key, value).apply()
}
