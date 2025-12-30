package com.example.myapplication3

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelUuid
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BlePeripheralService : Service() {

    private val TAG = "BlePeripheralService"
    private val NOTIFICATION_ID = 2001
    private val CHANNEL_ID = "ble_peripheral_channel"

    // BLE Constants
    private val serviceUuid = UUID.fromString("bb21801d-a324-418f-abc7-f23d10e7d588")
    private val characteristicUuidMsisdn = UUID.fromString("b6a0912e-e715-438b-96a2-b21149015db1")
    private val characteristicUuidWrite = UUID.fromString("b6a0912e-e715-438b-96a2-b21149015db2")
    private val characteristicUuidNotify = UUID.fromString("b6a0912e-e715-438b-96a2-b21149015db3")
    private val characteristicUuidRead = UUID.fromString("00001104-0000-1000-8000-00805F9B34FB")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val binder = LocalBinder()

    // BLE Managers
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    // State
    var isAdvertising = false
        private set

    // Data structures
    private val connectedClientMap = ConcurrentHashMap<String, BluetoothDevice>()

    // Characteristics
    private var bidirectionalCharacteristicServer: BluetoothGattCharacteristic? = null
    private var writeCharacteristicServer: BluetoothGattCharacteristic? = null
    private var notifyCharacteristicServer: BluetoothGattCharacteristic? = null
    private var readCharacteristicServer: BluetoothGattCharacteristic? = null

    // Callbacks
    var onAdvertisingStateChanged: ((Boolean) -> Unit)? = null
    var onClientsConnected: ((List<BluetoothDevice>) -> Unit)? = null
    var onLogMessage: ((String) -> Unit)? = null
    var onMessageReceived: ((String, String) -> Unit)? = null // (message, from device address)

    inner class LocalBinder : Binder() {
        fun getService(): BlePeripheralService = this@BlePeripheralService
    }

    override fun onCreate() {
        super.onCreate()
        log("📱 Peripheral Service onCreate() - Service created")
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        log("✓ Bluetooth Manager initialized")
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        log("🔗 onBind() - Service bound to activity")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("🚀 onStartCommand() - Peripheral Service starting")
        startForeground(NOTIFICATION_ID, createNotification("Starting Peripheral Mode..."))
        log("✓ Service running in foreground with notification")
        
        // ✅ Automatically start advertising when service starts
        log("⚙️ Auto-starting advertising...")
        startPeripheralAdvertising()
        
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            log("📢 createNotificationChannel() - Creating notification channel")
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Peripheral Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running BLE Peripheral in background"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            log("✓ Notification channel created successfully")
        }
    }

    private fun createNotification(message: String): Notification {
        log("🔔 createNotification() - Creating notification with message: $message")
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE Peripheral")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        log("🔄 updateNotification() - Updating notification: $message")
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(message))
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        onLogMessage?.invoke(message)
    }

    private fun hasPermission(permission: String): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            log("⚠️ Permission check failed: $permission")
        }
        return granted
    }

    // ============================================
    // PERIPHERAL ADVERTISING
    // ============================================

    fun startPeripheralAdvertising() {
        log("🎯 startPeripheralAdvertising() - Starting advertising process")
        
        if (isAdvertising) {
            log("⚠️ Already advertising, skipping")
            return
        }
        
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            log("❌ BLUETOOTH_ADVERTISE permission missing")
            return
        }
        
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            log("❌ BLUETOOTH_CONNECT permission missing")
            return
        }

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            log("❌ Failed to get BLE Advertiser from adapter")
            return
        }
        log("✓ BLE Advertiser obtained")

        if (!setupGattServer()) {
            log("❌ Failed to setup GATT Server, cannot advertise")
            return
        }

        log("⚙️ Building advertising settings...")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUuid)) // Only include service UUID to minimize size
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true) // Put device name in scan response, not main advertising data
            .build()

        log("📡 Starting BLE advertising with service UUID: $serviceUuid")
        updateNotification("Starting advertising...")
        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    fun stopPeripheralAdvertising() {
        log("🛑 stopPeripheralAdvertising() - Stopping advertising")
        if (!isAdvertising) {
            log("⚠️ Not advertising, nothing to stop")
            return
        }
        
        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
        onAdvertisingStateChanged?.invoke(false)
        updateNotification("Advertising stopped")
        log("✓ Advertising stopped successfully")
    }

    private fun setupGattServer(): Boolean {
        log("⚙️ setupGattServer() - Setting up GATT Server")
        
        if (gattServer != null) {
            log("✓ GATT Server already running")
            return true
        }

        log("📝 Opening GATT Server...")
        gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
        if (gattServer == null) {
            log("❌ Failed to open GATT Server")
            return false
        }
        log("✓ GATT Server opened successfully")

        log("🔧 Creating GATT Service with UUID: $serviceUuid")
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        bidirectionalCharacteristicServer = BluetoothGattCharacteristic(
            characteristicUuidMsisdn,
            BluetoothGattCharacteristic.PROPERTY_READ or    // Can be read
                    BluetoothGattCharacteristic.PROPERTY_WRITE,     // Can be written
            BluetoothGattCharacteristic.PERMISSION_READ or  // Read permission
                    BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        log("📝 Creating Write Characteristic (UUID: $characteristicUuidWrite)")
        writeCharacteristicServer = BluetoothGattCharacteristic(
            characteristicUuidWrite,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        log("📝 Creating Read Characteristic (UUID: $characteristicUuidRead)")
        readCharacteristicServer = BluetoothGattCharacteristic(
            characteristicUuidRead,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        log("📝 Creating Notify Characteristic (UUID: $characteristicUuidNotify)")
        notifyCharacteristicServer = BluetoothGattCharacteristic(
            characteristicUuidNotify,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        log("📝 Adding CCCD descriptor to characteristics")
        val cccdDescriptor = BluetoothGattDescriptor(
            cccdUuid,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        notifyCharacteristicServer?.addDescriptor(cccdDescriptor)
        readCharacteristicServer?.addDescriptor(cccdDescriptor)

        log("➕ Adding characteristics to service")
        service.addCharacteristic(bidirectionalCharacteristicServer)
        service.addCharacteristic(writeCharacteristicServer)
        service.addCharacteristic(notifyCharacteristicServer)
        service.addCharacteristic(readCharacteristicServer)

        log("➕ Adding service to GATT Server")
        val serviceAdded = gattServer?.addService(service) ?: false
        if (serviceAdded) {
            log("✅ GATT Service added successfully")
            return true
        } else {
            log("❌ Failed to add GATT Service")
            gattServer?.close()
            gattServer = null
            return false
        }
    }

    private fun stopGattServer() {
        log("🛑 stopGattServer() - Closing GATT Server")
        if (gattServer == null) {
            log("⚠️ GATT Server not running")
            return
        }
        
        gattServer?.close()
        gattServer = null
        writeCharacteristicServer = null
        notifyCharacteristicServer = null
        bidirectionalCharacteristicServer = null
        connectedClientMap.clear()
        onClientsConnected?.invoke(emptyList())
        log("✓ GATT Server closed and cleaned up")
    }

    // ============================================
    // SEND MESSAGE TO CONNECTED CLIENTS
    // ============================================

    fun sendMessageToClients(message: String) {
        log("📤 sendMessageToClients() - Attempting to send message: '$message'")
        
        if (gattServer == null || notifyCharacteristicServer == null || connectedClientMap.isEmpty()) {
            log("❌ Cannot send: No clients connected or server not ready")
            return
        }
        
        log("✓ Sending message to ${connectedClientMap.size} connected client(s)")
        val data = message.toByteArray(Charsets.UTF_8)
        log("📊 Message size: ${data.size} bytes")

        notifyCharacteristicServer?.value = data
        connectedClientMap.values.forEachIndexed { index, device ->
            val indicate = (notifyCharacteristicServer!!.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            gattServer?.notifyCharacteristicChanged(device, notifyCharacteristicServer, indicate)
            log("✓ Message sent to client ${index + 1}: ${device.name ?: device.address}")
        }
    }

    // ============================================
    // ADVERTISE CALLBACK
    // ============================================

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            log("✅ Advertising started successfully!")
            log("📡 Mode: ${settingsInEffect.mode}, Timeout: ${settingsInEffect.timeout}ms")
            isAdvertising = true
            onAdvertisingStateChanged?.invoke(true)
            updateNotification("Advertising - Waiting for connections")
        }

        override fun onStartFailure(errorCode: Int) {
            val errorMsg = when(errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                else -> "Unknown error"
            }
            log("❌ Advertising failed: Error Code $errorCode ($errorMsg)")
            isAdvertising = false
            onAdvertisingStateChanged?.invoke(false)
            updateNotification("Advertising failed: $errorMsg")
        }
    }

    // ============================================
    // GATT SERVER CALLBACK
    // ============================================

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val deviceAddress = device.address
            val deviceName = device.name ?: "Unknown"
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("✅ Client CONNECTED: $deviceName ($deviceAddress)")
                    log("📊 Connection status code: $status")
                    connectedClientMap[deviceAddress] = device
                    onClientsConnected?.invoke(connectedClientMap.values.toList())
                    updateNotification("Connected: $deviceName")
                    log("📈 Total connected clients: ${connectedClientMap.size}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("❌ Client DISCONNECTED: $deviceName ($deviceAddress)")
                    connectedClientMap.remove(deviceAddress)
                    onClientsConnected?.invoke(connectedClientMap.values.toList())
                    
                    if (connectedClientMap.isEmpty()) {
                        updateNotification("Advertising - No connections")
                        log("📉 No clients connected")
                    } else {
                        updateNotification("Connected: ${connectedClientMap.size} clients")
                        log("📈 Remaining connected clients: ${connectedClientMap.size}")
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, 
            requestId: Int, 
            offset: Int, 
            characteristic: BluetoothGattCharacteristic
        ) {
            log("📖 READ request from ${device.name ?: device.address}")
            log("📝 Characteristic UUID: ${characteristic.uuid}, Offset: $offset")
            
            var responseValue: ByteArray? = null
            var responseStatus = BluetoothGatt.GATT_FAILURE

            when (characteristic.uuid) {
                characteristicUuidMsisdn -> {
                    val msisdn = "01000000000"
                    responseValue = msisdn.toByteArray(Charsets.UTF_8)
                    responseStatus = BluetoothGatt.GATT_SUCCESS
                    log("✓ Sending read response: MSISDN: $msisdn")
                }
                characteristicUuidRead -> {
                    val timestamp = System.currentTimeMillis()
                    responseValue = "Time: $timestamp".toByteArray(Charsets.UTF_8)
                    responseStatus = BluetoothGatt.GATT_SUCCESS
                    log("✓ Sending read response: Time: $timestamp")
                }
                else -> {
                    log("⚠️ Unknown characteristic read request")
                }
            }

            gattServer?.sendResponse(device, requestId, responseStatus, offset, responseValue)
            log("✓ Read response sent with status: $responseStatus")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, 
            requestId: Int, 
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, 
            responseNeeded: Boolean, 
            offset: Int, 
            value: ByteArray?
        ) {
            val deviceAddress = device.address
            val deviceName = device.name ?: deviceAddress
            
            log("✍️ WRITE request from $deviceName")
            log("📝 Characteristic UUID: ${characteristic.uuid}")
            log("📊 Data size: ${value?.size ?: 0} bytes, Offset: $offset")
            log("⚙️ Response needed: $responseNeeded, Prepared write: $preparedWrite")

            var responseStatus = BluetoothGatt.GATT_FAILURE
            when (characteristic.uuid) {
                characteristicUuidMsisdn -> {
                    val msisdn = value?.toString(Charsets.UTF_8) ?: "null"
                    log("📨 MSISDN RECEIVED from $deviceName: '$msisdn'")

                    responseStatus = BluetoothGatt.GATT_SUCCESS
                    log("✓ MSISDN processed successfully")
                }
                characteristicUuidWrite, characteristicUuidRead -> {
                    val message = value?.toString(Charsets.UTF_8) ?: "null"
                    log("📨 MESSAGE RECEIVED (${value?.size} bytes) from $deviceName: '$message'")

                    // ✅ Notify UI about received message
                    onMessageReceived?.invoke(message, deviceName)

                    responseStatus = BluetoothGatt.GATT_SUCCESS
                    log("✓ Message processed successfully")
                }
                else -> {
                    log("⚠️ Unknown characteristic write request")
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, responseStatus, offset, value)
                log("✓ Write response sent with status: $responseStatus")
            } else {
                log("ℹ️ No response needed for this write")
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, 
            requestId: Int, 
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, 
            responseNeeded: Boolean, 
            offset: Int, 
            value: ByteArray?
        ) {
            log("📝 DESCRIPTOR write request from ${device.address}")
            log("📊 Descriptor UUID: ${descriptor.uuid}")
            
            var responseStatus = BluetoothGatt.GATT_FAILURE
            if (descriptor.uuid == cccdUuid) {
                responseStatus = BluetoothGatt.GATT_SUCCESS
                when {
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                        log("🔔 Notifications ENABLED by ${device.address}")
                        log("📡 Characteristic: ${descriptor.characteristic.uuid}")
                    }
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) -> {
                        log("🔔 Indications ENABLED by ${device.address}")
                        log("📡 Characteristic: ${descriptor.characteristic.uuid}")
                    }
                    else -> {
                        log("🔕 Notifications/Indications DISABLED by ${device.address}")
                    }
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, responseStatus, offset, null)
                log("✓ Descriptor write response sent")
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val statusText = if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "FAILURE ($status)"
            log("📤 Notification sent to ${device.address}: $statusText")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        log("💀 onDestroy() - Peripheral Service being destroyed")
        stopPeripheralAdvertising()
        stopGattServer()
        log("✓ Service cleanup complete")
    }

    private val imageChunks = mutableMapOf<Int, String>()
    private var expectedImageChunks = 0
    private var isReceivingImage = false

    private fun handleReceivedImage(imageBytes: ByteArray) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: run {
                    log("❌ Failed to decode image")
                    return
                }

            val fileName = "BLE_IMG_${System.currentTimeMillis()}.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BleImages")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = applicationContext.contentResolver
            val imageUri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )

            if (imageUri == null) {
                log("❌ Failed to create MediaStore entry")
                return
            }

            resolver.openOutputStream(imageUri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(imageUri, contentValues, null, null)

            log("✅ Image saved to gallery: $fileName")

        } catch (e: Exception) {
            log("❌ Error saving image: ${e.message}")
        }
    }

}