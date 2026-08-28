package com.evsuite.profile.ui

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import androidx.appcompat.app.AppCompatDelegate
import com.evsuite.profile.MainActivity
import com.evsuite.profile.util.ThemeHelper
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.evsuite.profile.BuildConfig
import com.evsuite.profile.R
import com.evsuite.profile.util.QrCode
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.diag.CrashLogger
import com.evsuite.hardware.diag.PrivateBin
import com.evsuite.hardware.EVHardware
import com.evsuite.profile.update.UpdateChannel
import java.io.File
import com.evsuite.profile.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private val githubUrl = "https://github.com/malys/EVProfile"
    private val gitlabUrl = "https://gitlab.com/SliDeeN/evprofile"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    /**
     * Firmware generation. The indicator lived in the top bar, where he occupied
     * the width of each screen for information that is read once — and where its
     * pellets of a few dp were invisible at 70 cm. It's here, in Vehicle,
     * in buttons of 72 dp. They only become clickable if the generation is unknown
     * or already forced: forcing a generation changes what the application writes in the
     * car, this is not a setting to be touched by mistake.
     */
    private fun setupFirmwareSection(view: View) {
        val ctx = requireContext()
        val gen = FirmwareInfo.getGeneration()
        val forced = FirmwareInfo.isForced(ctx)

        view.findViewById<TextView>(R.id.tv_firmware_detected).text =
            FirmwareInfo.getDetectedString()

        val chips = listOf(
            R.id.chip_swi133 to FirmwareInfo.Gen.SWI133,
            R.id.chip_swi132 to FirmwareInfo.Gen.SWI132,
            R.id.chip_swi68  to FirmwareInfo.Gen.SWI68,
            R.id.chip_swi69  to FirmwareInfo.Gen.SWI69,
            R.id.chip_swi131 to FirmwareInfo.Gen.SWI131,
            R.id.chip_swi165 to FirmwareInfo.Gen.SWI165
        )
        val selectable = gen == FirmwareInfo.Gen.UNKNOWN || forced

        chips.forEach { (viewId, chipGen) ->
            val button = view.findViewById<MaterialButton>(viewId)
            val active = chipGen == gen
            button.backgroundTintList = ColorStateList.valueOf(
                ctx.getColor(if (active) R.color.dash_accent_dim else R.color.dash_btn))
            button.setTextColor(ctx.getColor(when {
                active     -> R.color.dash_accent
                selectable -> R.color.dash_danger
                else       -> R.color.text_secondary
            }))
            button.strokeColor = ColorStateList.valueOf(
                ctx.getColor(if (active) R.color.dash_accent else R.color.dash_border))
            button.isSelected = active
            // A generation that cannot be chosen remains readable, but does not invite.
            button.isEnabled = selectable
            button.alpha = if (selectable || active) 1f else 0.5f
            if (selectable) {
                button.setOnClickListener {
                    FirmwareInfo.forceGeneration(ctx, chipGen)
                    requireActivity().recreate()
                }
            }
        }
    }

    /**
     * Category rail: The right pane only displays one category at a time, instead of
     * of the single column of cards that had to be unrolled to find out what it contained.
     */
    private fun setupSettingsRail(view: View) {
        val detail = view.findViewById<ViewFlipper>(R.id.settings_detail)
        val railButtons = listOf(
            R.id.rail_settings_language,
            R.id.rail_settings_display,
            R.id.rail_settings_vehicle,
            R.id.rail_settings_app,
            R.id.rail_settings_about
        ).map { view.findViewById<MaterialButton>(it) }

        val accentDim     = requireContext().getColor(R.color.dash_accent_dim)
        val inactiveColor = requireContext().getColor(R.color.dash_btn)
        val textActive    = requireContext().getColor(R.color.dash_accent)
        val textInactive  = requireContext().getColor(R.color.text_secondary)

        fun select(index: Int) {
            detail.displayedChild = index
            railButtons.forEachIndexed { i, button ->
                val current = i == index
                button.backgroundTintList =
                    ColorStateList.valueOf(if (current) accentDim else inactiveColor)
                button.setTextColor(if (current) textActive else textInactive)
                button.isSelected = current
            }
        }

        railButtons.forEachIndexed { index, button -> button.setOnClickListener { select(index) } }
        select(0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSettingsRail(view)
        setupFirmwareSection(view)
        bindAboutSection(view)

        val prefs = requireContext().getSharedPreferences("ev_settings", Context.MODE_PRIVATE)
        val accentColor  = requireContext().getColor(R.color.dash_accent)
        val accentDim    = requireContext().getColor(R.color.dash_accent_dim)
        val inactiveColor = requireContext().getColor(R.color.dash_btn)
        val textActive    = requireContext().getColor(R.color.dash_accent)
        val textInactive  = requireContext().getColor(R.color.text_secondary)

        // ── Language ─────────────────────────────────────────────────────────
        val langButtons: List<Pair<String, MaterialButton>> = listOf(
            "en" to view.findViewById(R.id.btn_lang_en),
            "de" to view.findViewById(R.id.btn_lang_de),
            "es" to view.findViewById(R.id.btn_lang_es),
            "pt" to view.findViewById(R.id.btn_lang_pt),
            "it" to view.findViewById(R.id.btn_lang_it)
        )

        fun updateLangButtons(lang: String) {
            langButtons.forEach { (code, btn) ->
                val active = lang == code
                btn.backgroundTintList = ColorStateList.valueOf(if (active) accentDim else inactiveColor)
                btn.setTextColor(if (active) textActive else textInactive)
                btn.isSelected = active
            }
        }

        updateLangButtons(LocaleHelper.getLanguage(requireContext()))

        langButtons.forEach { (code, btn) ->
            btn.setOnClickListener {
                if (LocaleHelper.getLanguage(requireContext()) != code) {
                    LocaleHelper.setLanguage(requireContext(), code)
                    requireActivity().recreate()
                }
            }
        }

        // ── Default screen ──────────────────────── ─────────────────────────
        val btnDefDashboard  = view.findViewById<MaterialButton>(R.id.btn_default_dashboard)
        val btnDefProfiles   = view.findViewById<MaterialButton>(R.id.btn_default_profiles)
        val btnDefShortcuts  = view.findViewById<MaterialButton>(R.id.btn_default_shortcuts)
        val defaultScreenBtns = listOf(
            "dashboard" to btnDefDashboard,
            "profiles"  to btnDefProfiles,
            "shortcuts" to btnDefShortcuts
        )

        fun updateDefaultScreenButtons(selected: String) {
            defaultScreenBtns.forEach { (key, btn) ->
                val active = key == selected
                btn.backgroundTintList = ColorStateList.valueOf(if (active) accentDim else inactiveColor)
                btn.setTextColor(if (active) textActive else textInactive)
                btn.isSelected = active
            }
        }

        val currentDefault = prefs.getString("default_screen", "dashboard") ?: "dashboard"
        updateDefaultScreenButtons(currentDefault)

        defaultScreenBtns.forEach { (key, btn) ->
            btn.setOnClickListener {
                prefs.edit().putString("default_screen", key).apply()
                updateDefaultScreenButtons(key)
            }
        }

        // ── Theme: Auto / Dark / Light ───────────────────────────────────
        val btnThemeAuto  = view.findViewById<MaterialButton>(R.id.btn_theme_auto)
        val btnThemeDark  = view.findViewById<MaterialButton>(R.id.btn_theme_dark)
        val btnThemeLight = view.findViewById<MaterialButton>(R.id.btn_theme_light)

        val themeBtns = listOf("auto" to btnThemeAuto, "dark" to btnThemeDark, "light" to btnThemeLight)

        fun updateThemeButtons(mode: String) {
            themeBtns.forEach { (key, btn) ->
                val active = key == mode
                btn.backgroundTintList = ColorStateList.valueOf(if (active) accentDim else inactiveColor)
                btn.setTextColor(if (active) textActive else textInactive)
                btn.isSelected = active
                btn.strokeColor = ColorStateList.valueOf(
                    if (active) accentColor else requireContext().getColor(R.color.dash_border)
                )
            }
        }

        val currentMode = prefs.getString(ThemeHelper.PREF_THEME_MODE, "auto") ?: "auto"
        updateThemeButtons(currentMode)

        fun applyThemeMode(mode: String) {
            if (prefs.getString(ThemeHelper.PREF_THEME_MODE, null) == mode) return
            prefs.edit().putString(ThemeHelper.PREF_THEME_MODE, mode).apply()
            updateThemeButtons(mode)
            AppCompatDelegate.setDefaultNightMode(ThemeHelper.resolveNightMode(requireContext()))
            requireActivity().recreate()
        }

        btnThemeAuto.setOnClickListener  { applyThemeMode("auto")  }
        btnThemeDark.setOnClickListener  { applyThemeMode("dark")  }
        btnThemeLight.setOnClickListener { applyThemeMode("light") }

        // ── Auto-apply ───────────────────────────────────────────────────────
        val switchAutoApply = view.findViewById<Switch>(R.id.switch_auto_apply)
        switchAutoApply.isChecked = prefs.getBoolean("auto_apply_profile", true)
        switchAutoApply.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_apply_profile", checked).apply()
        }

        // ── Auto check for updates ───────────────────────────────
        // Stable: no network → hook hides all update UI.
        // Channel-specific setup is delegated below. Stable hides this section and has no
        // updater classes; unstable owns both the preference and its OTA handlers.

        // ── Vehicle power (SWI133) — turns off the car, keeps the screen on ──
        val rowVehiclePower = view.findViewById<View>(R.id.row_vehicle_power)
        val dividerVehiclePower = view.findViewById<View>(R.id.row_vehicle_power_divider)
        val btnVehiclePower = view.findViewById<MaterialButton>(R.id.btn_vehicle_power_off)
        if (!EVHardware.hasVehiclePowerOff()) {
            rowVehiclePower.visibility = View.GONE
            dividerVehiclePower.visibility = View.GONE
        } else {
            btnVehiclePower.setOnClickListener {
                // Safety: extinction is only proposed if the lever is confirmed in P.
                CoroutineScope(Dispatchers.IO).launch {
                    val inPark = EVHardware.isVehicleInPark()
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        if (inPark == true) {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.vehicle_power_dialog_title)
                                .setMessage(R.string.vehicle_power_dialog_msg)
                                .setNegativeButton(R.string.vehicle_power_dialog_cancel, null)
                                .setPositiveButton(R.string.vehicle_power_dialog_confirm) { _, _ ->
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val ok = EVHardware.vehiclePowerOff()
                                        AppLogger.i("EV_SETTINGS", "Vehicle power off → $ok")
                                    }
                                }
                                .show()
                        } else {
                            Toast.makeText(requireContext(), R.string.vehicle_power_need_park, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        // ── Check for update button ──────────────────────────────────────
        val btnUpdate     = view.findViewById<MaterialButton>(R.id.btn_check_update)
        val btnDiagnostic = view.findViewById<MaterialButton>(R.id.btn_diagnostic)
        val originalUpdateText = getString(R.string.btn_check_update)

        // Diagnostic button unlocked via 5 clicks on the logo (see MainActivity)
        btnDiagnostic.visibility = if (MainActivity.diagnosticUnlocked) View.VISIBLE else View.GONE

        UpdateChannel.configureSettings(
            fragment = this,
            root = view,
            onNoUpdate = { showUpToDate(btnUpdate, originalUpdateText) },
            onError = { showUpdateError(btnUpdate, originalUpdateText) }
        )

        // ── Bouton Nettoyer APK ──────────────────────────────────────────────
        // ── Diagnostic button (hidden by default — unlocked by 5 clicks on SHIFT) ──
        btnDiagnostic.setOnClickListener {
            showDiagnosticDialog()
        }

        // ── Bouton Automatisation (EVTasker) ────────────────────────────────
        setupTaskerButton(view)
    }

    override fun onResume() {
        super.onResume()
        refreshTaskerButton(requireView())
    }

    /**
     * EVTasker automates what EVProfile adjusts by hand: the button opens it, it does not
     * does not replace or offer to install it.
     *
     * The click rechecks the intent rather than trusting the display: between the
     * moment the button appears and the finger arrives, the app may have been
     * uninstalled, and a startActivity() on an absent package would drop EVProfile.
     */
    private fun setupTaskerButton(view: View) {
        refreshTaskerButton(view)
        view.findViewById<MaterialButton>(R.id.btn_open_tasker).setOnClickListener { button ->
            val intent = taskerLaunchIntent()
            if (intent == null) {
                button.visibility = View.GONE
                return@setOnClickListener
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(requireContext(), R.string.nav_tasker_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Reevaluated each time: installing EVTasker while EVProfile is running should not
     * not require a reboot for the button to appear.
     */
    private fun refreshTaskerButton(view: View) {
        view.findViewById<MaterialButton>(R.id.btn_open_tasker).visibility =
            if (taskerLaunchIntent() != null) View.VISIBLE else View.GONE
    }

    /** L'intent de lancement de EVTasker, stable d'abord, instable ensuite. */
    private fun taskerLaunchIntent(): Intent? =
        TASKER_PACKAGES.firstNotNullOfOrNull { requireContext().packageManager.getLaunchIntentForPackage(it) }

    // ── Up-to-date button feedback ───────────────────────────────────────────

    private fun showUpToDate(btn: MaterialButton, originalText: String) {
        val ctx = requireContext()
        val ecoDim    = ctx.getColor(R.color.dash_eco_dim)
        val eco       = ctx.getColor(R.color.dash_eco)
        val accentDim = ctx.getColor(R.color.dash_accent_dim)
        val accent    = ctx.getColor(R.color.dash_accent)

        // Turn the button green to indicate that the app is up to date.
        btn.text = getString(R.string.update_up_to_date)
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(ecoDim)
        btn.strokeColor        = android.content.res.ColorStateList.valueOf(eco)
        btn.setTextColor(eco)
        btn.isEnabled = false

        // Returns to normal state after 3 seconds
        btn.postDelayed({
            if (isAdded) {
                btn.text = originalText
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(accentDim)
                btn.strokeColor        = android.content.res.ColorStateList.valueOf(accent)
                btn.setTextColor(accent)
                btn.isEnabled = true
            }
        }, 3_000)
    }

    // ── Network-error button feedback ────────────────────────────────────────

    private fun showUpdateError(btn: MaterialButton, originalText: String) {
        val ctx = requireContext()
        val dangerDim = ctx.getColor(R.color.dash_danger_dim)
        val danger    = ctx.getColor(R.color.dash_danger)
        val accentDim = ctx.getColor(R.color.dash_accent_dim)
        val accent    = ctx.getColor(R.color.dash_accent)

        btn.text = getString(R.string.update_network_error)
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(dangerDim)
        btn.strokeColor        = android.content.res.ColorStateList.valueOf(danger)
        btn.setTextColor(danger)
        btn.isEnabled = false

        btn.postDelayed({
            if (isAdded) {
                btn.text = originalText
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(accentDim)
                btn.strokeColor        = android.content.res.ColorStateList.valueOf(accent)
                btn.setTextColor(accent)
                btn.isEnabled = true
            }
        }, 3_000)
    }

    // ── Dialog Diagnostic ────────────────────────────────────────────────────

    private fun showDiagnosticDialog() {
        val ctx = requireContext()

        // Diagnostic probe: volume log + door status BEFORE rendering the logs,
        // so that the report contains them (independent of the toggle / Audio tab).
        EVHardware.runDoorVolumeDiag()

        val appVersion = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }

        // ── Layout: crash banner (optional) + material report ──────────────
        var btnClearCrash: Button? = null
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // "Share" sends the complete report to PrivateBin through EVHardware.
        // At the top of the content: the car has no sharing target, and this is the gesture
        // what we are looking for when we have just opened this dialog to send a report.
        // Absent from the stable channel, which does not declare INTERNET permission: suggest
        // sending there would only produce failure.
        val btnShare = if (BuildConfig.OFFLINE) null else Button(ctx).apply {
            text = getString(R.string.diag_share)
        }
        btnShare?.let { container.addView(it) }

        // Crash log section (if a crash was recorded)
        val crashLog = CrashLogger.read(ctx)
        if (crashLog != null) {
            val tvCrash = TextView(ctx).apply {
                text = crashLog
                typeface = Typeface.MONOSPACE
                textSize = 16f
                setTextColor(ctx.getColor(R.color.dash_danger))
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(ctx.getColor(R.color.dash_danger_dim))
            }
            container.addView(tvCrash)

            // Separator
            val divider = android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * resources.displayMetrics.density).toInt()
                ).also { it.topMargin = 0; it.bottomMargin = 0 }
                setBackgroundColor(ctx.getColor(R.color.dash_border))
            }
            container.addView(divider)

            // “Clear crash” moved to the content (the neutral slot is used for Download)
            btnClearCrash = Button(ctx).apply {
                text = getString(R.string.diag_clear_crash)
            }
            container.addView(btnClearCrash)
        }

        // Hardware report section
        val tvReport = TextView(ctx).apply {
            text = getString(R.string.diag_loading)
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setTextColor(ctx.getColor(R.color.text_secondary))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        container.addView(tvReport)

        // Real-time AppLogger section (last 30 lines)
        val tvLogs = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setTextColor(ctx.getColor(R.color.dash_text_lo))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, pad)
            val entries = AppLogger.entries
            text = if (entries.isEmpty()) "─── AppLogger vide ───"
                   else "─── AppLogger (${entries.size} entries) ───\n" +
                        entries.takeLast(30).joinToString("\n") { e ->
                            "${e.time} [${e.level.name[0]}] ${e.tag}: ${e.msg}"
                        }
        }
        container.addView(tvLogs)

        val scrollView = ScrollView(ctx).apply {
            addView(container)
        }

        val title = if (crashLog != null)
            "⚠ ${getString(R.string.diag_title)} — CRASH DETECTED"
        else
            getString(R.string.diag_title)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(getString(R.string.diag_copy), null)
            .setNeutralButton(getString(R.string.diag_download), null)
            .setNegativeButton(getString(R.string.nav_close), null)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(ctx.getColor(R.color.dash_card)))

        // Short report (last 30 lines) for the clipboard; full report for
        // the file and for sending.
        //
        // [leaving] removes the dividing lines: a sending leaves the link and the message there
        // passes from the previous paste, and the log is copied into the report. Without this
        // filter, the second shipment delivers the first to its recipient.
        fun buildReport(fullLog: Boolean, leaving: Boolean = false) = buildString {
            if (crashLog != null) { appendLine(crashLog); appendLine() }
            appendLine(tvReport.text)
            appendLine()
            if (fullLog) {
                val entries = AppLogger.entries.filterNot { leaving && it.tag == SHARE_TAG }
                appendLine("─── AppLogger (${entries.size} entries) ───")
                entries.forEach { e -> appendLine("${e.time} [${e.level.name[0]}] ${e.tag}: ${e.msg}") }
            } else appendLine(tvLogs.text)
        }

        dialog.setOnShowListener {
            // “Copy” — copies everything without closing the dialog
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("EVProfile Diagnostic", buildReport(false)))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text = getString(R.string.diag_copied)
            }
            // "Download" writes the complete report to the vehicle's Downloads directory.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                downloadDiagnostic(ctx, buildReport(true))
            }
            // “Share” (in content) — sending confirmed, dialog remains open
            btnShare?.setOnClickListener {
                confirmDiagnosticShare(ctx) { buildReport(fullLog = true, leaving = true) }
            }
            // “Clear crash” (in content) — deletes the file and closes the dialog
            btnClearCrash?.setOnClickListener {
                CrashLogger.clear(ctx)
                dialog.dismiss()
            }
        }

        dialog.show()

        // Generating the hardware report on the IO thread
        CoroutineScope(Dispatchers.IO).launch {
            val report = EVHardware.buildDiagnosticReport(appVersion)
            withContext(Dispatchers.Main) {
                if (isAdded) tvReport.text = report
            }
        }
    }

    /**
     * Confirmation before sending: the report leaves the car for a public server.
     * Encrypted and password protected, but it leaves — it's not a decision
     * take the user's place because they touched a "Share" button.
     */
    private fun confirmDiagnosticShare(ctx: Context, report: () -> String) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.diag_share))
            .setMessage(getString(R.string.diag_share_confirm, PasteConfig.HOST))
            .setNegativeButton(getString(R.string.nav_close), null)
            .setPositiveButton(getString(R.string.diag_share_send)) { _, _ ->
                uploadDiagnostic(ctx, report())
            }
            .show()
    }

    /**
     * Sends to PrivateBin off the main thread.
     *
     * The link is written in AppLogger, not just displayed: a toast disappears and the
     * car offers no way to catch a URL. The newspaper can be reread and
     * found in the next report.
     */
    private fun uploadDiagnostic(ctx: Context, report: String) {
        Toast.makeText(ctx, getString(R.string.diag_share_running), Toast.LENGTH_SHORT).show()
        val appCtx = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val message = when (val outcome = PrivateBin.paste(report, PasteConfig.CONFIG)) {
                is PrivateBin.Outcome.Ok -> {
                    AppLogger.i(SHARE_TAG, "diagnostic sent — ${outcome.url}")
                    AppLogger.i(SHARE_TAG, "password ${PasteConfig.CONFIG.password}, expires in 1 hour")
                    appCtx.getString(R.string.diag_share_ok)
                }
                is PrivateBin.Outcome.Failed -> {
                    AppLogger.w(SHARE_TAG, "diagnostic upload failed — ${outcome.reason}")
                    appCtx.getString(R.string.diag_share_failed, outcome.reason)
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(appCtx, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Where does a shared relationship start?
     *
     * Chapril's PrivateBin instance, run by April, a French association — no
     * account, no tracking, and the server never holds the key. One hour is
     * deliberately short: enough to send the link to whoever helps, too little to leave
     * dragging out the diagnosis of a car.
     */
    private object PasteConfig {
        const val HOST = "paste.chapril.org"

        val CONFIG = PrivateBin.Config(
            baseUrl = "https://$HOST/",
            password = "evprofileR0ck\$",
            expire = "1hour",
            formatter = "plaintext",
        )
    }

    /** Writes the diagnostic report to the car's Download folder (time-stamped .txt file). */
    private fun downloadDiagnostic(ctx: Context, report: String) {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val file = File(dir, "EVProfile_diag_$ts.txt")
            file.writeText(report)
            Toast.makeText(ctx, getString(R.string.diag_downloaded, file.absolutePath), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, getString(R.string.diag_download_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
        }
    }

    // ── About section ────────────────────────── ───────────────────────────

    /**
     * “About” is no longer a window to open: it is the last category of the rail,
     * just like the others. Nothing can be activated while driving, only read.
     */
    private fun bindAboutSection(view: View) {
        val versionName = try {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) { "1.0" }
        val tvVersion = view.findViewById<TextView>(R.id.tv_app_version)
        tvVersion.text = versionName

        QrCode.generate(githubUrl, 400)?.let {
            view.findViewById<ImageView>(R.id.iv_qr_code_github).setImageBitmap(it)
        }
        QrCode.generate(gitlabUrl, 400)?.let {
            view.findViewById<ImageView>(R.id.iv_qr_code_gitlab).setImageBitmap(it)
        }

        view.findViewById<TextView>(R.id.tv_github_link).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
        }
        view.findViewById<TextView>(R.id.tv_gitlab_link).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(gitlabUrl)))
        }

        // Three presses on the version open the diagnosis. Deliberately hidden gesture:
        // the report contains the firmware and the logs, it has nothing to do under your finger
        // of a passenger exploring the screen.
        var taps = 0
        var lastTap = 0L
        tvVersion.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            // A pause breaks the series: three presses spaced in time are not
            // the gesture is three distinct presses on a field that expects none.
            taps = if (now - lastTap > VERSION_TAP_WINDOW_MS) 1 else taps + 1
            lastTap = now
            if (taps >= VERSION_TAPS_FOR_DIAGNOSTIC) {
                taps = 0
                showDiagnosticDialog()
            }
        }
    }

    private companion object {
        const val SHARE_TAG = "EV_SHARE"

        const val VERSION_TAPS_FOR_DIAGNOSTIC = 3
        const val VERSION_TAP_WINDOW_MS = 1_000L

        /**
         * EVTasker, all channels combined, from the most “official” to the most experimental.
         *
         * Historical and current suffixes are retained to detect installations
         * already broadcast. Stable and unstable are installed side by side, and
         * a tester that only has the unstable channel does indeed have EVTasker — only list
         * the stable id amounted to hiding the button.
         */
        val TASKER_PACKAGES = listOf(
            "com.evsuite.tasker",
            "com.evsuite.tasker.offline",
            "com.evsuite.tasker.unstable",
            "com.evsuite.tasker.offline.unstable"
        )
    }
}
