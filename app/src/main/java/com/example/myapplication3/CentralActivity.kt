package com.example.myapplication3

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.*

class CentralActivity : ComponentActivity() {

    private val TAG = "CentralActivity"
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var connectedDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CentralScreen(
                onScanClick = { startScanning() },
                onDisconnectClick = { disconnectDevice() },
                onSendMessageClick = { sendMessage("Hello BLE") }
            )
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!hasPermissions()) {
            requestPermissions()
            return
        }

        val bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            Toast.makeText(this, "BLE Scanner not available", Toast.LENGTH_SHORT).show()
            return
        }

        val scanFilter = ScanFilter.Builder().build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        Toast.makeText(this, "Scanning started", Toast.LENGTH_SHORT).show()
    }

    private fun hasPermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Permissions are granted at install time for < API 31
        }
    }

    private fun requestPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val requestPermissionLauncher =
                registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                    val scanGranted = permissions[Manifest.permission.BLUETOOTH_SCAN] ?: false
                    val connectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false
                    if (scanGranted && connectGranted) {
                        startScanning()
                    } else {
                        Toast.makeText(this, "Bluetooth permissions denied", Toast.LENGTH_SHORT).show()
                    }
                }
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ))
        } else {
            startScanning()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to device: ${device.address}")
        gatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Connected to ${device.address}")
                    connectedDevice = device
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Disconnected from ${device.address}")
                    connectedDevice = null
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Services discovered on ${device.address}")
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Message sent successfully")
                } else {
                    Log.e(TAG, "Failed to send message")
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun disconnectDevice() {
        if (gatt != null) {
            Log.d(TAG, "Disconnecting from device: ${connectedDevice?.address}")
            gatt?.disconnect()
            gatt?.close()
            gatt = null
            connectedDevice = null
        } else {
            Log.e(TAG, "No device to disconnect")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendMessage(message: String) {
        if (connectedDevice == null || gatt == null) {
            Log.e(TAG, "No connected device to send message")
            return
        }

        val service = gatt?.getService(UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"))
        val characteristic = service?.getCharacteristic(UUID.fromString("00001102-0000-1000-8000-00805F9B34FB"))

        if (characteristic != null) {
            characteristic.value = message.toByteArray(Charsets.UTF_8)
            gatt?.writeCharacteristic(characteristic)
        } else {
            Log.e(TAG, "Characteristic not found")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device
            Log.d(TAG, "Discovered device: ${device.name} - ${device.address}")
            connectToDevice(device)
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { result ->
                val device: BluetoothDevice = result.device
                Log.d(TAG, "Batch discovered device: ${device.name} - ${device.address}")
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
        }
    }
}

@Composable
fun CentralScreen(
    onScanClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onSendMessageClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = onScanClick, modifier = Modifier.fillMaxWidth()) {
            Text("Start Scanning")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onDisconnectClick, modifier = Modifier.fillMaxWidth()) {
            Text("Disconnect Device")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onSendMessageClick, modifier = Modifier.fillMaxWidth()) {
            Text("Send Message")
        }
    }
}
