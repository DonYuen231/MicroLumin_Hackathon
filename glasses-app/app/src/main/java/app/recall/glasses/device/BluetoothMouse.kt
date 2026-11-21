package app.recall.glasses.device

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import app.recall.glasses.core.MouseCandidate
import app.recall.glasses.core.MousePairing
import app.recall.glasses.core.MouseRanking
import app.recall.glasses.core.PairingState

/** Finds, bonds, and connects a Bluetooth mouse from the glasses, with no Settings app needed. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BluetoothMouse(context: Context) : MousePairing {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val inputManager = appContext.getSystemService(InputManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val found = linkedMapOf<String, MouseCandidate>()
    private var listener: ((PairingState) -> Unit)? = null
    private var status = ""
    private var pairingAddress: String? = null

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = onInputChanged()
        override fun onInputDeviceChanged(deviceId: Int) = onInputChanged()
        override fun onInputDeviceRemoved(deviceId: Int) = onInputChanged()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    if (adapter?.state == BluetoothAdapter.STATE_ON) beginScan()
                BluetoothDevice.ACTION_FOUND -> intent.device()?.let { onFound(it, intent) }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onScanFinished()
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> intent.device()?.let(::onBondChanged)
                BluetoothDevice.ACTION_PAIRING_REQUEST -> onPairingRequest(intent)
            }
        }
    }

    override fun open(listener: (PairingState) -> Unit) {
        this.listener = listener
        found.clear()
        val bluetooth = adapter
        if (bluetooth == null) {
            publish("This device has no Bluetooth")
            return
        }
        if (!hasScanPermission()) {
            publish("Grant location: adb install -r -g")
            return
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        }
        appContext.registerReceiver(receiver, filter)
        inputManager.registerInputDeviceListener(inputListener, handler)
        registered = true

        if (bluetooth.isEnabled) {
            beginScan()
        } else {
            publish("Turning on Bluetooth")
            if (!bluetooth.enable()) publish("Could not turn on Bluetooth")
        }
    }

    private var registered = false

    override fun close() {
        listener = null
        pairingAddress = null
        runCatching { adapter?.cancelDiscovery() }
        if (registered) {
            registered = false
            runCatching { appContext.unregisterReceiver(receiver) }
            inputManager.unregisterInputDeviceListener(inputListener)
        }
    }

    override fun pair(address: String) {
        val bluetooth = adapter ?: return
        val device = runCatching { bluetooth.getRemoteDevice(address) }.getOrNull() ?: return
        // A running scan makes bonding slow or makes it fail.
        bluetooth.cancelDiscovery()
        pairingAddress = address
        val label = displayName(device)
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            publish("Connecting $label")
            connectHid(device)
        } else {
            publish("Pairing $label")
            if (!device.createBond()) publish("Pairing failed, try again")
        }
    }

    override fun connectedName(): String? {
        val bonded = runCatching { adapter?.bondedDevices?.mapNotNull { it.name }?.toSet() }
            .getOrNull().orEmpty()
        return InputDevice.getDeviceIds().toList()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull {
                !it.isVirtual && it.supportsSource(InputDevice.SOURCE_MOUSE) && it.name in bonded
            }?.name
    }

    private fun beginScan() {
        val bluetooth = adapter ?: return
        bluetooth.bondedDevices.orEmpty().forEach { device ->
            val deviceClass = device.bluetoothClass?.deviceClass ?: 0
            if (MouseRanking.isPointing(deviceClass)) remember(device, deviceClass)
        }
        if (bluetooth.isDiscovering) bluetooth.cancelDiscovery()
        val started = bluetooth.startDiscovery()
        publish(if (started) "Put mouse in pairing mode" else "Could not start scan")
    }

    private fun onFound(device: BluetoothDevice, intent: Intent) {
        val deviceClass = intent.getParcelableExtra<android.bluetooth.BluetoothClass>(
            BluetoothDevice.EXTRA_CLASS,
        )?.deviceClass ?: device.bluetoothClass?.deviceClass ?: 0
        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name
        if (MouseRanking.isCandidate(deviceClass, name)) {
            remember(device, deviceClass, name)
            publish(status)
        }
    }

    private fun remember(device: BluetoothDevice, deviceClass: Int, name: String? = device.name) {
        found[device.address] = MouseCandidate(
            address = device.address,
            name = name?.takeIf { it.isNotBlank() } ?: device.address,
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            pointing = MouseRanking.isPointing(deviceClass),
        )
    }

    private fun onScanFinished() {
        if (listener == null || pairingAddress != null) return
        if (found.isNotEmpty()) {
            publish("Pick your mouse")
            return
        }
        val locationOn = appContext.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        publish(if (locationOn) "No mouse found, rescanning" else "No mouse. Turn on location")
        handler.postDelayed({ if (listener != null && pairingAddress == null) beginScan() }, RESCAN_MS)
    }

    private fun onBondChanged(device: BluetoothDevice) {
        if (device.address != pairingAddress) return
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                found[device.address]?.let { found[device.address] = it.copy(bonded = true) }
                publish("Connecting ${displayName(device)}")
                connectHid(device)
                handler.postDelayed({ checkConnected(device) }, CONNECT_CHECK_MS)
            }
            BluetoothDevice.BOND_NONE -> {
                pairingAddress = null
                publish("Pairing failed, try again")
            }
        }
    }

    /** Mice use a fixed legacy PIN, and the glasses have no way to type one into a dialog. */
    private fun onPairingRequest(intent: Intent) {
        val device = intent.device() ?: return
        if (device.address != pairingAddress) return
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        if (variant == BluetoothDevice.PAIRING_VARIANT_PIN) {
            device.setPin("0000".toByteArray())
            receiver.abortBroadcast()
        }
    }

    /** Android usually connects a bonded mouse by itself. This covers the times it does not. */
    private fun connectHid(device: BluetoothDevice) {
        val bluetooth = adapter ?: return
        bluetooth.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                runCatching {
                    proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, device)
                }.onFailure { Log.w(TAG, "HID connect call failed", it) }
                bluetooth.closeProfileProxy(profile, proxy)
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }, PROFILE_HID_HOST)
    }

    private fun checkConnected(device: BluetoothDevice) {
        if (pairingAddress != device.address || listener == null) return
        if (connectedName() == null) publish("Paired. Move the mouse")
    }

    private fun onInputChanged() {
        val name = connectedName() ?: return
        pairingAddress = null
        publish("Connected: $name")
    }

    private fun hasScanPermission() =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun displayName(device: BluetoothDevice) =
        found[device.address]?.name ?: device.name ?: device.address

    private fun publish(message: String) {
        status = message
        listener?.invoke(PairingState(message, MouseRanking.sort(found.values)))
    }

    private fun Intent.device(): BluetoothDevice? =
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private companion object {
        const val TAG = "RecallBluetooth"
        const val PROFILE_HID_HOST = 4 // BluetoothProfile.HID_HOST is hidden on older APIs.
        const val RESCAN_MS = 3_000L
        const val CONNECT_CHECK_MS = 4_000L
    }
}
