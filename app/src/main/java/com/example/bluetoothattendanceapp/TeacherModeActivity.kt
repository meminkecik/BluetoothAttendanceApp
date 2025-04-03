package com.example.bluetoothattendanceapp

import android.Manifest
import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeScanner
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
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothattendanceapp.TeacherAttendanceActivity.Companion.EXTRA_COURSE_ID
import com.example.bluetoothattendanceapp.data.AttendanceDatabase
import com.example.bluetoothattendanceapp.data.AttendanceRecord
import com.example.bluetoothattendanceapp.data.Course
import com.example.bluetoothattendanceapp.data.CourseDao
import com.example.bluetoothattendanceapp.utils.UuidUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.nio.charset.Charset
import java.util.Date
import java.util.UUID

class TeacherModeActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private lateinit var btnToggleBluetooth: MaterialButton
    private lateinit var btnStartAttendance: MaterialButton
    private lateinit var deviceAdapter: DeviceAdapter
    private var isScanning = false

    // Veritabanı
    private lateinit var database: AttendanceDatabase
    private lateinit var courseDao: CourseDao

    // Tarama oturumu için kaydedilen MAC adresleri
    private val recordedDevices = mutableSetOf<String>()

    private lateinit var courseNameInput: TextInputEditText

    private var currentCourseId: Int = -1

    companion object {
        private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        private const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
        // MAX_CHUNK_SIZE TeacherModeActivity'de kullanılmadığı için kaldırdık.
    }

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                BLUETOOTH_CONNECT,
                BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, getString(R.string.all_permissions_granted), Toast.LENGTH_SHORT).show()
        } else {
            showPermissionDeniedDialog()
        }
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

    // Tarama işlemi sırasında alınan reklam verisini parçalarına ayırıp birleştirme mantığı
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            
            Log.d("BLEScan", """
                Yeni tarama sonucu:
                - Adres: ${result.device.address}
                - RSSI: ${result.rssi}
                - Device Name: ${result.device.name}
                - Manufacturer Data: ${result.scanRecord?.manufacturerSpecificData?.toString()}
            """.trimIndent())
            
            // Bu MAC adresi daha önce kaydedildiyse işleme alma
            if (recordedDevices.contains(result.device.address)) {
                return
            }

            // Manufacturer specific data'yı kontrol et
            result.scanRecord?.manufacturerSpecificData?.let { manufacturerData ->
                for (i in 0 until manufacturerData.size()) {
                    val manufacturerId = manufacturerData.keyAt(i)
                    val data = manufacturerData.get(manufacturerId)
                    try {
                        val receivedData = String(data, Charset.forName("UTF-8"))
                        Log.d("BLEScan", "Manufacturer Data - ID: $manufacturerId, Data: $receivedData")
                        
                        if (receivedData.contains("|")) {
                            val parts = receivedData.split("|")
                            if (parts.size >= 4) {
                                processAttendanceData(parts, result)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("BLEScan", "Veri işleme hatası: ${e.message}")
                    }
                }
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            Log.d("BLEScan", "Toplu tarama sonuçları: ${results.size} sonuç")
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMessage = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Tarama zaten başlatılmış"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Uygulama kaydı başarısız"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Özellik desteklenmiyor"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Dahili hata"
                else -> "Bilinmeyen hata: $errorCode"
            }
            Log.e("BLEScan", "Tarama hatası: $errorMessage")
        }
    }

    private val attendanceScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            result.scanRecord?.getServiceData(ParcelUuid.fromString("0000b81d-0000-1000-8000-00805f9b34fb"))?.let { data ->
                try {
                    val attendanceData = String(data, Charset.forName("UTF-8"))
                    Log.d("BLEScan", "Alınan yoklama verisi: $attendanceData")

                    // Format: studentNumber|name|surname
                    val parts = attendanceData.split("|")
                    if (parts.size >= 3) {
                        val studentNumber = parts[0]
                        val studentName = parts[1]
                        val studentSurname = parts[2]

                        // Yoklama kaydını veritabanına ekle
                        lifecycleScope.launch {
                            try {
                                val attendanceRecord = AttendanceRecord(
                                    courseId = currentCourseId,
                                    studentId = result.device.address,
                                    studentName = studentName,
                                    studentSurname = studentSurname,
                                    studentNumber = studentNumber,
                                    deviceAddress = result.device.address,
                                    timestamp = Date()
                                )
                                database.attendanceDao().insertAttendance(attendanceRecord)
                                Log.d("Attendance", "Yoklama kaydı eklendi: $studentName $studentSurname")

                                // Öğrenciye başarılı bildirimi gönder
                                sendAttendanceStatus(result.device, true)
                            } catch (e: Exception) {
                                Log.e("Attendance", "Yoklama kaydı eklenemedi: ${e.message}")
                            }
                        }
                    } else {
                        Log.e("BLEScan", "Yoklama verisi beklenen formatta değil: $attendanceData")
                    }
                } catch (e: Exception) {
                    Log.e("BLEScan", "Yoklama verisi işleme hatası: ${e.message}")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLEScan", "Yoklama taraması başarısız: $errorCode")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_mode)

        // Bluetooth adaptörünü başlat
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        // Veritabanını başlat
        database = AttendanceDatabase.getDatabase(this)
        courseDao = database.courseDao()

        // UI elemanlarını ayarla
        setupUI()

        // İzinleri kontrol et
        checkAndRequestPermissions()

        // Bluetooth durumu değişikliklerini dinle
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        updateBluetoothStatus(bluetoothAdapter.isEnabled)

        courseNameInput = findViewById(R.id.courseNameInput)
        btnStartAttendance = findViewById(R.id.btnStartAttendance)

        btnStartAttendance.setOnClickListener {
            val courseName = courseNameInput.text.toString().trim()
            if (courseName.isEmpty()) {
                courseNameInput.error = "Lütfen ders adını girin"
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    // Önce tüm dersleri pasif yap
                    database.courseDao().deactivateAllCourses()
                    
                    // Yeni dersi oluştur ve aktif olarak işaretle
                    val courseId = database.courseDao().insertCourse(
                        Course(
                            name = courseName,
                            teacherId = getUniqueDeviceId(),
                            isActive = true,
                            createdAt = Date()
                        )
                    )
                    currentCourseId = courseId.toInt()
                    Log.d("TeacherMode", "Yeni ders oluşturuldu: ID=$currentCourseId, Name=$courseName")
                    startAttendanceActivity(currentCourseId)
                } catch (e: Exception) {
                    Log.e("TeacherMode", "Ders oluşturma hatası: ${e.message}")
                    Toast.makeText(this@TeacherModeActivity, "Ders oluşturma hatası: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupUI() {
        btnToggleBluetooth = findViewById(R.id.btnToggleBluetooth)
        val recyclerView = findViewById<RecyclerView>(R.id.deviceList)
        deviceAdapter = DeviceAdapter()
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@TeacherModeActivity)
            adapter = deviceAdapter
        }

        btnToggleBluetooth.setOnClickListener { toggleBluetooth() }

        // Veritabanından yoklama kayıtlarını gözlemle
        lifecycleScope.launch {
            try {
                // Belirli bir tarihe ait yoklamaları al
                database.attendanceDao().getAttendanceByDate(Date()).collect { records ->
                    Log.d("Attendance", "Günlük kayıtlar alındı: ${records.size} kayıt")
                    records.forEach { record ->
                        Log.d("Attendance", "Kayıt: ${record.studentName} ${record.studentSurname} (${record.studentNumber})")
                    }
                    
                    deviceAdapter.updateDevices(records.map { record ->
                        DeviceAdapter.BluetoothDevice(
                            name = buildString {
                                append("Öğrenci: ${record.studentName} ${record.studentSurname}")
                                if (!record.studentNumber.isNullOrBlank()) {
                                    append(" (${record.studentNumber})")
                                }
                            },
                            address = record.deviceAddress,
                            timestamp = record.timestamp
                        )
                    })
                }
            } catch (e: Exception) {
                Log.e("Attendance", "Veritabanı okuma hatası: ${e.message}", e)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest)
        } else {
            Toast.makeText(this, getString(R.string.all_permissions_already_granted), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bluetooth_permission_required)
            .setMessage("Bluetooth ve konum izinleri olmadan uygulama çalışamaz. Lütfen gerekli izinleri verin.")
            .setPositiveButton("İzinleri Tekrar İste") { _, _ -> checkAndRequestPermissions() }
            .setNegativeButton("İptal") { dialog, _ -> dialog.dismiss(); finish() }
            .setCancelable(false)
            .show()
    }

    private fun toggleBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(BLUETOOTH_CONNECT), 1)
            return
        }

        if (bluetoothAdapter.isEnabled) {
            bluetoothAdapter.disable()
            Toast.makeText(this, getString(R.string.bluetooth_turning_off), Toast.LENGTH_SHORT).show()
        } else {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            try {
                startActivity(enableBtIntent)
            } catch (e: SecurityException) {
                Toast.makeText(this, getString(R.string.bluetooth_permission_denied), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startAttendanceActivity(courseId: Int) {
        lifecycleScope.launch {
            try {
                val course = database.courseDao().getCourseById(courseId)
                
                // Önce taramayı başlat
                startScanning()
                
                // Sonra ders yayınını başlat
                startCourseAdvertising(course)
                
                // Yoklama aktivitesini başlat
                val intent = Intent(this@TeacherModeActivity, TeacherAttendanceActivity::class.java).apply {
                    putExtra(EXTRA_COURSE_ID, courseId)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("TeacherMode", "Ders yayını başlatılamadı: ${e.message}")
            }
        }
    }

    private fun startScanning() {
        if (!checkBluetoothPermissions()) {
            Log.e("BLEScan", "Bluetooth izinleri eksik")
            return
        }

        try {
            Log.d("BLEScan", "Tarama başlatılıyor...")
            
            // Manufacturer specific data için filtre ekleyelim
            val manufacturerId = 0x0000 // Örnek manufacturer ID
            val scanFilter = ScanFilter.Builder()
                .setManufacturerData(manufacturerId, ByteArray(0))
                .build()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()

            if (ActivityCompat.checkSelfPermission(this, BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                isScanning = true
                bluetoothLeScanner.startScan(
                    listOf(scanFilter),
                    scanSettings,
                    scanCallback
                )
                Log.d("BLEScan", "Yoklama taraması başlatıldı")
            }
        } catch (e: Exception) {
            Log.e("BLEScan", "Tarama başlatma hatası", e)
        }
    }

    private fun startCourseAdvertising(course: Course) {
        if (!checkBluetoothPermissions()) {
            Log.e("BLEAdvertise", "Bluetooth izinleri eksik")
            return
        }

        try {
            // Ders yayınını başlat
            val courseData = buildString {
                append("C|")
                append(course.id)
                append("|")
                append(course.name)
                append("|")
                append(course.isActive)
            }

            val dataBytes = courseData.toByteArray(Charset.forName("UTF-8"))
            Log.d("BLEAdvertise", "Ders yayını başlatılıyor: $courseData (${dataBytes.size} bytes)")

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(0)
                .build()

            val serviceUuid = ParcelUuid.fromString("0000b81d-0000-1000-8000-00805f9b34fb")

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(serviceUuid)
                .addServiceData(serviceUuid, dataBytes)
                .build()

            bluetoothAdapter.bluetoothLeAdvertiser?.let { advertiser ->
                // Önceki yayınları temizle
                try {
                    advertiser.stopAdvertising(object : AdvertiseCallback() {})
                } catch (e: Exception) {
                    Log.e("BLEAdvertise", "Önceki yayın durdurma hatası: ${e.message}")
                }

                // Yeni yayını başlat
                advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                        Log.d("BLEAdvertise", "Ders yayını başlatıldı")
                        Toast.makeText(this@TeacherModeActivity, "Ders yayını başlatıldı", Toast.LENGTH_SHORT).show()

                        // Yoklama taramasını başlat
                        val scanFilter = ScanFilter.Builder()
                            .setServiceUuid(ParcelUuid.fromString("0000b81d-0000-1000-8000-00805f9b34fb"))
                            .build()

                        val scanSettings = ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build()

                        if (ActivityCompat.checkSelfPermission(this@TeacherModeActivity, BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                            bluetoothAdapter.bluetoothLeScanner?.startScan(
                                listOf(scanFilter),
                                scanSettings,
                                attendanceScanCallback
                            )
                            Log.d("BLEScan", "Yoklama taraması başlatıldı")
                        }
                    }

                    override fun onStartFailure(errorCode: Int) {
                        val errorMessage = when (errorCode) {
                            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Veri boyutu çok büyük"
                            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Çok fazla yayıncı"
                            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Yayın zaten başlatılmış"
                            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Dahili hata"
                            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Özellik desteklenmiyor"
                            else -> "Bilinmeyen hata: $errorCode"
                        }
                        Log.e("BLEAdvertise", "Ders yayını başlatılamadı: $errorMessage")
                        // Hata durumunda tekrar dene
                        Handler(Looper.getMainLooper()).postDelayed({
                            startCourseAdvertising(course)
                        }, 1000)
                    }
                })
            } ?: run {
                Log.e("BLEAdvertise", "BluetoothLeAdvertiser null")
                Toast.makeText(this, "BLE yayını desteklenmiyor", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("BLEAdvertise", "Ders yayını hatası: ${e.message}")
            Toast.makeText(this, "Ders yayını hatası: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Yeni yardımcı fonksiyon
    private fun processAttendanceData(parts: List<String>, result: ScanResult) {
        try {
            val studentNumber = parts[0]
            val nameInitial = parts[1]
            val surname = parts[2]
            val receivedCourseId = parts[3].toIntOrNull()

            Log.d("BLEScan", """
                Ayrıştırılan veriler:
                - Öğrenci No: $studentNumber
                - Ad (İlk Harf): $nameInitial
                - Soyad: $surname
                - Gelen Ders ID: $receivedCourseId
                - Beklenen Ders ID: $currentCourseId
            """.trimIndent())

            if (receivedCourseId == currentCourseId) {
                lifecycleScope.launch {
                    saveAttendanceRecord(result, studentNumber, nameInitial, surname)
                }
            } else {
                Log.d("BLEScan", "Ders ID'leri eşleşmiyor: Beklenen=$currentCourseId, Gelen=$receivedCourseId")
            }
        } catch (e: Exception) {
            Log.e("BLEScan", "Veri işleme hatası", e)
        }
    }

    // Yeni yardımcı fonksiyon
    private suspend fun saveAttendanceRecord(result: ScanResult, studentNumber: String, nameInitial: String, surname: String) {
        try {
            val attendanceRecord = AttendanceRecord(
                courseId = currentCourseId,
                studentId = result.device.address,
                studentName = nameInitial,
                studentSurname = surname,
                studentNumber = studentNumber,
                deviceAddress = result.device.address,
                timestamp = Date()
            )
            
            database.attendanceDao().insertAttendance(attendanceRecord)
            Log.d("BLEScan", "Yoklama kaydı başarıyla eklendi")
            
            recordedDevices.add(result.device.address)
            sendAttendanceStatus(result.device, true)
            
            val device = DeviceAdapter.BluetoothDevice(
                name = "$nameInitial $surname ($studentNumber)",
                address = result.device.address,
                timestamp = Date()
            )
            runOnUiThread {
                deviceAdapter.addDevice(device)
            }
        } catch (e: Exception) {
            Log.e("BLEScan", "Yoklama kaydı eklenemedi", e)
            sendAttendanceStatus(result.device, false)
        }
    }

    private fun checkBluetoothPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            Toast.makeText(
                this,
                "Gerekli izinler eksik: ${missingPermissions.joinToString()}",
                Toast.LENGTH_SHORT
            ).show()
            permissionLauncher.launch(missingPermissions.toTypedArray())
            return false
        }
        return true
    }

    // Örnek: Öğrenciye yoklama durumunu bildir (BLE Advertise yöntemi)
    private fun sendAttendanceStatus(device: android.bluetooth.BluetoothDevice, success: Boolean) {
        if (!checkBluetoothPermissions()) {
            Log.e("BLEScan", "Bluetooth izinleri eksik")
            return
        }
        try {
            val advertiseSettings = android.bluetooth.le.AdvertiseSettings.Builder()
                .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(1000) // 1 saniye
                .build()

            val advertiseData = android.bluetooth.le.AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid.fromString(UuidUtils.getStatusUuid()))
                .addServiceData(
                    ParcelUuid.fromString(UuidUtils.getStatusUuid()),
                    byteArrayOf(if (success) 1 else 0)
                )
                .build()

            if (ActivityCompat.checkSelfPermission(
                    this,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_ADVERTISE else Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("BLEScan", "BLUETOOTH_ADVERTISE izni eksik")
                return
            }

            bluetoothAdapter.bluetoothLeAdvertiser.startAdvertising(
                advertiseSettings,
                advertiseData,
                object : android.bluetooth.le.AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: android.bluetooth.le.AdvertiseSettings) {
                        Log.d("BLEScan", "Durum bildirimi başarılı: ${device.address}")
                    }

                    override fun onStartFailure(errorCode: Int) {
                        Log.e("BLEScan", "Durum bildirimi başarısız: $errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("BLEScan", "Durum bildirimi hatası: ${e.message}")
        }
    }

    // updateBluetoothStatus: Bluetooth durumuna göre UI güncellemesi yapar
    private fun updateBluetoothStatus(isEnabled: Boolean) {
        btnToggleBluetooth.text = if (isEnabled) getString(R.string.bluetooth_on) else getString(R.string.bluetooth_off)
        // Ek UI güncellemelerini buraya ekleyebilirsin (örneğin buton renkleri vs.)
    }

    override fun onResume() {
        super.onResume()
        updateBluetoothStatus(bluetoothAdapter.isEnabled)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothReceiver)
        if (isScanning) {
            stopScanning()
            stopCourseAdvertising()
        }
    }

    private fun getUniqueDeviceId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: 
               UUID.randomUUID().toString()
    }

    private fun stopScanning() {
        if (isScanning) {
            if (ActivityCompat.checkSelfPermission(this, BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothLeScanner.stopScan(scanCallback)
                isScanning = false
            }
        }
    }

    private fun stopCourseAdvertising() {
        try {
            if (ActivityCompat.checkSelfPermission(this, BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter.bluetoothLeScanner?.stopScan(attendanceScanCallback)
            }
        } catch (e: Exception) {
            Log.e("BLEScan", "Yoklama taraması durdurma hatası: ${e.message}")
        }
    }
}
