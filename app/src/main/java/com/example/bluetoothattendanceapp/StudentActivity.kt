package com.example.bluetoothattendanceapp

import Student
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
import com.example.bluetoothattendanceapp.utils.UuidUtils
import com.google.android.material.button.MaterialButton
import java.nio.charset.Charset
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothattendanceapp.adapter.CourseAdapter
import com.example.bluetoothattendanceapp.data.AttendanceDatabase
import com.example.bluetoothattendanceapp.data.Course
import kotlinx.coroutines.launch
import java.util.Date

class StudentActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeAdvertiser: BluetoothLeAdvertiser
    private lateinit var btnToggleBluetooth: MaterialButton
    private lateinit var txtStatus: TextView
    private var isAdvertising = false

    // Öğrenci bilgileri (örnek olarak SharedPreferences ya da sabit değer)
    private lateinit var student: Student

    private lateinit var courseRecyclerView: RecyclerView
    private lateinit var courseAdapter: CourseAdapter

    private lateinit var database: AttendanceDatabase

    // Tarama için zamanlayıcı ekleyelim
    private val scanHandler = Handler(Looper.getMainLooper())
    private var isScanning = false

    private var permissionsRequested = false

    companion object {
        private const val BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
        private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        private const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
        private const val PERMISSION_REQUEST_CODE = 1
    }

    // Advertise callback: Her parça için durum bildirimi
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.d("BLEAdvertise", "Yayın başarıyla başlatıldı")
            isAdvertising = true
            runOnUiThread { txtStatus.text = getString(R.string.broadcast_started) }
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e("BLEAdvertise", "Yayın başlatma hatası kodu: $errorCode")
            isAdvertising = false
            runOnUiThread { txtStatus.text = getString(R.string.broadcast_failed) }
        }
    }

    // Öğrenci modunda ayrıca gelen durum bildirimi dinleniyorsa scan callback tanımı
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            result.scanRecord?.getServiceData(ParcelUuid.fromString(UuidUtils.getStatusUuid()))?.let {
                val success = it[0] == 1.toByte()
                showAttendanceStatus(success)
                if (success) {
                    stopAdvertising()
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 3000)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student)

        database = AttendanceDatabase.getDatabase(this)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser

        setupUI()
        
        // İzinleri kontrol et
        val hasPermissions = checkPermissions()
        Log.d("Permissions", "İzin durumu: $hasPermissions")
        
        if (hasPermissions) {
            startInitialScan()
        } else {
            requestPermissions()
        }

        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        updateBluetoothStatus(bluetoothAdapter.isEnabled)

        loadStudentInfo()

        courseRecyclerView = findViewById(R.id.courseRecyclerView)
        setupRecyclerView()
    }

    private fun setupUI() {
        btnToggleBluetooth = findViewById(R.id.btnToggleBluetooth)
        txtStatus = findViewById(R.id.txtStatus)

        btnToggleBluetooth.setOnClickListener { toggleBluetooth() }

        // Yayın başlat butonunu kaldır
        findViewById<Button>(R.id.btnStartAdvertising)?.visibility = View.GONE
    }

    private fun toggleBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(BLUETOOTH_CONNECT),
                    PERMISSION_REQUEST_CODE
                )
                return
            }
        }

        try {
            if (bluetoothAdapter.isEnabled) {
                bluetoothAdapter.disable()
                Toast.makeText(this, getString(R.string.bluetooth_turning_off), Toast.LENGTH_SHORT).show()
            } else {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivity(enableBtIntent)
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.bluetooth_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBluetoothStatus(isEnabled: Boolean) {
        btnToggleBluetooth.apply {
            text = getString(if (isEnabled) R.string.bluetooth_on else R.string.bluetooth_off)
            icon = AppCompatResources.getDrawable(
                context,
                if (isEnabled) R.drawable.ic_check else R.drawable.ic_cross
            )
            iconGravity = MaterialButton.ICON_GRAVITY_END
            setTextColor(getColor(if (isEnabled) R.color.bluetooth_enabled else R.color.bluetooth_disabled))
        }
        if (!isEnabled && isAdvertising) {
            stopAdvertising()
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                BLUETOOTH_ADVERTISE,
                BLUETOOTH_CONNECT,
                BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.d("Permissions", "Eksik izinler: ${missingPermissions.joinToString()}")
            return false
        }

        Log.d("Permissions", "Tüm izinler mevcut")
        return true
    }

    private fun requestPermissions() {
        if (permissionsRequested) {
            showPermissionSettingsDialog()
            return
        }

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                BLUETOOTH_ADVERTISE,
                BLUETOOTH_CONNECT,
                BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        permissionsRequested = true
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Hangi izinlerin verilip verilmediğini logla
            permissions.forEachIndexed { index, permission ->
                val isGranted = grantResults[index] == PackageManager.PERMISSION_GRANTED
                Log.d("Permissions", "İzin: $permission - Verildi: $isGranted")
            }

            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d("Permissions", "Tüm izinler verildi")
                startInitialScan()
                Toast.makeText(this, "İzinler verildi, işlemler başlatılıyor", Toast.LENGTH_SHORT).show()
            } else {
                Log.e("Permissions", "Bazı izinler reddedildi")
                updateAdvertisingStatus("Bluetooth ve konum izinleri gerekli")
                showPermissionSettingsDialog()
            }
        }
    }

    private fun stopAdvertising() {
        if (!checkPermissions()) return

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        BLUETOOTH_ADVERTISE
                    } else {
                        Manifest.permission.BLUETOOTH_ADMIN
                    }
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
                isAdvertising = false
                txtStatus.text = getString(R.string.broadcast_stopped)

                // Scan durumunu kontrol et
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
                }
            }
        } catch (e: Exception) {
            Log.e("Bluetooth", "Yayın durdurma hatası: ${e.message}")
            Toast.makeText(
                this,
                getString(R.string.broadcast_stop_error, e.message),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateAdvertisingStatus(status: String) {
        runOnUiThread {
            txtStatus.text = status
            Log.d("BLEAdvertise", "Durum güncellendi: $status")
        }
    }

    // Basit bir geri bildirim fonksiyonu
    private fun showAttendanceStatus(success: Boolean) {
        val statusMessage = if (success) getString(R.string.attendance_success) else getString(R.string.attendance_failure)
        Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothReceiver)
        if (isAdvertising) stopAdvertising()
        
        try {
            // Android sürümüne göre izin kontrolü
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.checkSelfPermission(this, BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            } else {
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }

            if (hasPermission) {
                bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
                bluetoothAdapter.bluetoothLeScanner?.stopScan(courseScanCallback)
            }
        } catch (e: SecurityException) {
            Log.e("BLEScan", "Tarama durdurma izin hatası: ${e.message}")
        } catch (e: Exception) {
            Log.e("BLEScan", "Tarama durdurma hatası: ${e.message}")
        }
        
        stopScanning()
        scanHandler.removeCallbacksAndMessages(null)
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_ON -> updateBluetoothStatus(true)
                        BluetoothAdapter.STATE_OFF -> updateBluetoothStatus(false)
                    }
                }
            }
        }
    }

    private fun setupRecyclerView() {
        courseAdapter = CourseAdapter { course: Course ->
            Log.d("StudentActivity", "Ders seçildi: ${course.name}")
            // Bluetooth durumunu kontrol et
            if (!bluetoothAdapter.isEnabled) {
                Toast.makeText(this, getString(R.string.turn_on_bluetooth), Toast.LENGTH_SHORT).show()
                return@CourseAdapter
            }
            
            // Yoklama göndermeyi başlat
            startAdvertising(course)
        }
        courseRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@StudentActivity)
            adapter = courseAdapter
        }
    }

    private fun loadStudentInfo() {
        // Öğrenci bilgilerini yükle (örnek olarak)
        val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        student = Student(
            id = prefs.getString("student_id", "12345") ?: "12345",
            name = prefs.getString("name", "Ahmet") ?: "Ahmet",
            surname = prefs.getString("surname", "Yılmaz") ?: "Yılmaz",
            studentNumber = prefs.getString("student_number", "001") ?: "001"
        )
        findViewById<TextView>(R.id.txtStudentInfo).text =
            getString(R.string.student_info_format, student.name, student.surname, student.studentNumber)
        findViewById<MaterialButton>(R.id.btnEditInfo).setOnClickListener {
            startActivity(Intent(this, StudentLoginActivity::class.java))
            finish()
        }
    }

    private fun startAdvertising(course: Course) {
        Log.d("BLEAdvertise", "Yoklama gönderme başlatılıyor - Ders: ${course.name}")
        
        if (!bluetoothAdapter.isEnabled) {
            Log.e("BLEAdvertise", "Bluetooth kapalı")
            updateAdvertisingStatus(getString(R.string.turn_on_bluetooth))
            return
        }

        // İzinleri kontrol et
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasPermission) {
            Log.e("BLEAdvertise", "Bluetooth izinleri eksik")
            updateAdvertisingStatus("Bluetooth izinleri gerekli. Lütfen izinleri verin.")
            requestPermissions()
            return
        }

        try {
            val attendanceData = buildString {
                append(student.studentNumber ?: "")
                append("|")
                append(student.name.take(1))
                append("|")
                append(student.surname)
                append("|")
                append(course.id)
            }

            val dataBytes = attendanceData.toByteArray(Charset.forName("UTF-8"))
            Log.d("BLEAdvertise", "Yoklama verisi hazırlandı: $attendanceData (${dataBytes.size} bytes)")

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(0) // Süre sınırı yok
                .build()

            // Manufacturer specific data kullan
            val manufacturerId = 0x0000 // Örnek manufacturer ID
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addManufacturerData(manufacturerId, dataBytes)
                .build()

            bluetoothLeAdvertiser?.let { advertiser ->
                try {
                    advertiser.stopAdvertising(advertiseCallback)
                    advertiser.startAdvertising(settings, data, advertiseCallback)
                    Log.d("BLEAdvertise", "Yoklama yayını başlatıldı")
                    updateAdvertisingStatus("Yoklama gönderiliyor...")

                    // Yayını 30 saniye sonra durdur
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isAdvertising) {
                            stopAdvertising()
                        }
                    }, 30000)

                } catch (e: Exception) {
                    Log.e("BLEAdvertise", "Yayın hatası: ${e.message}")
                    updateAdvertisingStatus("Yayın hatası: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("BLEAdvertise", "Veri hazırlama hatası: ${e.message}")
            updateAdvertisingStatus("Veri hazırlama hatası: ${e.message}")
        }
    }

    private fun showPermissionSettingsDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("İzinler Gerekli")
            .setMessage("Bluetooth ve Konum izinleri eksik. Lütfen ayarlardan tüm izinleri verin.")
            .setPositiveButton("Ayarlara Git") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("Settings", "Ayarlar sayfası açılamadı: ${e.message}")
                    // Alternatif olarak genel ayarlar sayfasını aç
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("İptal") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun startInitialScan() {
        if (!checkPermissions()) {
            Log.e("BLEScan", "Bluetooth izinleri eksik")
            updateAdvertisingStatus("Bluetooth ve konum izinleri gerekli")
            requestPermissions()
            return
        }

        try {
            val serviceUuid = ParcelUuid.fromString("0000b81d-0000-1000-8000-00805f9b34fb")
            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(serviceUuid)
                .build()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            // Android sürümüne göre izin kontrolü
            val canScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.checkSelfPermission(this, BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            } else {
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }

            if (!canScan) {
                Log.e("BLEScan", "Tarama izinleri eksik")
                updateAdvertisingStatus("Bluetooth ve konum izinleri gerekli")
                requestPermissions()
                return
            }

            bluetoothAdapter.bluetoothLeScanner?.let { scanner ->
                try {
                    isScanning = true
                    updateAdvertisingStatus("Aktif dersler aranıyor...")
                    scanner.startScan(listOf(scanFilter), scanSettings, courseScanCallback)
                    scanHandler.postDelayed({
                        stopScanning()
                    }, 5000)
                } catch (e: SecurityException) {
                    Log.e("BLEScan", "Tarama izin hatası: ${e.message}")
                    updateAdvertisingStatus("Tarama izni hatası")
                }
            } ?: run {
                Log.e("BLEScan", "BluetoothLeScanner null")
                updateAdvertisingStatus("BLE taraması desteklenmiyor")
            }
        } catch (e: Exception) {
            Log.e("BLEScan", "Tarama başlatma hatası: ${e.message}")
            updateAdvertisingStatus("Tarama başlatılamadı: ${e.message}")
        }
    }

    private fun stopScanning() {
        if (isScanning) {
            try {
                // Android sürümüne göre izin kontrolü
                val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ActivityCompat.checkSelfPermission(this, BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                } else {
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                }

                if (hasPermission) {
                    bluetoothAdapter.bluetoothLeScanner?.stopScan(courseScanCallback)
                    isScanning = false
                    updateAdvertisingStatus("Aktif dersler listelendi")
                } else {
                    Log.e("BLEScan", "Bluetooth tarama izinleri eksik")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        requestPermissions()
                    }
                }
            } catch (e: SecurityException) {
                Log.e("BLEScan", "Tarama durdurma izin hatası: ${e.message}")
            } catch (e: Exception) {
                Log.e("BLEScan", "Tarama durdurma hatası: ${e.message}")
            }
        }
    }

    private val courseScanCallback = object : ScanCallback() {
        private val activeCourses = mutableMapOf<Int, Course>()

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val serviceUuid = ParcelUuid.fromString("0000b81d-0000-1000-8000-00805f9b34fb")
            result.scanRecord?.getServiceData(serviceUuid)?.let { data ->
                try {
                    val courseData = String(data, Charset.forName("UTF-8"))
                    if (courseData.startsWith("C|")) {
                        val parts = courseData.split("|")
                        if (parts.size >= 4) {
                            val courseId = parts[1].toInt()
                            val courseName = parts[2]
                            val isActive = parts[3].toBoolean()

                            if (isActive) {
                                val course = Course(
                                    id = courseId,
                                    name = courseName,
                                    teacherId = result.device.address,
                                    isActive = true,
                                    createdAt = Date()
                                )
                                activeCourses[courseId] = course
                                
                                runOnUiThread {
                                    courseAdapter.submitList(activeCourses.values.toList())
                                }
                            } else {
                                activeCourses.remove(courseId)
                            }
                        } else {
                            Log.e("BLEScan", "Geçersiz ders verisi: $courseData")
                        }
                    } else {
                        Log.e("BLEScan", "Geçersiz ders verisi: $courseData")
                    }
                } catch (e: Exception) {
                    Log.e("BLEScan", "Ders verisi işleme hatası: ${e.message}")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLEScan", "Ders taraması başarısız: $errorCode")
            stopScanning()
        }
    }
}
