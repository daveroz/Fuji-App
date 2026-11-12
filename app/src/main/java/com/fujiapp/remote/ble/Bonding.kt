package com.fujiapp.remote.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bluetooth bonding with the camera. Fujifilm's secure firmware uses LE Secure Connections with
 * numeric comparison: the camera shows a six-digit code that must be confirmed with MENU/OK within
 * about 30 s, and Android shows the same code to confirm on the phone.
 */
@SuppressLint("MissingPermission")
object Bonding {

    /** Bond with [device] if needed. Returns true once bonded, false if pairing failed or timed out. */
    suspend fun bond(context: Context, device: BluetoothDevice, timeoutMs: Long = 60_000): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (d?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> result.complete(true)
                    BluetoothDevice.BOND_NONE -> result.complete(false)
                }
            }
        }
        // A protected system broadcast sent by the Bluetooth stack, so the receiver must be exported.
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) {
                return device.bondState == BluetoothDevice.BOND_BONDED
            }
            return withTimeoutOrNull(timeoutMs) { result.await() } ?: false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
