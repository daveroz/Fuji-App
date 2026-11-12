package com.fujiapp.remote.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GattException(message: String, val status: Int = -1) : IOException(message)

/**
 * A coroutine wrapper around [BluetoothGatt] for one connection to one camera.
 *
 * Android allows a single outstanding GATT operation, so every operation goes through [mutex] and
 * waits for its callback. Values the camera pushes (notifications / indications) are queued per
 * characteristic from the moment [subscribe] is called, so a value that arrives before anyone
 * waits for it is not lost.
 *
 * Callers must hold BLUETOOTH_CONNECT (Android 12+); the UI requests it before connecting.
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
) {
    private class OpResult(val status: Int, val value: ByteArray?)

    private val mutex = Mutex()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var pending: CompletableDeferred<OpResult>? = null
    private val connected = CompletableDeferred<Unit>()
    private val channels = ConcurrentHashMap<UUID, Channel<ByteArray>>()

    private val _disconnected = MutableStateFlow(false)
    /** Becomes true once the link is gone (camera switched off, out of range, or [close]). */
    val disconnected: StateFlow<Boolean> = _disconnected.asStateFlow()

    var mtu: Int = 23
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("link state $newState (status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val error = GattException("The camera disconnected (status $status).", status)
                _disconnected.value = true
                connected.completeExceptionally(error)
                pending?.completeExceptionally(error)
                channels.values.forEach { it.close(error) }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(status, null)

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@GattClient.mtu = mtu
            finish(status, null)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) = finish(status, value)

        @Deprecated("Used before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) finish(status, characteristic.value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            finish(status, null)

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            finish(status, null)

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            deliver(characteristic.uuid, value)

        @Deprecated("Used before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) deliver(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }
    }

    private fun finish(status: Int, value: ByteArray?) {
        pending?.complete(OpResult(status, value?.copyOf()))
    }

    private fun deliver(uuid: UUID, value: ByteArray) {
        log("← ${nameOf(uuid)}: ${FujiProtocol.hex(value)}")
        channels[uuid]?.trySend(value.copyOf())
    }

    private fun nameOf(uuid: UUID): String =
        KNOWN_NAMES[uuid] ?: uuid.toString().substring(0, 8)

    // ---- connection ----

    /**
     * Open the link. With [autoConnect] Android waits in the background until the (bonded) camera
     * shows up, which suits a registered camera that only advertises briefly after it is switched on.
     */
    suspend fun connect(autoConnect: Boolean, timeoutMs: Long) {
        log("connecting to ${device.address} (autoConnect=$autoConnect)")
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw GattException("Android could not start a Bluetooth connection.")
        withTimeoutOrNull(timeoutMs) { connected.await() }
            ?: throw GattException("The camera didn't answer. Make sure it is switched on and nearby.")
    }

    /**
     * Ask for a larger MTU. Only an optimisation (Android splits long reads itself), and newer
     * Android versions negotiate it on their own, so don't wait long for the answer.
     */
    suspend fun requestMtu(size: Int = 517) =
        op("MTU $size", timeoutMs = 5_000) { it.requestMtu(size) }.also { log("MTU $mtu") }

    suspend fun discoverServices() {
        op("discover services", timeoutMs = 30_000) { it.discoverServices() }
        log("services: " + (gatt?.services?.joinToString { nameOf(it.uuid) } ?: "none"))
    }

    fun close() {
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
        _disconnected.value = true
        val error = GattException("Disconnected.")
        pending?.completeExceptionally(error)
        channels.values.forEach { it.close(error) }
    }

    // ---- characteristics ----

    fun hasService(service: UUID): Boolean = gatt?.getService(service) != null

    fun has(c: GattChar): Boolean = find(c) != null

    private fun find(c: GattChar): BluetoothGattCharacteristic? = gatt?.getService(c.service)?.getCharacteristic(c.uuid)

    private fun require(c: GattChar): BluetoothGattCharacteristic =
        find(c) ?: throw GattException("The camera has no ${c.name} characteristic.")

    suspend fun read(c: GattChar): ByteArray {
        val ch = require(c)
        val value = op("read ${c.name}") { it.readCharacteristic(ch) } ?: ByteArray(0)
        log("read ${c.name}: ${FujiProtocol.hex(value)}")
        return value
    }

    suspend fun write(c: GattChar, value: ByteArray) {
        val ch = require(c)
        log("→ ${c.name}: ${FujiProtocol.hex(value)}")
        op("write ${c.name}") { g ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                ch.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        }
    }

    /**
     * Turn on notifications, or indications where the characteristic only indicates (as XApp does).
     * Values start queueing for [awaitValue] before the descriptor is written.
     */
    suspend fun subscribe(c: GattChar) {
        val ch = require(c)
        channelFor(c.uuid)
        val g = gatt ?: throw GattException("Not connected.")
        if (!g.setCharacteristicNotification(ch, true)) throw GattException("Couldn't enable ${c.name}.")
        val cccd = ch.getDescriptor(FujiGatt.CCCD) ?: throw GattException("${c.name} has no CCCD.")
        val indicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
            ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op("subscribe ${c.name}") { gg ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gg.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                gg.writeDescriptor(cccd)
            }
        }
        log("subscribed ${c.name} (${if (indicate) "indications" else "notifications"})")
    }

    /** The next value the camera pushes on [c], or null after [timeoutMs]. */
    suspend fun awaitValue(c: GattChar, timeoutMs: Long): ByteArray? =
        withTimeoutOrNull(timeoutMs) { channelFor(c.uuid).receive() }

    /** Forget values already queued for [c], so the next [awaitValue] sees only new ones. */
    fun drain(c: GattChar) {
        val ch = channels[c.uuid] ?: return
        while (ch.tryReceive().isSuccess) Unit
    }

    private fun channelFor(uuid: UUID): Channel<ByteArray> =
        channels.getOrPut(uuid) { Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST) }

    /** Run one GATT operation and wait for its callback. Returns the callback's value, if any. */
    private suspend fun op(name: String, timeoutMs: Long = 15_000, start: (BluetoothGatt) -> Boolean): ByteArray? =
        mutex.withLock {
            val g = gatt ?: throw GattException("Not connected.")
            val done = CompletableDeferred<OpResult>()
            pending = done
            try {
                if (!start(g)) throw GattException("Couldn't start: $name.")
                val result = withTimeoutOrNull(timeoutMs) { done.await() }
                    ?: throw GattException("The camera didn't answer: $name.")
                if (result.status != BluetoothGatt.GATT_SUCCESS) {
                    throw GattException("$name failed (status ${result.status}).", result.status)
                }
                result.value
            } finally {
                pending = null
            }
        }

    companion object {
        /** ATT errors that mean the link isn't encrypted with a bond yet. */
        fun isAuthError(status: Int): Boolean = status == 5 || status == 15 || status == 137

        private val KNOWN_NAMES: Map<UUID, String> by lazy {
            FujiGatt.ALL.associate { it.uuid to it.name } + FujiGatt.SERVICE_NAMES
        }
    }
}
