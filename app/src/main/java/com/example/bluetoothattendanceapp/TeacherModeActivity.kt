package com.example.bluetoothattendanceapp

import android.Manifest
import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
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
import com.example.bluetoothattendanceapp.data.FirebaseAttendanceRecord
import com.example.bluetoothattendanceapp.utils.FirebaseManager
import com.example.bluetoothattendanceapp.utils.UuidUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.nio.charset.Charset
import java.util.Date
import java.util.UUID
import com.example.bluetoothattendanceapp.databinding.ActivityTeacherBinding

class TeacherModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTeacherBinding
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
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

    private lateinit var firebaseManager: FirebaseManager
    private var sessionId: String? = null

    private lateinit var advertisingHandler: Handler
    private lateinit var advertiseCallback: AdvertiseCallback
    private var isAdvertising = false

    companion object {
        private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        private const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
        private const val MANUFACTURER_ID = 0x0000 // Uygulamamıza özel ID
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

                        // "STUDENT|studentNumber|courseId" formatını kontrol et
                        if (receivedData.startsWith("STUDENT|")) {
                            val parts = receivedData.split("|")
                            if (parts.size >= 3) {
                                val studentNumber = parts[1]
                                val receivedCourseId = parts[2].toIntOrNull()

                                Log.d("BLEScan", """
                                    Ayrıştırılan veriler:
                                    - Öğrenci No: $studentNumber
                                    - Gelen Ders ID: $receivedCourseId
                                    - Beklenen Ders ID: $currentCourseId
                                """.trimIndent())

                                if (receivedCourseId == currentCourseId) {
                                    lifecycleScope.launch {
                                        saveAttendanceRecord(result, studentNumber)
                                    }
                                } else {
                                    Log.d("BLEScan", "Ders ID'leri eşleşmiyor: Beklenen=$currentCourseId, Gelen=$receivedCourseId")
                                }
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
                                    deviceAddress = result.device.address,
                                    studentName = studentName,
                                    studentSurname = studentSurname,
                                    studentNumber = studentNumber,
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
        binding = ActivityTeacherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Google Nearby servisini tamamen devre dışı bırak
        disableNearbyServices()
        
        setupUI()
        setupBluetooth()
        setupRecyclerView()

        // Firebase ve veritabanı başlatma
        firebaseManager = FirebaseManager(this)
        database = AttendanceDatabase.getDatabase(this)
        courseDao = database.courseDao()

        // Kullanıcı verilerini senkronize et
        lifecycleScope.launch {
            try {
                firebaseManager.syncUsersToLocalDb()
            } catch (e: Exception) {
                Log.e("TeacherMode", "Senkronizasyon hatası: ${e.message}")
            }
        }

        getCurrentUserInfo()
    }

    private fun disableNearbyServices() {
        try {
            // Nearby Broadcasting'i devre dışı bırak
            sendBroadcast(Intent("com.google.android.gms.nearby.DISABLE_BROADCASTING"))
            
            // Nearby Discovery'i devre dışı bırak
            sendBroadcast(Intent("com.google.android.gms.nearby.DISABLE_DISCOVERY"))
            
            // Tüm Nearby servislerini durdur
            sendBroadcast(Intent("com.google.android.gms.nearby.STOP_SERVICE"))
            
            Log.d("Nearby", "Google Nearby servisleri devre dışı bırakıldı")
        } catch (e: Exception) {
            Log.e("Nearby", "Google Nearby servisleri devre dışı bırakılamadı: ${e.message}")
        }
    }

    private fun setupUI() {
        btnToggleBluetooth = binding.btnToggleBluetooth
        btnStartAttendance = binding.btnStartAttendance
        courseNameInput = binding.courseNameInput

        val recyclerView = binding.deviceList
        deviceAdapter = DeviceAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this@TeacherModeActivity)
        recyclerView.adapter = deviceAdapter

        btnToggleBluetooth.setOnClickListener { toggleBluetooth() }
        btnStartAttendance.setOnClickListener { startAttendance() }
        binding.btnShowRecords.setOnClickListener { showAttendanceRecords() }

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

    private fun startAttendance() {
        val courseName = courseNameInput.text.toString().trim()
        if (courseName.isEmpty()) {
            Toast.makeText(this, "Lütfen ders adını girin", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val course = Course(
                    name = courseName,
                    isActive = true,
                    createdAt = Date()
                )
                
                // Önce dersi veritabanına ekle ve ID'sini al
                val courseId = courseDao.insertCourse(course).toInt()
                currentCourseId = courseId
                
                // ID'si güncellenmiş yeni course objesi oluştur
                val updatedCourse = course.copy(id = courseId)
                Log.d("TeacherMode", "Yeni ders oluşturuldu: ${updatedCourse.name} (ID: ${updatedCourse.id})")

                // Taramayı başlat
                startScanning()

                // Güncellenmiş course objesi ile yayını başlat
                startCourseAdvertising(updatedCourse)

                binding.txtStatus.text = getString(R.string.broadcast_started)
                btnStartAttendance.text = getString(R.string.stop_attendance)
                btnStartAttendance.setIconResource(R.drawable.ic_stop)
            } catch (e: Exception) {
                Log.e("TeacherMode", "Ders başlatma hatası: ${e.message}")
                Toast.makeText(this@TeacherModeActivity, "Ders başlatılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
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
            val scanFilter = ScanFilter.Builder()
                .setManufacturerData(MANUFACTURER_ID, ByteArray(0))
                .build()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()

            if (ActivityCompat.checkSelfPermission(this, BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                isScanning = true
                bluetoothLeScanner.startScan(
                    listOf(scanFilter),
                    scanSettings,
                    scanCallback
                )
                Log.d("BLEScan", "Yoklama taraması başlatıldı")
                binding.txtStatus.text = "Öğrenci yoklaması bekleniyor..."
            }
        } catch (e: Exception) {
            Log.e("BLEScan", "Tarama başlatma hatası: ${e.message}")
            binding.txtStatus.text = "Tarama başlatılamadı: ${e.message}"
        }
    }

    private fun startCourseAdvertising(course: Course) {
        if (!checkBluetoothPermissions()) {
            Log.e("BLEAdvertise", "Bluetooth izinleri eksik")
            return
        }

        try {
            // Önce mevcut yayınları temizle
            cleanupAdvertising()

            // Course ID kontrolü
            if (course.id <= 0) {
                Log.e("BLEAdvertise", "Geçersiz ders ID: ${course.id}")
                updateAdvertisingStatus("Geçersiz ders ID")
                return
            }

            // Bluetooth'un hazır olmasını bekle
            advertisingHandler.postDelayed({
                try {
                    bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
                    if (bluetoothLeAdvertiser == null) {
                        Log.e("BLEAdvertise", "BLE Advertiser null")
                        updateAdvertisingStatus("BLE yayını desteklenmiyor")
                        return@postDelayed
                    }

                    // Ders yayınını başlat
                    val courseData = buildString {
                        append("COURSE|") // Sabit prefix
                        append(course.id)
                        append("|")
                        append(course.name)
                    }

                    Log.d("BLEAdvertise", """
                        Ders yayını hazırlanıyor:
                        - Ders Adı: ${course.name}
                        - Ders ID: ${course.id}
                        - Veri: $courseData
                    """.trimIndent())

                    val dataBytes = courseData.toByteArray(Charset.forName("UTF-8"))
                    Log.d("BLEAdvertise", "Ders yayını başlatılıyor: $courseData (${dataBytes.size} bytes)")

                    val settings = AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                        .setConnectable(false)
                        .setTimeout(0)
                        .build()

                    val data = AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .addManufacturerData(MANUFACTURER_ID, dataBytes)
                        .build()

                    // Yayını başlatmadan önce kısa bir bekleme
                    Thread.sleep(500)
                    
                    bluetoothLeAdvertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
                        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                            Log.d("BLEAdvertise", "Ders yayını başarıyla başlatıldı")
                            isAdvertising = true
                            updateAdvertisingStatus("Ders yayını aktif")
                        }

                        override fun onStartFailure(errorCode: Int) {
                            val errorMessage = when (errorCode) {
                                ADVERTISE_FAILED_ALREADY_STARTED -> "Yayın zaten başlatılmış"
                                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Yayın verisi çok büyük"
                                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Çok fazla yayıncı var"
                                ADVERTISE_FAILED_INTERNAL_ERROR -> "Dahili bir hata oluştu"
                                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Bu özellik desteklenmiyor"
                                else -> "Bilinmeyen hata kodu: $errorCode"
                            }
                            Log.e("BLEAdvertise", "Yayın başlatma hatası: $errorMessage")
                            handleAdvertisingError(errorMessage)
                        }
                    })
                } catch (e: Exception) {
                    Log.e("BLEAdvertise", "Yayın başlatma hatası: ${e.message}")
                    handleAdvertisingError(e.message ?: "Bilinmeyen hata")
                }
            }, 1000) // 1 saniye bekle

        } catch (e: Exception) {
            Log.e("BLEAdvertise", "Ders yayını hatası: ${e.message}")
            updateAdvertisingStatus("Hata: ${e.message}")
            isAdvertising = false
        }
    }

    private fun cleanupAdvertising() {
        try {
            // Önce tüm callback'leri temizle
            advertisingHandler.removeCallbacksAndMessages(null)
            
            // Mevcut yayını durdur
            try {
                bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            } catch (e: Exception) {
                Log.e("BLEAdvertise", "Yayın durdurma hatası: ${e.message}")
            }

            // Google Nearby servisini devre dışı bırak
            try {
                val nearbyIntent = Intent("com.google.android.gms.nearby.DISABLE_BROADCASTING")
                sendBroadcast(nearbyIntent)
            } catch (e: Exception) {
                Log.e("BLEAdvertise", "Nearby devre dışı bırakma hatası: ${e.message}")
            }

            isAdvertising = false
            Log.d("BLEAdvertise", "Tüm yayınlar temizlendi")
        } catch (e: Exception) {
            Log.e("BLEAdvertise", "Yayın temizleme hatası: ${e.message}")
        }
    }

    private fun handleAdvertisingError(error: String) {
        updateAdvertisingStatus("Yayın başlatılamadı: $error")
        Toast.makeText(this, "Yayın başlatılamadı: $error", Toast.LENGTH_LONG).show()
    }

    override fun onPause() {
        super.onPause()
        cleanupAdvertising()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAdvertising()
        unregisterReceiver(bluetoothReceiver)
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

    private fun updateAdvertisingStatus(status: String) {
        runOnUiThread {
            binding.txtStatus.text = status
            Log.d("BLEAdvertise", "Durum güncellendi: $status")
        }
    }

    private suspend fun saveAttendanceRecord(result: ScanResult, studentNumber: String) {
        try {
            Log.d("BLEScan", """
                Yoklama kaydı ekleniyor:
                - Öğrenci No: $studentNumber
                - MAC Adresi: ${result.device.address}
                - Ders ID: $currentCourseId
            """.trimIndent())

            // Önce mevcut yoklama kaydını kontrol et
            val hasExisting = database.attendanceDao().hasExistingAttendance(
                courseId = currentCourseId,
                deviceAddress = result.device.address
            )

            if (hasExisting) {
                Log.d("BLEScan", "Bu cihaz için zaten yoklama kaydı mevcut")
                sendAttendanceStatus(result.device, false)
                return
            }

            // Firebase'den öğrenci bilgilerini al
            Log.d("BLEScan", "Firebase'den öğrenci bilgileri alınıyor...")
            var studentInfo = firebaseManager.getStudentByNumber(studentNumber)
            
            if (studentInfo != null) {
                Log.d("BLEScan", """
                    Öğrenci bilgileri başarıyla alındı:
                    - Öğrenci No: $studentNumber
                    - Ad: ${studentInfo.name}
                    - Soyad: ${studentInfo.surname}
                    - Email: ${studentInfo.email}
                    - Kullanıcı Tipi: ${studentInfo.userType}
                """.trimIndent())
            } else {
                Log.e("BLEScan", "Öğrenci bilgileri Firebase'den alınamadı!")
                // Firebase'den bilgi alınamadığında yerel veritabanını kontrol et
                val localUser = database.userDao().getUserByStudentNumber(studentNumber)
                if (localUser != null) {
                    Log.d("BLEScan", "Öğrenci bilgileri yerel veritabanında bulundu")
                    studentInfo = localUser.toUser()
                } else {
                    Log.e("BLEScan", "Öğrenci bilgileri yerel veritabanında da bulunamadı")
                }
            }
            
            val attendanceRecord = AttendanceRecord(
                courseId = currentCourseId,
                deviceAddress = result.device.address,
                studentName = studentInfo?.name ?: "Bilinmeyen",
                studentSurname = studentInfo?.surname ?: "Öğrenci",
                studentNumber = studentNumber,
                timestamp = Date()
            )
            
            // Önce yerel veritabanına kaydet
            database.attendanceDao().insertAttendance(attendanceRecord)
            Log.d("BLEScan", """
                Yoklama kaydı yerel veritabanına eklendi:
                - Öğrenci: ${attendanceRecord.studentName} ${attendanceRecord.studentSurname}
                - Numara: ${attendanceRecord.studentNumber}
                - Ders ID: ${attendanceRecord.courseId}
            """.trimIndent())
            
            // Firebase'e kaydet
            sessionId?.let { id ->
                Log.d("BLEScan", "Firebase'e yoklama kaydı ekleniyor - Session ID: $id")
                val firebaseRecord = FirebaseAttendanceRecord(
                    studentId = result.device.address,
                    studentName = studentInfo?.name ?: "Bilinmeyen",
                    studentSurname = studentInfo?.surname ?: "Öğrenci",
                    studentNumber = studentNumber,
                    timestamp = attendanceRecord.timestamp.time
                )
                
                firebaseManager.addAttendanceRecord(id, firebaseRecord)
                    .onSuccess {
                        Log.d("BLEScan", "Yoklama kaydı Firebase'e başarıyla eklendi")
                    }
                    .onFailure { exception ->
                        Log.e("BLEScan", "Firebase kayıt hatası: ${exception.message}")
                        exception.printStackTrace()
                    }
            } ?: run {
                Log.e("BLEScan", "Session ID null olduğu için Firebase'e kayıt yapılamadı")
            }

            Log.d("BLEScan", "Yoklama kaydı başarıyla eklendi")
            recordedDevices.add(result.device.address)
            sendAttendanceStatus(result.device, true)
            
            val device = DeviceAdapter.BluetoothDevice(
                name = buildString {
                    append(studentInfo?.let { "${it.name} ${it.surname}" } ?: "Bilinmeyen Öğrenci")
                    append(" (")
                    append(studentNumber)
                    append(")")
                },
                address = result.device.address,
                timestamp = Date()
            )
            runOnUiThread {
                deviceAdapter.addDevice(device)
                Log.d("BLEScan", "Cihaz listesi güncellendi: ${device.name}")
            }
        } catch (e: Exception) {
            Log.e("BLEScan", "Yoklama kaydı eklenemedi: ${e.message}")
            Log.e("BLEScan", "Detaylı hata:", e)
            e.printStackTrace()
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

    private fun updateBluetoothStatus(isEnabled: Boolean) {
        btnToggleBluetooth.text = if (isEnabled) getString(R.string.bluetooth_on) else getString(R.string.bluetooth_off)
        // Ek UI güncellemelerini buraya ekleyebilirsin (örneğin buton renkleri vs.)
    }

    override fun onResume() {
        super.onResume()
        updateBluetoothStatus(bluetoothAdapter.isEnabled)
    }

    private fun setupBluetooth() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser

        advertisingHandler = Handler(Looper.getMainLooper())
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d("BLEAdvertise", "Advertising started")
                isAdvertising = true
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e("BLEAdvertise", "Advertising failed: $errorCode")
                isAdvertising = false
            }
        }

        // Bluetooth durumu değişikliklerini dinle
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        updateBluetoothStatus(bluetoothAdapter.isEnabled)
    }

    private fun setupRecyclerView() {
        // Implement the logic to set up the RecyclerView
    }

    private fun getCurrentUserInfo() {
        lifecycleScope.launch {
            try {
                val user = firebaseManager.getCurrentUserDetails()
                if (user != null) {
                    Log.d("TeacherMode", "Öğretmen bilgileri alındı: ${user.name} ${user.surname}")
                    // Öğretmen bilgileri alındıktan sonra oturum oluştur
                    createAttendanceSession(user.id)
                } else {
                    Log.e("TeacherMode", "Öğretmen bilgileri alınamadı")
                    Toast.makeText(this@TeacherModeActivity, "Öğretmen bilgileri alınamadı", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e("TeacherMode", "Öğretmen bilgileri alınırken hata: ${e.message}")
                Toast.makeText(this@TeacherModeActivity, "Öğretmen bilgileri alınamadı: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun createAttendanceSession(teacherId: String) {
        lifecycleScope.launch {
            try {
                firebaseManager.createAttendanceSession(
                    teacherId = teacherId,
                    courseName = courseNameInput.text.toString(),
                    courseId = currentCourseId
                ).onSuccess { id ->
                    sessionId = id
                    Log.d("TeacherMode", "Yoklama oturumu oluşturuldu: $id")
                }.onFailure { exception ->
                    Log.e("TeacherMode", "Yoklama oturumu oluşturulamadı: ${exception.message}")
                }
            } catch (e: Exception) {
                Log.e("TeacherMode", "Yoklama oturumu oluşturma hatası: ${e.message}")
            }
        }
    }

    private fun showAttendanceRecords() {
        val intent = Intent(this, AttendanceRecordsActivity::class.java)
        startActivity(intent)
    }
}
