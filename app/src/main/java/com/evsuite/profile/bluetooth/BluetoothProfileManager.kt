package com.evsuite.profile.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context

/**
 * [BT-PROFILES] Bluetooth utilities for automatic profiles functionality.
 *
 * Bluetooth ↔ Profile associations are now stored directly in
 * [DrivingProfile.btDeviceMac] — this file only manages:
 *   • in-memory tracking of connected ACL devices
 *   • the list of paired devices (for the profile editor UI)
 *   • the asynchronous HFP query (fixes first-time setup)
 *
 * To remove the feature: delete this file + all blocks
 * marked // [BT-PROFILES] in other modified files.
 */
object BluetoothProfileManager {

    // ── Currently connected devices (in memory) ─────────────────────────
    // Updated from EVProfileService via ACTION_ACL_CONNECTED/DISCONNECTED.
    // Reset if the service restarts — the HFP async path compensates for this case.

    private val connectedMacs = mutableSetOf<String>()

    fun onDeviceConnected(mac: String)    { connectedMacs.add(mac) }
    fun onDeviceDisconnected(mac: String) { connectedMacs.remove(mac) }
    fun getConnectedMacs(): Set<String>   = connectedMacs.toSet()

    // ── Paired devices ────────────────────────── ──────────────────────────

    data class BtDeviceInfo(val name: String, val mac: String)

    /** Returns the list of paired Bluetooth devices, sorted by name. */
    fun getBondedDevices(context: Context): List<BtDeviceInfo> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            (adapter.bondedDevices ?: emptySet<BluetoothDevice>())
                .filter { it.name != null }
                .map { BtDeviceInfo(it.name, it.address) }
                .sortedBy { it.name }
        } catch (_: SecurityException) { emptyList() }
    }

    // ── Currently connected HFP devices (async request) ─────────────────
    //
    // Used in applyProfileOnIgnition() when connectedMacs is empty,
    // what happens if the phone was already connected before booting
    // of the service (first configuration, restart of the AAOS service).
    //
    // We use BluetoothProfile.HEADSET (HFP AG profile, car role):
    // on AAOS the car is the Audio Gateway, the connected phones are
    // therefore accessible via this profile. HEADSET_CLIENT is not exposed on AAOS.

    fun checkConnectedHfpDevices(context: Context, onResult: (List<BluetoothDevice>) -> Unit) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
        if (adapter == null) { onResult(emptyList()); return }
        try {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val connected = try { proxy.connectedDevices ?: emptyList() }
                                    catch (_: Exception) { emptyList() }
                    adapter.closeProfileProxy(profile, proxy)
                    onResult(connected)
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
        } catch (_: Exception) { onResult(emptyList()) }
    }
}
