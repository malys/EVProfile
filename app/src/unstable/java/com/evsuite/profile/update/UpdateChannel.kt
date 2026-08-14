package com.evsuite.profile.update

import android.content.Context
import android.content.res.ColorStateList
import android.os.Environment
import android.view.View
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.evsuite.profile.R

/** Unstable-only entry point for startup checks and the settings OTA controls. */
object UpdateChannel {
    private const val PREFS = "ev_settings"
    private const val AUTO_CHECK = "auto_check_update"

    fun checkAtStartup(activity: AppCompatActivity) {
        val enabled = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(AUTO_CHECK, true)
        if (!enabled) return

        UpdateChecker.check(
            context = activity,
            onUpdateAvailable = { info ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    UpdateDialogManager.show(activity, info)
                }
            }
        )
    }

    fun configureSettings(
        fragment: Fragment,
        root: View,
        onNoUpdate: () -> Unit,
        onError: () -> Unit
    ) {
        val context = fragment.requireContext()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        root.findViewById<Switch>(R.id.switch_auto_update).apply {
            isChecked = prefs.getBoolean(AUTO_CHECK, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(AUTO_CHECK, checked).apply()
            }
        }

        val updateButton = root.findViewById<MaterialButton>(R.id.btn_check_update)
        updateButton.setOnClickListener {
            updateButton.isEnabled = false
            UpdateChecker.check(
                context = context,
                onUpdateAvailable = { info ->
                    if (fragment.isAdded) {
                        updateButton.isEnabled = true
                        UpdateDialogManager.show(fragment.requireActivity() as AppCompatActivity, info)
                    }
                },
                onNoUpdate = { if (fragment.isAdded) onNoUpdate() },
                onError = { if (fragment.isAdded) onError() }
            )
        }

        val cleanButton = root.findViewById<MaterialButton>(R.id.btn_clean_apk)
        val originalText = fragment.getString(R.string.btn_clean_apk)
        cleanButton.setOnClickListener {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val files = downloads.listFiles { _, name -> ApkCleanup.isAppApk(name) } ?: emptyArray()
            val count = files.count { it.delete() }

            cleanButton.isEnabled = false
            if (count > 0) {
                cleanButton.text = fragment.getString(R.string.clean_apk_done, count)
                cleanButton.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.dash_eco_dim))
                cleanButton.strokeColor = ColorStateList.valueOf(context.getColor(R.color.dash_eco))
                cleanButton.setTextColor(context.getColor(R.color.dash_eco))
            } else {
                cleanButton.text = fragment.getString(R.string.clean_apk_none)
            }
            cleanButton.postDelayed({
                if (fragment.isAdded) {
                    cleanButton.text = originalText
                    cleanButton.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.dash_btn))
                    cleanButton.strokeColor = ColorStateList.valueOf(context.getColor(R.color.dash_border))
                    cleanButton.setTextColor(context.getColor(R.color.text_secondary))
                    cleanButton.isEnabled = true
                }
            }, 3_000)
        }
    }
}
