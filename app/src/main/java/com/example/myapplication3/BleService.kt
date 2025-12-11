package com.example.myapplication3

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myapplication3.BleRole.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BleConnectionsService : Service() {

    private val TAG = "BleService"
    private val NOTIFICATION_ID = 2001
    private val CHANNEL_ID = "ble_connections_channel"

    // BLE Constants
    private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CHARACTERISTIC_UUID_WRITE = UUID.fromString("00001102-0000-1000-8000-00805F9B34FB")
    private val CHARACTERISTIC_UUID_NOTIFY = UUID.fromString("00001103-0000-1000-8000-00805F9B34FB")
    private val CHARACTERISTIC_UUID_READ = UUID.fromString("00001104-0000-1000-8000-00805F9B34FB")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())

    // BLE Managers
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    // State
    var currentRole = IDLE
        private set
    var isScanning = false
        private set
    var isAdvertising = false
        private set

    // Data structures
    private val discoveredDeviceMap = ConcurrentHashMap<String, DiscoveredDevice>()
    private val connectedGattClients = ConcurrentHashMap<String, ConnectedDevice>()
    private val connectedClientMap = ConcurrentHashMap<String, BluetoothDevice>()

    // Characteristics
    private var writeCharacteristicServer: BluetoothGattCharacteristic? = null
    private var notifyCharacteristicServer: BluetoothGattCharacteristic? = null
    private var readCharacteristicServer: BluetoothGattCharacteristic? = null

    // Callbacks
    var onRoleChanged: ((BleRole) -> Unit)? = null
    var onScanningStateChanged: ((Boolean) -> Unit)? = null
    var onAdvertisingStateChanged: ((Boolean) -> Unit)? = null
    var onDevicesDiscovered: ((List<DiscoveredDevice>) -> Unit)? = null
    var onDevicesConnected: ((List<ConnectedDevice>) -> Unit)? = null
    var onClientsConnected: ((List<BluetoothDevice>) -> Unit)? = null
    var onLogMessage: ((String) -> Unit)? = null
    var onMessageReceived: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): BleConnectionsService = this@BleConnectionsService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BLE Service created")
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        createNotificationChannel()

        // Automatically start as peripheral and begin advertising
        setRole(PERIPHERAL)
        startPeripheralAdvertising()
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service bound")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        startForeground(NOTIFICATION_ID, createNotification("BLE Connections Active"))
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Connections",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running BLE Connections in background"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun createNotification(message: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE Connections")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(message))
        Log.d(TAG, "Notification updated: $message")
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        onLogMessage?.invoke(message)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    // ============================================
    // ROLE MANAGEMENT
    // ============================================

    fun setRole(role: BleRole) {
        if (currentRole == role) return

        stopCurrentRole()
        currentRole = role
        log("Switched role to: $role")
        onRoleChanged?.invoke(role)

        when (role) {
            CENTRAL -> {
                isAdvertising = false
                connectedClientMap.clear()
                onClientsConnected?.invoke(emptyList())
                updateNotification("Central Mode")
            }
            PERIPHERAL -> {
                isScanning = false
                discoveredDeviceMap.clear()
                connectedGattClients.clear()
                onDevicesDiscovered?.invoke(emptyList())
                onDevicesConnected?.invoke(emptyList())
                updateNotification("Peripheral Mode")
            }
            IDLE -> {
                updateNotification("BLE Connections Active")
            }
        }
    }

    private fun stopCurrentRole() {
        if (currentRole == PERIPHERAL) {
            stopPeripheral()
        }
        currentRole = IDLE
    }

    // ============================================
    // PERIPHERAL ROLE
    // ============================================

    fun startPeripheralAdvertising() {
        if (currentRole != PERIPHERAL) {
            log("Not in Peripheral role")
            return
        }
        if (isAdvertising) {
            log("Already advertising")
            return
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            log("BLUETOOTH_ADVERTISE permission missing")
            return
        }

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            log("Failed to get BLE Advertiser")
            return
        }

        if (!setupGattServer()) {
            log("Failed to setup GATT Server")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        log("Starting Advertising...")
        updateNotification("Starting advertising...")
        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    fun stopPeripheralAdvertising() {
        if (!isAdvertising) return
        log("Stopping Advertising")
        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
        onAdvertisingStateChanged?.invoke(false)
        updateNotification("Peripheral Mode")
    }

    private fun setupGattServer(): Boolean {
        if (gattServer != null) {
            log("GATT Server already running")
            return true
        }

        gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
        if (gattServer == null) {
            log("Failed to open GATT Server")
            return false
        }

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        writeCharacteristicServer = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID_WRITE,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        readCharacteristicServer = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID_READ,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        notifyCharacteristicServer = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID_NOTIFY,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccdDescriptor = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        notifyCharacteristicServer?.addDescriptor(cccdDescriptor)
        readCharacteristicServer?.addDescriptor(cccdDescriptor)

        service.addCharacteristic(writeCharacteristicServer)
        service.addCharacteristic(notifyCharacteristicServer)
        service.addCharacteristic(readCharacteristicServer)

        val serviceAdded = gattServer?.addService(service) ?: false
        if (serviceAdded) {
            log("GATT Service added successfully")
            return true
        } else {
            log("Failed to add GATT Service")
            gattServer?.close()
            gattServer = null
            return false
        }
    }

    private fun stopGattServer() {
        if (gattServer == null) return
        log("Closing GATT Server")
        gattServer?.close()
        gattServer = null
        writeCharacteristicServer = null
        notifyCharacteristicServer = null
        connectedClientMap.clear()
        onClientsConnected?.invoke(emptyList())
    }

    private fun stopPeripheral() {
        log("Stopping Peripheral Role...")
        stopPeripheralAdvertising()
        stopGattServer()
        isAdvertising = false
        connectedClientMap.clear()
        onClientsConnected?.invoke(emptyList())
    }


    // ============================================
    // SCAN CALLBACK
    // ============================================

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
            val deviceAddress = device.address

            if (!discoveredDeviceMap.containsKey(deviceAddress)) {
                log("Discovered: $deviceName ($deviceAddress)")
                val discovered = DiscoveredDevice(deviceName, deviceAddress, device)
                discoveredDeviceMap[deviceAddress] = discovered
                onDevicesDiscovered?.invoke(discoveredDeviceMap.values.toList())
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { result ->
                val device = result.device
                val deviceName = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
                val deviceAddress = device.address
                if (!discoveredDeviceMap.containsKey(deviceAddress)) {
                    log("Discovered Batch: $deviceName ($deviceAddress)")
                    discoveredDeviceMap[deviceAddress] = DiscoveredDevice(deviceName, deviceAddress, device)
                }
            }
            onDevicesDiscovered?.invoke(discoveredDeviceMap.values.toList())
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan Failed: Error Code $errorCode")
            isScanning = false
            onScanningStateChanged?.invoke(false)
            updateNotification("Scan failed")
        }
    }

    // ============================================
    // GATT CLIENT CALLBACK
    // ============================================

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        log("Connected to $deviceAddress")
                        connectedGattClients[deviceAddress] = ConnectedDevice(gatt.device, gatt)
                        onDevicesConnected?.invoke(connectedGattClients.values.toList())
                        updateNotification("Connected to ${gatt.device.name ?: deviceAddress}")

                        log("Discovering services for $deviceAddress")
                        val servicesDiscovered = gatt.discoverServices()
                        if (!servicesDiscovered) {
                            log("Failed to start service discovery")
                            connectedGattClients.remove(deviceAddress)
                            gatt.close()
                            onDevicesConnected?.invoke(connectedGattClients.values.toList())
                        }
                    } else {
                        log("Connection failed with status $status")
                        connectedGattClients.remove(deviceAddress)
                        gatt.close()
                        onDevicesConnected?.invoke(connectedGattClients.values.toList())
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Disconnected from $deviceAddress")
                    connectedGattClients.remove(deviceAddress)
                    gatt.close()
                    onDevicesConnected?.invoke(connectedGattClients.values.toList())
                    updateNotification("Device disconnected")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Services discovered for $deviceAddress")
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val writeChar = service.getCharacteristic(CHARACTERISTIC_UUID_WRITE)
                    val readChar = service.getCharacteristic(CHARACTERISTIC_UUID_READ)

                    connectedGattClients[deviceAddress]?.let { connectedDevice ->
                        connectedDevice.writeCharacteristic = writeChar
                        connectedDevice.readCharacteristic = readChar
                        onDevicesConnected?.invoke(connectedGattClients.values.toList())
                    }
                } else {
                    log("Service not found for $deviceAddress")
                }
            } else {
                log("Service discovery failed with status $status")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Write successful for $deviceAddress")
                updateNotification("Write successful for $deviceAddress")
            } else {
                log("Write failed for $deviceAddress with status: $status")
                updateNotification("Write failed for $deviceAddress with status: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == CHARACTERISTIC_UUID_NOTIFY || characteristic.uuid == CHARACTERISTIC_UUID_READ) {
                val receivedMessage = value.toString(Charsets.UTF_8)
                log("Received: $receivedMessage")
                onMessageReceived?.invoke(receivedMessage)
                updateNotification(receivedMessage)
            }
        }
    }

    // ============================================
    // ADVERTISE CALLBACK
    // ============================================

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            log("Advertising started successfully")
            isAdvertising = true
            onAdvertisingStateChanged?.invoke(true)
            updateNotification("Advertising...")
        }

        override fun onStartFailure(errorCode: Int) {
            log("Advertising failed: Error Code $errorCode")
            isAdvertising = false
            onAdvertisingStateChanged?.invoke(false)
            updateNotification("Advertising failed")
        }
    }

    // ============================================
    // GATT SERVER CALLBACK
    // ============================================

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val deviceAddress = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Client connected: $deviceAddress")
                connectedClientMap[deviceAddress] = device
                onClientsConnected?.invoke(connectedClientMap.values.toList())
                updateNotification("Client connected: ${device.name ?: deviceAddress}")
                stopPeripheralAdvertising()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Client disconnected: $deviceAddress")
                connectedClientMap.remove(deviceAddress)
                onClientsConnected?.invoke(connectedClientMap.values.toList())
                updateNotification("Client disconnected")
                startPeripheralAdvertising()
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            log("Read request from ${device.address}")
            var responseValue: ByteArray? = null
            var responseStatus = BluetoothGatt.GATT_FAILURE

            if (characteristic.uuid == CHARACTERISTIC_UUID_READ) {
                responseValue = "Time: ${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
                responseStatus = BluetoothGatt.GATT_SUCCESS
            }

            gattServer?.sendResponse(device, requestId, responseStatus, offset, responseValue)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val deviceAddress = device.address
            log("Write request from $deviceAddress")

            var responseStatus = BluetoothGatt.GATT_FAILURE
            if (characteristic.uuid == CHARACTERISTIC_UUID_WRITE || characteristic.uuid == CHARACTERISTIC_UUID_READ) {
                val message = value?.toString(Charsets.UTF_8) ?: "null"
                log("Received: $message from $deviceAddress")
                onMessageReceived?.invoke(message)
                responseStatus = BluetoothGatt.GATT_SUCCESS
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, responseStatus, offset, value)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            var responseStatus = BluetoothGatt.GATT_FAILURE
            if (descriptor.uuid == CCCD_UUID) {
                responseStatus = BluetoothGatt.GATT_SUCCESS
                when {
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ->
                        log("Notifications enabled by ${device.address}")
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) ->
                        log("Indications enabled by ${device.address}")
                    else ->
                        log("Notifications disabled by ${device.address}")
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, responseStatus, offset, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            log("Notification sent to ${device.address}, Status: ${if (status == BluetoothGatt.GATT_SUCCESS) "Success" else "Failure"}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        log("Service destroyed")
        stopCurrentRole()
        handler.removeCallbacksAndMessages(null)
    }

    fun getConnectedDevices(): List<ConnectedDevice> {
        return connectedGattClients.values.toList()
    }

    fun getConnectedClients(): List<BluetoothDevice> {
        return connectedClientMap.values.toList()
    }
}