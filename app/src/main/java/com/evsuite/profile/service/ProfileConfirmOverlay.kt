package com.evsuite.profile.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.evsuite.profile.R
import com.evsuite.profile.automation.AutomationSettings
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.VehicleWriteGate
import com.evsuite.hardware.model.DrivingProfile
import com.evsuite.profile.util.LocaleHelper

/**
 * YES/NO popup asking whether to apply [profile] because the ext temp exceeds a threshold.
 * Modeled after ProfilePickerOverlay (window overlay, 8 sec countdown, 0 km/h lock).
 * YES → onConfirmed; NO or timeout → onDeclined (exactly once).
 */
object ProfileConfirmOverlay {

    private const val TAG = "EV_OVERLAY"
    private const val AUTO_DISMISS_MS = 8_000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null

    fun show(
        context: Context,
        profile: DrivingProfile,
        threshold: Int,
        currentTemp: Float,
        direction: AutomationSettings.Direction,
        onConfirmed: () -> Unit,
        onDeclined: () -> Unit
    ) {
        handler.post { showOnMain(context, profile, threshold, currentTemp, direction, onConfirmed, onDeclined) }
    }

    private fun showOnMain(
        context: Context,
        profile: DrivingProfile,
        threshold: Int,
        currentTemp: Float,
        direction: AutomationSettings.Direction,
        onConfirmed: () -> Unit,
        onDeclined: () -> Unit
    ) {
        // While driving (active lock): no writing → we decline directly (BT fallback/default).
        if (VehicleWriteGate.decideNow() != VehicleWriteGate.Decision.ALLOWED) {
            AppLogger.w(TAG, "Confirmation not shown: driving safety gate active → onDeclined")
            onDeclined(); return
        }
        dismiss(context)

        val localized = LocaleHelper.applyLocale(context)
        val themed = ContextThemeWrapper(localized, R.style.Theme_EVProfile)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_profile_confirm, null)

        val tempStr = String.format(java.util.Locale.getDefault(), "%.1f", currentTemp)
        val msgRes = if (direction == AutomationSettings.Direction.ABOVE)
            R.string.automation_confirm_msg_above else R.string.automation_confirm_msg
        view.findViewById<TextView>(R.id.confirm_message).text =
            localized.getString(msgRes, threshold, tempStr, profile.name)

        // A single exit path: safeguard to only trigger one callback.
        var done = false
        fun finish(confirmed: Boolean) {
            if (done) return
            done = true
            dismiss(context)
            if (confirmed) onConfirmed() else onDeclined()
        }

        view.findViewById<MaterialButton>(R.id.confirm_btn_yes).setOnClickListener { finish(true) }
        view.findViewById<MaterialButton>(R.id.confirm_btn_no).setOnClickListener { finish(false) }
        view.findViewById<View>(R.id.confirm_backdrop).setOnClickListener { finish(false) }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // Focusable: this is a confirmation of vehicle writing, it must be reachable
        // on the keyboard and announced by TalkBack. RETURN is “No”, like the background.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                finish(false); true
            } else false
        }

        wm.addView(view, params)
        view.requestFocus()
        overlayView = view
        AppLogger.i(TAG, "Confirmation shown for '${profile.name}'")

        val tvCountdown = view.findViewById<TextView>(R.id.confirm_countdown)
        var remaining = (AUTO_DISMISS_MS / 1_000L).toInt()
        val tick = object : Runnable {
            override fun run() {
                if (overlayView == null) return
                tvCountdown.text = localized.getString(R.string.overlay_countdown, remaining)
                if (remaining > 0) { remaining--; handler.postDelayed(this, 1_000L) }
            }
        }
        countdownRunnable = tick
        handler.post(tick)

        val dr = Runnable {
            AppLogger.i(TAG, "Confirm — timeout → onDeclined")
            finish(false)
        }
        dismissRunnable = dr
        handler.postDelayed(dr, AUTO_DISMISS_MS)
    }

    private fun dismiss(context: Context) {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        countdownRunnable = null
        val v = overlayView ?: return
        overlayView = null
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            AppLogger.i(TAG, "Confirmation closed")
        } catch (e: Exception) {
            AppLogger.i(TAG, "Erreur fermeture confirm : ${e.message}")
        }
    }
}
