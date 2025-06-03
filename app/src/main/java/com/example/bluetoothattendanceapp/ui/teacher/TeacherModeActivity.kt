package com.example.bluetoothattendanceapp.ui.teacher

import android.Manifest
import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.annotation.SuppressLint
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
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bluetoothattendanceapp.R
import com.example.bluetoothattendanceapp.data.local.AttendanceDatabase
import com.example.bluetoothattendanceapp.data.local.CourseDao
import com.example.bluetoothattendanceapp.data.model.AttendanceRecord
import com.example.bluetoothattendanceapp.data.model.Classroom
import com.example.bluetoothattendanceapp.data.model.Course
import com.example.bluetoothattendanceapp.data.model.FirebaseAttendanceRecord
import com.example.bluetoothattendanceapp.data.model.LocationPoint
import com.example.bluetoothattendanceapp.data.remote.FirebaseManager
import com.example.bluetoothattendanceapp.databinding.ActivityTeacherModeBinding
import com.example.bluetoothattendanceapp.ui.auth.LoginActivity
import com.example.bluetoothattendanceapp.ui.common.DeviceAdapter
import com.example.bluetoothattendanceapp.utils.LocationUtils
import com.example.bluetoothattendanceapp.utils.SessionManager
import com.example.bluetoothattendanceapp.utils.UuidUtils
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.util.Calendar
import java.util.Date


class TeacherModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTeacherModeBinding
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private lateinit var deviceAdapter: DeviceAdapter
    private var isScanning = false
    private var isAttendanceActive = false

    private lateinit var database: AttendanceDatabase
    private lateinit var courseDao: CourseDao

    private val recordedDevices = mutableSetOf<String>()

    private var currentCourseId: Int = -1

    private lateinit var firebaseManager: FirebaseManager
    private lateinit var sessionManager: SessionManager
    private var sessionId: String? = null

    private lateinit var advertisingHandler: Handler
    private lateinit var advertiseCallback: AdvertiseCallback
    private var isAdvertising = false

    private lateinit var mutex: Mutex

    private var selectedClassroom: Classroom? = null
    private lateinit var classroomAdapter: ArrayAdapter<String>
    private val classroomList = mutableListOf<Classroom>()

    companion object {
        private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        private const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
        private const val MANUFACTURER_ID = 0x0000
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

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d("BLEScan_TEACHER_RAW", """
                -------- HAM TARAMA SONUCU --------
                Cihaz Adresi: ${result.device?.address}
                Cihaz Adı: ${result.device?.name ?: "İsimsiz"}
                RSSI: ${result.rssi}
                Scan Record: ${result.scanRecord}
                Manufacturer Data: ${result.scanRecord?.manufacturerSpecificData}
                Service Data: ${result.scanRecord?.serviceData}
                Service UUIDs: ${result.scanRecord?.serviceUuids}
                --------------------------------
            """.trimIndent())

            if (recordedDevices.contains(result.device.address)) {
                return
            }

            result.scanRecord?.manufacturerSpecificData?.let { manufacturerData ->
                for (i in 0 until manufacturerData.size()) {
                    val manufacturerId = manufacturerData.keyAt(i)
                    val data = manufacturerData.get(manufacturerId)
                    try {
                        val receivedData = String(data, Charset.forName("UTF-8"))
                        Log.d("BLEScan", "Manufacturer Data - ID: $manufacturerId, Data: $receivedData")

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
                else -> "Bilinmeyen hata: $errorCode"
            }
            Log.e("BLEScan", "Tarama hatası: $errorMessage")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeacherModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        disableNearbyServices()
        
        mutex = Mutex()
        
        firebaseManager = FirebaseManager(this)
        sessionManager = SessionManager(this)
        database = AttendanceDatabase.getDatabase(this)
        courseDao = database.courseDao()

        setupBluetooth()
        setupRecyclerView()
        setupUI()
        setupClassroomDropdown()

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
            sendBroadcast(Intent("com.google.android.gms.nearby.DISABLE_BROADCASTING"))
            
            sendBroadcast(Intent("com.google.android.gms.nearby.DISABLE_DISCOVERY"))
            
            sendBroadcast(Intent("com.google.android.gms.nearby.STOP_SERVICE"))
            
            Log.d("Nearby", "Google Nearby servisleri devre dışı bırakıldı")
        } catch (e: Exception) {
            Log.e("Nearby", "Google Nearby servisleri devre dışı bırakılamadı: ${e.message}")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupUI() {
        binding.apply {
            btnToggleAttendance.setOnClickListener { 
                if (isAttendanceActive) {
                    stopAttendance()
                } else {
                    startAttendance()
                }
            }

            btnAddClassroom.setOnClickListener {
                startActivity(Intent(this@TeacherModeActivity, AddClassroomActivity::class.java))
            }
        }

        binding.tvStudentCount.text = "Toplam Öğrenci: 0"
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

    private fun setupClassroomDropdown() {
        classroomAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        binding.classroomDropdown.setAdapter(classroomAdapter)

        binding.classroomDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position < classroomList.size) {
                selectedClassroom = classroomList[position]
                Log.d("TeacherMode", "Seçilen derslik: ${selectedClassroom?.name}")
            }
        }

        // Derslikleri yükle
        loadClassrooms()
    }
    private fun loadClassrooms() {
        lifecycleScope.launch {
            try {
                Log.d("TeacherMode", "Derslikler yükleniyor...")
                val classrooms = withContext(Dispatchers.IO) {
                    firebaseManager.getClassrooms()
                }

                Log.d("TeacherMode", "Firebase'den ${classrooms.size} derslik alındı")

                classroomList.clear()
                classroomList.addAll(classrooms)

                val classroomNames = classrooms.map { it.name }
                classroomAdapter.clear()
                classroomAdapter.addAll(classroomNames)

                Log.d("TeacherMode", "Derslikler dropdown'a eklendi: ${classroomNames.size} adet")

                if (classroomNames.isEmpty()) {
                    Toast.makeText(
                        this@TeacherModeActivity,
                        "Henüz derslik eklenmemiş. Lütfen 'Derslik Ekle' butonuna tıklayarak derslik ekleyin.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("TeacherMode", "Derslikler yüklenirken hata: ${e.message}")
                e.printStackTrace()
                Toast.makeText(
                    this@TeacherModeActivity,
                    "Derslikler yüklenirken hata oluştu: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun startAttendance() {
        if (!checkBluetoothPermissions()) {
            Log.e("TeacherMode", "Bluetooth izinleri eksik")
            return
        }

        val courseName = binding.courseNameInput.text.toString().trim()
        if (courseName.isEmpty()) {
            Toast.makeText(this, "Lütfen ders adını girin", Toast.LENGTH_SHORT).show()
            return
        }

        if (selectedClassroom == null) {
            Toast.makeText(this, "Lütfen bir derslik seçin", Toast.LENGTH_SHORT).show()
            return
        }

        startAttendanceWithClassroom(courseName)
    }

    private fun startAttendanceWithClassroom(courseName: String) {
        lifecycleScope.launch {
            try {
                val course = Course(
                    name = courseName,
                    isActive = true,
                    createdAt = Date()
                )
                
                val courseId = courseDao.insertCourse(course).toInt()
                currentCourseId = courseId
                
                val updatedCourse = course.copy(id = courseId)
                Log.d("TeacherMode", "Yeni ders oluşturuldu: ${updatedCourse.name} (ID: ${updatedCourse.id})")

                deviceAdapter.updateDevices(emptyList())
                updateStudentCount()

                startScanning()

                startCourseAdvertising(updatedCourse)

                binding.tvStatus.text = getString(R.string.broadcast_started)
                binding.btnToggleAttendance.text = getString(R.string.stop_attendance)
                binding.btnToggleAttendance.setIconResource(R.drawable.ic_stop)
                isAttendanceActive = true

                getCurrentUserInfo()

                observeCurrentCourseAttendance()
            } catch (e: Exception) {
                Log.e("TeacherMode", "Ders başlatma hatası: ${e.message}")
                Toast.makeText(this@TeacherModeActivity, "Ders başlatılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun observeCurrentCourseAttendance() {
        lifecycleScope.launch {
            try {
                val calendar = Calendar.getInstance()
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val startOfDay = calendar.time

                database.attendanceDao().getAttendanceByCourseId(currentCourseId).collect { allRecords ->
                    Log.d("Attendance", """
                        Tüm kayıtlar alındı:
                        - Toplam kayıt: ${allRecords.size}
                        - Ders ID: $currentCourseId
                        - Tarih: $startOfDay
                    """.trimIndent())
                    
                    val todayRecords = allRecords.filter { record -> 
                        record.timestamp >= startOfDay
                    }

                    val uniqueRecords = todayRecords
                        .groupBy { it.studentNumber }
                        .mapValues { (_, records) -> 
                            records.maxByOrNull { it.timestamp }!! 
                        }
                        .values
                        .sortedBy { "${it.studentName} ${it.studentSurname}" }
                        .toList()

                    Log.d("Attendance", """
                        İşlenen kayıtlar:
                        - Bugünkü kayıt: ${todayRecords.size}
                        - Tekil öğrenci: ${uniqueRecords.size}
                        - Tarih filtresi: $startOfDay
                    """.trimIndent())

                    val devices = uniqueRecords.map { record ->
                        DeviceAdapter.BluetoothDevice(
                            name = "${record.studentName} ${record.studentSurname} (${record.studentNumber})",
                            address = record.deviceAddress,
                            timestamp = record.timestamp
                        )
                    }
                    
                    runOnUiThread {
                        updateDeviceList(devices)
                        binding.tvStudentCount.text = "Toplam Öğrenci: ${devices.size}"
                        binding.tvStatus.text = "Yoklama alınıyor... (${devices.size} öğrenci)"
                    }
                }
            } catch (e: Exception) {
                Log.e("Attendance", "Veritabanı okuma hatası: ${e.message}", e)
                runOnUiThread {
                    binding.tvStatus.text = "Yoklama listesi güncellenirken hata oluştu"
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startScanning() {
        if (!checkBluetoothPermissions()) {
            Log.e("BLEScan", "Bluetooth izinleri eksik")
            return
        }

        try {
            Log.d("BLEScan", "Tarama başlatılıyor...")

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
                binding.tvStatus.text = "Öğrenci yoklaması bekleniyor..."
            }
        } catch (e: Exception) {
            Log.e("BLEScan", "Tarama başlatma hatası: ${e.message}")
            binding.tvStatus.text = "Tarama başlatılamadı: ${e.message}"
        }
    }

    private fun startCourseAdvertising(course: Course) {
        if (!checkBluetoothPermissions()) {
            Log.e("BLEAdvertise", "Bluetooth izinleri eksik")
            return
        }

        try {
            cleanupAdvertising()

            if (course.id <= 0) {
                Log.e("BLEAdvertise", "Geçersiz ders ID: ${course.id}")
                updateAdvertisingStatus("Geçersiz ders ID")
                return
            }

            advertisingHandler.postDelayed({
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(this, BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                            Log.e("BLEAdvertise", "BLUETOOTH_ADVERTISE izni eksik")
                            permissionLauncher.launch(arrayOf(BLUETOOTH_ADVERTISE))
                            return@postDelayed
                        }
                    }

                    bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
                    if (bluetoothLeAdvertiser == null) {
                        Log.e("BLEAdvertise", "BLE Advertiser null")
                        updateAdvertisingStatus("BLE yayını desteklenmiyor")
                        return@postDelayed
                    }

                    val courseData = buildString {
                        append("COURSE|")
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
            }, 1000)

        } catch (e: Exception) {
            Log.e("BLEAdvertise", "Ders yayını hatası: ${e.message}")
            updateAdvertisingStatus("Hata: ${e.message}")
            isAdvertising = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun cleanupAdvertising() {
        try {
            advertisingHandler.removeCallbacksAndMessages(null)
            
            try {
                bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            } catch (e: Exception) {
                Log.e("BLEAdvertise", "Yayın durdurma hatası: ${e.message}")
            }

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
            binding.tvStatus.text = status
            Log.d("BLEAdvertise", "Durum güncellendi: $status")
        }
    }

    private suspend fun saveAttendanceRecord(result: ScanResult, studentNumber: String) {
        try {
            Log.d("BLEScan", """
                Yoklama kaydı kontrol ediliyor:
                - Öğrenci No: $studentNumber
                - MAC Adresi: ${result.device.address}
                - Ders ID: $currentCourseId
            """.trimIndent())

            mutex.withLock {
                val existingStudentRecord = database.attendanceDao().getAttendanceByStudentNumber(
                    courseId = currentCourseId,
                    studentNumber = studentNumber
                )

                if (existingStudentRecord != null) {
                    Log.d("BLEScan", "Bu öğrenci numarası için zaten yoklama kaydı mevcut: $studentNumber")
                    sendAttendanceStatus(result.device, false)
                    return
                }

                val existingDeviceRecord = database.attendanceDao().hasExistingAttendance(
                    courseId = currentCourseId,
                    deviceAddress = result.device.address
                )

                if (existingDeviceRecord) {
                    Log.d("BLEScan", "Bu cihaz için zaten yoklama kaydı mevcut: ${result.device.address}")
                    sendAttendanceStatus(result.device, false)
                    return
                }

                Log.d("BLEScan", "Firebase'den öğrenci bilgileri alınıyor...")
                var studentInfo = firebaseManager.getStudentByNumber(studentNumber)
                
                if (studentInfo == null) {
                    Log.e("BLEScan", "Öğrenci bilgileri Firebase'den alınamadı!")
                    val localUser = database.userDao().getUserByStudentNumber(studentNumber)
                    if (localUser != null) {
                        Log.d("BLEScan", "Öğrenci bilgileri yerel veritabanında bulundu")
                        studentInfo = localUser.toUser()
                    } else {
                        Log.e("BLEScan", "Öğrenci bilgileri yerel veritabanında da bulunamadı")
                        sendAttendanceStatus(result.device, false)
                        return
                    }
                }

                // Öğrencinin konumunu kontrol et
                val isInClassroom = selectedClassroom?.let { classroom ->
                    LocationUtils.isPointInPolygon(
                        point = LocationPoint(
                            latitude = result.scanRecord?.manufacturerSpecificData?.get(0x0000)?.let { data ->
                                // Burada öğrencinin konum bilgisini almalıyız
                                // Şimdilik varsayılan bir değer döndürüyoruz
                                0.0
                            } ?: 0.0,
                            longitude = 0.0
                        ),
                        corners = classroom.corners
                    )
                } ?: true // Eğer derslik seçilmemişse konum kontrolü yapma

                if (!isInClassroom) {
                    Log.d("BLEScan", "Öğrenci derslikte değil: $studentNumber")
                    sendAttendanceStatus(result.device, false)
                    return
                }
                
                val attendanceRecord = AttendanceRecord(
                    courseId = currentCourseId,
                    deviceAddress = result.device.address,
                    studentName = studentInfo.name,
                    studentSurname = studentInfo.surname,
                    studentNumber = studentNumber,
                    timestamp = Date()
                )
                
                database.attendanceDao().insertAttendance(attendanceRecord)
                Log.d("BLEScan", """
                    Yoklama kaydı yerel veritabanına eklendi:
                    - Öğrenci: ${attendanceRecord.studentName} ${attendanceRecord.studentSurname}
                    - Numara: ${attendanceRecord.studentNumber}
                    - Ders ID: ${attendanceRecord.courseId}
                """.trimIndent())
                
                sessionId?.let { id ->
                    Log.d("BLEScan", "Firebase'e yoklama kaydı ekleniyor - Session ID: $id")
                    val firebaseRecord = FirebaseAttendanceRecord(
                        studentId = result.device.address,
                        studentName = studentInfo.name,
                        studentSurname = studentInfo.surname,
                        studentNumber = studentNumber,
                        timestamp = attendanceRecord.timestamp.time
                    )
                    
                    firebaseManager.addAttendanceRecord(id, firebaseRecord)
                        .onSuccess {
                            Log.d("BLEScan", "Yoklama kaydı Firebase'e başarıyla eklendi")
                            sendAttendanceStatus(result.device, true)
                        }
                        .onFailure { exception ->
                            Log.e("BLEScan", "Firebase kayıt hatası: ${exception.message}")
                            exception.printStackTrace()
                        }
                }
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
            arrayOf(
                BLUETOOTH_CONNECT,
                BLUETOOTH_SCAN,
                BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.d("Permissions", "Eksik izinler: ${missingPermissions.joinToString()}")
            permissionLauncher.launch(missingPermissions.toTypedArray())
            return false
        }

        Log.d("Permissions", "Tüm izinler mevcut")
        return true
    }

    private fun sendAttendanceStatus(device: android.bluetooth.BluetoothDevice, success: Boolean) {
        if (!checkBluetoothPermissions()) {
            Log.e("BLEScan", "Bluetooth izinleri eksik")
            return
        }
        try {
            val advertiseSettings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(1000)
                .build()

            val advertiseData = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid.fromString(UuidUtils.getStatusUuid()))
                .addServiceData(
                    ParcelUuid.fromString(UuidUtils.getStatusUuid()),
                    byteArrayOf(if (success) 1 else 0)
                )
                .build()

            if (ActivityCompat.checkSelfPermission(
                    this,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BLUETOOTH_ADVERTISE else Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("BLEScan", "BLUETOOTH_ADVERTISE izni eksik")
                return
            }

            bluetoothAdapter.bluetoothLeAdvertiser.startAdvertising(
                advertiseSettings,
                advertiseData,
                object : AdvertiseCallback() {
                    @SuppressLint("MissingPermission")
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                        Log.d("BLEScan", "Durum bildirimi başarılı: ${device.address}")
                        Handler(Looper.getMainLooper()).postDelayed({
                            bluetoothAdapter.bluetoothLeAdvertiser.stopAdvertising(this)
                        }, 1000)
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
        binding.btnToggleAttendance.text = if (isEnabled) {
            getString(R.string.start_attendance)
        } else {
            getString(R.string.bluetooth_off)
        }
    }

    override fun onResume() {
        super.onResume()
        updateBluetoothStatus(bluetoothAdapter.isEnabled)
        loadClassrooms()
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

        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        updateBluetoothStatus(bluetoothAdapter.isEnabled)
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter()
        binding.rvDevices.apply {
            adapter = deviceAdapter
            layoutManager = LinearLayoutManager(this@TeacherModeActivity)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateStudentCount() {
        val count = deviceAdapter.getStudentCount()
        Log.d("Attendance", "Öğrenci sayısı güncelleniyor: $count")
        binding.tvStudentCount.text = "Toplam Öğrenci: $count"
    }

    private fun updateDeviceList(devices: List<DeviceAdapter.BluetoothDevice>) {
        Log.d("Attendance", "Cihaz listesi güncelleniyor: ${devices.size} cihaz")
        deviceAdapter.updateDevices(devices)
        updateStudentCount()
    }

    private fun getCurrentUserInfo() {
        lifecycleScope.launch {
            try {
                val user = firebaseManager.getCurrentUserDetails()
                if (user != null) {
                    Log.d("TeacherMode", "Öğretmen bilgileri alındı: ${user.name} ${user.surname}")
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
                if (currentCourseId <= 0) {
                    Log.e("TeacherMode", "Geçersiz ders ID: $currentCourseId")
                    return@launch
                }

                val courseName = binding.courseNameInput.text.toString().trim()
                if (courseName.isBlank()) {
                    Log.e("TeacherMode", "Ders adı boş olamaz")
                    Toast.makeText(this@TeacherModeActivity, "Ders adı boş olamaz", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                Log.d("TeacherMode", "Yoklama oturumu oluşturuluyor - Öğretmen: $teacherId, Ders: $courseName, ID: $currentCourseId")

                val sessionData = hashMapOf(
                    "teacherId" to teacherId,
                    "courseName" to courseName,
                    "courseId" to currentCourseId,
                    "date" to Date().time,
                    "isActive" to true,
                    "attendees" to HashMap<String, Any>()
                )

                val database = FirebaseDatabase.getInstance()
                val sessionRef = database.reference.child("attendance_sessions").push()
                
                sessionRef.setValue(sessionData).await()
                sessionId = sessionRef.key
                
                Log.d("TeacherMode", "Yoklama oturumu Firebase'e kaydedildi: $sessionId")
                Log.d("TeacherMode", "Ders Bilgileri - Ad: $courseName, ID: $currentCourseId")

            } catch (e: Exception) {
                Log.e("TeacherMode", "Yoklama oturumu oluşturulurken hata: ${e.message}")
                Toast.makeText(this@TeacherModeActivity, "Yoklama oturumu oluşturulamadı: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                performLogout()
                true
            }
            R.id.action_past_attendances -> {
                startActivity(Intent(this, PastAttendancesActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun performLogout() {
        try {
            stopAttendanceIfActive()
            firebaseManager.logout()
            sessionManager.clearSession()
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        } catch (e: Exception) {
            Log.e("TeacherModeActivity", "Çıkış yapılırken hata: ${e.message}")
            Toast.makeText(this, "Çıkış yapılırken hata oluştu: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAttendanceIfActive() {
        if (isAttendanceActive) {
            stopAttendance()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun stopAttendance() {
        try {
            stopScanning()
            cleanupAdvertising()
            sessionId?.let { id ->
                lifecycleScope.launch {
                    firebaseManager.closeAttendanceSession(id)
                }
            }
            isAttendanceActive = false
            binding.tvStatus.text = "Yoklama durduruldu"
            binding.btnToggleAttendance.text = getString(R.string.start_attendance)
            binding.btnToggleAttendance.setIconResource(R.drawable.ic_play)

            lifecycleScope.launch {
                try {
                    courseDao.updateCourseStatus(currentCourseId, false)
                    deviceAdapter.updateDevices(emptyList())
                    updateStudentCount()
                } catch (e: Exception) {
                    Log.e("TeacherMode", "Ders durumu güncellenirken hata: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("TeacherMode", "Yoklama durdurma hatası: ${e.message}")
            Toast.makeText(this, "Yoklama durdurulamadı: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
