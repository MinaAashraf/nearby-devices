package com.example.myapplication3

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.myapplication3.BleRole.CENTRAL
import com.example.myapplication3.BleRole.IDLE
import com.example.myapplication3.BleRole.PERIPHERAL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// --- ViewModel ---
@SuppressLint("MissingPermission")
class BluetoothViewModel : ViewModel() {

    // --- Constants ---
    val tag = "BLECombinedApp"

    // ✅ NUS Service UUID
    private val SERVICE_UUID =
        UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CHARACTERISTIC_UUID_WRITE =
        UUID.fromString("00001102-0000-1000-8000-00805F9B34FB") // Example Write UUID
    private val CHARACTERISTIC_UUID_NOTIFY =
        UUID.fromString("00001103-0000-1000-8000-00805F9B34FB") // Example Notify UUID
    private val CHARACTERISTIC_UUID_READ =
        UUID.fromString("00001104-0000-1000-8000-00805F9B34FB") // Example Notify UUID
    private val CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // Standard CCCD UUID

//    // ✅ Standard CCCD for enabling notifications
//    private val CCCD_UUID =
//        UUID.fromString("00001105-0000-1000-8000-00805f9b34fb")

    lateinit var mContext: Context
    private val _logMessages = MutableLiveData<List<String>>(emptyList())
    val logMessages: LiveData<List<String>> = _logMessages

    private val _discoveredDevices = MutableLiveData<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: LiveData<List<DiscoveredDevice>> = _discoveredDevices

    private val _connectedDevice = MutableLiveData<BluetoothDevice?>(null)

    private val _connectedClients = MutableLiveData<List<BluetoothDevice>>(emptyList())
    val connectedClients: LiveData<List<BluetoothDevice>> =
        _connectedClients // Peripheral role connected clients

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _isAdvertising = MutableLiveData(false)
    val isAdvertising: LiveData<Boolean> = _isAdvertising

    private val _currentRole = MutableLiveData(IDLE)
    val currentRole: LiveData<BleRole> = _currentRole

    private val _messageToSend = MutableLiveData("")
    val messageToSend: LiveData<String> = _messageToSend

    // --- BLE Managers ---
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var gattClient: BluetoothGatt? = null // For Central role
    private var gattServer: BluetoothGattServer? = null // For Peripheral role
    private var advertiser: BluetoothLeAdvertiser? = null

    // Characteristics (cached after discovery/setup)
    private var writeCharacteristicClient: BluetoothGattCharacteristic? = null
    private var readCharacteristicServer: BluetoothGattCharacteristic? = null
    private var notifyCharacteristicClient: BluetoothGattCharacteristic? = null
    private var writeCharacteristicServer: BluetoothGattCharacteristic? = null
    private var notifyCharacteristicServer: BluetoothGattCharacteristic? = null

    // Keep track of discovered devices by address to avoid duplicates in the list
    private val discoveredDeviceMap = ConcurrentHashMap<String, DiscoveredDevice>()

    // Keep track of connected clients by address (Peripheral role)
    private val connectedClientMap = ConcurrentHashMap<String, BluetoothDevice>()

    // In your BluetoothViewModel class:
    private val connectedGattClients = ConcurrentHashMap<String, ConnectedDevice>()
    private val _connectedDevicesCentral = MutableLiveData<List<ConnectedDevice>>(emptyList())
    val connectedDevicesCentral: LiveData<List<ConnectedDevice>> = _connectedDevicesCentral

    // Handler for delayed operations like stopping scan
    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD: Long = 1000000000000 // Scan for 10 seconds

// In your BluetoothViewModel class:

    private fun updateConnectedDevicesCentral() {
        // Create a list of ConnectedDevice objects from the connectedGattClients map
        val connectedList = connectedGattClients.mapNotNull { (address, _) ->
            // Find the corresponding ConnectedDevice object to include the cached characteristic
            // This assumes you are storing the ConnectedDevice objects somewhere,
            // or you can retrieve the characteristic from the gatt object directly
            // if you prefer not to store the full ConnectedDevice object in the map.
            // For simplicity, let's assume you have a way to get the characteristic
            // based on the device address or gatt instance.
            // A more robust approach might be to store ConnectedDevice in the map directly.

            // Let's refine the approach: store ConnectedDevice in the map
            // instead of just BluetoothGatt.
            // So, the map would be: ConcurrentHashMap<String, ConnectedDevice>()

            // If you are storing ConnectedDevice in the map:
            connectedGattClients[address]?.let { connectedDevice ->
                ConnectedDevice(
                    connectedDevice.device,
                    connectedDevice.gatt,
                    connectedDevice.writeCharacteristic
                )
            }
        }
        _connectedDevicesCentral.postValue(connectedList)
    }

    // --- Initialization ---
    fun initialize(context: Context) {
        mContext = context
        if (bluetoothManager == null) {
            bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            bluetoothAdapter?.name = "01146114834"
            log("Bluetooth Manager Initialized")
            if (!isBleSupported(context)) {
                log("BLE Not Supported on this device.")
                // Handle appropriately - disable BLE features
            }
            if (!isBluetoothEnabled()) {
                log("Bluetooth is disabled. Please enable it.")
                // Prompt user to enable Bluetooth
            }
        }
    }

    // --- Role Management ---
    fun setRole(role: BleRole, context: Context) {
        if (_currentRole.value == role) return // No change

        // Stop current role activities before switching
        stopCurrentRole(context)

        _currentRole.value = role
        log("Switched role to: $role")

        // Reset state relevant to the *other* role
        when (role) {
            CENTRAL -> {
                _isAdvertising.value = false
                _connectedClients.value = emptyList()
                connectedClientMap.clear()
            }

            PERIPHERAL -> {
                _isScanning.value = false
                _discoveredDevices.value = emptyList()
                discoveredDeviceMap.clear()
                _connectedDevice.value = null
            }

            IDLE -> {
                // Already stopped by stopCurrentRole
            }
        }
    }

    private fun stopCurrentRole(context: Context) {
        when (_currentRole.value) {
            CENTRAL -> stopCentral(context)
            PERIPHERAL -> stopPeripheral(context)
            IDLE -> { /* Nothing to stop */
            }

            null -> TODO()
        }
    }

    // --- Permission Checks ---
    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getRequiredPermissions(): Array<String> {
        val targetSdk = Build.VERSION.SDK_INT
        return when {
            targetSdk >= Build.VERSION_CODES.S -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION // Still recommended for reliable scanning
            )

            targetSdk >= Build.VERSION_CODES.Q -> arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION // Required for scan
            )

            else -> arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_COARSE_LOCATION // Required for scan
            )
        }
    }

    // --- Logging ---
    private fun log(message: String) {
        Log.d(tag, message)
        val currentLogs = _logMessages.value ?: emptyList()
        // Keep only the last 100 messages for performance
        _logMessages.postValue(
            (listOf("(${System.currentTimeMillis() % 10000}) $message") + currentLogs).take(
                100
            )
        )
    }

    // --- BLE Support Checks ---
    private fun isBleSupported(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    private fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    // --- Message Handling ---
    fun updateMessageToSend(message: String) {
        _messageToSend.value = message
    }

    fun sendMessage(context: Context) {
        val message = _messageToSend.value ?: ""
        if (message.isEmpty()) {
            log("Message is empty.")
            return
        }
        val data = message.toByteArray(Charsets.UTF_8)

        when (_currentRole.value) {
            CENTRAL -> {
                if (connectedGattClients.isEmpty()) {
                    log("Central: No devices connected.")
                    return
                }
                if (!hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) {
                    log("Central: BLUETOOTH_CONNECT permission missing.")
                    return
                }
                log("Central: Sending message: $message to ${connectedGattClients.size} devices")

                connectedGattClients.forEach { (address, connectedDevice) ->
                    val writeCharacteristic = connectedDevice.writeCharacteristic
                    if (writeCharacteristic != null) {
                        writeCharacteristic.value = data
                        val writeType =
                            if (writeCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            } else {
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            }
                        writeCharacteristic.writeType = writeType
                        // Perform the write operation for this specific device's GATT client
                        connectedDevice.gatt.writeCharacteristic(writeCharacteristic)
                        log("Central: Attempting to send to $address")
                    } else {
                        log("Central: Write characteristic not found for device $address.")
                    }
                }
                // Clear message field after sending attempt
                _messageToSend.postValue("")
            }

            PERIPHERAL -> {
                if (gattServer == null || notifyCharacteristicServer == null || connectedClientMap.isEmpty()) {
                    log("Peripheral: No clients connected or notify characteristic not ready.")
                    return
                }
                if (!hasPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                ) { // Connect needed for notify
                    log("Peripheral: BLUETOOTH_CONNECT permission missing.")
                    return
                }
                log("Peripheral: Notifying message: $message to ${connectedClientMap.size} clients")

                notifyCharacteristicServer?.value = data
                connectedClientMap.values.forEach { device ->
                    // Third parameter: indicate = true for indications, false for notifications
                    val indicate =
                        (notifyCharacteristicServer!!.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    gattServer?.notifyCharacteristicChanged(
                        device,
                        notifyCharacteristicServer,
                        indicate
                    )
                }
                // Clear message field after sending attempt
                _messageToSend.postValue("")
            }

            IDLE -> log("Cannot send message in IDLE mode.")
            null -> TODO()

        }
    }

    // ========================================================================
    // Central Role Logic
    // ========================================================================

    fun startCentralScan(context: Context) {
        if (_currentRole.value != CENTRAL) {
            log("Not in Central role. Switch role first.")
            return
        }
        if (_isScanning.value == true) {
            log("Scan already in progress.")
            return
        }
        if (!hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)) {
            log("BLUETOOTH_SCAN permission missing.")
            return
        }
        // Location permission check (especially for older Android versions)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            !hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            log("Location permission missing for scanning on this Android version.")
            return
        }
        if (!isBluetoothEnabled()) {
            log("Bluetooth is not enabled.")
            return
        }

        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            log("Failed to get BLE Scanner.")
            return
        }

        // Clear previous results
        discoveredDeviceMap.clear()
        _discoveredDevices.postValue(emptyList())

        val serviceUUIDFilter = ScanFilter.Builder().setServiceUuid(
            ParcelUuid(SERVICE_UUID)
        ).build()

        val scanFilters =
            listOf(
                serviceUUIDFilter
            )
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Use low latency for active scanning
            .setLegacy(true) // Use low latency for active scanning
            .build()

        log("Starting BLE Scan...")
        _isScanning.value = true
        bleScanner?.startScan(scanFilters, scanSettings, scanCallback)

        // Stop scanning after a predefined period
        handler.postDelayed({ stopCentralScan(context) }, SCAN_PERIOD)
    }

    fun stopCentralScan(context: Context) {
        if (_isScanning.value == false) return // Already stopped
        if (!hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)) {
            log("BLUETOOTH_SCAN permission missing to stop scan.")
            // Still attempt to update state
            _isScanning.postValue(false)
            handler.removeCallbacksAndMessages(null) // Remove pending stop callbacks
            return
        }
        log("Stopping BLE Scan.")
        bleScanner?.stopScan(scanCallback)
        _isScanning.value = false
        handler.removeCallbacksAndMessages(null) // Remove pending stop callbacks
    }

    fun connectToDevice(context: Context, device: BluetoothDevice) {
        if (_currentRole.value != CENTRAL) {
            log("Cannot connect: App is not in Central role.")
            return
        }
        if (!hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            log("BLUETOOTH_CONNECT permission missing.")
            return
        }

        if (connectedGattClients.containsKey(device.address)) {
            log("Already connected to ${device.address}")
            return
        }

        log("Connecting to device: ${device.address}")
        val gatt = device.connectGatt(context, false, gattClientCallback)
        // Store a ConnectedDevice object with the initial gatt
        connectedGattClients[device.address] = ConnectedDevice(
            device, gatt
        )
        updateConnectedDevicesCentral() // Update UI with pending connection
    }

    fun disconnectCentral(context: Context) {
        if (_currentRole.value != CENTRAL) return
        if (!hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            log("BLUETOOTH_CONNECT permission missing.")
            return
        }
        if (gattClient == null) {
            log("Not connected.")
            return
        }
        log("Disconnecting from ${gattClient?.device?.address}")
        gattClient?.disconnect()
        // gattClient?.close() will be called in onConnectionStateChange when disconnected
    }

    private fun stopCentral(context: Context) {
        log("Stopping Central Role...")
        stopCentralScan(context)
        disconnectCentral(context)
        // Ensure GATT client is closed if disconnect didn't trigger callback properly
        if (hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            gattClient?.close()
        }
        gattClient = null
        _connectedDevice.value = null
        writeCharacteristicClient = null
        notifyCharacteristicClient = null
        _isScanning.value = false // Ensure state is reset
        discoveredDeviceMap.clear()
        _discoveredDevices.postValue(emptyList())
    }


    // --- Central Role Callbacks ---
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val record = result.scanRecord
            val bytes = record?.bytes
            val hex = bytes?.joinToString(" ") { String.format("%02X", it) }
            Log.d(
                "BLE_SCAN",
                "Device: ${result.device.address}, Name: ${result.device.name}, Data: $hex"
            )


            val device = result.device
            val deviceName = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
            val deviceAddress = device.address

            if (deviceAddress == "5F:FE:82:63:58:DE") {
                Log.d(
                    "BLE_SCAN",
                    "Device: ${result.device.address}, Name: ${result.device.name}, Data: $hex"
                )

            }
            // Add or update device in the map and update LiveData
            if (!discoveredDeviceMap.containsKey(deviceAddress)) {
                log("Discovered: $deviceName ($deviceAddress)")
                val discovered = DiscoveredDevice(deviceName, deviceAddress, device)
                discoveredDeviceMap[deviceAddress] = discovered
                _discoveredDevices.postValue(discoveredDeviceMap.values.toList())
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            results.forEach { result ->
                val device = result.device
                val deviceName = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
                val deviceAddress = device.address
                if (!discoveredDeviceMap.containsKey(deviceAddress)) {
                    log("Discovered Batch: $deviceName ($deviceAddress)")
                    val discovered = DiscoveredDevice(deviceName, deviceAddress, device)
                    discoveredDeviceMap[deviceAddress] = discovered
                }
            }
            _discoveredDevices.postValue(discoveredDeviceMap.values.toList()) // Update list after batch
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            log("Scan Failed: Error Code $errorCode")
            _isScanning.postValue(false)
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {


        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        log("GATT client connected to $deviceAddress")
                        // Store the BluetoothGatt instance and device in the map
                        // Initialize ConnectedDevice with gatt and device, characteristics will be added later
                        connectedGattClients[deviceAddress] = ConnectedDevice(
                            gatt.device,
                            gatt
                        )
                        updateConnectedDevicesCentral() // Update UI to show pending connection

                        // Discover services after successful connection
                        log("Discovering services for $deviceAddress")
                        val servicesDiscovered = gatt.discoverServices()
                        if (!servicesDiscovered) {
                            log("Failed to start service discovery for $deviceAddress")
                            // Handle the failure to start discovery, potentially disconnect
                            connectedGattClients.remove(deviceAddress)
                            gatt.close()
                            updateConnectedDevicesCentral()
                        }
                    } else {
                        log("GATT client connection failed with status $status for $deviceAddress")
                        // Handle connection failure (e.g., remove from pending list, show error)
                        connectedGattClients.remove(deviceAddress)
                        gatt.close()
                        updateConnectedDevicesCentral()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("GATT client disconnected from $deviceAddress")
                    // Remove the ConnectedDevice object from the map
                    connectedGattClients.remove(deviceAddress)
                    // Close the GATT client to free up resources
                    gatt.close()
                    updateConnectedDevicesCentral() // Update UI to reflect disconnection
                }

                BluetoothProfile.STATE_CONNECTING -> {
                    log("GATT client connecting to $deviceAddress")
                    // You might want to update UI to show a connecting state
                }

                BluetoothProfile.STATE_DISCONNECTING -> {
                    log("GATT client disconnecting from $deviceAddress")
                    // You might want to update UI to show a disconnecting state
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Services discovered for $deviceAddress")
                val service = gatt.getService(SERVICE_UUID) // Check if service is found
                if (service != null) {
                    val writeChar =
                        service.getCharacteristic(CHARACTERISTIC_UUID_WRITE) // Check if write characteristic is found
                    val readChar =
                        service.getCharacteristic(CHARACTERISTIC_UUID_READ) // Check if read characteristic is found

                    // Update the existing ConnectedDevice object in the map with the characteristics
                    connectedGattClients[deviceAddress]?.let { connectedDevice ->
                        connectedDevice.writeCharacteristic = writeChar
                        connectedDevice.readCharacteristic =
                            readChar // Cache the read characteristic as well
                        updateConnectedDevicesCentral() // Update UI
                    }

                } else {
                    log("Service UUID not found for $deviceAddress")
                }
            } else {
                log("onServicesDiscovered received: $status for $deviceAddress")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID_READ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("Read successful: ${characteristic.value?.toString(Charsets.UTF_8)}")
                    // Message field already cleared on send attempt
                } else {
                    log("Read failed: Status $status")
                    // Handle failed write (e.g., show error)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Characteristic write successful for $deviceAddress")
            } else {
                log("Characteristic write failed for $deviceAddress with status: $status")
            }
        }

        // Override for API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChange(characteristic.uuid, value)
        }

        private fun handleCharacteristicChange(uuid: UUID, value: ByteArray) {
            if (uuid == CHARACTERISTIC_UUID_NOTIFY) {
                val receivedMessage = value.toString(Charsets.UTF_8)
                log("Central: Received Notification: $receivedMessage")
                // Update UI or handle received data
            } else if (uuid == CHARACTERISTIC_UUID_READ) {
                val receivedMessage = value.toString(Charsets.UTF_8)
                log("Central: Received Read: $receivedMessage")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (descriptor.value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        log("Notifications Enabled for ${descriptor.characteristic.uuid}")
                    } else if (descriptor.value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                        log("Notifications Disabled for ${descriptor.characteristic.uuid}")
                    }
                } else {
                    log("Failed to write CCCD: Status $status")
                }
            }
        }
    }


// ========================================================================
// Peripheral Role Logic
// ========================================================================

    fun startPeripheralAdvertising(context: Context) {
        if (_currentRole.value != PERIPHERAL) {
            log("Not in Peripheral role. Switch role first.")
            return
        }
        if (_isAdvertising.value == true) {
            log("Already advertising.")
            return
        }
        if (!hasPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)) {
            log("BLUETOOTH_ADVERTISE permission missing.")
            return
        }
        if (!hasPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        ) { // Needed for GATT Server
            log("BLUETOOTH_CONNECT permission missing.")
            return
        }
        if (!isBluetoothEnabled()) {
            log("Bluetooth is not enabled.")
            return
        }

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            log("Failed to get BLE Advertiser.")
            return
        }

        // Setup GATT Server first
        if (!setupGattServer(context)) {
            log("Failed to setup GATT Server. Cannot start advertising.")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true) // Important: Must be connectable
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // Advertise the device name
            .addServiceUuid(ParcelUuid(SERVICE_UUID)) // Advertise our custom service
            .build()


        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        log("Starting Advertising...")
        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        // State update (_isAdvertising = true) will happen in the callback
    }

    fun stopPeripheralAdvertising(context: Context) {
        if (_isAdvertising.value == false) return // Already stopped
        if (!hasPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)) {
            log("BLUETOOTH_ADVERTISE permission missing to stop advertising.")
            // Still update state
            _isAdvertising.postValue(false)
            return
        }
        log("Stopping Advertising.")
        advertiser?.stopAdvertising(advertiseCallback)
        _isAdvertising.value = false // Update state immediately
    }

    private fun setupGattServer(context: Context): Boolean {
        if (!hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            log("CONNECT permission missing for GATT server setup.")
            return false
        }
        if (gattServer != null) {
            log("GATT Server already running.")
            return true // Assume it's correctly set up
        }

        bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager // Ensure manager is available
        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)

        if (gattServer == null) {
            log("Failed to open GATT Server.")
            return false
        }

        // --- Define Service and Characteristics ---
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Write Characteristic (Central writes TO this Peripheral)
        writeCharacteristicServer = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID_WRITE,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        readCharacteristicServer = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID_READ,
            BluetoothGattCharacteristic.PROPERTY_READ, // Allow read and notify
            BluetoothGattCharacteristic.PERMISSION_READ // Read permission needed
            // Write permission for CCCD is handled implicitly by the system
        )

        // Notify Characteristic (This Peripheral sends TO Central)
        notifyCharacteristicServer = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID_NOTIFY,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, // Allow read and notify
            BluetoothGattCharacteristic.PERMISSION_READ // Read permission needed
            // Write permission for CCCD is handled implicitly by the system
        )
        // Add CCCD to Notify Characteristic to allow clients to subscribe
        val cccdDescriptor = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        notifyCharacteristicServer?.addDescriptor(cccdDescriptor)
        readCharacteristicServer?.addDescriptor(cccdDescriptor)


        // Add characteristics to the service
        service.addCharacteristic(writeCharacteristicServer)
        service.addCharacteristic(notifyCharacteristicServer)
        service.addCharacteristic(readCharacteristicServer)

        // Add the service to the GATT server
        val serviceAdded = gattServer?.addService(service) ?: false
        if (serviceAdded) {
            log("GATT Service $SERVICE_UUID added successfully.")
            return true
        } else {
            log("Failed to add GATT Service $SERVICE_UUID.")
            gattServer?.close() // Clean up if service add failed
            gattServer = null
            return false
        }
    }

    private fun stopGattServer(context: Context) {
        if (gattServer == null) return
        if (!hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            log("CONNECT permission missing to close GATT server.")
            return
        }
        log("Closing GATT Server.")
        gattServer?.close()
        gattServer = null
        writeCharacteristicServer = null
        notifyCharacteristicServer = null
        connectedClientMap.clear()
        _connectedClients.postValue(emptyList())
    }

    private fun stopPeripheral(context: Context) {
        log("Stopping Peripheral Role...")
        stopPeripheralAdvertising(context)
        stopGattServer(context)
        _isAdvertising.value = false // Ensure state is reset
        connectedClientMap.clear()
        _connectedClients.postValue(emptyList())
    }

    // --- Peripheral Role Callbacks ---
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            log("Advertising Started Successfully.")
            _isAdvertising.postValue(true)
        }

        override fun onStartFailure(errorCode: Int) {
            log("Advertising Failed: Error Code $errorCode")
            _isAdvertising.postValue(false)
            // Consider closing GATT server if advertising fails critically
            // stopGattServer(context)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val deviceAddress = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Peripheral: Device Connected: $deviceAddress")
                connectedClientMap[deviceAddress] = device
                _connectedClients.postValue(connectedClientMap.values.toList())
                // Optional: Stop advertising if you only want one connection
                stopPeripheralAdvertising(mContext)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Peripheral: Device Disconnected: $deviceAddress")
                connectedClientMap.remove(deviceAddress)
                _connectedClients.postValue(connectedClientMap.values.toList())
                // Optional: Restart advertising if stopped previously
                startPeripheralAdvertising(mContext)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            log("Read request for ${characteristic.uuid} from ${device.address}")
            if (!hasPermission(
                    mContext,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            ) { // Need context
                log("CONNECT permission missing for sendResponse")
                // Cannot respond without permission
                return
            }

            var responseValue: ByteArray? = null
            var responseStatus = BluetoothGatt.GATT_FAILURE

            if (characteristic.uuid == CHARACTERISTIC_UUID_READ) {
                // Example: Respond with current time or some status
                responseValue =
                    "Current time: ${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
                responseStatus = BluetoothGatt.GATT_SUCCESS
            }
            log("CONNECT permission missing for sendResponse")

            // Add other readable characteristics here if needed

            gattServer?.sendResponse(device, requestId, responseStatus, offset, responseValue)
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
            log("Write request for ${characteristic.uuid} from $deviceAddress")

            if (!hasPermission(
                    mContext,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            ) { // Need context
                log("CONNECT permission missing for sendResponse")
                // Cannot respond without permission, but process the write if possible
                // If responseNeeded is true, the central will likely time out.
            }

            var responseStatus = BluetoothGatt.GATT_FAILURE
            if (characteristic.uuid == CHARACTERISTIC_UUID_WRITE) {
                val message = value?.toString(Charsets.UTF_8) ?: "null"
                log("Peripheral: Received Write: $message from $deviceAddress")
                // Handle the received message (e.g., display in UI)
                responseStatus = BluetoothGatt.GATT_SUCCESS
            }
            if (characteristic.uuid == CHARACTERISTIC_UUID_READ) {
                val message = value?.toString(Charsets.UTF_8) ?: "null"
                log("Peripheral: Received Read: $message from $deviceAddress")
                // Handle the received message (e.g., display in UI)
                responseStatus = BluetoothGatt.GATT_SUCCESS
            }

            if (responseNeeded) {
                if (hasPermission(
                        mContext,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                ) { // Need context
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        responseStatus,
                        offset,
                        value
                    ) // Echo back value or send null
                } else {
                    log("Cannot send write response - permission missing.")
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            log("Read request for descriptor ${descriptor.uuid} from ${device.address}")
            if (!hasPermission(
                    mContext,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            ) { // Need context
                log("CONNECT permission missing for sendResponse")
                return
            }

            var responseValue: ByteArray? = null
            var responseStatus = BluetoothGatt.GATT_FAILURE

            if (descriptor.uuid == CCCD_UUID) {
                // Check current notification/indication state for this device (needs more complex state tracking)
                // For simplicity, just return a default value like disabled.
                responseValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                responseStatus = BluetoothGatt.GATT_SUCCESS
            }

            gattServer?.sendResponse(device, requestId, responseStatus, offset, responseValue)
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
            val deviceAddress = device.address
            log("Write request for descriptor ${descriptor.uuid} from $deviceAddress")

            if (!hasPermission(
                    mContext,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            ) { // Need context
                log("CONNECT permission missing for sendResponse")
            }

            var responseStatus = BluetoothGatt.GATT_FAILURE
            if (descriptor.uuid == CCCD_UUID) {
                responseStatus = BluetoothGatt.GATT_SUCCESS // Acknowledge the write
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    log("Notifications ENABLED for ${descriptor.characteristic.uuid} by $deviceAddress")
                    // Store subscription state per device if needed
                } else if (value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    log("Indications ENABLED for ${descriptor.characteristic.uuid} by $deviceAddress")
                    // Store subscription state per device if needed
                } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                ) {
                    log("Notifications/Indications DISABLED for ${descriptor.characteristic.uuid} by $deviceAddress")
                    // Store subscription state per device if needed
                }
            }

            if (responseNeeded) {
                if (hasPermission(
                        mContext,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                ) { // Need context
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        responseStatus,
                        offset,
                        null
                    ) // No value needed for CCCD response
                } else {
                    log("Cannot send descriptor write response - permission missing.")
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            log("Notification sent to ${device.address}, Status: ${if (status == BluetoothGatt.GATT_SUCCESS) "Success" else "Failure ($status)"}")
        }

        // Implement other necessary overrides like onExecuteWrite, onMtuChanged etc. if needed
    }


    // --- Cleanup ---
    override fun onCleared() {
        super.onCleared()
        log("ViewModel Cleared - Stopping BLE Activities")
        // Stop everything regardless of role when ViewModel is destroyed
        // Need context here, which is problematic. Cleanup should ideally be triggered from Activity/Fragment lifecycle.
        // For now, just log. Proper cleanup needs context passed or different architecture.
        // stopCentral(context)
        // stopPeripheral(context)
        handler.removeCallbacksAndMessages(null)
        Log.w(tag, "Cleanup in onCleared limited without context.")
    }
}

