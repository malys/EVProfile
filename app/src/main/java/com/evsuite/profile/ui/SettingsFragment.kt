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
     * Génération de firmware. L'indicateur vivait dans la barre du haut, où il occupait
     * la largeur de chaque écran pour une information qu'on lit une fois — et où ses
     * pastilles de quelques dp étaient invisibles à 70 cm. Il est ici, dans Véhicule,
     * en boutons de 72 dp. Ils ne deviennent cliquables que si la génération est inconnue
     * ou déjà forcée : forcer une génération change ce que l'application écrit dans la
     * voiture, ce n'est pas un réglage à effleurer par erreur.
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
            // Une génération qu'on ne peut pas choisir reste lisible, mais n'invite pas.
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
     * Rail de catégories : le volet de droite n'affiche qu'une catégorie à la fois, au lieu
     * de la colonne unique de cartes qu'il fallait dérouler pour savoir ce qu'elle contenait.
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
        // Stable : pas de réseau → le hook masque toute l'UI de mise à jour.
        // Channel-specific setup is delegated below. Stable hides this section and has no
        // updater classes; unstable owns both the preference and its OTA handlers.

        // ── Alimentation véhicule (SWI133) — éteint la voiture, garde l'écran ──
        val rowVehiclePower = view.findViewById<View>(R.id.row_vehicle_power)
        val dividerVehiclePower = view.findViewById<View>(R.id.row_vehicle_power_divider)
        val btnVehiclePower = view.findViewById<MaterialButton>(R.id.btn_vehicle_power_off)
        if (!EVHardware.hasVehiclePowerOff()) {
            rowVehiclePower.visibility = View.GONE
            dividerVehiclePower.visibility = View.GONE
        } else {
            btnVehiclePower.setOnClickListener {
                // Sécurité : on ne propose l'extinction que si le levier est confirmé en P.
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

        // ── Bouton Vérifier mise à jour ──────────────────────────────────────
        val btnUpdate     = view.findViewById<MaterialButton>(R.id.btn_check_update)
        val btnDiagnostic = view.findViewById<MaterialButton>(R.id.btn_diagnostic)
        val originalUpdateText = getString(R.string.btn_check_update)

        // Bouton Diagnostic débloqué via 5 clics sur le logo (cf. MainActivity)
        btnDiagnostic.visibility = if (MainActivity.diagnosticUnlocked) View.VISIBLE else View.GONE

        UpdateChannel.configureSettings(
            fragment = this,
            root = view,
            onNoUpdate = { showUpToDate(btnUpdate, originalUpdateText) },
            onError = { showUpdateError(btnUpdate, originalUpdateText) }
        )

        // ── Bouton Nettoyer APK ──────────────────────────────────────────────
        // ── Bouton Diagnostic (caché par défaut — débloqué par 5 clics sur MAJ) ──
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
     * EVTasker automatise ce que EVProfile règle à la main : le bouton l'ouvre, il ne le
     * remplace pas et ne propose pas de l'installer.
     *
     * Le clic revérifie l'intent plutôt que de faire confiance à l'affichage : entre le
     * moment où le bouton apparaît et celui où le doigt arrive, l'app peut avoir été
     * désinstallée, et un startActivity() sur un paquet absent ferait tomber EVProfile.
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
     * Réévaluée à chaque reprise : installer EVTasker pendant que EVProfile tourne ne doit
     * pas demander un redémarrage pour que le bouton apparaisse.
     */
    private fun refreshTaskerButton(view: View) {
        view.findViewById<MaterialButton>(R.id.btn_open_tasker).visibility =
            if (taskerLaunchIntent() != null) View.VISIBLE else View.GONE
    }

    /** L'intent de lancement de EVTasker, stable d'abord, instable ensuite. */
    private fun taskerLaunchIntent(): Intent? =
        TASKER_PACKAGES.firstNotNullOfOrNull { requireContext().packageManager.getLaunchIntentForPackage(it) }

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
        EVHardware.runDoorVolumeDiag()

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

        // "Partager" — envoi du rapport complet vers PrivateBin (moteur EVHardware).
        // En tête du contenu : la voiture n'a pas de cible de partage, et c'est le geste
        // qu'on cherche quand on vient d'ouvrir ce dialog pour envoyer un rapport.
        // Absent du canal stable, qui ne déclare pas la permission INTERNET : proposer
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

        val dialog = MaterialAlertDialogBuilder(ctx)
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
                cm.setPrimaryClip(ClipData.newPlainText("EVProfile Diagnostic", buildReport(false)))
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
            val report = EVHardware.buildDiagnosticReport(appVersion)
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
            password = "evprofileR0ck\$",
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
            val file = File(dir, "EVProfile_diag_$ts.txt")
            file.writeText(report)
            Toast.makeText(ctx, getString(R.string.diag_downloaded, file.absolutePath), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, getString(R.string.diag_download_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
        }
    }

    // ── Section À propos ─────────────────────────────────────────────────────

    /**
     * « À propos » n'est plus une fenêtre à ouvrir : c'est la dernière catégorie du rail,
     * au même titre que les autres. Rien n'y est actionnable en conduite, seulement lu.
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
                showDiagnosticDialog()
            }
        }
    }

    private companion object {
        const val SHARE_TAG = "EV_SHARE"

        const val VERSION_TAPS_FOR_DIAGNOSTIC = 3
        const val VERSION_TAP_WINDOW_MS = 1_000L

        /**
         * EVTasker, tous canaux confondus, du plus « officiel » au plus expérimental.
         *
         * Les suffixes historiques et actuels sont conservés pour détecter les installations
         * déjà diffusées. Stable et unstable s'installent côte à côte, et
         * un testeur qui n'a que le canal instable a bel et bien EVTasker — n'énumérer que
         * l'id stable revenait à lui masquer le bouton.
         */
        val TASKER_PACKAGES = listOf(
            "com.evsuite.tasker",
            "com.evsuite.tasker.offline",
            "com.evsuite.tasker.unstable",
            "com.evsuite.tasker.offline.unstable"
        )
    }
}
