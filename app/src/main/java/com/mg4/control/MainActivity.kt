package com.mg4.control

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.mg4.control.ui.AudioFragment
import com.mg4.control.ui.DashboardFragment
import com.mg4.control.ui.ProfileFragment
import com.mg4.control.ui.SettingsFragment
import com.mg4.control.ui.ShortcutsFragment
import com.mg4.hardware.MG4Hardware
import com.mg4.control.profile.ProfileManager
import com.mg4.control.service.MG4ControlService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mg4.control.update.UpdateChecker
import com.mg4.control.update.UpdateDialogManager
import com.mg4.hardware.FirmwareInfo
import com.mg4.control.util.LocaleHelper
import com.mg4.control.util.ThemeHelper

class MainActivity : AppCompatActivity() {

    /**
     * One screen of the application, in the order the top bar lists them.
     *
     * [buttonId] is the top-bar button that selects it — the same button the page-change
     * callback marks as current. The pair being declared once is what keeps the button and
     * the swipe agreeing about where they lead.
     */
    private class Screen(val buttonId: Int, val create: () -> Fragment)

    private lateinit var pager: ViewPager2

    /** Built in [setupNavButtons]; Audio is absent on firmwares with no vendor audio control. */
    private var screens: List<Screen> = emptyList()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Init firmware EN PREMIER — avant toute inflation de fragment
        // Charge le mode forcé éventuel depuis les prefs
        FirmwareInfo.initWithContext(this)

        // [THEME-AUTO] Recrée l'activité quand le launcher MG change de thème en mode "auto"
        ThemeHelper.onThemeChanged = { recreate() }

        // Premier lancement : choix de la langue avant tout
        if (LocaleHelper.isFirstLaunch(this)) {
            showLanguagePicker()
            return
        }

        setContentView(R.layout.activity_main)

        startForegroundService(Intent(this, MG4ControlService::class.java))
        MG4Hardware.initAudio(applicationContext)  // connecte le helper audio vendor (A9 uniquement, no-op ailleurs)

        pager = findViewById(R.id.main_pager)

        setupNavButtons()
        setupDiagnosticUnlock()
        checkUnknownFirmware()
        navigateToDefaultScreen(savedInstanceState)
        checkForUpdates()
        checkProfileRestore()
    }

    // ── Restauration des profils depuis la sauvegarde (après réinstallation) ──────
    // Ne se déclenche QUE si l'app n'a aucun profil local (vraie désinstallation /
    // effacement de données — pas une simple mise à jour qui conserve les données).
    private fun checkProfileRestore() {
        val pm = ProfileManager(this)
        if (pm.getAll().isNotEmpty()) return
        val settings = getSharedPreferences("mg4_settings", MODE_PRIVATE)
        if (settings.getBoolean("restore_prompt_dismissed", false)) return

        CoroutineScope(Dispatchers.IO).launch {
            val backup = pm.readBackup()
            if (backup == null || backup.profiles.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.profile_restore_title)
                    .setMessage(getString(R.string.profile_restore_msg, backup.profiles.size))
                    .setCancelable(false)
                    .setNegativeButton(R.string.profile_restore_cancel) { _, _ ->
                        settings.edit().putBoolean("restore_prompt_dismissed", true).apply()
                    }
                    .setPositiveButton(R.string.profile_restore_confirm) { _, _ ->
                        CoroutineScope(Dispatchers.IO).launch {
                            val n = pm.restoreFrom(backup)
                            withContext(Dispatchers.Main) {
                                if (!isFinishing && !isDestroyed) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        getString(R.string.profile_restore_done, n),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                    .show()
            }
        }
    }

    // ── Déblocage du bouton Diagnostic (5 clics sur le logo) ────────────────

    private var logoClickCount = 0

    private fun setupDiagnosticUnlock() {
        findViewById<View>(R.id.topbar_logo)?.setOnClickListener {
            if (diagnosticUnlocked) return@setOnClickListener
            logoClickCount++
            if (logoClickCount >= 5) {
                logoClickCount = 0
                diagnosticUnlocked = true
                // Révèle immédiatement le bouton si l'onglet Réglages est déjà affiché
                findViewById<View>(R.id.btn_diagnostic)?.visibility = View.VISIBLE
                Toast.makeText(this, getString(R.string.diagnostic_unlocked), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Navigation vers l'écran par défaut au démarrage ─────────────────────

    private fun navigateToDefaultScreen(savedInstanceState: android.os.Bundle?) {
        // Ne naviguer que si c'est un vrai démarrage (pas une rotation / recreate)
        if (savedInstanceState != null) return
        val prefs = getSharedPreferences("mg4_settings", android.content.Context.MODE_PRIVATE)
        val buttonId = when (prefs.getString("default_screen", "dashboard")) {
            "profiles"  -> R.id.btn_nav_profiles
            "shortcuts" -> R.id.btn_nav_shortcuts
            else        -> return  // "dashboard" → c'est déjà la première page
        }
        val index = screens.indexOfFirst { it.buttonId == buttonId }
        // Sans animation : au démarrage il n'y a pas de mouvement à expliquer, seulement un
        // écran de départ.
        if (index >= 0) pager.setCurrentItem(index, false)
    }

    // ── Vérification de mise à jour au démarrage ──────────────────────────────

    private fun checkForUpdates() {
        if (BuildConfig.OFFLINE) return  // build offline : aucune vérif réseau
        val prefs = getSharedPreferences("mg4_settings", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_check_update", true)) return

        UpdateChecker.check(
            context = this,
            onUpdateAvailable = { updateInfo ->
                if (!isFinishing && !isDestroyed) {
                    UpdateDialogManager.show(this, updateInfo)
                }
            }
            // onNoUpdate et onError ignorés au démarrage — silencieux si tout va bien
        )
    }

    // ── Dialog firmware non reconnu ───────────────────────────────────────────

    private fun checkUnknownFirmware() {
        // Ne montre le dialog que si le firmware est inconnu ET pas encore de choix forcé
        if (FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN) return
        if (FirmwareInfo.isForced(this)) return

        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_unknown_firmware, null)

        // Affiche la chaîne firmware brute dans le badge (ex: "SWI69-12345")
        dialogView.findViewById<TextView>(R.id.tv_fw_detected_badge).text =
            FirmwareInfo.getDetectedString()

        val dialog = AlertDialog.Builder(this, R.style.Theme_MG4_Picker)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btn_fw_close_app).setOnClickListener {
            finishAffinity()
        }

        dialogView.findViewById<MaterialButton>(R.id.btn_fw_continue).setOnClickListener {
            dialog.dismiss()
            // L'utilisateur peut maintenant taper sur les chips SWI133/SWI68
        }

        dialog.show()
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    // ── Boutons de navigation dans la top-bar ─────────────────────────────────

    /**
     * Wires the top bar to the pager: every button is a page, and every page is a button.
     *
     * There is no "close" anywhere any more. Profils, Réglages and Raccourcis used to be
     * sub-screens reached by a button and left by a Fermer button in their own bottom row —
     * a row that cost 72 dp on a 480 dp panel and existed only to undo the previous tap. As
     * pages they are left the way they were reached: another button, or a swipe.
     */
    private fun setupNavButtons() {
        // Bouton Audio : contrôle vendor caradapter dispo uniquement sur A9. Ailleurs, ni
        // bouton ni page — un écran vide atteignable au balayage serait pire que son absence.
        val hasAudio = MG4Hardware.hasAudioControl()
        findViewById<MaterialButton>(R.id.btn_nav_audio).visibility =
            if (hasAudio) View.VISIBLE else View.GONE

        screens = buildList {
            add(Screen(R.id.dashboard_tab_controls) {
                DashboardFragment.newInstance(DashboardFragment.PAGE_CONTROLS)
            })
            add(Screen(R.id.dashboard_tab_elk) {
                DashboardFragment.newInstance(DashboardFragment.PAGE_ELK)
            })
            if (hasAudio) add(Screen(R.id.btn_nav_audio) { AudioFragment() })
            add(Screen(R.id.btn_nav_profiles) { ProfileFragment() })
            add(Screen(R.id.btn_nav_shortcuts) { ShortcutsFragment() })
            add(Screen(R.id.btn_nav_settings) { SettingsFragment() })
        }

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = screens.size
            override fun createFragment(position: Int) = screens[position].create()
        }
        // Only the neighbours are kept alive. Holding all six would mean six vehicle-reading
        // fragments refreshing at once on a head unit that has other work to do.
        pager.offscreenPageLimit = 1

        screens.forEachIndexed { index, screen ->
            findViewById<MaterialButton>(screen.buttonId).setOnClickListener {
                // Animated, so the button does the same thing the swipe does: the direction
                // of travel is what tells the driver where they are in the row.
                pager.setCurrentItem(index, true)
            }
        }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = markCurrentPage(position)
        })
        markCurrentPage(0)
    }

    /**
     * Marks the top-bar tab of the page on screen.
     *
     * Only `isSelected` is set: fill, text, icon and stroke come from the
     * res/color/nav_tab_*.xml selectors applied by Widget.MG4.NavTab. Painting the
     * colours here as well would declare them twice, and the code copy would win even
     * for states it does not understand (pressed and disabled). `isSelected` also lets
     * TalkBack announce the current destination instead of relying on colour alone.
     */
    private fun markCurrentPage(position: Int) {
        screens.forEachIndexed { index, screen ->
            findViewById<MaterialButton>(screen.buttonId).isSelected = index == position
        }
    }

    // ── Dialogue de choix de langue au premier lancement ─────────────────────

    private fun showLanguagePicker() {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_language_picker, null)

        val dialog = AlertDialog.Builder(this, R.style.Theme_MG4_Picker)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val buttons = mapOf(
            R.id.btn_pick_fr to "fr",
            R.id.btn_pick_en to "en",
            R.id.btn_pick_de to "de",
            R.id.btn_pick_es to "es",
            R.id.btn_pick_pt to "pt",
            R.id.btn_pick_it to "it"
        )
        buttons.forEach { (viewId, code) ->
            dialogView.findViewById<MaterialButton>(viewId).setOnClickListener {
                dialog.dismiss()
                LocaleHelper.setLanguage(this, code)
                recreate()
            }
        }

        dialog.show()
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    companion object {
        /**
         * Débloqué via 5 clics sur le logo en haut à gauche. En mémoire uniquement :
         * réinitialisé au redémarrage du process (le bouton Diagnostic reste masqué par défaut).
         */
        @Volatile var diagnosticUnlocked = false
    }
}
