package io.github.indigodev.kteps

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class KtepsManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "Kteps"
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val FAST_RECONNECT_DELAY_MS = 1_200L
        private const val BLE_ENABLING_RETRY_MS = 600L
        private const val DISCONNECT_GRACE_MS = 15_000L
        private const val SERVICE_DISCOVERY_DELAY_MS = 250L

        /**
         * Settle window after service discovery finishes before subscribing to 0x2AC1.
         *
         * During full GATT service discovery Android Fluoride switches the link to interval=6
         * (7.5ms) and, upon completion, issues an HCI_LE_Connection_Update back to interval=36
         * (45ms) that takes ~85ms to complete over the air. Writing 0x2AC1 CCCD while that update
         * is still in flight arms Shimano's Nordic ble_conn_params (start_on_notify_cccd_handle =
         * 0x2AC1, max_conn_params_update_count = 1, disconnect_on_fail = true), which immediately
         * sends L2CAP_CMD_BLE_UPDATE_REQ (62.5–125ms). When Android's in-flight 45ms update lands
         * first, EW-EN100 treats 45ms < 62.5ms as a failed negotiation and disconnects with 0x3B
         * (status=59). Waiting here guarantees Android's post-discovery update finishes first.
         */
        private const val POST_DISCOVERY_SETTLE_MS = 600L
        private const val POST_DISCOVERY_SETTLE_STEP_MS = 400L
        private const val POST_DISCOVERY_SETTLE_MAX_MS = 1_400L

        /**
         * Stabilization window after enabling 0x2AC1 notifications before reporting CONNECTED to
         * Karoo OS, allowing the peripheral's L2CAP connection parameter negotiation (~270ms) and
         * initial telemetry packets to settle without flapping sensor streams.
         */
        private const val CONNECTION_STABILIZATION_MS = 500L

        /**
         * Max time from connectGatt() until STATE_CONNECTED before aborting the attempt.
         * Kept slightly above Android's native 30s direct-connect timeout (status=255) to avoid
         * racing the stack's own teardown callback.
         */
        private const val CONNECT_TIMEOUT_MS = 32_000L

        /** Max time from STATE_CONNECTED until telemetry notifications are confirmed. */
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L

        /** Fallback battery re-read interval in case Battery Level notifications are not sent. */
        private const val BATTERY_POLL_INTERVAL_MS = 10_000L

        /** GATT_ERROR as reported by many stacks when the peer is out of range / switched off. */
        private const val GATT_STATUS_GENERIC_ERROR = 255

        /** HCI_ERR_CONN_INTERVAL_UNACCEPTABLE (0x3B), sent by Nordic SoftDevice on param mismatch. */
        private const val GATT_CONN_INTERVAL_UNACCEPTABLE = 59

        /** GATT error codes for security/authentication required (triggers bonding/pairing). */
        private const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        private const val GATT_INSUFFICIENT_AUTHORIZATION = 8
        private const val GATT_INSUFFICIENT_ENCRYPTION = 15
        private const val GATT_AUTH_FAIL = 137

        /** Max time to wait when user enters passkey / confirms pairing on bike display or Karoo. */
        private const val BONDING_TIMEOUT_MS = 60_000L

        /** Hammerhead SensorService actions for coordinating Bluetooth power state on Karoo OS. */
        private const val HAMMERHEAD_SENSOR_SERVICE_PACKAGE = "io.hammerhead.sensorservice"
        private const val ACTION_BLUETOOTH_REQUEST = "io.hammerhead.sensorservice.BLUETOOTH_REQUEST"
        private const val ACTION_BLUETOOTH_RELEASE = "io.hammerhead.sensorservice.BLUETOOTH_RELEASE"
        private const val EXTRA_CLIENT_ID = "ID"
        private const val HAMMERHEAD_CLIENT_ID = "kteps"
    }

    /** Steps of the GATT handshake for the currently active [BluetoothGatt]. */
    private enum class HandshakeStage {
        IDLE,
        CONNECTING,
        DISCOVERING_SERVICES,
        SETTLING_CONN_PARAMS,
        WAITING_FOR_BOND,
        READING_BATTERY,
        SUBSCRIBING_TELEMETRY,
        STABILIZING,
        READY
    }

    private enum class NotifyResult { WRITE_PENDING, NO_CCCD, FAILED }

    private val _telemetry = MutableStateFlow(KtepsTelemetry())
    val telemetry: StateFlow<KtepsTelemetry> = _telemetry.asStateFlow()

    private val gattLock = Any()
    private var bluetoothGatt: BluetoothGatt? = null
    private var targetMacAddress: String? = null
    private var reconnectJob: Job? = null
    private var disconnectGraceJob: Job? = null
    private var serviceDiscoveryJob: Job? = null
    private var postDiscoveryJob: Job? = null
    private var stabilizationJob: Job? = null
    private var handshakeWatchdogJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var batteryPollJob: Job? = null
    private var handshakeStage = HandshakeStage.IDLE
    private var consecutiveFastFailures = 0
    private var consecutiveIntervalErrors = 0
    private var authRetryCount = 0
    private var activeScanCallback: ScanCallback? = null
    private var nextToken = 1L
    private val activeTokens = mutableSetOf<Long>()

    private fun isGattConnectedLocked(): Boolean =
        handshakeStage != HandshakeStage.IDLE && handshakeStage != HandshakeStage.CONNECTING

    @Volatile
    private var sessionStartOdoMeters: Long? = null

    /** True once enableBLE() succeeded, i.e. we hold a BLE-app registration to release later. */
    @Volatile
    private var bleAppRegistered = false

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)

            var resumedBond = false
            var gattToRestart: BluetoothGatt? = null

            synchronized(gattLock) {
                if (device.address != targetMacAddress) return
                val gatt = bluetoothGatt ?: return
                if (handshakeStage != HandshakeStage.WAITING_FOR_BOND) return

                when (bondState) {
                    BluetoothDevice.BOND_BONDED -> {
                        handshakeStage = HandshakeStage.SETTLING_CONN_PARAMS
                        startHandshakeWatchdogLocked(gatt)
                        postDiscoveryJob?.cancel()
                        postDiscoveryJob = scope.launch {
                            delay(POST_DISCOVERY_SETTLE_MS)
                            beginPostDiscoveryHandshake(gatt)
                        }
                        resumedBond = true
                    }
                    BluetoothDevice.BOND_NONE -> {
                        if (prevBondState == BluetoothDevice.BOND_BONDING) {
                            gattToRestart = gatt
                        }
                    }
                }
            }

            if (resumedBond) {
                Log.i(TAG, "Device ${device.address} successfully bonded! Resuming GATT handshake...")
                ExtensionDiagnostics.onGattEvent("deviceBonded")
            }
            gattToRestart?.let { gatt ->
                Log.w(TAG, "Bonding failed or cancelled for ${device.address}")
                ExtensionDiagnostics.onError("Bonding failed or rejected")
                restartConnection(gatt, "bonding cancelled by user/device", delayMs = RECONNECT_DELAY_MS)
            }
        }
    }

    init {
        runCatching {
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            ContextCompat.registerReceiver(
                context,
                bondStateReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to register bondStateReceiver", e)
        }
    }

    private enum class BleAvailability { READY, ENABLING, UNAVAILABLE }

    private fun bluetoothManager(): BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private fun bluetoothAdapter(): BluetoothAdapter? =
        bluetoothManager()?.adapter

    /**
     * True if BLE is usable, including Android's "LE only" STATE_BLE_ON, where
     * [BluetoothAdapter.isEnabled] is false. Uses the public API first:
     * getBluetoothLeScanner() is non-null in STATE_ON and STATE_BLE_ON. The hidden
     * isLeEnabled() is only a fallback, since it may be blocked by hidden-API restrictions.
     */
    private fun isLeEnabled(adapter: BluetoothAdapter): Boolean {
        if (adapter.isEnabled) return true
        if (runCatching { adapter.bluetoothLeScanner != null }.getOrDefault(false)) return true
        return runCatching {
            val method = adapter.javaClass.getMethod("isLeEnabled")
            method.invoke(adapter) as? Boolean ?: false
        }.getOrDefault(false)
    }

    /** Mirrors the hidden BluetoothAdapter.isBleScanAlwaysAvailable(), a precondition of enableBLE(). */
    private fun isBleScanAlwaysAvailable(): Boolean =
        runCatching {
            Settings.Global.getInt(context.contentResolver, "ble_scan_always_enabled", 0) == 1
        }.getOrDefault(false)

    /**
     * Informs Hammerhead's BluetoothCoordinator that this extension needs Bluetooth.
     * On Karoo OS, if no native BLE sensors are active, BluetoothCoordinator forcefully
     * disables Bluetooth whenever it enters STATE_ON unless an active client ID is registered
     * via this broadcast.
     */
    private fun requestHammerheadBluetooth() {
        runCatching {
            val intent = Intent(ACTION_BLUETOOTH_REQUEST).apply {
                setPackage(HAMMERHEAD_SENSOR_SERVICE_PACKAGE)
                putExtra(EXTRA_CLIENT_ID, HAMMERHEAD_CLIENT_ID)
            }
            context.sendBroadcast(intent)
            Log.i(TAG, "Sent BLUETOOTH_REQUEST to Hammerhead SensorService ($HAMMERHEAD_CLIENT_ID)")
            ExtensionDiagnostics.onGattEvent("hhBtRequest")
        }.onFailure { e ->
            Log.w(TAG, "Failed to send BLUETOOTH_REQUEST", e)
        }
    }

    /**
     * Releases our client registration with Hammerhead's BluetoothCoordinator so Karoo OS
     * can power down Bluetooth to save battery when no sensors or extensions are using it.
     */
    private fun releaseHammerheadBluetooth() {
        runCatching {
            val intent = Intent(ACTION_BLUETOOTH_RELEASE).apply {
                setPackage(HAMMERHEAD_SENSOR_SERVICE_PACKAGE)
                putExtra(EXTRA_CLIENT_ID, HAMMERHEAD_CLIENT_ID)
            }
            context.sendBroadcast(intent)
            Log.i(TAG, "Sent BLUETOOTH_RELEASE to Hammerhead SensorService ($HAMMERHEAD_CLIENT_ID)")
            ExtensionDiagnostics.onGattEvent("hhBtRelease")
        }.onFailure { e ->
            Log.w(TAG, "Failed to send BLUETOOTH_RELEASE", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureBleEnabled(adapter: BluetoothAdapter): BleAvailability {
        // Register with Hammerhead's BluetoothCoordinator so it keeps Bluetooth enabled
        // even when no native Karoo BLE sensors are active.
        requestHammerheadBluetooth()

        // Also register as a BLE app in AOSP BluetoothManagerService if enableBLE() is available.
        val enableBleResult = runCatching {
            val method = adapter.javaClass.getMethod("enableBLE")
            method.invoke(adapter) as? Boolean ?: false
        }.getOrDefault(false)
        if (enableBleResult) {
            bleAppRegistered = true
        }

        if (isLeEnabled(adapter)) {
            return BleAvailability.READY
        }

        Log.i(TAG, "Bluetooth LE enabling via Hammerhead BluetoothCoordinator")
        ExtensionDiagnostics.onGattEvent("enablingBt")
        return BleAvailability.ENABLING
    }

    /** Releases our BLE registration with Hammerhead and Android so BLE does not stay on after the ride. */
    private fun releaseBleAppRegistrationLocked() {
        if (activeTokens.isNotEmpty() || activeScanCallback != null) return
        releaseHammerheadBluetooth()
        if (bleAppRegistered) {
            val adapter = bluetoothAdapter()
            if (adapter != null) {
                val result = runCatching {
                    val method = adapter.javaClass.getMethod("disableBLE")
                    method.invoke(adapter) as? Boolean ?: false
                }.getOrDefault(false)
                Log.i(TAG, "Released BLE app registration (disableBLE=$result)")
                ExtensionDiagnostics.onGattEvent("disableBLE($result)")
            }
            bleAppRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopActiveScan() {
        val callback = synchronized(gattLock) {
            val current = activeScanCallback
            activeScanCallback = null
            current
        } ?: return
        runCatching {
            bluetoothAdapter()?.bluetoothLeScanner?.stopScan(callback)
            Log.i(TAG, "Stopped active BLE scan before GATT connect")
        }
    }

    @SuppressLint("MissingPermission")
    fun scanForShimanoBikes(): Flow<DiscoveredShimanoBike> = callbackFlow {
        if (!BlePermissions.hasScanPermissions(context)) {
            val msg = "Missing Bluetooth scan permissions"
            Log.w(TAG, msg)
            ExtensionDiagnostics.onError(msg)
            close()
            return@callbackFlow
        }

        val manager = bluetoothManager()
        val adapter = manager?.adapter
        if (adapter != null) {
            synchronized(gattLock) {
                disconnectGraceJob?.cancel()
                disconnectGraceJob = null
            }
            ensureBleEnabled(adapter)
        }
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null) {
            val msg = "Bluetooth LE scanner unavailable (Bluetooth off?)"
            Log.w(TAG, msg)
            ExtensionDiagnostics.onError(msg)
            close()
            return@callbackFlow
        }
        ExtensionDiagnostics.clearError()

        val seenMacs = mutableSetOf<String>()

        fun emitConnectedIfShimano(device: BluetoothDevice) {
            val mac = device.address ?: return
            val name = runCatching { device.name.orEmpty() }.getOrDefault("")
            val uuids = runCatching {
                device.uuids?.mapNotNull { it?.uuid } ?: emptyList()
            }.getOrDefault(emptyList())
            if (ShimanoBleParser.isShimanoAdvertisement(name, uuids) && seenMacs.add(mac)) {
                val display = name.ifBlank { "Shimano STEPS ($mac)" }
                Log.i(TAG, "Found active Shimano Bike: $display [$mac]")
                ExtensionDiagnostics.onDeviceReported("$display [$mac]")
                trySend(DiscoveredShimanoBike(macAddress = mac, displayName = display))
            }
        }

        runCatching {
            manager.getConnectedDevices(BluetoothProfile.GATT).forEach(::emitConnectedIfShimano)
        }

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val mac = device.address ?: return
                val name = try {
                    device.name ?: result.scanRecord?.deviceName.orEmpty()
                } catch (_: SecurityException) {
                    result.scanRecord?.deviceName.orEmpty()
                }
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                val mfgData = result.scanRecord?.manufacturerSpecificData
                val manufacturerIds = if (mfgData != null && mfgData.size() > 0) {
                    buildSet {
                        for (i in 0 until mfgData.size()) {
                            add(mfgData.keyAt(i))
                        }
                    }
                } else {
                    emptySet()
                }

                if (ShimanoBleParser.isShimanoAdvertisement(name, uuids, manufacturerIds) && seenMacs.add(mac)) {
                    val display = name.ifBlank { "Shimano STEPS ($mac)" }
                    Log.i(TAG, "Discovered Shimano Bike: $display [$mac]")
                    ExtensionDiagnostics.onDeviceReported("$display [$mac]")
                    trySend(DiscoveredShimanoBike(macAddress = mac, displayName = display))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val msg = "BLE Scan failed (code=$errorCode)"
                Log.e(TAG, msg)
                ExtensionDiagnostics.onError(msg)
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanStarted = try {
            synchronized(gattLock) {
                activeScanCallback = scanCallback
            }
            scanner.startScan(null, settings, scanCallback)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting BLE scan", e)
            ExtensionDiagnostics.onError("SecurityException: ${e.message}")
            close()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE scan", e)
            ExtensionDiagnostics.onError("Scan error: ${e.message}")
            close()
            false
        }

        awaitClose {
            val shouldScheduleGrace = synchronized(gattLock) {
                if (activeScanCallback === scanCallback) {
                    activeScanCallback = null
                }
                activeTokens.isEmpty()
            }
            if (scanStarted) {
                try {
                    scanner.stopScan(scanCallback)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping BLE scan", e)
                }
            }
            if (shouldScheduleGrace) {
                synchronized(gattLock) {
                    scheduleBleReleaseGraceLocked()
                }
            }
        }
    }

    private fun scheduleBleReleaseGraceLocked() {
        disconnectGraceJob?.cancel()
        disconnectGraceJob = scope.launch {
            delay(DISCONNECT_GRACE_MS)
            synchronized(gattLock) {
                if (activeTokens.isEmpty() && activeScanCallback == null) {
                    disconnectLocked()
                }
            }
        }
    }

    fun acquireConnection(macAddress: String): Long {
        val token: Long
        val shouldOpenNewGatt: Boolean
        synchronized(gattLock) {
            val hadConsumers = activeTokens.isNotEmpty()
            token = nextToken++
            activeTokens.add(token)
            ExtensionDiagnostics.onConsumersChanged(activeTokens.size)
            disconnectGraceJob?.cancel()
            disconnectGraceJob = null

            if (targetMacAddress == macAddress &&
                (bluetoothGatt != null || (hadConsumers && reconnectJob?.isActive == true))
            ) {
                shouldOpenNewGatt = false
            } else {
                if (targetMacAddress != macAddress) {
                    if (targetMacAddress != null) {
                        sessionStartOdoMeters = null
                    }
                    consecutiveFastFailures = 0
                    consecutiveIntervalErrors = 0
                }
                targetMacAddress = macAddress
                reconnectJob?.cancel()
                reconnectJob = null
                closeGattLocked(disconnectFirst = isGattConnectedLocked())
                shouldOpenNewGatt = true
            }
        }
        if (shouldOpenNewGatt) {
            openGattConnection(macAddress)
        }
        return token
    }

    fun releaseConnection(token: Long) {
        synchronized(gattLock) {
            activeTokens.remove(token)
            ExtensionDiagnostics.onConsumersChanged(activeTokens.size)
            if (activeTokens.isNotEmpty()) {
                return
            }
            scheduleBleReleaseGraceLocked()
        }
    }

    fun disconnect() {
        synchronized(gattLock) {
            disconnectLocked()
        }
    }

    fun destroy() {
        disconnect()
        runCatching {
            context.unregisterReceiver(bondStateReceiver)
        }.onFailure { e ->
            Log.w(TAG, "Failed to unregister bondStateReceiver", e)
        }
    }

    private fun disconnectLocked() {
        activeTokens.clear()
        ExtensionDiagnostics.onConsumersChanged(0)
        disconnectGraceJob?.cancel()
        disconnectGraceJob = null
        targetMacAddress = null
        consecutiveFastFailures = 0
        consecutiveIntervalErrors = 0
        reconnectJob?.cancel()
        reconnectJob = null
        closeGattLocked(disconnectFirst = isGattConnectedLocked())
        sessionStartOdoMeters = null
        _telemetry.value = KtepsTelemetry(connected = false)
        releaseBleAppRegistrationLocked()
    }

    @SuppressLint("MissingPermission")
    private fun openGattConnection(macAddress: String) {
        synchronized(gattLock) {
            if (targetMacAddress != macAddress) return
            if (bluetoothGatt != null) return
        }

        if (!BlePermissions.hasConnectPermissions(context)) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission; retrying in ${RECONNECT_DELAY_MS}ms")
            ExtensionDiagnostics.onError("Missing BLUETOOTH_CONNECT")
            scheduleReconnect(macAddress)
            return
        }

        val adapter = bluetoothAdapter()
        if (adapter == null) {
            Log.w(TAG, "Bluetooth adapter unavailable; retrying in ${RECONNECT_DELAY_MS}ms")
            ExtensionDiagnostics.onError("Bluetooth adapter null")
            scheduleReconnect(macAddress, RECONNECT_DELAY_MS)
            return
        }

        when (ensureBleEnabled(adapter)) {
            BleAvailability.READY -> Unit
            BleAvailability.ENABLING -> {
                Log.w(TAG, "Bluetooth LE turning on, retrying in ${BLE_ENABLING_RETRY_MS}ms")
                scheduleReconnect(macAddress, BLE_ENABLING_RETRY_MS)
                return
            }
            BleAvailability.UNAVAILABLE -> {
                // Cause already reported to diagnostics; poll slowly until Bluetooth is available.
                scheduleReconnect(macAddress, RECONNECT_DELAY_MS)
                return
            }
        }

        ExtensionDiagnostics.clearError()
        stopActiveScan()

        try {
            val device = adapter.getRemoteDevice(macAddress)
            // connectGatt() is called while holding gattLock on purpose: GATT callbacks run on a
            // binder thread and can fire before connectGatt() returns (e.g. fast status 133 or an
            // already-connected peer). Without the lock such a callback would see a null
            // bluetoothGatt, close the new GATT as "stale", and leave a dead connection behind.
            synchronized(gattLock) {
                if (targetMacAddress != macAddress || bluetoothGatt != null) return
                Log.i(TAG, "Connecting to Shimano E-Bike ($macAddress)...")
                ExtensionDiagnostics.onGattEvent("connectGatt($macAddress)")
                val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) {
                    Log.w(TAG, "connectGatt returned null for $macAddress")
                    ExtensionDiagnostics.onError("connectGatt returned null")
                    scheduleReconnect(macAddress, RECONNECT_DELAY_MS)
                    return
                }
                bluetoothGatt = gatt
                handshakeStage = HandshakeStage.CONNECTING
                startConnectWatchdogLocked(gatt, macAddress)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException connecting to $macAddress", e)
            ExtensionDiagnostics.onError("SecurityException: ${e.message}")
            synchronized(gattLock) {
                closeGattLocked(disconnectFirst = false)
            }
            scheduleReconnect(macAddress, RECONNECT_DELAY_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to $macAddress", e)
            ExtensionDiagnostics.onError("Connect error: ${e.message}")
            synchronized(gattLock) {
                closeGattLocked(disconnectFirst = false)
            }
            scheduleReconnect(macAddress, RECONNECT_DELAY_MS)
        }
    }

    private fun startConnectWatchdogLocked(gatt: BluetoothGatt, macAddress: String) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            val timedOut = synchronized(gattLock) {
                gatt === bluetoothGatt && handshakeStage == HandshakeStage.CONNECTING
            }
            if (timedOut) {
                Log.w(TAG, "Connection attempt to $macAddress timed out after ${CONNECT_TIMEOUT_MS}ms")
                ExtensionDiagnostics.onGattEvent("connectTimeout")
                synchronized(gattLock) {
                    if (gatt === bluetoothGatt) {
                        closeGattLocked(disconnectFirst = false)
                    }
                }
                scheduleReconnect(macAddress, RECONNECT_DELAY_MS)
            }
        }
    }

    private fun scheduleReconnect(macAddress: String, delayMs: Long = RECONNECT_DELAY_MS) {
        synchronized(gattLock) {
            if (targetMacAddress != macAddress) return
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                val shouldRetry = synchronized(gattLock) {
                    targetMacAddress == macAddress && bluetoothGatt == null
                }
                if (shouldRetry) {
                    openGattConnection(macAddress)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattLocked(disconnectFirst: Boolean = false) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        serviceDiscoveryJob?.cancel()
        serviceDiscoveryJob = null
        postDiscoveryJob?.cancel()
        postDiscoveryJob = null
        stabilizationJob?.cancel()
        stabilizationJob = null
        handshakeWatchdogJob?.cancel()
        handshakeWatchdogJob = null
        batteryPollJob?.cancel()
        batteryPollJob = null
        authRetryCount = 0
        handshakeStage = HandshakeStage.IDLE
        val gatt = bluetoothGatt ?: return
        bluetoothGatt = null
        if (disconnectFirst) {
            try {
                gatt.disconnect()
            } catch (_: Exception) {
            }
        }
        try {
            gatt.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing BluetoothGatt", e)
        }
    }

    /**
     * Tears down [gatt] (if it is still the active connection) and schedules a fresh connection
     * attempt. Used whenever the link is up but the handshake cannot complete, so the extension
     * never stays "connected" without delivering data.
     */
    private fun restartConnection(
        gatt: BluetoothGatt,
        reason: String,
        refreshCache: Boolean = false,
        delayMs: Long = FAST_RECONNECT_DELAY_MS
    ) {
        val mac = synchronized(gattLock) {
            if (gatt !== bluetoothGatt) return
            Log.w(TAG, "Restarting GATT connection: $reason")
            ExtensionDiagnostics.onConnectionRestarted(reason)
            if (refreshCache) {
                refreshGattCache(gatt)
            }
            closeGattLocked(disconnectFirst = true)
            _telemetry.update {
                it.copy(
                    connected = false,
                    speedKmh = 0.0,
                    riderPowerWatts = 0,
                    cadenceRpm = 0
                )
            }
            targetMacAddress
        }
        if (mac != null) {
            scheduleReconnect(mac, delayMs)
        }
    }

    /**
     * Clears Android's cached GATT database for this device (hidden API, widely used by BLE
     * libraries). Helps when a stale cache hides the Shimano telemetry service.
     */
    private fun refreshGattCache(gatt: BluetoothGatt) {
        val refreshed = runCatching {
            gatt.javaClass.getMethod("refresh").invoke(gatt) as? Boolean ?: false
        }.getOrDefault(false)
        Log.i(TAG, "GATT cache refresh requested (result=$refreshed)")
    }

    private fun startHandshakeWatchdogLocked(gatt: BluetoothGatt) {
        handshakeWatchdogJob?.cancel()
        handshakeWatchdogJob = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            val timedOut = synchronized(gattLock) {
                gatt === bluetoothGatt && handshakeStage != HandshakeStage.READY
            }
            if (timedOut) {
                restartConnection(gatt, "handshake timeout")
            }
        }
    }

    /** Moves the handshake of [gatt] to [stage]; returns false if [gatt] is no longer active. */
    private fun setHandshakeStage(gatt: BluetoothGatt, stage: HandshakeStage): Boolean {
        synchronized(gattLock) {
            if (gatt !== bluetoothGatt) return false
            handshakeStage = stage
            return true
        }
    }

    private fun currentHandshakeStage(gatt: BluetoothGatt): HandshakeStage? =
        synchronized(gattLock) {
            if (gatt === bluetoothGatt) handshakeStage else null
        }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val mac = synchronized(gattLock) {
                if (gatt !== bluetoothGatt) {
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }
                    return
                }
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                targetMacAddress
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to ${gatt.device.address}, discovering services...")
                synchronized(gattLock) {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    handshakeStage = HandshakeStage.DISCOVERING_SERVICES
                    startHandshakeWatchdogLocked(gatt)
                    serviceDiscoveryJob?.cancel()
                    serviceDiscoveryJob = scope.launch {
                        delay(SERVICE_DISCOVERY_DELAY_MS)
                        if (isActiveGatt(gatt)) {
                            val started = try {
                                gatt.discoverServices()
                            } catch (e: SecurityException) {
                                Log.e(TAG, "SecurityException during discoverServices", e)
                                false
                            }
                            if (!started) {
                                restartConnection(gatt, "discoverServices not started")
                            }
                        }
                    }
                }
                // Telemetry is only flagged as connected once the handshake is complete
                // (see completeHandshake), so Karoo never receives placeholder values.
                ExtensionDiagnostics.onGattConnected()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from ${gatt.device.address} (status=$status)")
                if (status == GATT_CONN_INTERVAL_UNACCEPTABLE) {
                    Log.w(
                        TAG,
                        "Peripheral rejected connection interval (status=59 / 0x3B); extending post-discovery settle delay on retry"
                    )
                }
                ExtensionDiagnostics.onGattDisconnected(status)
                val retryDelayMs = synchronized(gattLock) {
                    if (bluetoothGatt === gatt) {
                        val wasReady = handshakeStage == HandshakeStage.READY
                        if (status == GATT_CONN_INTERVAL_UNACCEPTABLE) {
                            consecutiveIntervalErrors++
                        }
                        if (!wasReady) {
                            consecutiveFastFailures++
                        }
                        closeGattLocked(disconnectFirst = false)
                    } else {
                        try {
                            gatt.close()
                        } catch (_: Exception) {
                        }
                    }
                    _telemetry.update {
                        it.copy(
                            connected = false,
                            speedKmh = 0.0,
                            riderPowerWatts = 0,
                            cadenceRpm = 0
                        )
                    }
                    if (status == GATT_STATUS_GENERIC_ERROR || consecutiveFastFailures > 2) {
                        RECONNECT_DELAY_MS
                    } else {
                        FAST_RECONNECT_DELAY_MS
                    }
                }
                if (mac != null) {
                    scheduleReconnect(mac, retryDelayMs)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isActiveGatt(gatt)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed with status=$status")
                ExtensionDiagnostics.onGattEvent("discoverFail($status)")
                restartConnection(gatt, "discoverServices failed ($status)", refreshCache = true)
                return
            }
            ExtensionDiagnostics.onGattEvent("servicesDiscovered")

            val hasDfly = gatt.getService(ShimanoBleParser.SERVICE_SHIMANO_DFLY) != null
            val telemetryChar = findTelemetryCharacteristic(gatt)
            val rawDeviceName = runCatching { gatt.device.name }.getOrNull()
            val isKnownUnsupported = ShimanoBleParser.isUnsupportedModel(rawDeviceName)

            if (!hasDfly || telemetryChar == null) {
                // Only permanently abort for explicitly known unsupported display units (e.g. SC-E8000).
                // Never use SERVICE_SHIMANO_AUTH (0x18FF) as a heuristic here: EW-EN100, SC-EM800, and
                // SC-E7000 all expose 0x18FF alongside 0x18EF, so a partial GATT cache must refresh & retry.
                if (isKnownUnsupported) {
                    val modelName = rawDeviceName?.ifBlank { "Shimano Device" } ?: "Shimano Device"
                    val errorMsg = "Incompatible device: $modelName lacks BLE telemetry (SC-E8000 not supported)"
                    Log.e(TAG, errorMsg)
                    ExtensionDiagnostics.onError(errorMsg)
                    ExtensionDiagnostics.onGattEvent("unsupportedModel($modelName)")
                    synchronized(gattLock) {
                        closeGattLocked(disconnectFirst = true)
                        _telemetry.update {
                            it.copy(
                                connected = false,
                                speedKmh = 0.0,
                                riderPowerWatts = 0,
                                cadenceRpm = 0
                            )
                        }
                    }
                    return
                }

                ExtensionDiagnostics.onGattEvent("missing 0x2AC1")
                restartConnection(
                    gatt,
                    "telemetry characteristic 0x2AC1 missing",
                    refreshCache = true,
                    delayMs = RECONNECT_DELAY_MS
                )
                return
            }

            // Wait for Android's post-discovery connection interval update (7.5ms -> 45ms, ~85ms OTA)
            // to finish before arming Shimano's ble_conn_params state machine via 0x2AC1 CCCD write.
            val settleMs = synchronized(gattLock) {
                if (gatt !== bluetoothGatt) return
                handshakeStage = HandshakeStage.SETTLING_CONN_PARAMS
                val computedSettleMs =
                    (POST_DISCOVERY_SETTLE_MS + consecutiveIntervalErrors * POST_DISCOVERY_SETTLE_STEP_MS)
                        .coerceAtMost(POST_DISCOVERY_SETTLE_MAX_MS)
                postDiscoveryJob?.cancel()
                postDiscoveryJob = scope.launch {
                    delay(computedSettleMs)
                    beginPostDiscoveryHandshake(gatt)
                }
                computedSettleMs
            }
            Log.d(TAG, "Services discovered; waiting ${settleMs}ms for BLE connection parameters to settle...")
            ExtensionDiagnostics.onGattEvent("settle(${settleMs}ms)")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicRead(gatt, characteristic, value, status)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicRead(gatt, characteristic, characteristic.value, status)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val stage = currentHandshakeStage(gatt) ?: return
            val characteristicUuid = descriptor.characteristic?.uuid

            if (isAuthenticationOrEncryptionError(status)) {
                handleAuthenticationRequired(gatt, "descriptor write ($status)")
                return
            }

            if (stage == HandshakeStage.SUBSCRIBING_TELEMETRY &&
                characteristicUuid == ShimanoBleParser.CHAR_SHIMANO_TELEMETRY
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    ExtensionDiagnostics.onGattEvent("notify(0x2AC1)")
                    onTelemetrySubscribed(gatt)
                } else {
                    restartConnection(gatt, "enable 0x2AC1 notifications failed ($status)")
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (!isActiveGatt(gatt)) return
            if (characteristic.uuid == ShimanoBleParser.CHAR_SHIMANO_TELEMETRY &&
                currentHandshakeStage(gatt) == HandshakeStage.SUBSCRIBING_TELEMETRY
            ) {
                onTelemetrySubscribed(gatt)
            }
            handleCharacteristicData(characteristic.uuid, value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (!isActiveGatt(gatt)) return
                if (characteristic.uuid == ShimanoBleParser.CHAR_SHIMANO_TELEMETRY &&
                    currentHandshakeStage(gatt) == HandshakeStage.SUBSCRIBING_TELEMETRY
                ) {
                    onTelemetrySubscribed(gatt)
                }
                handleCharacteristicData(characteristic.uuid, characteristic.value)
            }
        }
    }

    private fun beginPostDiscoveryHandshake(gatt: BluetoothGatt) {
        if (!isActiveGatt(gatt)) return
        // Read Battery Level (0x2A19) first, then enable 0x2AC1 notifications
        val batteryChar = findBatteryCharacteristic(gatt)
        if (batteryChar != null && setHandshakeStage(gatt, HandshakeStage.READING_BATTERY)) {
            if (readCharacteristicSafely(gatt, batteryChar)) {
                return
            }
        }
        subscribeTelemetry(gatt)
    }

    private fun isActiveGatt(gatt: BluetoothGatt): Boolean =
        synchronized(gattLock) { gatt === bluetoothGatt }

    private fun findBatteryCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? =
        gatt.getService(ShimanoBleParser.SERVICE_BATTERY)
            ?.getCharacteristic(ShimanoBleParser.CHAR_BATTERY_LEVEL)

    private fun findTelemetryCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? =
        gatt.getService(ShimanoBleParser.SERVICE_SHIMANO_DFLY)
            ?.getCharacteristic(ShimanoBleParser.CHAR_SHIMANO_TELEMETRY)

    @SuppressLint("MissingPermission")
    private fun readCharacteristicSafely(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean = try {
        gatt.readCharacteristic(characteristic)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read characteristic ${characteristic.uuid}", e)
        false
    }

    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
        status: Int
    ) {
        val stage = currentHandshakeStage(gatt) ?: return
        if (isAuthenticationOrEncryptionError(status)) {
            handleAuthenticationRequired(gatt, "characteristic read ($status)")
            return
        }
        if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == ShimanoBleParser.CHAR_BATTERY_LEVEL) {
            val level = ShimanoBleParser.parseBatteryLevel(value)
            if (level != null) {
                _telemetry.update { it.copy(batteryPercent = level) }
                ExtensionDiagnostics.onGattEvent("battery($level%)")
            }
        }
        // Continue the handshake only for the initial read; periodic battery reads end here.
        if (stage == HandshakeStage.READING_BATTERY) {
            subscribeTelemetry(gatt)
        }
    }

    private fun isAuthenticationOrEncryptionError(status: Int): Boolean =
        status == GATT_INSUFFICIENT_AUTHENTICATION ||
            status == GATT_INSUFFICIENT_AUTHORIZATION ||
            status == GATT_INSUFFICIENT_ENCRYPTION ||
            status == GATT_AUTH_FAIL

    @SuppressLint("MissingPermission")
    private fun handleAuthenticationRequired(gatt: BluetoothGatt, reason: String) {
        if (!isActiveGatt(gatt)) return

        val bondState = try {
            gatt.device.bondState
        } catch (_: SecurityException) {
            BluetoothDevice.BOND_NONE
        }

        if (bondState == BluetoothDevice.BOND_BONDED) {
            val shouldRestart = synchronized(gattLock) {
                if (gatt !== bluetoothGatt) return
                if (authRetryCount >= 1) {
                    true
                } else {
                    authRetryCount++
                    handshakeStage = HandshakeStage.SETTLING_CONN_PARAMS
                    startHandshakeWatchdogLocked(gatt)
                    postDiscoveryJob?.cancel()
                    postDiscoveryJob = scope.launch {
                        delay(POST_DISCOVERY_SETTLE_MS)
                        beginPostDiscoveryHandshake(gatt)
                    }
                    false
                }
            }
            if (shouldRestart) {
                Log.w(TAG, "Authentication failed despite existing bond ($reason); restarting GATT")
                restartConnection(
                    gatt,
                    "authentication failed despite existing bond ($reason)",
                    delayMs = RECONNECT_DELAY_MS
                )
            } else {
                Log.i(TAG, "Device already bonded ($reason); waiting for link encryption before retrying...")
                ExtensionDiagnostics.onGattEvent("encryptingLink($reason)")
            }
            return
        }

        Log.w(TAG, "GATT authentication or encryption required ($reason). Waiting for bonding...")
        ExtensionDiagnostics.onGattEvent("authRequired($reason)")

        if (!setHandshakeStage(gatt, HandshakeStage.WAITING_FOR_BOND)) return
        extendHandshakeTimeoutForBonding(gatt)

        if (bondState == BluetoothDevice.BOND_NONE) {
            val started = try {
                gatt.device.createBond()
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException calling createBond()", e)
                false
            }
            Log.i(TAG, "Triggered createBond() for ${gatt.device.address}: $started")
            if (!started) {
                restartConnection(gatt, "createBond() returned false", delayMs = RECONNECT_DELAY_MS)
            }
        }
    }

    private fun extendHandshakeTimeoutForBonding(gatt: BluetoothGatt) {
        handshakeWatchdogJob?.cancel()
        handshakeWatchdogJob = scope.launch {
            delay(BONDING_TIMEOUT_MS)
            val timedOut = synchronized(gattLock) {
                gatt === bluetoothGatt && handshakeStage != HandshakeStage.READY
            }
            if (timedOut) {
                restartConnection(gatt, "bonding / passkey timeout (${BONDING_TIMEOUT_MS}ms)")
            }
        }
    }

    private fun subscribeTelemetry(gatt: BluetoothGatt) {
        val telemetryChar = findTelemetryCharacteristic(gatt)
        if (telemetryChar == null) {
            restartConnection(gatt, "telemetry characteristic 0x2AC1 missing", refreshCache = true)
            return
        }
        if (!setHandshakeStage(gatt, HandshakeStage.SUBSCRIBING_TELEMETRY)) return
        when (enableNotifications(gatt, telemetryChar)) {
            NotifyResult.WRITE_PENDING -> Unit // continues in onDescriptorWrite
            NotifyResult.NO_CCCD -> {
                ExtensionDiagnostics.onGattEvent("notify(0x2AC1, no CCCD)")
                onTelemetrySubscribed(gatt)
            }
            NotifyResult.FAILED -> restartConnection(gatt, "enable 0x2AC1 notifications failed")
        }
    }

    /**
     * Telemetry notifications are enabled; wait briefly for peripheral L2CAP connection parameter
     * negotiation (~270ms) and initial packets to settle before reporting CONNECTED to Karoo OS.
     */
    private fun onTelemetrySubscribed(gatt: BluetoothGatt) {
        synchronized(gattLock) {
            if (gatt !== bluetoothGatt) return
            if (handshakeStage == HandshakeStage.STABILIZING || handshakeStage == HandshakeStage.READY) return
            handshakeStage = HandshakeStage.STABILIZING
            stabilizationJob?.cancel()
            stabilizationJob = scope.launch {
                delay(CONNECTION_STABILIZATION_MS)
                completeHandshake(gatt)
            }
        }
    }

    private fun completeHandshake(gatt: BluetoothGatt) {
        val newlyCompleted = synchronized(gattLock) {
            if (gatt !== bluetoothGatt || handshakeStage == HandshakeStage.READY) return
            handshakeStage = HandshakeStage.READY
            consecutiveFastFailures = 0
            consecutiveIntervalErrors = 0
            authRetryCount = 0
            handshakeWatchdogJob?.cancel()
            handshakeWatchdogJob = null
            stabilizationJob?.cancel()
            stabilizationJob = null
            startBatteryPollLocked(gatt)
            _telemetry.update { it.copy(connected = true) }
            true
        }
        if (newlyCompleted) {
            Log.i(TAG, "GATT handshake complete, telemetry active")
            ExtensionDiagnostics.onGattEvent("ready")
        }
    }

    /**
     * Periodically re-reads Battery Level. This keeps the value fresh even if the drive unit does
     * not send battery notifications; with notifications active it is a cheap safety net.
     */
    private fun startBatteryPollLocked(gatt: BluetoothGatt) {
        batteryPollJob?.cancel()
        batteryPollJob = null
        if (findBatteryCharacteristic(gatt) == null) return
        batteryPollJob = scope.launch {
            while (true) {
                delay(BATTERY_POLL_INTERVAL_MS)
                if (currentHandshakeStage(gatt) != HandshakeStage.READY) break
                val batteryChar = findBatteryCharacteristic(gatt) ?: break
                readCharacteristicSafely(gatt, batteryChar)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): NotifyResult {
        return try {
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                Log.w(TAG, "setCharacteristicNotification failed for ${characteristic.uuid}")
                return NotifyResult.FAILED
            }
            val descriptor = characteristic.getDescriptor(ShimanoBleParser.CCCD_UUID)
                ?: return NotifyResult.NO_CCCD
            if (writeCccdDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                NotifyResult.WRITE_PENDING
            } else {
                Log.w(TAG, "CCCD write not initiated for ${characteristic.uuid}")
                NotifyResult.FAILED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable notifications for ${characteristic.uuid}", e)
            ExtensionDiagnostics.onError("Notify error: ${e.message}")
            NotifyResult.FAILED
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCccdDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleCharacteristicData(uuid: UUID, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return

        when (uuid) {
            ShimanoBleParser.CHAR_SHIMANO_TELEMETRY -> {
                _telemetry.update { current ->
                    val parsed = ShimanoBleParser.parseTelemetryPacket(
                        bytes = bytes,
                        current = current,
                        sessionStartOdoMeters = sessionStartOdoMeters
                    )
                    sessionStartOdoMeters = parsed.sessionStartOdoMeters
                    parsed.telemetry
                }
            }

            ShimanoBleParser.CHAR_BATTERY_LEVEL -> {
                val level = ShimanoBleParser.parseBatteryLevel(bytes) ?: return
                _telemetry.update { it.copy(batteryPercent = level) }
            }
        }
    }
}
