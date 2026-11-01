package com.sirveillanceai.mixokeys.input

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentLinkedQueue

/** A key going down or up on a connected keyboard. [nanos] is when the keyboard sent it (System.nanoTime base). */
data class KeyEvent(val note: Int, val velocity: Int, val down: Boolean, val nanos: Long)

/**
 * Connects to a MIDI keyboard over USB (with an OTG adapter) or Bluetooth, and queues its key presses.
 * Uses each message's own timestamp, so how often the app redraws does not affect timing.
 */
class MidiKeyboard(context: Context) {
    private val app = context.applicationContext
    private val mm = app.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val main = Handler(Looper.getMainLooper())

    val supported get() = mm != null
    val events = ConcurrentLinkedQueue<KeyEvent>()

    var connectedName by mutableStateOf<String?>(null); private set
    var devices by mutableStateOf<List<MidiDeviceInfo>>(emptyList()); private set
    var bleFound by mutableStateOf<List<BluetoothDevice>>(emptyList()); private set
    var scanning by mutableStateOf(false); private set
    var lastError by mutableStateOf<String?>(null); private set
    /** While true, another screen (calibration) reads the key events. */
    var exclusive by mutableStateOf(false)

    private var device: MidiDevice? = null
    private var port: MidiOutputPort? = null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            refresh()
            if (device == null && info.outputPortCount > 0) open(info)
        }
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            refresh()
            if (device?.info?.id == info.id) close()
        }
    }

    fun start() {
        val m = mm ?: return
        @Suppress("DEPRECATION")
        m.registerDeviceCallback(callback, main)
        refresh()
        if (device == null) devices.firstOrNull()?.let { open(it) }
    }

    fun stop() {
        runCatching { mm?.unregisterDeviceCallback(callback) }
        stopScan()
        close()
    }

    fun refresh() {
        val m = mm ?: return
        val all = if (Build.VERSION.SDK_INT >= 33) m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        else @Suppress("DEPRECATION") m.devices.toList()
        devices = all.filter { it.outputPortCount > 0 }
    }

    fun nameOf(info: MidiDeviceInfo): String {
        val p = info.properties
        return p.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: listOfNotNull(p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER), p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)).joinToString(" ").ifBlank { "MIDI keyboard" }
    }

    fun open(info: MidiDeviceInfo) {
        val m = mm ?: return
        close()
        m.openDevice(info, { dev -> attach(dev, nameOf(info)) }, main)
    }

    @SuppressLint("MissingPermission")
    fun openBluetooth(bt: BluetoothDevice) {
        val m = mm ?: return
        stopScan()
        close()
        m.openBluetoothDevice(bt, { dev -> attach(dev, runCatching { bt.name }.getOrNull() ?: "Bluetooth keyboard") }, main)
    }

    private fun attach(dev: MidiDevice?, name: String) {
        if (dev == null) { lastError = "Couldn't open $name."; return }
        val out = dev.info.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
        if (out == null) { dev.close(); lastError = "$name doesn't send notes."; return }
        val p = dev.openOutputPort(out.portNumber)
        if (p == null) { dev.close(); lastError = "$name is busy in another app."; return }
        p.connect(receiver)
        device = dev; port = p; connectedName = name; lastError = null
        refresh()
    }

    fun close() {
        runCatching { port?.disconnect(receiver); port?.close() }
        runCatching { device?.close() }
        port = null; device = null; connectedName = null
    }

    /* Bluetooth LE MIDI: devices advertise the standard BLE-MIDI service. */
    private val BLE_MIDI = ParcelUuid.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            if (bleFound.none { it.address == d.address }) bleFound = bleFound + d
        }
        override fun onScanFailed(errorCode: Int) { scanning = false; lastError = "Bluetooth search failed ($errorCode)." }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val bm = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = bm?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) { lastError = "Turn on Bluetooth first."; return }
        bleFound = emptyList(); scanning = true
        runCatching {
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(BLE_MIDI).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        }.onFailure { scanning = false; lastError = it.message }
        main.postDelayed({ stopScan() }, 12_000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        val bm = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        runCatching { bm?.adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    @SuppressLint("MissingPermission")
    fun bleName(d: BluetoothDevice) = runCatching { d.name }.getOrNull() ?: d.address

    /** Parses the MIDI byte stream (with running status) into key events. */
    private val receiver = object : MidiReceiver() {
        private var running = 0
        private var d1 = -1
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val ts = if (timestamp > 0) timestamp else System.nanoTime()
            for (i in offset until offset + count) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) continue                       // clock, active sensing: ignore, keep running status
                if (b >= 0x80) { running = if (b >= 0xF0) 0 else b; d1 = -1; continue }
                if (running == 0) continue
                val type = running and 0xF0
                if (type == 0xC0 || type == 0xD0) continue    // one data byte, not needed
                if (d1 < 0) { d1 = b; continue }
                val note = d1; d1 = -1
                when {
                    type == 0x90 && b > 0 -> events.add(KeyEvent(note, b, true, ts))
                    type == 0x90 || type == 0x80 -> events.add(KeyEvent(note, 0, false, ts))
                }
            }
        }
    }
}
