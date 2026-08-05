package com.mg4.control.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import androidx.appcompat.app.AppCompatDelegate
import com.mg4.control.MainActivity
import com.mg4.control.util.ThemeHelper
import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.mg4.control.BuildConfig
import com.mg4.control.R
import com.mg4.control.util.QrCode
import com.mg4.hardware.AppLogger
import com.mg4.hardware.FirmwareInfo
import com.mg4.hardware.diag.CrashLogger
import com.mg4.hardware.diag.PrivateBin
import com.mg4.hardware.MG4Hardware
import com.mg4.control.update.ApkCleanup
import com.mg4.control.update.UpdateChecker
import com.mg4.control.update.UpdateDialogManager
import java.io.File
import com.mg4.control.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private val githubUrl = "https://github.com/malys/MG4Control"
    private val gitlabUrl = "https://gitlab.com/SliDeeN/mg4control"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    /**
     * Rail de catégories : le volet de droite n'affiche qu'une catégorie à la fois, au lieu
     * de la colonne unique de cartes qu'il fallait dérouler pour savoir ce qu'elle contenait.
     */
    private fun setupSettingsRail(view: View) {
        val detail = view.findViewById<ViewFlipper>(R.id.settings_detail)
        val railButtons = listOf(
            R.id.rail_settings_language,
            R.id.rail_settings_display,
            R.id.rail_settings_vehicle,
            R.id.rail_settings_app
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

        val prefs = requireContext().getSharedPreferences("mg4_settings", Context.MODE_PRIVATE)
        val accentColor  = requireContext().getColor(R.color.dash_accent)
        val accentDim    = requireContext().getColor(R.color.dash_accent_dim)
        val inactiveColor = requireContext().getColor(R.color.dash_btn)
        val textActive    = requireContext().getColor(R.color.dash_accent)
        val textInactive  = requireContext().getColor(R.color.text_secondary)

        // ── Langue ───────────────────────────────────────────────────────────
        val langButtons = listOf(
            "fr" to view.findViewById<MaterialButton>(R.id.btn_lang_fr),
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

        // ── Écran par défaut ─────────────────────────────────────────────────
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

        // ── Thème : Auto / Sombre / Clair ───────────────────────────────────
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

        // ── Vérification auto des mises à jour ───────────────────────────────
        // Build offline : pas de réseau → on masque toute l'UI de mise à jour.
        if (BuildConfig.OFFLINE) {
            view.findViewById<View>(R.id.row_auto_update).visibility = View.GONE
            view.findViewById<View>(R.id.row_update_buttons).visibility = View.GONE
        } else {
            val switchAutoUpdate = view.findViewById<Switch>(R.id.switch_auto_update)
            switchAutoUpdate.isChecked = prefs.getBoolean("auto_check_update", true)
            switchAutoUpdate.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("auto_check_update", checked).apply()
            }
        }

        // ── Alimentation véhicule (SWI133) — éteint la voiture, garde l'écran ──
        val rowVehiclePower = view.findViewById<View>(R.id.row_vehicle_power)
        val dividerVehiclePower = view.findViewById<View>(R.id.row_vehicle_power_divider)
        val btnVehiclePower = view.findViewById<MaterialButton>(R.id.btn_vehicle_power_off)
        if (!MG4Hardware.hasVehiclePowerOff()) {
            rowVehiclePower.visibility = View.GONE
            dividerVehiclePower.visibility = View.GONE
        } else {
            btnVehiclePower.setOnClickListener {
                // Sécurité : on ne propose l'extinction que si le levier est confirmé en P.
                CoroutineScope(Dispatchers.IO).launch {
                    val inPark = MG4Hardware.isVehicleInPark()
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        if (inPark == true) {
                            AlertDialog.Builder(requireContext())
                                .setTitle(R.string.vehicle_power_dialog_title)
                                .setMessage(R.string.vehicle_power_dialog_msg)
                                .setNegativeButton(R.string.vehicle_power_dialog_cancel, null)
                                .setPositiveButton(R.string.vehicle_power_dialog_confirm) { _, _ ->
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val ok = MG4Hardware.vehiclePowerOff()
                                        AppLogger.i("MG4_SETTINGS", "Vehicle power off → $ok")
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

        // ── Bouton Vérifier mise à jour ──────────────────────────────────────
        val btnUpdate     = view.findViewById<MaterialButton>(R.id.btn_check_update)
        val btnDiagnostic = view.findViewById<MaterialButton>(R.id.btn_diagnostic)
        val originalUpdateText = getString(R.string.btn_check_update)

        // Bouton Diagnostic débloqué via 5 clics sur le logo (cf. MainActivity)
        btnDiagnostic.visibility = if (MainActivity.diagnosticUnlocked) View.VISIBLE else View.GONE

        btnUpdate.setOnClickListener {
            btnUpdate.isEnabled = false

            UpdateChecker.check(
                context = requireContext(),
                onUpdateAvailable = { updateInfo ->
                    if (isAdded) {
                        btnUpdate.isEnabled = true
                        UpdateDialogManager.show(
                            requireActivity() as androidx.appcompat.app.AppCompatActivity,
                            updateInfo
                        )
                    }
                },
                onNoUpdate = {
                    if (isAdded) showUpToDate(btnUpdate, originalUpdateText)
                },
                onError = {
                    if (isAdded) showUpdateError(btnUpdate, originalUpdateText)
                }
            )
        }

        // ── Bouton Nettoyer APK ──────────────────────────────────────────────
        val btnClean = view.findViewById<MaterialButton>(R.id.btn_clean_apk)
        val originalCleanText = getString(R.string.btn_clean_apk)

        btnClean.setOnClickListener {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val apkFiles = downloadsDir.listFiles { _, name ->
                ApkCleanup.isAppApk(name)
            } ?: emptyArray()

            val count = apkFiles.count { it.delete() }

            btnClean.isEnabled = false
            if (count > 0) {
                btnClean.text = getString(R.string.clean_apk_done, count)
                btnClean.backgroundTintList = ColorStateList.valueOf(
                    requireContext().getColor(R.color.dash_eco_dim))
                btnClean.strokeColor = ColorStateList.valueOf(
                    requireContext().getColor(R.color.dash_eco))
                btnClean.setTextColor(requireContext().getColor(R.color.dash_eco))
            } else {
                btnClean.text = getString(R.string.clean_apk_none)
            }

            btnClean.postDelayed({
                if (isAdded) {
                    btnClean.text = originalCleanText
                    btnClean.backgroundTintList = ColorStateList.valueOf(
                        requireContext().getColor(R.color.dash_btn))
                    btnClean.strokeColor = ColorStateList.valueOf(
                        requireContext().getColor(R.color.dash_border))
                    btnClean.setTextColor(requireContext().getColor(R.color.text_secondary))
                    btnClean.isEnabled = true
                }
            }, 3_000)
        }

        // ── Bouton Diagnostic (caché par défaut — débloqué par 5 clics sur MAJ) ──
        btnDiagnostic.setOnClickListener {
            showDiagnosticDialog()
        }

        // ── Bouton Infos ─────────────────────────────────────────────────────
        view.findViewById<MaterialButton>(R.id.btn_infos).setOnClickListener {
            showInfosDialog()
        }

        // ── Bouton Fermer ─────────────────────────────────────────────────────
        view.findViewById<MaterialButton>(R.id.btn_close_settings).setOnClickListener {
            findNavController().popBackStack(R.id.dashboardFragment, false)
        }
    }

    // ── Feedback "application à jour" sur le bouton ──────────────────────────

    private fun showUpToDate(btn: MaterialButton, originalText: String) {
        val ctx = requireContext()
        val ecoDim    = ctx.getColor(R.color.dash_eco_dim)
        val eco       = ctx.getColor(R.color.dash_eco)
        val accentDim = ctx.getColor(R.color.dash_accent_dim)
        val accent    = ctx.getColor(R.color.dash_accent)

        // Passe le bouton en vert "à jour"
        btn.text = getString(R.string.update_up_to_date)
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(ecoDim)
        btn.strokeColor        = android.content.res.ColorStateList.valueOf(eco)
        btn.setTextColor(eco)
        btn.isEnabled = false

        // Revient à l'état normal après 3 secondes
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

    // ── Feedback "erreur réseau" sur le bouton ────────────────────────────────

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

        // Sonde diagnostic : logge volume + état des portes AVANT de rendre les logs,
        // pour que le rapport les contienne (indépendant du toggle / de l'onglet Audio).
        MG4Hardware.runDoorVolumeDiag()

        val appVersion = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }

        // ── Layout : crash banner (optionnel) + rapport matériel ──────────────
        var btnClearCrash: Button? = null
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // "Partager" — envoi du rapport complet vers PrivateBin (moteur MG4Hardware).
        // En tête du contenu : la voiture n'a pas de cible de partage, et c'est le geste
        // qu'on cherche quand on vient d'ouvrir ce dialog pour envoyer un rapport.
        // Absent du build offline, qui ne déclare pas la permission INTERNET : proposer
        // l'envoi là-bas ne produirait qu'un échec.
        val btnShare = if (BuildConfig.OFFLINE) null else Button(ctx).apply {
            text = getString(R.string.diag_share)
        }
        btnShare?.let { container.addView(it) }

        // Section crash log (si un crash a été enregistré)
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

            // Séparateur
            val divider = android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * resources.displayMetrics.density).toInt()
                ).also { it.topMargin = 0; it.bottomMargin = 0 }
                setBackgroundColor(ctx.getColor(R.color.dash_border))
            }
            container.addView(divider)

            // "Effacer crash" déplacé dans le contenu (le slot neutre sert au Télécharger)
            btnClearCrash = Button(ctx).apply {
                text = getString(R.string.diag_clear_crash)
            }
            container.addView(btnClearCrash)
        }

        // Section rapport matériel
        val tvReport = TextView(ctx).apply {
            text = getString(R.string.diag_loading)
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setTextColor(ctx.getColor(R.color.text_secondary))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        container.addView(tvReport)

        // Section AppLogger en temps réel (30 dernières lignes)
        val tvLogs = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setTextColor(ctx.getColor(R.color.dash_text_lo))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, pad)
            val entries = AppLogger.entries
            text = if (entries.isEmpty()) "─── AppLogger vide ───"
                   else "─── AppLogger (${entries.size} entrées) ───\n" +
                        entries.takeLast(30).joinToString("\n") { e ->
                            "${e.time} [${e.level.name[0]}] ${e.tag}: ${e.msg}"
                        }
        }
        container.addView(tvLogs)

        val scrollView = ScrollView(ctx).apply {
            addView(container)
        }

        val title = if (crashLog != null)
            "⚠ ${getString(R.string.diag_title)} — CRASH DÉTECTÉ"
        else
            getString(R.string.diag_title)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(getString(R.string.diag_copy), null)
            .setNeutralButton(getString(R.string.diag_download), null)
            .setNegativeButton(getString(R.string.nav_close), null)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(ctx.getColor(R.color.dash_card)))

        // Rapport court (30 dernières lignes) pour le presse-papier ; rapport complet pour
        // le fichier et pour l'envoi.
        //
        // [leaving] écarte les lignes de partage : un envoi y laisse le lien et le mot de
        // passe du paste précédent, et le journal est recopié dans le rapport. Sans ce
        // filtre, le deuxième envoi livre le premier à son destinataire.
        fun buildReport(fullLog: Boolean, leaving: Boolean = false) = buildString {
            if (crashLog != null) { appendLine(crashLog); appendLine() }
            appendLine(tvReport.text)
            appendLine()
            if (fullLog) {
                val entries = AppLogger.entries.filterNot { leaving && it.tag == SHARE_TAG }
                appendLine("─── AppLogger (${entries.size} entrées) ───")
                entries.forEach { e -> appendLine("${e.time} [${e.level.name[0]}] ${e.tag}: ${e.msg}") }
            } else appendLine(tvLogs.text)
        }

        dialog.setOnShowListener {
            // "Copier" — copie tout sans fermer le dialog
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("MG4Control Diagnostic", buildReport(false)))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text = getString(R.string.diag_copied)
            }
            // "Télécharger" — écrit le rapport complet dans le dossier Download de la voiture
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                downloadDiagnostic(ctx, buildReport(true))
            }
            // "Partager" (dans le contenu) — envoi confirmé, le dialog reste ouvert
            btnShare?.setOnClickListener {
                confirmDiagnosticShare(ctx) { buildReport(fullLog = true, leaving = true) }
            }
            // "Effacer crash" (dans le contenu) — supprime le fichier et ferme le dialog
            btnClearCrash?.setOnClickListener {
                CrashLogger.clear(ctx)
                dialog.dismiss()
            }
        }

        dialog.show()

        // Génération du rapport matériel sur le thread IO
        CoroutineScope(Dispatchers.IO).launch {
            val report = MG4Hardware.buildDiagnosticReport(appVersion)
            withContext(Dispatchers.Main) {
                if (isAdded) tvReport.text = report
            }
        }
    }

    /**
     * Confirmation avant envoi : le rapport quitte la voiture pour un serveur public.
     * Chiffré et protégé par mot de passe, mais il part — ce n'est pas une décision à
     * prendre à la place de l'utilisateur parce qu'il a touché un bouton "Partager".
     */
    private fun confirmDiagnosticShare(ctx: Context, report: () -> String) {
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.diag_share))
            .setMessage(getString(R.string.diag_share_confirm, PasteConfig.HOST))
            .setNegativeButton(getString(R.string.nav_close), null)
            .setPositiveButton(getString(R.string.diag_share_send)) { _, _ ->
                uploadDiagnostic(ctx, report())
            }
            .show()
    }

    /**
     * Envoi vers PrivateBin, hors du thread principal.
     *
     * Le lien est écrit dans AppLogger, pas seulement affiché : un toast disparaît et la
     * voiture n'offre aucun moyen de rattraper une URL. Le journal, lui, se relit et se
     * retrouve dans le prochain rapport.
     */
    private fun uploadDiagnostic(ctx: Context, report: String) {
        Toast.makeText(ctx, getString(R.string.diag_share_running), Toast.LENGTH_SHORT).show()
        val appCtx = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val message = when (val outcome = PrivateBin.paste(report, PasteConfig.CONFIG)) {
                is PrivateBin.Outcome.Ok -> {
                    AppLogger.i(SHARE_TAG, "diagnostic envoyé — ${outcome.url}")
                    AppLogger.i(SHARE_TAG, "mot de passe ${PasteConfig.CONFIG.password}, expire dans 1 heure")
                    appCtx.getString(R.string.diag_share_ok)
                }
                is PrivateBin.Outcome.Failed -> {
                    AppLogger.w(SHARE_TAG, "envoi du diagnostic échoué — ${outcome.reason}")
                    appCtx.getString(R.string.diag_share_failed, outcome.reason)
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(appCtx, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Où part un rapport partagé.
     *
     * L'instance PrivateBin de Chapril, tenue par April, association française — pas de
     * compte, pas de pistage, et le serveur ne détient jamais la clé. Une heure est
     * volontairement court : assez pour envoyer le lien à qui aide, trop peu pour laisser
     * traîner le diagnostic d'une voiture.
     */
    private object PasteConfig {
        const val HOST = "paste.chapril.org"

        val CONFIG = PrivateBin.Config(
            baseUrl = "https://$HOST/",
            password = "mg4controlR0ck\$",
            expire = "1hour",
            formatter = "plaintext",
        )
    }

    /** Écrit le rapport de diagnostic dans le dossier Download de la voiture (fichier .txt horodaté). */
    private fun downloadDiagnostic(ctx: Context, report: String) {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val file = File(dir, "MG4Control_diag_$ts.txt")
            file.writeText(report)
            Toast.makeText(ctx, getString(R.string.diag_downloaded, file.absolutePath), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, getString(R.string.diag_download_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
        }
    }

    // ── Dialog À propos ──────────────────────────────────────────────────────

    private fun showInfosDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_app_info, null)

        // Version de l'app
        val versionName = try {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) { "1.0" }
        val tvVersion = dialogView.findViewById<TextView>(R.id.tv_app_version)
        tvVersion.text = versionName

        // Version firmware : même propriété système et même repli que la détection de
        // génération, donc lue par la librairie plutôt qu'une seconde fois ici.
        dialogView.findViewById<TextView>(R.id.tv_firmware_info).text =
            FirmwareInfo.getDetectedString().takeIf { it != "?" } ?: "N/A"

        // QR Code GitHub
        val ivQrGithub = dialogView.findViewById<ImageView>(R.id.iv_qr_code_github)
        QrCode.generate(githubUrl, 400)?.let { ivQrGithub.setImageBitmap(it) }

        // QR Code GitLab
        val ivQrGitlab = dialogView.findViewById<ImageView>(R.id.iv_qr_code_gitlab)
        QrCode.generate(gitlabUrl, 400)?.let { ivQrGitlab.setImageBitmap(it) }

        // Lien GitHub cliquable
        dialogView.findViewById<TextView>(R.id.tv_github_link).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
        }

        // Lien GitLab cliquable
        dialogView.findViewById<TextView>(R.id.tv_gitlab_link).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(gitlabUrl)))
        }

        // Création du dialog sans chrome Android (fond transparent = layout seul visible)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Bouton Fermer intégré dans le layout
        dialogView.findViewById<MaterialButton>(R.id.btn_info_close).setOnClickListener {
            dialog.dismiss()
        }

        // Trois appuis sur la version ouvrent le diagnostic. Geste caché volontairement :
        // le rapport contient le firmware et les logs, il n'a rien à faire sous le doigt
        // d'un passager qui explore l'écran.
        var taps = 0
        var lastTap = 0L
        tvVersion.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            // Une pause casse la série : trois appuis espacés dans le temps ne sont pas
            // le geste, ce sont trois appuis distincts sur un champ qui n'en attend aucun.
            taps = if (now - lastTap > VERSION_TAP_WINDOW_MS) 1 else taps + 1
            lastTap = now
            if (taps >= VERSION_TAPS_FOR_DIAGNOSTIC) {
                taps = 0
                dialog.dismiss()
                showDiagnosticDialog()
            }
        }

        dialog.show()
    }

    private companion object {
        const val SHARE_TAG = "MG4_SHARE"

        const val VERSION_TAPS_FOR_DIAGNOSTIC = 3
        const val VERSION_TAP_WINDOW_MS = 1_000L
    }
}
