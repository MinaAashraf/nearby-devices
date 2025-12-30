package com.example.myapplication3

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class CentralActivity : ComponentActivity() {

    private val TAG = "CentralActivity"

    // BLE Constants
    private val SERVICE_UUID = UUID.fromString("bb21801d-a324-418f-abc7-f23d10e7d588")
    private val CHARACTERISTIC_UUID_BIDIRECTIONAL =
        UUID.fromString("b6a0912e-e715-438b-96a2-b21149015db1")
    private val CHARACTERISTIC_UUID_WRITE = UUID.fromString("b6a0912e-e715-438b-96a2-b21149015db2")

    // BLE Components
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())

    // State
    private var isScanning by mutableStateOf(false)
    private var discoveredDevices by mutableStateOf<List<DiscoveredDevice>>(emptyList())
    private var connectedDevices by mutableStateOf<List<ConnectedDevice>>(emptyList())
    private var logMessages by mutableStateOf<List<String>>(emptyList())
    private var messageToSend by mutableStateOf("")

    private val discoveredDeviceMap = ConcurrentHashMap<String, DiscoveredDevice>()
    private val connectedGattClients = ConcurrentHashMap<String, ConnectedDevice>()

    private lateinit var pickImageLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log("📱 CentralActivity onCreate() - Activity created")

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        log("✓ Bluetooth Manager and Scanner initialized")

        pickImageLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                uri?.let {
                    val base64 = imageUriToBase64(it)
                    sendImage(base64)
                }
            }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CentralScreen()
                }
            }
        }
    }

    @Composable
    fun CentralScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "BLE Central Mode",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Scan Button
            Button(
                onClick = {
                    if (isScanning) stopScan() else startScan()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isScanning) "⏹️ Stop Scan" else "🔍 Start Scan")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Connected Devices Section
            Text(
                text = "Connected Devices (${connectedDevices.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )

            if (connectedDevices.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                        .border(2.dp, Color.Green, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    items(connectedDevices) { device ->
                        ConnectedDeviceItem(device.device)
                    }
                }
            } else {
                Text(
                    "No connected devices",
                    modifier = Modifier.padding(8.dp),
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Discovered Devices Section
            Text(
                text = "Discovered Devices (${discoveredDevices.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
            ) {
                if (discoveredDevices.isEmpty()) {
                    item {
                        Text(
                            "No devices found. Tap 'Start Scan' to discover devices.",
                            modifier = Modifier.padding(16.dp),
                            color = Color.Gray
                        )
                    }
                } else {
                    items(discoveredDevices) { device ->
                        DiscoveredDeviceItem(
                            device = device,
                            onClick = { connectToDevice(device.device) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Message Input
            MessageInput(
                message = messageToSend,
                onMessageChange = { messageToSend = it },
                onSendClick = {
                    sendMessage(messageToSend)
                    messageToSend = ""
                },
                enabled = connectedDevices.isNotEmpty()
            )

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { pickImageFromGallery() }
            ) {
                Text("Send Image")
            }
            // Logs
            LogDisplay(logMessages)
        }
    }

    @Composable
    fun ConnectedDeviceItem(device: BluetoothDevice) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✓", fontSize = 20.sp, color = Color.Green)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        device.name ?: "Unknown Device",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        device.address ?: "Unknown Address",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
        }
    }

    @Composable
    fun DiscoveredDeviceItem(device: DiscoveredDevice, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name ?: "Unknown Device",
                    fontWeight = FontWeight.Medium
                )
                Text(
                    device.msisdn,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            Text("→", fontSize = 24.sp, color = Color.Blue)
        }
        Divider()
    }

    @Composable
    fun MessageInput(
        message: String,
        onMessageChange: (String) -> Unit,
        onSendClick: () -> Unit,
        enabled: Boolean
    ) {
        var textFieldValue by remember { mutableStateOf(TextFieldValue(message)) }

        LaunchedEffect(message) {
            if (textFieldValue.text != message) {
                textFieldValue = textFieldValue.copy(text = message)
            }
        }

        Column {
            Text("Send Message", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = {
                        textFieldValue = it
                        onMessageChange(it.text)
                    },
                    label = { Text("Message") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = enabled
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onSendClick,
                    enabled = enabled && message.isNotEmpty()
                ) {
                    Text("Send")
                }
            }
        }
    }

    @Composable
    fun LogDisplay(logMessages: List<String>) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.LightGray, RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Text("Logs:", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .heightIn(max = 150.dp),
                reverseLayout = true
            ) {
                items(logMessages) { message ->
                    Text(message, fontSize = 11.sp)
                    Divider()
                }
            }
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val currentLogs = logMessages
        logMessages =
            (listOf("${System.currentTimeMillis() % 10000}: $message") + currentLogs).take(100)
    }

    private fun hasPermission(permission: String): Boolean {
        val granted =
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            log("⚠️ Permission check failed: $permission")
        }
        return granted
    }

    // ============================================
    // SCANNING
    // ============================================

    private fun startScan() {
        log("🔍 startScan() - Initiating BLE scan")

        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            log("❌ BLUETOOTH_SCAN permission missing")
            Toast.makeText(this, "Missing BLUETOOTH_SCAN permission", Toast.LENGTH_SHORT).show()
            return
        }

        log("🧹 Clearing previous scan results")
        discoveredDeviceMap.clear()
        discoveredDevices = emptyList()

        log("⚙️ Creating scan filter for service UUID: $SERVICE_UUID")
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        log("📡 Starting BLE scan with LOW_LATENCY mode")
        isScanning = true
        bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        log("✓ Scan started successfully")
        Toast.makeText(this, "Scanning for devices...", Toast.LENGTH_SHORT).show()
    }

    private fun stopScan() {
        log("🛑 stopScan() - Stopping BLE scan")
        if (!isScanning) {
            log("⚠️ Scan not running, nothing to stop")
            return
        }

        bleScanner?.stopScan(scanCallback)
        isScanning = false
        log("✓ Scan stopped. Found ${discoveredDeviceMap.size} device(s)")
        Toast.makeText(
            this,
            "Scan stopped. Found ${discoveredDeviceMap.size} devices",
            Toast.LENGTH_SHORT
        ).show()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = result.scanRecord?.deviceName ?: device.name ?: "Unknown"

            val msisdnBytes = result.scanRecord?.getManufacturerSpecificData(0x1234)
            val msisdn = msisdnBytes?.let { String(msisdnBytes, Charsets.UTF_8) } ?: "null"

            if (!discoveredDeviceMap.containsKey(msisdn)) {
                log("📱 NEW DEVICE discovered: $deviceName ($msisdn)")
                log("📊 RSSI: ${result.rssi} dBm")
                log("📞 MSISDN received: $msisdn")

                val discovered = DiscoveredDevice(deviceName, msisdn, device)
                discoveredDeviceMap[msisdn] = discovered
                discoveredDevices = discoveredDeviceMap.values.toList()
                connectToDevice(discovered.device)
                log("📈 Total discovered devices: ${discoveredDeviceMap.size}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                else -> "Unknown error"
            }
            log("❌ Scan FAILED: Error $errorCode ($errorMsg)")
            isScanning = false
            Toast.makeText(this@CentralActivity, "Scan failed: $errorMsg", Toast.LENGTH_LONG).show()
        }
    }

    // ============================================
    // CONNECTION
    // ============================================

    private fun connectToDevice(device: BluetoothDevice) {
        log("🔗 connectToDevice() - Attempting connection to ${device.address}")
        log("📱 Device name: ${device.name ?: "Unknown"}")

        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            log("❌ BLUETOOTH_CONNECT permission missing")
            Toast.makeText(this, "Missing BLUETOOTH_CONNECT permission", Toast.LENGTH_SHORT).show()
            return
        }

        // Check for duplicates by device name to handle random MAC addresses
        val duplicate = connectedGattClients.values.find {
            it.device.name == device.name && it.device.name != null
        }

        if (duplicate != null) {
            log("⚠️ Already connected to device with name: ${device.name}")
            Toast.makeText(this, "Already connected to ${device.name}", Toast.LENGTH_SHORT).show()
            return
        }

        log("⚙️ Initiating GATT connection...")
        val gatt = device.connectGatt(this, false, gattCallback)
        connectedGattClients[device.address] = ConnectedDevice(device = device, gatt = gatt)
        connectedDevices = connectedGattClients.values.toList()
        log("✓ GATT connection initiated, waiting for callback")
        Toast.makeText(
            this,
            "Connecting to ${device.name ?: device.address}...",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun disconnectAll() {
        log("🔌 disconnectAll() - Disconnecting from all devices")
        log("📊 Disconnecting ${connectedGattClients.size} device(s)")

        connectedGattClients.values.forEachIndexed { index, device ->
            log("🔌 Disconnecting device ${index + 1}: ${device.device.address}")
            device.gatt.disconnect()
            device.gatt.close()
        }

        connectedGattClients.clear()
        connectedDevices = emptyList()
        log("✓ All devices disconnected")
        Toast.makeText(this, "Disconnected all devices", Toast.LENGTH_SHORT).show()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            val deviceName = gatt.device.name ?: "Unknown"

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        log("✅ GATT CONNECTED to $deviceName ($deviceAddress)")
                        log("📊 Connection status: $status")

                        connectedGattClients[deviceAddress] =
                            ConnectedDevice(device = gatt.device, gatt = gatt)
                        runOnUiThread {
                            connectedDevices = connectedGattClients.values.toList()
                            Toast.makeText(
                                this@CentralActivity,
                                "Connected to $deviceName",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        log("🔍 Discovering GATT services...")
                        gatt.discoverServices()
                    } else {
                        log("❌ Connection FAILED with status $status for $deviceAddress")
                        connectedGattClients.remove(deviceAddress)
                        gatt.close()
                        runOnUiThread {
                            connectedDevices = connectedGattClients.values.toList()
                            Toast.makeText(
                                this@CentralActivity,
                                "Connection failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("❌ GATT DISCONNECTED from $deviceName ($deviceAddress)")
                    connectedGattClients.remove(deviceAddress)
                    gatt.close()
                    runOnUiThread {
                        connectedDevices = connectedGattClients.values.toList()
                        Toast.makeText(
                            this@CentralActivity,
                            "Disconnected from $deviceName",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    log("📉 Total connected devices: ${connectedGattClients.size}")
                }

                BluetoothProfile.STATE_CONNECTING -> {
                    log("⏳ GATT CONNECTING to $deviceAddress...")
                }

                BluetoothProfile.STATE_DISCONNECTING -> {
                    log("⏳ GATT DISCONNECTING from $deviceAddress...")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val deviceAddress = gatt.device.address
            log("🔍 onServicesDiscovered() for $deviceAddress")
            log("📊 Discovery status: $status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("✓ Services discovered successfully")
                log("📋 Total services: ${gatt.services.size}")

                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    log("✓ Found target service: $SERVICE_UUID")

                    val msisdnChar = service.getCharacteristic(CHARACTERISTIC_UUID_BIDIRECTIONAL)
                    if (msisdnChar != null) {
                        connectedGattClients[gatt.device.address]?.msisdnCharacteristic = msisdnChar
                        sendMessage("01012345678", true)
                    } else {
                        log("⚠️ MSISDN characteristic not found")
                    }

                    val writeChar = service.getCharacteristic(CHARACTERISTIC_UUID_WRITE)
                    if (writeChar != null) {
                        log("✓ Found write characteristic: $CHARACTERISTIC_UUID_WRITE")
                        log("📝 Write properties: ${writeChar.properties}")
                        connectedGattClients[gatt.device.address]?.writeCharacteristic = writeChar
                        log("✅ Device $deviceAddress is ready for communication")
                    } else {
                        log("⚠️ Write characteristic not found")
                    }
                } else {
                    log("⚠️ Target service $SERVICE_UUID not found")
                    log("📋 Available services: ${gatt.services.map { it.uuid }}")
                }
            } else {
                log("❌ Service discovery failed with status: $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("✅ Message SENT successfully to $deviceAddress")
                val device = connectedGattClients[deviceAddress]
                device?.gatt?.readCharacteristic(device.msisdnCharacteristic)
                Log.d("BLE", "📥 READ request to peripheral")
            } else {
                log("❌ Message SEND FAILED to $deviceAddress with status $status")
                Toast.makeText(this@CentralActivity, "Send failed", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (characteristic.uuid) {
                    CHARACTERISTIC_UUID_BIDIRECTIONAL -> {
                        val response = value.toString(Charsets.UTF_8)
                        Log.d("BLE", "📨 READ response from peripheral: $response")
                        connectedGattClients[gatt.device.address] =
                            connectedGattClients[gatt.device.address]?.copy(msisdn = response)
                                ?: connectedGattClients[gatt.device.address]!!
                        connectedDevices = connectedGattClients.values.toList()
                    }
                }
            } else {
                Log.e("BLE", "❌ READ failed with status: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {

        }
    }

    // ============================================
    // SEND MESSAGE
    // ============================================

    private fun sendMessage(
        message: String,
        isMsisdnMessage: Boolean = false,
        msisdns: List<String>? = null
    ) {
        log("📤 sendMessage() - Preparing to send message: '$message'")

        if (connectedGattClients.isEmpty()) {
            log("❌ No connected devices")
            Toast.makeText(this, "No connected devices", Toast.LENGTH_SHORT).show()
            return
        }

        log("✓ Sending message to ${connectedGattClients.size} device(s)")
        val data = message.toByteArray(Charsets.UTF_8)
        log("📊 Message size: ${data.size} bytes")

        val connectedDevice = if (msisdns != null) {
            connectedGattClients.values.filter { it.msisdn in msisdns }
        } else {
            connectedGattClients.values
        }

        connectedDevice.forEach { connectedDevice ->
            val msisdnChar = connectedDevice.msisdnCharacteristic
            val writeChar = connectedDevice.writeCharacteristic
            if (msisdnChar != null && isMsisdnMessage) {
                log("🆔 Writing MSISDN to characteristic: $message")
                msisdnChar.value = data
                msisdnChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                connectedDevice.gatt.writeCharacteristic(msisdnChar)
                log("✓ MSISDN write operation queued for ${connectedDevice.msisdn}")
            } else if (writeChar != null) {
                writeChar.value = data
                writeChar.writeType =
                    if (writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                        log("⚙️ Using WRITE_TYPE_NO_RESPONSE")
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    } else {
                        log("⚙️ Using WRITE_TYPE_DEFAULT")
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
                connectedDevice.gatt.writeCharacteristic(writeChar)
                log("✓ Write operation queued for ${connectedDevice.msisdn}")
            } else {
                log("❌ Write characteristic not found for ${connectedDevice.msisdn}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        log("💀 onDestroy() - CentralActivity being destroyed")
        stopScan()
        disconnectAll()
        log("✓ Activity cleanup complete")
    }

    fun pickImageFromGallery() {
        pickImageLauncher.launch("image/*")
    }

    private fun imageUriToBase64(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri)
        val bytes = inputStream?.readBytes() ?: return ""
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun sendImage(base64Image: String) {
        bleScope.launch {
            val chunks = chunkString(base64Image)

            sendMessage("IMG_START:${chunks.size}")
            delay(50)

            chunks.forEachIndexed { index, chunk ->
                sendMessage("IMG_CHUNK:$index:$chunk")
                delay(20) // 🔑 prevents overflow
            }

            delay(50)
            sendMessage("IMG_END")

            log("🖼️ Image sent successfully (${chunks.size} chunks)")
        }
    }

    private fun chunkString(data: String): List<String> {
        return data.chunked(IMAGE_CHUNK_SIZE)
    }

    private val bleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val IMAGE_CHUNK_SIZE = 180
    }
}
