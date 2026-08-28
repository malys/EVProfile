package com.evsuite.profile

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.evsuite.profile.ui.AudioFragment
import com.evsuite.profile.ui.DashboardFragment
import com.evsuite.profile.ui.ProfileFragment
import com.evsuite.profile.ui.SettingsFragment
import com.evsuite.profile.ui.ShortcutsFragment
import com.evsuite.hardware.EVHardware
import com.evsuite.profile.profile.ProfileManager
import com.evsuite.profile.service.EVProfileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.profile.util.LocaleHelper
import com.evsuite.profile.util.ThemeHelper
import com.evsuite.profile.update.UpdateChannel

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

    /**
     * The two runtime permissions that the app declared without ever asking for them.
     *
     * On Android, a permission declared and never requested is not held: the
     * service notification was silently deleted, the list of paired devices
     * was returning empty, and the service type `connectedDevice` — which requires BLUETOOTH_CONNECT
     * since API 34 — was not covered. A refusal only costs the function concerned,
     * so nothing awaits the response.
     */
    private val startupPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // Granted Bluetooth access changes the type of service that EVProfileService can
            // hold: restarting it is what applies it without waiting for the next start.
            if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) {
                startForegroundService(Intent(this, EVProfileService::class.java))
            }
        }

    private fun requestStartupPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) startupPermissions.launch(missing.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize firmware first, before inflating any fragment.
        // Load any forced mode from the prefs
        FirmwareInfo.initWithContext(this)

        // [THEME-AUTO] Recreates the activity when the MG launcher changes theme in “auto” mode
        ThemeHelper.onThemeChanged = { recreate() }

        // First launch: choice of language above all
        if (LocaleHelper.isFirstLaunch(this)) {
            showLanguagePicker()
            return
        }

        setContentView(R.layout.activity_main)

        requestStartupPermissions()
        startForegroundService(Intent(this, EVProfileService::class.java))
        EVHardware.initAudio(applicationContext)  // connects the audio vendor helper (A9 only, no-op elsewhere)

        pager = findViewById(R.id.main_pager)

        setupNavButtons()
        setupDiagnosticUnlock()
        checkUnknownFirmware()
        navigateToDefaultScreen(savedInstanceState)
        checkForUpdates()
        checkProfileRestore()
    }

    // ── Restoring profiles from backup (after reinstallation) ──────
    // ONLY triggers if the app has no local profile (real uninstallation /
    // data erasure — not a simple update that retains the data).
    private fun checkProfileRestore() {
        val pm = ProfileManager(this)
        if (pm.getAll().isNotEmpty()) return
        val settings = getSharedPreferences("ev_settings", MODE_PRIVATE)
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

    // ── Unlocking the Diagnostic button (5 clicks on the logo) ────────────────

    private var logoClickCount = 0

    private fun setupDiagnosticUnlock() {
        findViewById<View>(R.id.topbar_logo)?.setOnClickListener {
            if (diagnosticUnlocked) return@setOnClickListener
            logoClickCount++
            if (logoClickCount >= 5) {
                logoClickCount = 0
                diagnosticUnlocked = true
                // Immediately reveals the button if the Settings tab is already displayed
                findViewById<View>(R.id.btn_diagnostic)?.visibility = View.VISIBLE
                Toast.makeText(this, getString(R.string.diagnostic_unlocked), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Navigate to default screen on startup ─────────────────────

    private fun navigateToDefaultScreen(savedInstanceState: android.os.Bundle?) {
        // Only navigate if it's a real start (not a rotation/recreate)
        if (savedInstanceState != null) return
        val prefs = getSharedPreferences("ev_settings", android.content.Context.MODE_PRIVATE)
        val buttonId = when (prefs.getString("default_screen", "dashboard")) {
            "profiles"  -> R.id.btn_nav_profiles
            "shortcuts" -> R.id.btn_nav_shortcuts
            else        -> return  // "dashboard" → it's already the first page
        }
        val index = screens.indexOfFirst { it.buttonId == buttonId }
        // Without animation: at start-up there is no movement to explain, only a
        // starting screen.
        if (index >= 0) pager.setCurrentItem(index, false)
    }

    // ── Update check at startup ──────────────────────────────

    private fun checkForUpdates() {
        UpdateChannel.checkAtStartup(this)
    }

    // ── Dialog firmware non reconnu ───────────────────────────────────────────

    private fun checkUnknownFirmware() {
        // Only shows the dialog if the firmware is unknown AND no forced choice yet
        if (FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN) return
        if (FirmwareInfo.isForced(this)) return

        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_unknown_firmware, null)

        // Displays the raw firmware string in the badge (ex: "SWI69-12345")
        dialogView.findViewById<TextView>(R.id.tv_fw_detected_badge).text =
            FirmwareInfo.getDetectedString()

        val dialog = AlertDialog.Builder(this, R.style.Theme_EV_Picker)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btn_fw_close_app).setOnClickListener {
            finishAffinity()
        }

        dialogView.findViewById<MaterialButton>(R.id.btn_fw_continue).setOnClickListener {
            dialog.dismiss()
            // User can now tap on SWI133/SWI68 chips
        }

        dialog.show()
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    // ── Navigation buttons in the top bar ─────────────────────────────────

    /**
     * Wires the top bar to the pager: every button is a page, and every page is a button.
     *
     * There is no "close" anywhere any more. Profiles, Settings and Shortcuts used to be
     * sub-screens reached by a button and left by a Fermer button in their own bottom row —
     * a row that cost 72 dp on a 480 dp panel and existed only to undo the previous tap. As
     * pages they are left the way they were reached: another button, or a swipe.
     */
    private fun setupNavButtons() {
        // Audio button: vendor caradaptor control only available on A9. Elsewhere, neither
        // button or page — a blank screen reachable by scanning would be worse than its absence.
        val hasAudio = EVHardware.hasAudioControl()
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
     * res/color/nav_tab_*.xml selectors applied by Widget.EV.NavTab. Painting the
     * colours here as well would declare them twice, and the code copy would win even
     * for states it does not understand (pressed and disabled). `isSelected` also lets
     * TalkBack announce the current destination instead of relying on colour alone.
     */
    private fun markCurrentPage(position: Int) {
        screens.forEachIndexed { index, screen ->
            findViewById<MaterialButton>(screen.buttonId).isSelected = index == position
        }
    }

    // ── First-launch language picker ─────────────────────────────────────────

    private fun showLanguagePicker() {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_language_picker, null)

        val dialog = AlertDialog.Builder(this, R.style.Theme_EV_Picker)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val buttons = mapOf(
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
         * Unlocked via 5 clicks on the logo at the top left. In memory only:
         * reset when the process restarts (the Diagnostic button remains hidden by default).
         */
        @Volatile var diagnosticUnlocked = false
    }
}
