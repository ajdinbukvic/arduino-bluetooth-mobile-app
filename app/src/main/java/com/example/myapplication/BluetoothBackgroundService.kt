package com.example.myapplication
import android.Manifest
import android.app.*
import android.bluetooth.*
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModelProvider
import java.io.IOException
import java.io.InputStream
import java.util.*


class BluetoothBackgroundService : Service() {

    private var inputStream: InputStream? = null
    private var connectedThread: ConnectedThread? = null
    private val CHANNEL_ID = "BluetoothDisconnectedChannel"
    private val notificationId = 1
    private val DEVICE_NAME = "HC-05"
    private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    var isConnected: Boolean = false
    private val phoneNumber = ""
    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval: Long = 5000 // Provjerava svakih 5 sekundi
    private val binder = LocalBinder()
    private var bluetoothDevice: BluetoothDevice? = null

    object PreferenceKeys {
        const val PREFS_NAME = "MyAppPreferences"
        const val KEY_PHONE_NUMBER = "phone_number"
        const val KEY_USER_NAME = "user_name"
        const val KEY_DEVICE_NAME = "device_name"
    }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothBackgroundService = this@BluetoothBackgroundService
    }

    private lateinit var bluetoothViewModel: BluetoothViewModel

    companion object {
        const val CHANNEL_ID = "BluetoothServiceChannel"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("status", status.toString())
            Log.d("newState", newState.toString())
            if (status == GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (ActivityCompat.checkSelfPermission(
                            this@BluetoothBackgroundService,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return
                    }
                    Log.d("BluetoothGattCallback", "Connected to GATT server.")
                    isConnected = true
                    //handler.removeCallbacks(bluetoothCheckRunnable)
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("BluetoothGattCallback", "Disconnected from GATT server.")
                    isConnected = false
                    sendBluetoothMessage("Disconnected")
                    sendDisconnectNotification()
                    handler.post(bluetoothCheckRunnable)
                    bluetoothViewModel.updateConnectionStatus(false)
                    gatt.close();
                } else {
                }
            } else {
                gatt.close();
            }
            if (status == BluetoothGatt.GATT_FAILURE) {
                Log.e("BluetoothGattCallback", "GATT Failure status: $status")
            }
        }
    }

    private val bluetoothCheckRunnable = object : Runnable {
        override fun run() {
            checkConnection()
            if (bluetoothDevice != null) {
                val device = bluetoothAdapter?.getRemoteDevice(bluetoothDevice!!.address)
                if (ActivityCompat.checkSelfPermission(
                        this@BluetoothBackgroundService,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                if (device != null) {
                    if (device.bondState != BluetoothDevice.BOND_BONDED) {
                        Log.d("BluetoothCheck", "Device not bonded")
                        isConnected = false
                        sendBluetoothMessage("Disconnected")
                        sendDisconnectNotification()
                        bluetoothViewModel.updateConnectionStatus(false)
                    } else {
                        //Log.d("BluetoothCheck", "Device bonded")
                        // Device is still connected, no need to take action
                    }
                }
                if (!isConnected && bluetoothAdapter?.isEnabled == true) {
                    Log.d("BluetoothCheck", "Trying to reconnect...")
                    connectBluetooth()
                }
                handler.postDelayed(this, checkInterval)
            }
        }
    }

    private val bluetoothDisconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (BluetoothDevice.ACTION_ACL_DISCONNECTED == intent?.action) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (device?.address == bluetoothDevice?.address) {
                    Log.d("BluetoothDisconnectReceiver", "Device disconnected.")
                    isConnected = false
                    sendBluetoothMessage("Disconnected")
                    sendDisconnectNotification()
                    handler.post(bluetoothCheckRunnable)
                    bluetoothViewModel.updateConnectionStatus(false)
                }
            }
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED == intent?.action) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        // Bluetooth je uključen
                        Log.d("BluetoothStateReceiver", "Bluetooth is ON")
                        connectBluetooth() // Povežite se s uređajem
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        // Bluetooth je isključen
                        Log.d("BluetoothStateReceiver", "Bluetooth is OFF")
                        isConnected = false
                        bluetoothViewModel.updateConnectionStatus(false)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BluetoothService", "Service created")
        bluetoothViewModel = ViewModelProvider.AndroidViewModelFactory.getInstance(application).create(BluetoothViewModel::class.java)
        createNotificationChannel()
        startForeground(1, getNotification())
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        registerBluetoothConnectionListener()
        connectBluetooth()
        handler.post(bluetoothCheckRunnable)
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        registerReceiver(bluetoothDisconnectReceiver, filter)
        val filter2 = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter2)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BluetoothService", "Service onStartCommand")
        /*if(!isConnected) {
            connectBluetooth()
        }*/
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(bluetoothCheckRunnable)
        unregisterReceiver(bluetoothDisconnectReceiver)
        unregisterReceiver(bluetoothStateReceiver)
        Log.d("BluetoothService", "Service destroyed")
        if (bluetoothSocket != null && bluetoothSocket!!.isConnected) {
            try {
                sendBluetoothMessage("Disconnected")
                bluetoothSocket!!.close()
                isConnected = false
                bluetoothViewModel.updateConnectionStatus(false)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun registerBluetoothConnectionListener() {
        bluetoothAdapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                /*if (profile == BluetoothProfile.A2DP) {
                    val devices = proxy?.connectedDevices
                    if (devices.isNullOrEmpty()) {
                        isConnected = false
                        sendBluetoothMessage("Disconnected")
                        sendDisconnectNotification()
                    }
                }*/
            }

            override fun onServiceDisconnected(profile: Int) {
                isConnected = false
                sendBluetoothMessage("Disconnected")
                sendDisconnectNotification()
                handler.postDelayed(bluetoothCheckRunnable, checkInterval)
                bluetoothViewModel.updateConnectionStatus(false)
            }

        }, BluetoothProfile.A2DP)
    }

    private fun sendDisconnectNotification() {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.sos_icon) // Replace with your app icon
            .setContentTitle("Bluetooth Disconnected")
            .setContentText("The Bluetooth connection has been lost.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 1000)) // Vibrate pattern: wait 0ms, vibrate 500ms, wait 1000ms

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notificationBuilder.build())

        // Trigger vibration
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(500) // Vibrate for 500 milliseconds
    }

    private fun sendConnectNotification() {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.sos_icon) // Replace with your app icon
            .setContentTitle("Bluetooth Connected")
            .setContentText("The Bluetooth connection has been successfully established.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 1000)) // Vibrate pattern: wait 0ms, vibrate 500ms, wait 1000ms

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notificationBuilder.build())

        // Trigger vibration
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(500) // Vibrate for 500 milliseconds
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun getNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bluetooth Service")
            .setContentText("Service is running")
            .setSmallIcon(R.mipmap.sos_icon)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Bluetooth Disconnect"
            val descriptionText = "Notifications for Bluetooth disconnection"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 1000) // Vibrate pattern: wait 0ms, vibrate 500ms, wait 1000ms
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }


    /*private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }*/

    fun connectBluetooth() {
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter!!.bondedDevices
        pairedDevices?.forEach { device ->
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val sharedPreferences = getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val deviceName = sharedPreferences.getString(PreferenceKeys.KEY_DEVICE_NAME, "")
            if (device.name == deviceName) {
                if (isConnected) {
                    Toast.makeText(this, "Bluetooth veza je već uspostavljena", Toast.LENGTH_SHORT).show()
                    //handler.removeCallbacks(bluetoothCheckRunnable)
                    Log.d("BluetoothService", "Already connected to the Bluetooth device.")
                    return
                }
                bluetoothSocket = device.createRfcommSocketToServiceRecord(MY_UUID)
                try {
                    bluetoothSocket!!.connect()
                    inputStream = bluetoothSocket!!.inputStream
                    connectedThread = ConnectedThread(inputStream!!)
                    connectedThread!!.start()
                    isConnected = true
                    //handler.removeCallbacks(bluetoothCheckRunnable)
                    sendBluetoothMessage("Connected")
                    bluetoothViewModel.updateConnectionStatus(true)
                    bluetoothDevice = device
                    handler.postDelayed(connectRunnable, 1000)
                    sendConnectNotification()
                    //val bluetoothGatt = device.connectGatt(this, false, gattCallback, 2)
                    Toast.makeText(this, "Bluetooth veza uspostavljena", Toast.LENGTH_SHORT).show()
                } catch (e: IOException) {
                    e.printStackTrace()
                    /*isConnected = false
                    sendBluetoothMessage("Disconnected")
                    bluetoothViewModel.updateConnectionStatus(false)*/
                    Toast.makeText(this, "Greška prilikom povezivanja s uređajem", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }
        Toast.makeText(this, "Nije pronađen uređaj ili je bluetooth ugašen", Toast.LENGTH_SHORT).show()
    }

    private val connectRunnable = Runnable {
        connectGattDelayed()
    }

    private fun connectGattDelayed() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        bluetoothDevice?.connectGatt(this@BluetoothBackgroundService, true, gattCallback, TRANSPORT_LE)
    }

    private fun closeConnection() {
        try {
            bluetoothSocket?.inputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e("BluetoothService", "Error closing connection: ${e.message}")
        } finally {
            bluetoothSocket = null
            isConnected = false
            handler.postDelayed(bluetoothCheckRunnable, checkInterval)
        }
    }

    private fun checkConnection() {
        if (bluetoothSocket != null) {
            try {
                val inputStream = bluetoothSocket!!.inputStream
                if (inputStream.available() == 0) {
                    throw IOException("Connection lost")
                }
            } catch (e: IOException) {
                //closeConnection()
            }
        }
    }

    private fun sendBluetoothMessage(message: String) {
        val outputStream = bluetoothSocket?.outputStream ?: return
        try {
            Log.d("TEST", "Sent message: $message")
            outputStream.write(message.toByteArray())
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    inner class ConnectedThread(inputStream: InputStream) : Thread() {
        private val mmInStream: InputStream = inputStream

        override fun run() {
            val buffer = ByteArray(1024)
            var bytes: Int
            while (true) {
                try {
                    bytes = mmInStream.read(buffer)
                    val readMessage = String(buffer, 0, bytes)
                    Log.d("BluetoothService", "Received message: $readMessage")
                    if (readMessage.startsWith("ButtonPressed")) {
                        launchMainActivity()
                        val sharedPreferences = getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
                        val phoneNumberPref = sharedPreferences.getString(PreferenceKeys.KEY_PHONE_NUMBER, "")
                        if (phoneNumberPref != null) {
                            makePhoneCall(phoneNumberPref)
                        }
                    }
                } catch (e: IOException) {
                    break
                }
            }
        }

        fun cancel() {
            try {
                mmInStream.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun isMainActivityRunning(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = activityManager.getRunningTasks(Int.MAX_VALUE)

        for (task in tasks) {
            if (task.baseActivity?.className == MainActivity::class.java.name) {
                return true
            }
        }
        return false
    }

    private fun launchMainActivity() {
        if (!isMainActivityRunning()) {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE // Dodajte ovu liniju
            )
            try {
                pendingIntent.send()
            } catch (e: PendingIntent.CanceledException) {
                e.printStackTrace()
            }
        }
    }

    fun makePhoneCall(phoneNumber: String) {
        Log.d("BluetoothService", "Making phone call to $phoneNumber")

        val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        if (telephonyManager.callState == TelephonyManager.CALL_STATE_IDLE) {
            val callIntent = Intent(Intent.ACTION_CALL)
            callIntent.data = Uri.parse("tel:$phoneNumber")
            callIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "myApp:MyWakelockTag"
            )
            wakeLock.acquire(10*60*1000L /*10 minutes*/)
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    startActivity(callIntent)
                }
            } catch (e: SecurityException) {
                Log.e("BluetoothService", "Failed to make phone call: ${e.message}")
            } finally {
            wakeLock.release()
        }
        } else {
            Log.e("BluetoothService", "Cannot make phone call while another call is in progress.")
        }
    }
}
