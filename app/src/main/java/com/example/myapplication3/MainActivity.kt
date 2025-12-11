package com.example.myapplication3

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.myapplication3.BleRole.*

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    private var bleService: BleConnectionsService? = null
    private var serviceBound = false

    // State
    private var currentRole by mutableStateOf(BleRole.IDLE)
    private var isScanning by mutableStateOf(false)
    private var isAdvertising by mutableStateOf(false)
    private var discoveredDevices by mutableStateOf<List<DiscoveredDevice>>(emptyList())
    private var connectedDevicesCentral by mutableStateOf<List<ConnectedDevice>>(emptyList())
    private var connectedClients by mutableStateOf<List<BluetoothDevice>>(emptyList())
    private var logMessages by mutableStateOf<List<String>>(emptyList())
    private var messageToSend by mutableStateOf("")

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as BleConnectionsService.LocalBinder
            bleService = binder.getService()
            serviceBound = true

            // Set up callbacks
            bleService?.onRoleChanged = { role ->
                currentRole = role
            }

            bleService?.onScanningStateChanged = { scanning ->
                isScanning = scanning
            }

            bleService?.onAdvertisingStateChanged = { advertising ->
                isAdvertising = advertising
            }

            bleService?.onDevicesDiscovered = { devices ->
                discoveredDevices = devices
            }

            bleService?.onDevicesConnected = { devices ->
                connectedDevicesCentral = devices.distinctBy { it.device.address }
            }

            bleService?.onClientsConnected = { clients ->
                connectedClients = clients.distinctBy { it.address }
            }

            bleService?.onLogMessage = { message ->
                val currentLogs = logMessages
                logMessages = (listOf(message) + currentLogs).take(100)
            }

            bleService?.onMessageReceived = { message ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Received: $message", Toast.LENGTH_SHORT).show()
                }
            }

            // Update UI from service state
            currentRole = bleService?.currentRole ?: BleRole.IDLE
            isScanning = bleService?.isScanning ?: false
            isAdvertising = bleService?.isAdvertising ?: false
            // Restore device lists from service state
            connectedDevicesCentral = bleService?.getConnectedDevices()?.distinctBy { it.device.address } ?: emptyList()
            connectedClients = bleService?.getConnectedClients()?.distinctBy { it.address } ?: emptyList()

            Toast.makeText(this@MainActivity, "Service connected", Toast.LENGTH_SHORT).show()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            bleService = null
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.d(TAG, "All permissions granted")
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            startAndBindService()
        } else {
            Log.w(TAG, "Some permissions denied")
            Toast.makeText(this, "Permissions required for BLE", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PermissionWrapper { MainScreen() }
                }
            }
        }
    }

    @Composable
    fun PermissionWrapper(content: @Composable () -> Unit) {
        val context = LocalContext.current
        val requiredPermissions = getRequiredPermissions()
        var permissionsGranted by remember {
            mutableStateOf(checkInitialPermissions(context, requiredPermissions))
        }

        LaunchedEffect(Unit) {
            if (!permissionsGranted) {
                permissionLauncher.launch(requiredPermissions)
            } else {
                startAndBindService()
            }
        }

        if (permissionsGranted) {
            content()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Requesting BLE Permissions...")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(requiredPermissions) }) {
                    Text("Grant Permissions")
                }
            }
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, BleConnectionsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "Service started and binding initiated")
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.toTypedArray()
    }

    private fun checkInitialPermissions(context: Context, permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun MainScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            // Role Selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { bleService?.setRole(BleRole.CENTRAL) },
                    enabled = currentRole != BleRole.CENTRAL
                ) { Text("Central") }

                Button(
                    onClick = { bleService?.setRole(PERIPHERAL) },
                    enabled = currentRole != PERIPHERAL
                ) { Text("Peripheral") }

                Button(
                    onClick = { bleService?.setRole(IDLE) },
                    enabled = currentRole != IDLE
                ) { Text("Idle") }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text("Role: ${currentRole.name}", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))

            // Role-Specific UI
            when (currentRole) {
                CENTRAL -> CentralRoleUI()
                PERIPHERAL -> PeripheralRoleUI()
                IDLE -> {}
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Message Input
            MessageInput(
                message = messageToSend,
                onMessageChange = { messageToSend = it },
                onSendClick = {
                    bleService?.sendMessage(messageToSend)
                    messageToSend = ""
                },
                enabled = (currentRole == BleRole.CENTRAL && connectedDevicesCentral.isNotEmpty()) ||
                        (currentRole == PERIPHERAL && connectedClients.isNotEmpty())
            )

            Spacer(modifier = Modifier.height(10.dp))
            LogDisplay(logMessages)
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun CentralRoleUI() {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Central Mode", fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { bleService?.startCentralScan() },
                    enabled = !isScanning && connectedDevicesCentral.isEmpty()
                ) { Text(if (isScanning) "Scanning..." else "Start Scan") }

                Button(
                    onClick = { bleService?.stopCentralScan() },
                    enabled = isScanning
                ) { Text("Stop Scan") }

                Button(
                    onClick = { bleService?.disconnectCentral() },
                    enabled = connectedDevicesCentral.isNotEmpty()
                ) { Text("Disconnect") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (connectedDevicesCentral.isNotEmpty()) {
                Text("Connected: ${connectedDevicesCentral.size}", fontWeight = FontWeight.Bold)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 100.dp)
                        .border(1.dp, Color.Gray)
                ) {
                    items(connectedDevicesCentral) { device ->
                        Text(
                            " - ${device.device.name ?: device.device.address}",
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
            } else {
                Text("Not Connected")
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Discovered Devices:", fontWeight = FontWeight.Bold)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
                    .border(1.dp, Color.Gray)
            ) {
                items(discoveredDevices) { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { bleService?.connectToDevice(device.device) }
                            .padding(8.dp)
                    ) {
                        Text("${device.name ?: "Unknown"} (${device.address})")
                    }
                    Divider()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun PeripheralRoleUI() {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Peripheral Mode", fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { bleService?.startPeripheralAdvertising() },
                    enabled = !isAdvertising
                ) { Text(if (isAdvertising) "Advertising..." else "Start Advertising") }

                Button(
                    onClick = { bleService?.stopPeripheralAdvertising() },
                    enabled = isAdvertising
                ) { Text("Stop Advertising") }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Connected Clients: ${connectedClients.size}", fontWeight = FontWeight.Bold)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 80.dp)
                    .border(1.dp, Color.Gray)
            ) {
                items(connectedClients) { client ->
                    Text(
                        " - ${client.name ?: client.address}",
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }
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

    @Composable
    fun LogDisplay(logMessages: List<String>) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.LightGray, RoundedCornerShape(4.dp))
                .padding(4.dp)
        ) {
            Text("Logs:", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true
            ) {
                items(logMessages) { message ->
                    Text(message, fontSize = 12.sp)
                    Divider()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        Log.d(TAG, "Activity destroyed")
    }
}