package com.evsuite.profile.update

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.evsuite.profile.R

/** Stable channel: offline and manually updated; no updater implementation is packaged. */
object UpdateChannel {
    fun checkAtStartup(@Suppress("UNUSED_PARAMETER") activity: AppCompatActivity) = Unit

    fun configureSettings(
        @Suppress("UNUSED_PARAMETER") fragment: Fragment,
        root: View,
        @Suppress("UNUSED_PARAMETER") onNoUpdate: () -> Unit,
        @Suppress("UNUSED_PARAMETER") onError: () -> Unit
    ) {
        root.findViewById<View>(R.id.row_auto_update).visibility = View.GONE
        root.findViewById<View>(R.id.row_update_buttons).visibility = View.GONE
    }
}
