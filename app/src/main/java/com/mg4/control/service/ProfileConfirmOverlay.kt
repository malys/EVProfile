package com.mg4.control.service

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
import com.mg4.control.R
import com.mg4.control.automation.AutomationSettings
import com.mg4.hardware.AppLogger
import com.mg4.hardware.VehicleWriteGate
import com.mg4.hardware.model.DrivingProfile
import com.mg4.control.util.LocaleHelper

/**
 * Popup OUI/NON demandant s'il faut appliquer [profile] car la temp ext dépasse un seuil.
 * Calqué sur ProfilePickerOverlay (fenêtre overlay, compte à rebours 8 s, verrou 0 km/h).
 * OUI → onConfirmed ; NON ou timeout → onDeclined (une seule fois).
 */
object ProfileConfirmOverlay {

    private const val TAG = "MG4_OVERLAY"
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
        // En roulant (verrou actif) : pas d'écriture → on décline directement (fallback BT/défaut).
        if (VehicleWriteGate.decideNow() != VehicleWriteGate.Decision.ALLOWED) {
            AppLogger.w(TAG, "Confirm non affiché : sécurité conduite active → onDeclined")
            onDeclined(); return
        }
        dismiss(context)

        val localized = LocaleHelper.applyLocale(context)
        val themed = ContextThemeWrapper(localized, R.style.Theme_MG4Control)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_profile_confirm, null)

        val tempStr = String.format(java.util.Locale.getDefault(), "%.1f", currentTemp)
        val msgRes = if (direction == AutomationSettings.Direction.ABOVE)
            R.string.automation_confirm_msg_above else R.string.automation_confirm_msg
        view.findViewById<TextView>(R.id.confirm_message).text =
            localized.getString(msgRes, threshold, tempStr, profile.name)

        // Un seul chemin de sortie : garde-fou pour ne déclencher qu'un callback.
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
        // Focusable : c'est une confirmation d'écriture véhicule, elle doit être atteignable
        // au clavier et annoncée par TalkBack. RETOUR vaut « Non », comme le fond.
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
        AppLogger.i(TAG, "Confirm affiché pour '${profile.name}'")

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
            AppLogger.i(TAG, "Confirm fermé")
        } catch (e: Exception) {
            AppLogger.i(TAG, "Erreur fermeture confirm : ${e.message}")
        }
    }
}
