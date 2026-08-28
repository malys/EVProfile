package com.evsuite.profile.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.Switch
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.evsuite.profile.R
import com.evsuite.hardware.EVHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_audio, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Volume when opening a door (SWI132/133) ───────────────────
        setupDoorVolume(view)
    }

    private fun setupDoorVolume(view: View) {
        val card = view.findViewById<View>(R.id.door_volume_card)
        if (!EVHardware.hasDoorVolumeFeature()) {
            card.visibility = View.GONE
            return
        }

        val prefs   = requireContext().getSharedPreferences("ev_settings", Context.MODE_PRIVATE)
        val toggle  = view.findViewById<Switch>(R.id.switch_door_volume)
        val slider  = view.findViewById<Slider>(R.id.slider_door_volume)
        val restore = view.findViewById<Switch>(R.id.switch_door_restore)
        val cbLeft  = view.findViewById<CheckBox>(R.id.cb_door_left)
        val cbRight = view.findViewById<CheckBox>(R.id.cb_door_right)

        val enabled = prefs.getBoolean("door_volume_enabled", false)
        toggle.isChecked  = enabled
        restore.isChecked = prefs.getBoolean("door_volume_restore", false)
        cbLeft.isChecked  = prefs.getBoolean("door_volume_left", true)
        cbRight.isChecked = prefs.getBoolean("door_volume_right", true)

        fun setSubEnabled(e: Boolean) {
            slider.isEnabled = e; restore.isEnabled = e; cbLeft.isEnabled = e; cbRight.isEnabled = e
        }
        setSubEnabled(enabled)

        // If already activated, (re)starts the watcher when the tab is opened (idempotent).
        if (enabled) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { EVHardware.startDoorVolumeWatcher() }
        }

        // Limit the slider to the actual maximum of the car (otherwise default XML value).
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val max = EVHardware.getMediaVolumeMax()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (max > 0) slider.valueTo = max.toFloat()
                val level = prefs.getInt("door_volume_level", 0)
                slider.value = level.coerceIn(0, slider.valueTo.toInt()).toFloat()
            }
        }

        toggle.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("door_volume_enabled", checked).apply()
            setSubEnabled(checked)
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                if (checked) EVHardware.startDoorVolumeWatcher()
                else EVHardware.stopDoorVolumeWatcher()
            }
        }

        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.edit().putInt("door_volume_level", value.toInt()).apply()
        }
        restore.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("door_volume_restore", checked).apply()
        }
        cbLeft.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("door_volume_left", checked).apply()
        }
        cbRight.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("door_volume_right", checked).apply()
        }
    }
}
