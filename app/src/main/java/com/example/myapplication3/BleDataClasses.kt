package com.example.myapplication3

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic

enum class BleRole {
    IDLE, CENTRAL, PERIPHERAL
}

// Discovered device data class
data class DiscoveredDevice(
    val name: String?,
    val msisdn: String,
    val device: BluetoothDevice
)

// Connected device for Central role
data class ConnectedDevice(
    val device: BluetoothDevice,
    val gatt: BluetoothGatt,
    var msisdnCharacteristic: BluetoothGattCharacteristic? = null,
    var writeCharacteristic: BluetoothGattCharacteristic? = null,
    var readCharacteristic: BluetoothGattCharacteristic? = null,
    val name: String? = null,
    val msisdn: String? = null
)