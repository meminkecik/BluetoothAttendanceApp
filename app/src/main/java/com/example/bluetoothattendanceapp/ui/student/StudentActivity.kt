package com.example.bluetoothattendanceapp.ui.student

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
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
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bluetoothattendanceapp.R
import com.example.bluetoothattendanceapp.data.model.Course
import com.example.bluetoothattendanceapp.data.model.User
import com.example.bluetoothattendanceapp.data.remote.FirebaseManager
import com.example.bluetoothattendanceapp.databinding.ActivityStudentBinding
import com.example.bluetoothattendanceapp.ui.auth.LoginActivity
import com.example.bluetoothattendanceapp.ui.common.CourseAdapter
import com.example.bluetoothattendanceapp.utils.SessionManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.nio.charset.Charset
import java.util.Timer
import java.util.TimerTask

class StudentActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeAdvertiser: BluetoothLeAdvertiser
    private lateinit var btnToggleBluetooth: MaterialButton
    private var isAdvertising = false
    private lateinit var courseAdapter: CourseAdapter
    private val scanHandler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var permissionsRequested = false
    private lateinit var firebaseManager: FirebaseManager
    private lateinit var binding: ActivityStudentBinding
    private var currentUser: User? = null
    private val activeCourses = mutableSetOf<Course>()
    private val updateTimer = Timer()
    private var advertisingRetryCount = 0
    private val MAX_RETRY_COUNT = 3
    private val advertisingHandler = Handler(Looper.getMainLooper())
    private var pendingAdvertisingRunnable: Runnable? = null
    private val MANUFACTURER_ID = 0x0000
    private val RSSI_THRESHOLD = -75
    private val SCAN_PERIOD = 30000L
    private val scannedDevices = mutableSetOf<String>()
    private lateinit var sessionManager: SessionManager
    private val attendedCourses = mutableSetOf<Int>()
    private val ATTENDANCE_PREFS = "attendance_prefs"
    private val ATTENDED_COURSES_KEY = "attended_courses"

    companion object {
        private const val BLUETOOTH_ADVERTISE = Manifest.permission.BLUETOOTH_ADVERTISE
        private const val BLUETOOTH_CONNECT = Manifest.permission.BLUETOOTH_CONNECT
        private const val BLUETOOTH_SCAN = Manifest.permission.BLUETOOTH_SCAN
        private const val PERMISSION_REQUEST_CODE = 1
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            val advertiseMode = when (settingsInEffect.txPowerLevel) {
                AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY -> "Düşük Gecikme"
                AdvertiseSettings.ADVERTISE_MODE_BALANCED -> "Dengeli"
                AdvertiseSettings.ADVERTISE_MODE_LOW_POWER -> "Düşük Güç"
                else -> "Bilinmeyen"
            }
            
            Log.i("BLEAdvertise_STUDENT", """
                YOKLAMA YAYINI BAŞARIYLA BAŞLATILDI!
                - Tx Power Level: ${settingsInEffect.txPowerLevel}
                - Advertise Mode: $advertiseMode
                - Timeout: ${settingsInEffect.timeout}
                - Connectable: ${settingsInEffect.isConnectable}
            """.trimIndent())
            isAdvertising = true
            advertisingRetryCount = 0
            runOnUiThread { 
                binding.txtStatus.text = getString(R.string.broadcast_started)
            }

            advertisingHandler.postDelayed({
                cleanupAllAdvertising()
                runOnUiThread {
                    binding.txtStatus.text = getString(R.string.broadcast_completed)
                }
            }, 10000)
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            val errorMessage = when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> "Yayın zaten başlatılmış"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Yayın verisi çok büyük"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Çok fazla yayıncı var"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Dahili bir hata oluştu"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Bu özellik desteklenmiyor"
                else -> "Bilinmeyen hata kodu: $errorCode"
            }
            Log.e("BLEAdvertise_STUDENT", """
                YOKLAMA YAYINI BAŞLATMA HATASI!
                - Hata Kodu: $errorCode
                - Hata Mesajı: $errorMessage
                - Deneme Sayısı: $advertisingRetryCount
            """.trimIndent())
            isAdvertising = false
            runOnUiThread { 
                binding.txtStatus.text = getString(R.string.broadcast_failed)
                Toast.makeText(applicationContext, errorMessage, Toast.LENGTH_SHORT).show()
            }

            if (advertisingRetryCount < MAX_RETRY_COUNT) {
                advertisingRetryCount++
                Log.d("BLEAdvertise_STUDENT", "Yayın yeniden deneniyor (${advertisingRetryCount}/${MAX_RETRY_COUNT})")
                advertisingHandler.postDelayed({
                    cleanupAllAdvertising()
                }, 3000)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission", "SetTextI18n")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d("BLEScan", """
                -------- Yeni Tarama Sonucu --------
                Cihaz Adresi: ${result.device.address}
                Cihaz Adı: ${result.device.name ?: "İsimsiz"}
                RSSI: ${result.rssi}
                --------------------------------
            """.trimIndent())

            if (result.rssi < RSSI_THRESHOLD) {
                Log.d("BLEScan", "Zayıf sinyal: ${result.rssi} dBm")
                return
            }

            val manufacturerData = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)
            if (manufacturerData == null) {
                Log.d("BLEScan", "Manufacturer data bulunamadı")
                return
            }

            try {
                val dataString = String(manufacturerData, Charset.forName("UTF-8"))
                Log.d("BLEScan", "Alınan veri: $dataString")
                
                val parts = dataString.split("|")
                if (parts.size >= 3 && parts[0] == "COURSE") {
                    val courseId = parts[1].toIntOrNull()
                    if (courseId == null) {
                        Log.e("BLEScan", "Geçersiz ders ID: ${parts[1]}")
                        return
                    }
                    
                    val courseName = parts[2]
                    
                    val course = Course(
                        id = courseId,
                        name = courseName,
                        isActive = true
                    )
                    
                    if (!activeCourses.any { it.id == course.id }) {
                        Log.d("BLEScan", "Yeni ders bulundu: $courseName (ID: $courseId)")
                        activeCourses.add(course)
                        
                        runOnUiThread {
                            updateCourseList(activeCourses.toList())
                            binding.txtStatus.text = "Aktif ders bulundu: $courseName"
                        }
                    }
                } else {
                    Log.d("BLEScan", "Geçersiz veri formatı: $dataString")
                }
            } catch (e: Exception) {
                Log.e("BLEScan", "Veri ayrıştırma hatası: ${e.message}")
            }
        }

        @SuppressLint("SetTextI18n")
        override fun onScanFailed(errorCode: Int) {
            Log.e("BLEScan", "Tarama hatası: $errorCode")
            runOnUiThread {
                binding.txtStatus.text = "Tarama hatası oluştu"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        btnToggleBluetooth = binding.btnToggleBluetooth
        setupUI()
        setupBluetooth()
        setupRecyclerView()
        firebaseManager = FirebaseManager(this)
        sessionManager = SessionManager(this)

        getCurrentUserInfo()

        loadAttendedCourses()
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
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupUI() {
        binding.apply {
            btnToggleBluetooth.setOnClickListener { toggleBluetooth() }
        }
    }

    private fun toggleBluetooth() {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasPermission) {
            ActivityCompat.requestPermissions(
                this,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(BLUETOOTH_CONNECT)
                } else {
                    arrayOf(Manifest.permission.BLUETOOTH_ADMIN)
                },
                PERMISSION_REQUEST_CODE
            )
            return
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
        binding.apply {
            btnToggleBluetooth.apply {
                text = getString(if (isEnabled) R.string.bluetooth_on else R.string.bluetooth_off)
                icon = AppCompatResources.getDrawable(
                    context,
                    if (isEnabled) R.drawable.ic_check else R.drawable.ic_cross
                )
                iconGravity = MaterialButton.ICON_GRAVITY_END
                setTextColor(getColor(if (isEnabled) android.R.color.holo_green_dark else android.R.color.holo_red_dark))
            }
            tvBluetoothStatus.text = if (isEnabled) "Bluetooth: Açık" else "Bluetooth: Kapalı"
            tvBluetoothStatus.setTextColor(getColor(if (isEnabled) android.R.color.holo_green_dark else android.R.color.holo_red_dark))
        }
        if (!isEnabled && isAdvertising) {
            stopAdvertising()
        }
    }

    private fun checkBluetoothPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                BLUETOOTH_ADVERTISE,
                BLUETOOTH_CONNECT,
                BLUETOOTH_SCAN,
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
            requestPermissions(missingPermissions.toTypedArray())
            return false
        }

        Log.d("Permissions", "Tüm izinler mevcut")
        return true
    }

    private fun requestPermissions(permissions: Array<String>) {
        if (permissionsRequested) {
            showPermissionSettingsDialog()
            return
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
        if (!checkBluetoothPermissions()) return

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
                cleanupAllAdvertising()
                binding.txtStatus.text = getString(R.string.broadcast_stopped)

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
            binding.txtStatus.text = status
            Log.d("BLEAdvertise", "Durum güncellendi: $status")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAllAdvertising()
        pendingAdvertisingRunnable?.let { advertisingHandler.removeCallbacks(it) }
        advertisingHandler.removeCallbacksAndMessages(null)
        unregisterReceiver(bluetoothReceiver)

        try {
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
        } catch (e: Exception) {
            Log.e("BLEScan", "Tarama durdurma hatası: ${e.message}")
        }

        stopScanning()
        scanHandler.removeCallbacksAndMessages(null)
        updateTimer.cancel()
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

            if (!bluetoothAdapter.isEnabled) {
                Toast.makeText(this, getString(R.string.turn_on_bluetooth), Toast.LENGTH_SHORT).show()
                return@CourseAdapter
            }

            if (currentUser == null) {
                Log.e("StudentActivity", "Kullanıcı bilgileri henüz yüklenmedi")
                Toast.makeText(this, "Lütfen biraz bekleyin, kullanıcı bilgileri yükleniyor", Toast.LENGTH_SHORT).show()
                return@CourseAdapter
            }

            startAdvertisingForCourse(course)
        }

        binding.courseRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@StudentActivity)
            adapter = courseAdapter
        }
    }

    private fun startAdvertisingForCourse(course: Course) {
        currentUser?.let { user ->
            if (attendedCourses.contains(course.id)) {
                Log.d("StudentActivity", "Bu derse (${course.name}) zaten yoklama gönderilmiş")
                Toast.makeText(
                    this,
                    "Bu derse bugün zaten yoklama gönderdiniz",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            if (!bluetoothAdapter.isEnabled) {
                Log.e("BLEAdvertise", "Bluetooth kapalı")
                updateAdvertisingStatus("Bluetooth açık değil")
                showEnableBluetoothDialog()
                return
            }

            if (course.id <= 0) {
                Log.e("BLEAdvertise", "Geçersiz ders ID: ${course.id}")
                updateAdvertisingStatus("Geçersiz ders ID")
                Toast.makeText(this, "Geçersiz ders ID. Lütfen tekrar deneyin.", Toast.LENGTH_SHORT).show()
                return
            }

            Log.d("BLEAdvertise", "Yoklama gönderiliyor - Ders: ${course.name} (ID: ${course.id})")

            pendingAdvertisingRunnable?.let { advertisingHandler.removeCallbacks(it) }
            pendingAdvertisingRunnable = null

            cleanupAllAdvertising()
            startSimpleAdvertising(course, user)

            saveAttendedCourse(course.id)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showEnableBluetoothDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Bluetooth Gerekli")
            .setMessage("Yoklama için Bluetooth'un açık olması gerekiyor. Bluetooth'u açmak ister misiniz?")
            .setPositiveButton("Evet") { _, _ ->
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                try {
                    startActivity(enableBtIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Bluetooth açılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hayır") { dialog, _ ->
                dialog.dismiss()
                updateAdvertisingStatus("Bluetooth açık değil")
            }
            .setCancelable(false)
            .show()
    }

    private fun startSimpleAdvertising(course: Course, user: User) {
        try {
            if (!checkBluetoothPermissions()) {
                Log.e("BLEAdvertise_STUDENT", "Bluetooth izinleri eksik")
                requestPermissions(arrayOf(BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION))
                return
            }

            if (!bluetoothAdapter.isEnabled) {
                showBluetoothSettingsDialog()
                return
            }

            cleanupAllAdvertising()

            advertisingHandler.postDelayed({
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(this, BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                            Log.e("BLEAdvertise_STUDENT", "BLUETOOTH_ADVERTISE izni eksik")
                            requestPermissions(arrayOf(BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION))
                            return@postDelayed
                        }
                    }

                    bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser

                    val attendanceData = buildString {
                        append("STUDENT|")
                        append(user.studentNumber ?: "")
                        append("|")
                        append(course.id)
                    }

                    Log.d("BLEAdvertise_STUDENT", """
                        Yoklama verisi hazırlanıyor:
                        - Öğrenci No: ${user.studentNumber}
                        - Ders Adı: ${course.name}
                        - Ders ID: ${course.id}
                        - Veri: $attendanceData
                    """.trimIndent())

                    val dataBytes = attendanceData.toByteArray(Charset.forName("UTF-8"))
                    Log.d("BLEAdvertise_STUDENT", "Yoklama verisi hazırlandı: $attendanceData (${dataBytes.size} bytes)")

                    val settings = AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                        .setConnectable(false)
                        .setTimeout(30000)
                        .build()

                    val data = AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .addManufacturerData(MANUFACTURER_ID, dataBytes)
                        .build()

                    bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
                    Log.d("BLEAdvertise_STUDENT", "Yoklama yayını başlatıldı")

                } catch (e: Exception) {
                    Log.e("BLEAdvertise_STUDENT", "Yayın başlatma hatası: ${e.message}")
                    handleAdvertisingError(e.message ?: "Bilinmeyen hata", course)
                }
            }, 1000)

        } catch (e: Exception) {
            Log.e("BLEAdvertise_STUDENT", "Yoklama gönderme hatası: ${e.message}")
            updateAdvertisingStatus("Hata: ${e.message}")
            isAdvertising = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun cleanupAllAdvertising() {
        try {
            advertisingHandler.removeCallbacksAndMessages(null)
            pendingAdvertisingRunnable?.let { advertisingHandler.removeCallbacks(it) }

            try {
                bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
                Log.d("BLEAdvertise", "Bluetooth yayını durduruldu")
            } catch (e: Exception) {
                Log.e("BLEAdvertise", "Bluetooth yayını durdurma hatası: ${e.message}")
            }

            isAdvertising = false
            Log.d("BLEAdvertise", "Tüm yayınlar temizlendi")
        } catch (e: Exception) {
            Log.e("BLEAdvertise", "Yayın temizleme hatası: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        cleanupAllAdvertising()
        pendingAdvertisingRunnable?.let { advertisingHandler.removeCallbacks(it) }
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
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("İptal") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun startActiveCoursesCheck() {
        Log.d("StudentActivity", "Aktif ders kontrolü başlatılıyor")

        lifecycleScope.launch {
            firebaseManager.getActiveCourses().collect { courses ->
                Log.d("StudentActivity", "Firebase'den gelen ders sayısı: ${courses.size}")
                updateCourseList(courses)
            }
        }

        updateTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    Log.d("BLEScan", "Periyodik tarama kontrolü")
                    if (!isScanning) {
                        Log.d("BLEScan", "Yeni tarama başlatılıyor")
                        startInitialScan()
                    } else {
                        Log.d("BLEScan", "Önceki tarama devam ediyor")
                    }
                }
            }
        }, 0, 30000)
    }

    @SuppressLint("SetTextI18n")
    private fun updateCourseList(firebaseCourses: List<Course>) {
        val allCourses = mutableSetOf<Course>()

        allCourses.addAll(firebaseCourses)

        activeCourses.forEach { bluetoothCourse ->
            val matchingFirebaseCourse = firebaseCourses.find { it.name == bluetoothCourse.name }
            if (matchingFirebaseCourse != null) {
                allCourses.add(bluetoothCourse.copy(id = matchingFirebaseCourse.id))
            } else {
                allCourses.add(bluetoothCourse)
            }
        }

        Log.d("StudentActivity", "Toplam aktif ders sayısı: ${allCourses.size} " +
            "(Firebase: ${firebaseCourses.size}, Bluetooth: ${activeCourses.size})")

        if (allCourses.isEmpty()) {
            binding.txtStatus.text = "Aktif ders bulunamadı"
        } else {
            binding.txtStatus.text = "Aktif dersler listelendi"
        }

        courseAdapter.submitList(allCourses.toList())
    }

    private val courseScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission", "SetTextI18n")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d("BLEScan", """
                -------- Yeni Tarama Sonucu --------
                Cihaz Adresi: ${result.device.address}
                Cihaz Adı: ${result.device.name ?: "İsimsiz"}
                RSSI: ${result.rssi}
                --------------------------------
            """.trimIndent())

            val scanRecord = result.scanRecord
            if (scanRecord == null) {
                Log.e("BLEScan", "Scan record null")
                return
            }

            val manufacturerData = scanRecord.getManufacturerSpecificData(0x0000)
            if (manufacturerData != null) {
                try {
                    val dataString = String(manufacturerData, Charset.forName("UTF-8"))
                    Log.d("BLEScan", "Alınan veri: $dataString")

                    val parts = dataString.split("|")
                    if (parts.size >= 4 && parts[0] == "C") {
                        val courseId = parts[1].toInt()
                        val courseName = parts[2]
                        val isActive = parts[3].toBoolean()

                        if (isActive) {
                            val course = Course(
                                id = courseId,
                                name = courseName,
                                isActive = true
                            )

                            if (!activeCourses.any { it.id == course.id }) {
                                activeCourses.add(course)
                                Log.d("BLEScan", "Yeni ders bulundu: ${course.name}")

                                runOnUiThread {
                                    updateCourseList(activeCourses.toList())
                                    binding.txtStatus.text = "Aktif dersler bulundu"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BLEScan", "Veri ayrıştırma hatası: ${e.message}")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMessage = when (errorCode) {
                else -> "Bilinmeyen hata: $errorCode"
            }
            Log.e("BLEScan", "Tarama hatası: $errorMessage")
            stopScanning()

            runOnUiThread {
                Toast.makeText(applicationContext,
                    "Tarama hatası: $errorMessage",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBluetooth() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser

        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        updateBluetoothStatus(bluetoothAdapter.isEnabled)

        if (checkBluetoothPermissions()) {
            startInitialScan()
        } else {
            requestPermissions(arrayOf(BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    @SuppressLint("MissingPermission", "SetTextI18n")
    private fun startInitialScan() {
        Log.d("BLEScan", "startInitialScan çağrıldı")

        if (!bluetoothAdapter.isEnabled) {
            Log.e("BLEScan", "Bluetooth kapalı")
            Toast.makeText(this, "Lütfen Bluetooth'u açın", Toast.LENGTH_SHORT).show()
            return
        }

        if (!checkBluetoothPermissions()) {
            Log.e("BLEScan", "Bluetooth izinleri eksik")
            requestPermissions(arrayOf(BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION))
            return
        }

        if (isScanning) {
            Log.d("BLEScan", "Tarama zaten devam ediyor")
            return
        }

        try {
            scannedDevices.clear()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .build()

            val scanner = bluetoothAdapter.bluetoothLeScanner
            if (scanner == null) {
                Log.e("BLEScan", "BluetoothLeScanner null")
                Toast.makeText(this, "Bluetooth LE tarayıcı başlatılamadı", Toast.LENGTH_SHORT).show()
                return
            }

            try {
                Log.d("BLEScan", "Bluetooth taraması başlatılıyor...")
                isScanning = true

                scanner.startScan(null, scanSettings, scanCallback)
                Log.d("BLEScan", "Bluetooth taraması başlatıldı")
                
                binding.txtStatus.text = "Yakındaki dersleri tarıyor..."

                scanHandler.postDelayed({
                    Log.d("BLEScan", "Tarama süresi doldu, durduruluyor")
                    stopScanning()
                }, SCAN_PERIOD)

            } catch (e: Exception) {
                Log.e("BLEScan", "Tarama başlatma hatası: ${e.message}")
                isScanning = false
                Toast.makeText(this, "Tarama başlatılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("BLEScan", "Tarama başlatma hatası: ${e.message}")
            isScanning = false
            Toast.makeText(this, "Tarama hatası: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun stopScanning() {
        Log.d("BLEScan", "stopScanning çağrıldı")
        
        if (!isScanning) {
            Log.d("BLEScan", "Tarama zaten durdurulmuş")
            return
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothAdapter.bluetoothLeScanner?.stopScan(courseScanCallback)
                }
            } else {
                bluetoothAdapter.bluetoothLeScanner?.stopScan(courseScanCallback)
            }
            
            isScanning = false
            
            runOnUiThread {
                if (activeCourses.isEmpty()) {
                    binding.txtStatus.text = "Yakında aktif ders bulunamadı"
                } else {
                    binding.txtStatus.text = "Bulunan dersler listelendi"
                }
                updateCourseList(emptyList())
            }
        } catch (e: Exception) {
            Log.e("BLEScan", "Tarama durdurma hatası: ${e.message}")
        } finally {
            isScanning = false
        }
    }

    private fun getCurrentUserInfo() {
        lifecycleScope.launch {
            try {
                val user = firebaseManager.getCurrentUserDetails()
                if (user != null) {
                    currentUser = user
                    Log.d("StudentActivity", "Kullanıcı bilgileri alındı: ${user.name} ${user.surname}")
                    updateUIWithUserInfo(user)
                    startActiveCoursesCheck()
                } else {
                    Log.e("StudentActivity", "Kullanıcı bilgileri null")
                    Toast.makeText(this@StudentActivity, "Kullanıcı bilgileri alınamadı", Toast.LENGTH_SHORT).show()
                    firebaseManager.logout()
                    startActivity(Intent(this@StudentActivity, LoginActivity::class.java))
                    finish()
                }
            } catch (e: Exception) {
                Log.e("StudentActivity", "Kullanıcı bilgileri alınırken hata: ${e.message}")
                Toast.makeText(this@StudentActivity, "Kullanıcı bilgileri alınamadı: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUIWithUserInfo(user: User) {
        binding.tvUserInfo.text = buildString {
            append("Öğrenci Bilgileri\n")
            append("Ad: ${user.name}\n")
            append("Soyad: ${user.surname}\n")
            append("Öğrenci No: ${user.studentNumber ?: "Belirtilmemiş"}")
        }
    }

    private fun handleAdvertisingError(error: String, course: Course) {
        if (advertisingRetryCount < MAX_RETRY_COUNT) {
            advertisingRetryCount++
            Log.d("BLEAdvertise", "Yayın yeniden deneniyor (${advertisingRetryCount}/${MAX_RETRY_COUNT})")
            
            advertisingHandler.postDelayed({
                currentUser?.let { user ->
                    startSimpleAdvertising(course, user)
                }
            }, 3000)
            
            updateAdvertisingStatus("Yayın yeniden deneniyor...")
        } else {
            advertisingRetryCount = 0
            updateAdvertisingStatus("Yayın başlatılamadı: $error")
            Toast.makeText(this, "Yoklama gönderilemedi. Lütfen tekrar deneyin.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showBluetoothSettingsDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Bluetooth Ayarları")
            .setMessage("Yoklama göndermek için Bluetooth'un açık olması ve düzgün çalışması gerekiyor. Bluetooth ayarlarını açmak ister misiniz?")
            .setPositiveButton("Bluetooth Ayarları") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Bluetooth ayarları açılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("İptal") { dialog, _ ->
                dialog.dismiss()
                updateAdvertisingStatus("Bluetooth açık değil")
            }
            .setCancelable(false)
            .show()
    }

    private fun showBluetoothNotSupportedDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Bluetooth Desteklenmiyor")
            .setMessage("Cihazınız Bluetooth Low Energy yayınını desteklemiyor veya Bluetooth düzgün çalışmıyor. Lütfen Bluetooth'u kapatıp açmayı deneyin.")
            .setPositiveButton("Bluetooth Ayarları") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Bluetooth ayarları açılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("İptal") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun performLogout() {
        try {
            firebaseManager.logout()
            sessionManager.clearSession()
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        } catch (e: Exception) {
            Log.e("StudentActivity", "Çıkış yapılırken hata: ${e.message}")
            Toast.makeText(this, "Çıkış yapılırken hata oluştu: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadAttendedCourses() {
        val prefs = getSharedPreferences(ATTENDANCE_PREFS, Context.MODE_PRIVATE)
        val attendedCoursesStr = prefs.getString(ATTENDED_COURSES_KEY, "")
        if (attendedCoursesStr?.isNotEmpty() == true) {
            attendedCourses.addAll(
                attendedCoursesStr.split(",")
                    .mapNotNull { it.toIntOrNull() }
            )
        }
        
        val lastResetTime = prefs.getLong("last_reset_time", 0)
        val currentTime = System.currentTimeMillis()
        if (shouldResetAttendance(lastResetTime, currentTime)) {
            resetAttendedCourses(currentTime)
        }
    }

    private fun shouldResetAttendance(lastResetTime: Long, currentTime: Long): Boolean {
        val lastResetDate = java.util.Calendar.getInstance().apply { timeInMillis = lastResetTime }
        val currentDate = java.util.Calendar.getInstance().apply { timeInMillis = currentTime }
        
        return lastResetTime == 0L || 
               lastResetDate.get(java.util.Calendar.DAY_OF_YEAR) != currentDate.get(java.util.Calendar.DAY_OF_YEAR) ||
               lastResetDate.get(java.util.Calendar.YEAR) != currentDate.get(java.util.Calendar.YEAR)
    }

    private fun resetAttendedCourses(currentTime: Long) {
        attendedCourses.clear()
        getSharedPreferences(ATTENDANCE_PREFS, Context.MODE_PRIVATE).edit().apply {
            putString(ATTENDED_COURSES_KEY, "")
            putLong("last_reset_time", currentTime)
            apply()
        }
        Log.d("StudentActivity", "Yoklama listesi sıfırlandı")
    }

    private fun saveAttendedCourse(courseId: Int) {
        attendedCourses.add(courseId)
        getSharedPreferences(ATTENDANCE_PREFS, Context.MODE_PRIVATE).edit().apply {
            putString(ATTENDED_COURSES_KEY, attendedCourses.joinToString(","))
            apply()
        }
        Log.d("StudentActivity", "Ders $courseId yoklamaya eklendi")
    }
}
