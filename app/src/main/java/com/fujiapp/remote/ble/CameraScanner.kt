package com.fujiapp.remote.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException

/** A Fujifilm camera seen advertising. */
data class FoundCamera(
    val device: BluetoothDevice,
    val name: String,
    val advert: FujiAdvert,
    val rssi: Int,
    val bonded: Boolean,
)

/**
 * Scans for Fujifilm cameras (manufacturer data with company ID 0x04D8). The scan is unfiltered at
 * the OS level, since an empty manufacturer-data filter is unreliable on some phones, and filtered
 * here instead. Requires BLUETOOTH_SCAN (Android 12+) or location (Android 11 and lower).
 */
@SuppressLint("MissingPermission")
class CameraScanner(private val adapter: BluetoothAdapter) {

    fun scan(): Flow<FoundCamera> = callbackFlow {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null || !adapter.isEnabled) {
            close(IOException("Bluetooth is off."))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                toCamera(result)?.let { trySend(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r -> toCamera(r)?.let { trySend(it) } }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IOException("Bluetooth scan failed (code $errorCode). Turn Bluetooth off and on, then try again."))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    private fun toCamera(result: ScanResult): FoundCamera? {
        val record = result.scanRecord ?: return null
        val data = record.getManufacturerSpecificData(FujiGatt.COMPANY_ID) ?: return null
        val uuids = record.serviceUuids?.map { it.uuid } ?: emptyList()
        val advert = FujiAdvert.parse(data, uuids) ?: return null
        val name = record.deviceName ?: runCatching { result.device.name }.getOrNull() ?: "Fujifilm camera"
        return FoundCamera(
            device = result.device,
            name = name,
            advert = advert,
            rssi = result.rssi,
            bonded = result.device.bondState == BluetoothDevice.BOND_BONDED,
        )
    }
}
