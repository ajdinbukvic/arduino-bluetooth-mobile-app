package com.example.myapplication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.telephony.SmsManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.BluetoothBackgroundService.PreferenceKeys
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }
    private lateinit var locationManager: LocationManager
    private val PERMISSION_REQUEST_CODE = 1
    private lateinit var bluetoothViewModel: BluetoothViewModel
    private lateinit var bluetoothBackgroundService: BluetoothBackgroundService
    private var mBound: Boolean = false
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            updateLocationUI(location)
            locationManager.removeUpdates(this) // Prekini ažuriranja lokacije nakon što se dobije lokacija
        }

        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance.
            val binder = service as BluetoothBackgroundService.LocalBinder
            bluetoothBackgroundService = binder.getService()
            mBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mBound = false
        }
    }

    private lateinit var etPhoneNumber: EditText
    private lateinit var etUserName: EditText
    private lateinit var etDeviceName: EditText
    private lateinit var btnSave: Button

    private fun saveToPreferences(context: Context, phoneNumber: String, userName: String, deviceName: String) {
        val sharedPreferences = context.getSharedPreferences(Companion.PREFS_NAME, Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putString(Companion.KEY_PHONE_NUMBER, phoneNumber)
            putString(Companion.KEY_USER_NAME, userName)
            putString(Companion.KEY_DEVICE_NAME, deviceName)
            apply()
        }
    }

    private fun loadFromPreferences(context: Context): Map<String, String?> {
        val sharedPreferences = context.getSharedPreferences(Companion.PREFS_NAME, Context.MODE_PRIVATE)
        return mapOf(
            Companion.KEY_PHONE_NUMBER to sharedPreferences.getString(Companion.KEY_PHONE_NUMBER, ""),
            Companion.KEY_USER_NAME to sharedPreferences.getString(Companion.KEY_USER_NAME, ""),
            Companion.KEY_DEVICE_NAME to sharedPreferences.getString(Companion.KEY_DEVICE_NAME, "")
        )
    }

    override fun onStart() {
        super.onStart()
        // Bind to LocalService.
        Intent(this, BluetoothBackgroundService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        unbindService(connection)
        mBound = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        checkAndRequestPermissions()
        createNotificationChannel()
        bluetoothViewModel = ViewModelProvider(this)[BluetoothViewModel::class.java]
        bluetoothViewModel.isConnected.observe(this, androidx.lifecycle.Observer { isConnected ->
            updateConnectionStatus(isConnected)
        })
        val serviceIntent = Intent(this, BluetoothBackgroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                PERMISSION_REQUEST_CODE
            )
        }
        showCurrentLocation()
        etPhoneNumber = findViewById(R.id.etPhoneNumber)
        etUserName = findViewById(R.id.etUserName)
        etDeviceName = findViewById(R.id.etDeviceName)
        btnSave = findViewById(R.id.btnSave)
        val savedData = loadFromPreferences(this)
        etPhoneNumber.setText(savedData[KEY_PHONE_NUMBER])
        etUserName.setText(savedData[KEY_USER_NAME])
        etDeviceName.setText(savedData[KEY_DEVICE_NAME])
        btnSave.setOnClickListener {
            val phoneNumber = etPhoneNumber.text.toString()
            val userName = etUserName.text.toString()
            val deviceName = etDeviceName.text.toString()

            saveToPreferences(this, phoneNumber, userName, deviceName)
            Toast.makeText(this, "Unesene promjene su uspješno spremljene", Toast.LENGTH_SHORT).show()
        }
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        btnConnect.setOnClickListener {
            if (mBound) {
                bluetoothBackgroundService.connectBluetooth()
            }
            /*if (bluetoothAdapter!!.isEnabled) {
                //setupBluetooth()
            } else {
                Toast.makeText(this, "Bluetooth nije omogućen", Toast.LENGTH_SHORT).show()
            }*/
        }
        val btnMakeCall = findViewById<Button>(R.id.btnMakeCall)
        btnMakeCall.setOnClickListener {
            if (mBound) {
                val sharedPreferences = getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
                val phoneNumberPref = sharedPreferences.getString(PreferenceKeys.KEY_PHONE_NUMBER, "")
                if (phoneNumberPref != null) {
                    bluetoothBackgroundService.makePhoneCall(phoneNumberPref)
                }
            }
        }
        val btnSendSMS = findViewById<Button>(R.id.btnSendSMS)
        btnSendSMS.setOnClickListener {
            if (mBound) {
                val sharedPreferences = getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
                val phoneNumberPref = sharedPreferences.getString(PreferenceKeys.KEY_PHONE_NUMBER, "")
                val userName = sharedPreferences.getString(KEY_USER_NAME, "")
                val tvCoordinates = findViewById<TextView>(R.id.tvCoordinates)
                val text = tvCoordinates.text
                val message = "$userName $text"
                if (phoneNumberPref != null) {
                    sendSMS(phoneNumberPref, message)
                }
            }
        }
        val btnCheckStatus = findViewById<Button>(R.id.btnCheckStatus)
        btnCheckStatus.setOnClickListener {
            if (mBound) {
                bluetoothBackgroundService.isConnected
                val status: Boolean = bluetoothBackgroundService.isConnected
                updateConnectionStatus(status)
            }
            /*if (connectedThread != null) {
                val tvCoordinates = findViewById<TextView>(R.id.tvCoordinates)
                //sendSMS(phoneNumber, tvCoordinates)
            } else {
                Toast.makeText(this, "Bluetooth veza nije uspostavljena", Toast.LENGTH_SHORT).show()
            }*/
        }
    }

    private fun updateConnectionStatus(isConnected: Boolean) {
        Log.d("TEST", isConnected.toString())
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val statusText = if (isConnected) "Bluetooth Connected" else "Bluetooth Disconnected"
        tvStatus.text = "Status: $statusText"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                BluetoothBackgroundService.CHANNEL_ID,
                "Bluetooth Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CALL_PHONE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WAKE_LOCK) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.WAKE_LOCK)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.VIBRATE), PERMISSION_REQUEST_CODE)
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if ((grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })) {
                    // All requested permissions granted
                } else {
                    // Permission denied
                }
                return
            }
        }
    }

    private fun sendSMS(phoneNumber: String, message: String) {
        val smsManager = SmsManager.getDefault() as SmsManager
        smsManager.sendTextMessage(phoneNumber, null, message, null, null)
        Toast.makeText(this, "SMS poslan", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        //connectedThread?.cancel()
        try {
            //bluetoothSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun showCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
          val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
          if (location != null) {
            updateLocationUI(location)
          }
          else {
              locationManager.requestLocationUpdates(
                  LocationManager.GPS_PROVIDER,
                  0L,
                  0f,
                  locationListener,
                  Looper.getMainLooper()
              )
          }
        }
    }

    private fun updateLocationUI(location: Location) {
        val latitude = location.latitude
        val longitude = location.longitude
        val mapsUrl = "https://www.google.com/maps/search/?api=1&query=$latitude,$longitude"
        val tvCoordinates = findViewById<TextView>(R.id.tvCoordinates)
        tvCoordinates.text = mapsUrl
    }

    override fun onResume() {
        super.onResume()
        registerLocationListener()
        if (bluetoothAdapter?.isEnabled == true) {
            startDiscovery()
        } else {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT)
        }
        showCurrentLocation()
    }

    override fun onPause() {
        super.onPause()
        unregisterLocationListener()
    }

    private fun registerLocationListener() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, null)
        }
    }

    private fun unregisterLocationListener() {
        locationManager.removeUpdates(locationListener)
    }

    private fun startDiscovery() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        bluetoothAdapter?.startDiscovery()
    }

    fun openLink(view: View) {
        val textView = view as TextView
        val url = textView.text.toString()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1
        private const val REQUEST_LOCATION_PERMISSION = 2
        private const val PERMISSION_SEND_SMS = 123
        private const val PERMISSION_ACCESS_LOCATION = 456
        private const val PERMISSION_REQUEST_CODE = 789
        private const val PERMISSION_CALL_PHONE = 987
        private const val PERMISSION_WAKE_LOCK = 765
        private const val PREFS_NAME = "MyAppPreferences"
        private const val KEY_PHONE_NUMBER = "phone_number"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_DEVICE_NAME = "device_name"
    }
}


