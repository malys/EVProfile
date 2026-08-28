package com.evsuite.profile.service

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.evsuite.hardware.VehicleWriteGate
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.evsuite.profile.R
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.model.DrivingProfile
import com.evsuite.profile.profile.ProfileApplier
import com.evsuite.profile.profile.ProfileManager
import com.evsuite.profile.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Floating overlay displaying the list of driving profiles.
 *
 * Deux modes d'utilisation :
 *  - Steering-wheel shortcut → show(ctx): displays all profiles
 *  - Bluetooth conflict      → show(ctx, profiles, onAutoDismiss): displays only
 *    profiles associated with connected devices; if the user does not choose
 *    not before the timeout, [onAutoDismiss] is called (e.g. applies the 1st profile).
 *
 * All WindowManager operations are done on the main thread.
 */
object ProfilePickerOverlay {

    private const val TAG             = "EV_OVERLAY"
    private const val AUTO_DISMISS_MS = 8_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null

    // ── API publique ─────────────────────────────────────────────────────────

    /**
     * Displays the overlay with all profiles (flying shortcut).
     * Can be called from any thread.
     */
    fun show(context: Context) {
        handler.post { showOnMainThread(context, profiles = null, onAutoDismiss = null) }
    }

    /**
     * Shows the overlay with a restricted list of profiles (BT conflict).
     * [onAutoDismiss] is called if the timeout elapses without selection.
     * Can be called from any thread.
     */
    fun show(context: Context, profiles: List<DrivingProfile>, onAutoDismiss: () -> Unit) {
        handler.post { showOnMainThread(context, profiles, onAutoDismiss) }
    }

    /**
     * Closes the overlay immediately (without triggering onAutoDismiss).
     * Can be called from any thread.
     */
    fun dismiss(context: Context) {
        handler.post { dismissOnMainThread(context, fireAutoDismiss = false) }
    }

    // ── Implementation (main thread) ──────────────────── ─────────────────────

    private fun showOnMainThread(
        context: Context,
        profiles: List<DrivingProfile>?,
        onAutoDismiss: (() -> Unit)?
    ) {
        // [T-904] The overlay is only used to apply a profile, therefore to write in the
        // vehicle: useless and dangerous to place it in front of the driver while driving.
        // Same policy as writing - also refusal if the speed is illegible.
        if (VehicleWriteGate.decide(EVHardware.getVehicleSpeedKmh())
                != VehicleWriteGate.Decision.ALLOWED) {
            AppLogger.w(TAG, "Overlay not shown: vehicle is not stopped")
            onAutoDismiss?.invoke()
            return
        }

        // If already displayed → we replace (without triggering the old onAutoDismiss)
        dismissOnMainThread(context, fireAutoDismiss = false)

        val profilesToShow = profiles ?: ProfileManager(context).getAll()
        if (profilesToShow.isEmpty()) {
            AppLogger.i(TAG, "No profiles — overlay not shown")
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // The context of the Service does not apply the language chosen in the app
        // (LocaleHelper is only placed on MainActivity/EVApp). Without that, the popup
        // falls on the system language. We wrap with the current locale (reading
        // fresh → reflects a language change during the session).
        val localizedContext = LocaleHelper.applyLocale(context)

        // The context of the Service does not have a Material theme → we wrap it
        // with the app theme so that MaterialButton can instantiate itself.
        val themedContext = ContextThemeWrapper(localizedContext, R.style.Theme_EVProfile)

        // Inflate the view from the XML layout (uses the themed context)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_profile_picker, null)

        // ── Two-column profile grid ──────────────────────────────────────
        val container      = view.findViewById<LinearLayout>(R.id.overlay_profiles_container)
        val accentColor    = context.getColor(R.color.dash_accent)
        val accentDimColor = context.getColor(R.color.dash_accent_dim)
        val dm             = context.resources.displayMetrics

        fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, dm).toInt()

        fun makeProfileButton(profile: com.evsuite.hardware.model.DrivingProfile) =
            MaterialButton(themedContext).apply {
                text      = profile.name
                textSize  = 19f
                isAllCaps = false
                setTextColor(accentColor)
                backgroundTintList = ColorStateList.valueOf(accentDimColor)
                strokeColor        = ColorStateList.valueOf(accentColor)
                strokeWidth        = dp(1f)
                cornerRadius       = dp(12f)
                setOnClickListener {
                    AppLogger.i(TAG, "Profile selected: '${profile.name}'")
                    CoroutineScope(Dispatchers.IO).launch {
                        ProfileApplier.apply(profile)
                    }
                    dismissOnMainThread(context)
                }
            }

        // Cut into lines of 2, each line = horizontal LinearLayout
        profilesToShow.chunked(2).forEach { row ->
            val rowLayout = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(10f) }
            }

            row.forEachIndexed { index, profile ->
                val btn = makeProfileButton(profile).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(90f), 1f).also {
                        if (index == 0 && row.size == 2) it.marginEnd = dp(10f)
                    }
                }
                rowLayout.addView(btn)
            }

            // Odd number → invisible placeholder to keep symmetry
            if (row.size == 1) {
                val spacer = android.view.View(themedContext).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(90f), 1f)
                }
                rowLayout.addView(spacer)
            }

            container.addView(rowLayout)
        }

        // ── Fermeture manuelle ────────────────────────────────────────────
        view.findViewById<View>(R.id.overlay_btn_close)?.setOnClickListener {
            dismissOnMainThread(context)
        }

        // ── “Turn off car” button (centered, gated firmware) ────────
        val btnPowerOff = view.findViewById<MaterialButton>(R.id.overlay_btn_poweroff)
        if (!EVHardware.hasVehiclePowerOff()) {
            btnPowerOff?.visibility = View.GONE
        } else {
            btnPowerOff?.setOnClickListener {
                dismissOnMainThread(context)              // closes the profile picker
                showVehiclePowerOffConfirm(context)       // P-check + confirmation
            }
        }

        // ── Tap on the bottom → close ───────────────────────────────────
        view.findViewById<View>(R.id.overlay_backdrop)?.setOnClickListener {
            dismissOnMainThread(context)
        }
        // The interior card intercepts supports without propagating to the bottom
        view.findViewById<View>(R.id.overlay_card)?.setOnClickListener { /* consommer */ }

        // ── WindowManager Settings ──────────────────────────────────────
        // The window is focusable: the overlay requires a choice, so it must enter
        // the focus order and be readable by TalkBack. FLAG_NOT_FOCUSABLE excluded it.
        // In return it receives the keys: RETURN closes it, like a press on the bottom.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismissOnMainThread(context); true
            } else false
        }

        wm.addView(view, params)
        view.requestFocus()
        overlayView = view
        AppLogger.i(TAG, "Overlay shown — ${profilesToShow.size} profile(s)")

        // ── Countdown ─────────────────────── ───────────────────────
        val tvCountdown = view.findViewById<TextView>(R.id.overlay_countdown)
        var remaining = (AUTO_DISMISS_MS / 1_000L).toInt()

        val tick: Runnable = object : Runnable {
            override fun run() {
                if (overlayView == null) return
                tvCountdown?.text = localizedContext.getString(R.string.overlay_countdown, remaining)
                if (remaining > 0) {
                    remaining--
                    handler.postDelayed(this, 1_000L)
                }
            }
        }
        countdownRunnable = tick
        handler.post(tick)

        // ── Fermeture automatique ─────────────────────────────────────────
        // onAutoDismiss is ONLY called here (timeout without selection).
        // If the user chooses a profile or presses Close,
        // dismissOnMainThread(fireAutoDismiss=false) annule ce runnable.
        val dr = Runnable {
            AppLogger.i(TAG, "Overlay — timeout, fallback onAutoDismiss")
            dismissOnMainThread(context, fireAutoDismiss = false)
            onAutoDismiss?.invoke()
        }
        dismissRunnable = dr
        handler.postDelayed(dr, AUTO_DISMISS_MS)

        // Re-arms the two timers (called at each brightness interaction to
        // do not close the popup while setting).
        fun resetTimers() {
            handler.removeCallbacks(dr)
            handler.postDelayed(dr, AUTO_DISMISS_MS)
            countdownRunnable?.let { handler.removeCallbacks(it) }
            remaining = (AUTO_DISMISS_MS / 1_000L).toInt()
            handler.post(tick)
        }

        // ── Brightness block (old SDK SWI133/68/165; A9 = phase 2) ─────
        val briSection = view.findViewById<View>(R.id.overlay_brightness_section)
        if (!EVHardware.hasBrightnessControl()) {
            briSection?.visibility = View.GONE
        } else {
            val slider   = view.findViewById<Slider>(R.id.overlay_bri_slider)
            val briValue = view.findViewById<TextView>(R.id.overlay_bri_value)

            fun applyBrightnessAsync(pct: Int) {
                CoroutineScope(Dispatchers.IO).launch { EVHardware.setScreenBrightnessPercent(pct) }
            }
            // Debounce writes while dragging (avoids spamming the binder)
            val pendingApply = Runnable { slider?.let { applyBrightnessAsync(it.value.toInt()) } }

            slider?.addOnChangeListener { _, value, fromUser ->
                briValue?.text = "${value.toInt()}%"
                if (fromUser) {
                    resetTimers()
                    handler.removeCallbacks(pendingApply)
                    handler.postDelayed(pendingApply, 60L)
                }
            }
            slider?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: Slider) { resetTimers() }
                override fun onStopTrackingTouch(s: Slider) {
                    handler.removeCallbacks(pendingApply)
                    applyBrightnessAsync(s.value.toInt())   // application finale
                    resetTimers()
                }
            })

            // Presets — move the cursor (updates the label via the listener) then apply
            fun preset(pct: Int) {
                slider?.value = pct.toFloat()
                applyBrightnessAsync(pct)
                resetTimers()
            }
            view.findViewById<View>(R.id.overlay_bri_night)?.setOnClickListener { preset(15) }
            view.findViewById<View>(R.id.overlay_bri_mid)?.setOnClickListener   { preset(50) }
            view.findViewById<View>(R.id.overlay_bri_day)?.setOnClickListener   { preset(100) }

            // Initialization from the current value (binder reading in background)
            briValue?.text = "…"
            CoroutineScope(Dispatchers.IO).launch {
                val cur = EVHardware.getScreenBrightnessPercent()
                handler.post {
                    if (overlayView == null) return@post
                    if (cur >= 0) slider?.value = cur.coerceIn(5, 100).toFloat()  // label updated by listener
                    else briValue?.text = "--%"
                }
            }
        }
    }

    /**
     * “Turn off the car” from the overlay: checks the P position (gear reading),
     * then displays the SAME confirmation dialog as Settings/shortcut, in
     * overlay window. If not in P → Toast. `vehiclePowerOff()` re-checks the P on sending.
     */
    private fun showVehiclePowerOffConfirm(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val inPark = EVHardware.isVehicleInPark()
            handler.post {
                if (inPark == true) {
                    val themed = ContextThemeWrapper(LocaleHelper.applyLocale(context), R.style.Theme_EVProfile)
                    val dialog = MaterialAlertDialogBuilder(themed)
                        .setTitle(R.string.vehicle_power_dialog_title)
                        .setMessage(R.string.vehicle_power_dialog_msg)
                        .setNegativeButton(R.string.vehicle_power_dialog_cancel, null)
                        .setPositiveButton(R.string.vehicle_power_dialog_confirm) { _, _ ->
                            CoroutineScope(Dispatchers.IO).launch {
                                val ok = EVHardware.vehiclePowerOff()
                                AppLogger.i(TAG, "OVERLAY VEHICLE_POWER_OFF confirmed → $ok")
                            }
                        }
                        .create()
                    dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    dialog.show()
                } else {
                    Toast.makeText(context, R.string.vehicle_power_need_park, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun dismissOnMainThread(context: Context, fireAutoDismiss: Boolean = false) {
        dismissRunnable?.let  { handler.removeCallbacks(it) }
        countdownRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable   = null
        countdownRunnable = null

        val v = overlayView ?: return
        overlayView = null
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
            AppLogger.i(TAG, "Overlay closed")
        } catch (e: Exception) {
            AppLogger.i(TAG, "Erreur fermeture overlay : ${e.message}")
        }
    }
}
