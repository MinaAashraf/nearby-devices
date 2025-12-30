package com.example.myapplication3

import android.Manifest
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
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private var bleService: BlePeripheralService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as BlePeripheralService.LocalBgitinder
            bleService = binder.getService()
            serviceBound = true
            Toast.makeText(this@MainActivity, "Service connected", Toast.LENGTH_SHORT).show()
            bleService?.onMessageReceived = { message, fromDevice ->
                Log.d(TAG, "Message received from central: $fromDevice")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
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
        val requiredPermissions = getRequiredPermissions()
        if (!checkInitialPermissions(this, requiredPermissions)) {
            permissionLauncher.launch(requiredPermissions)
        } else {
            startAndBindService()
        }
        setContent {
            Button(onClick = {
                val intent = Intent(this, CentralActivity::class.java)
                startActivity(intent)
            }) {
                Text("Go to Central Activity")
            }
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, BlePeripheralService::class.java)
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
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        return permissions.toTypedArray()
    }

    private fun checkInitialPermissions(context: Context, permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
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