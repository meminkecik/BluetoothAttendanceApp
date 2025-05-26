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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.bluetoothattendanceapp.utils.UuidUtils
import com.google.android.material.button.MaterialButton
import java.nio.charset.Charset
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothattendanceapp.adapter.CourseAdapter
import com.example.bluetoothattendanceapp.data.AttendanceDatabase
import com.example.bluetoothattendanceapp.data.Course
import com.example.bluetoothattendanceapp.data.User
import com.example.bluetoothattendanceapp.utils.FirebaseManager
import kotlinx.coroutines.launch
import java.util.Date
import com.example.bluetoothattendanceapp.databinding.ActivityStudentBinding
import java.util.Timer
import java.util.TimerTask
import android.app.ActivityManager
import com.example.bluetoothattendanceapp.utils.SessionManager

class StudentActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeAdvertiser: BluetoothLeAdvertiser
    private lateinit var btnToggleBluetooth: MaterialButton
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

    private lateinit var firebaseManager: FirebaseManager
    private lateinit var binding: ActivityStudentBinding
    private var currentUser: User? = null

    private val activeCourses = mutableSetOf<Course>()

    private val updateTimer = Timer()

    private var advertisingRetryCount = 0
    private val MAX_RETRY_COUNT = 3
    private val advertisingHandler = Handler(Looper.getMainLooper())
    private var pendingAdvertisingRunnable: Runnable? = null

    private var isResetting = false
    private var lastResetTime = 0L

    private val MANUFACTURER_ID = 0x0000 // Uygulamamıza özel ID
    private val RSSI_THRESHOLD = -75 // RSSI eşik değeri
    private val SCAN_PERIOD = 30000L // 30 saniye
    private val scannedDevices = mutableSetOf<String>() // Taranan cihazlar

    private lateinit var sessionManager: SessionManager

    // Yoklama gönderilen dersleri takip etmek için
    private val attendedCourses = mutableSetOf<Int>()
    private val ATTENDANCE_PREFS = "attendance_prefs"
    private val ATTENDED_COURSES_KEY = "attended_courses"

    companion object {
        private const val BLUETOOTH_ADVERTISE = Manifest.permission.BLUETOOTH_ADVERTISE
        private const val BLUETOOTH_CONNECT = Manifest.permission.BLUETOOTH_CONNECT
        private const val BLUETOOTH_SCAN = Manifest.permission.BLUETOOTH_SCAN
        private const val PERMISSION_REQUEST_CODE = 1
    }

    // Advertise callback: Her parça için durum bildirimi
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

            // 10 saniye sonra yayını otomatik durdur
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

            // Hata durumunda yeniden deneme
            if (advertisingRetryCount < MAX_RETRY_COUNT) {
                advertisingRetryCount++
                Log.d("BLEAdvertise_STUDENT", "Yayın yeniden deneniyor (${advertisingRetryCount}/${MAX_RETRY_COUNT})")
                advertisingHandler.postDelayed({
                    cleanupAllAdvertising()
                }, 3000) // 3 saniye bekle
            }
        }
    }

    // Öğrenci modunda ayrıca gelen durum bildirimi dinleniyorsa scan callback tanımı
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d("BLEScan", """
                -------- Yeni Tarama Sonucu --------
                Cihaz Adresi: ${result.device.address}
                Cihaz Adı: ${result.device.name ?: "İsimsiz"}
                RSSI: ${result.rssi}
                --------------------------------
            """.trimIndent())

            // RSSI kontrolü
            if (result.rssi < RSSI_THRESHOLD) {
                Log.d("BLEScan", "Zayıf sinyal: ${result.rssi} dBm")
                return
            }

            // Manufacturer specific data kontrolü
            val manufacturerData = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)
            if (manufacturerData == null) {
                Log.d("BLEScan", "Manufacturer data bulunamadı")
                return
            }

            try {
                val dataString = String(manufacturerData, Charset.forName("UTF-8"))
                Log.d("BLEScan", "Alınan veri: $dataString")
                
                // "COURSE|courseId|courseName" formatındaki veriyi parçala
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
                    
                    // Eğer bu ders daha önce eklenmemişse ekle
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

        // Google Nearby servislerini devre dışı bırakma çağrısını kaldırıyoruz
        // disableNearbyServices()

        btnToggleBluetooth = binding.btnToggleBluetooth
        setupUI()
        setupBluetooth()
        setupRecyclerView()
        firebaseManager = FirebaseManager(this)
        sessionManager = SessionManager(this)

        // Önce kullanıcı bilgilerini al, sonra dersleri kontrol etmeye başla
        getCurrentUserInfo()

        // Daha önce yoklama gönderilen dersleri yükle
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

    private fun disableNearbyServices() {
        try {
            // Tüm Nearby servislerini agresif bir şekilde devre dışı bırak
            val nearbyIntents = arrayOf(
                "com.google.android.gms.nearby.DISABLE_BROADCASTING",
                "com.google.android.gms.nearby.DISABLE_DISCOVERY",
                "com.google.android.gms.nearby.STOP_SERVICE",
                "com.google.android.gms.nearby.STOP_BROADCASTING",
                "com.google.android.gms.nearby.STOP_DISCOVERY",
                "com.google.android.gms.nearby.RESET"
            )

            nearbyIntents.forEach { action ->
                try {
                    sendBroadcast(Intent(action))
                    Log.d("Nearby", "Nearby servisi devre dışı bırakıldı: $action")
                } catch (e: Exception) {
                    Log.e("Nearby", "Nearby servisi devre dışı bırakılamadı: $action - ${e.message}")
                }
            }

            // Sistem servislerini kontrol et ve durdur
            val serviceIntent = Intent()
            serviceIntent.setPackage("com.google.android.gms")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val services = packageManager.queryIntentServices(
                    serviceIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
                services.forEach { service ->
                    try {
                        val componentName = ComponentName(service.serviceInfo.packageName, service.serviceInfo.name)
                        stopService(Intent().setComponent(componentName))
                        Log.d("Nearby", "Servis durduruldu: ${service.serviceInfo.name}")
                    } catch (e: Exception) {
                        Log.e("Nearby", "Servis durdurulamadı: ${service.serviceInfo.name} - ${e.message}")
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val services = packageManager.queryIntentServices(serviceIntent, PackageManager.GET_META_DATA)
                services.forEach { service ->
                    try {
                        val componentName = ComponentName(service.serviceInfo.packageName, service.serviceInfo.name)
                        stopService(Intent().setComponent(componentName))
                        Log.d("Nearby", "Servis durduruldu: ${service.serviceInfo.name}")
                    } catch (e: Exception) {
                        Log.e("Nearby", "Servis durdurulamadı: ${service.serviceInfo.name} - ${e.message}")
                    }
                }
            }

            Log.d("Nearby", "Tüm Google Nearby servisleri devre dışı bırakıldı")
        } catch (e: Exception) {
            Log.e("Nearby", "Google Nearby servisleri devre dışı bırakılamadı: ${e.message}")
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
            binding.txtStatus.text = status
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
            
            // Seçilen derse göre yoklama gönder
            startAdvertisingForCourse(course)
        }

        binding.courseRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@StudentActivity)
            adapter = courseAdapter
        }
    }

    private fun loadStudentInfo() {
        // Öğrenci bilgilerini yükle
        val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        student = Student(
            id = prefs.getString("student_id", "12345") ?: "12345",
            name = prefs.getString("name", "Ahmet") ?: "Ahmet",
            surname = prefs.getString("surname", "Yılmaz") ?: "Yılmaz",
            studentNumber = prefs.getString("student_number", "001") ?: "001"
        )
        
        binding.tvUserInfo.text = buildString {
            append("Öğrenci Bilgileri\n")
            append("Ad: ${student.name}\n")
            append("Soyad: ${student.surname}\n")
            append("Öğrenci No: ${student.studentNumber}")
        }
    }

    private fun startAdvertisingForCourse(course: Course) {
        currentUser?.let { user ->
            // Önce bu derse daha önce yoklama gönderilip gönderilmediğini kontrol et
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

            if (bluetoothLeAdvertiser == null) {
                Log.e("BLEAdvertise", "Bluetooth LE Advertiser desteklenmiyor")
                updateAdvertisingStatus("BLE yayını desteklenmiyor")
                return
            }

            // Course ID kontrolü
            if (course.id <= 0) {
                Log.e("BLEAdvertise", "Geçersiz ders ID: ${course.id}")
                updateAdvertisingStatus("Geçersiz ders ID")
                Toast.makeText(this, "Geçersiz ders ID. Lütfen tekrar deneyin.", Toast.LENGTH_SHORT).show()
                return
            }

            Log.d("BLEAdvertise", "Yoklama gönderiliyor - Ders: ${course.name} (ID: ${course.id})")

            // Önce bekleyen tüm işlemleri temizle
            pendingAdvertisingRunnable?.let { advertisingHandler.removeCallbacks(it) }
            pendingAdvertisingRunnable = null
            
            // Mevcut yayını temizle ve yeni yayını başlat
            cleanupAllAdvertising()
            startSimpleAdvertising(course, user)
            
            // Yoklama gönderilen dersi kaydet
            saveAttendedCourse(course.id)
        }
    }

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
            // İzinleri kontrol et
            if (!checkBluetoothPermissions()) {
                Log.e("BLEAdvertise_STUDENT", "Bluetooth izinleri eksik")
                requestPermissions(arrayOf(BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION))
                return
            }

            if (!bluetoothAdapter.isEnabled) {
                showBluetoothSettingsDialog()
                return
            }

            // Önceki yayınları temizle
            cleanupAllAdvertising()

            // Bluetooth'un hazır olmasını bekle
            advertisingHandler.postDelayed({
                try {
                    // İzinleri tekrar kontrol et
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(this, BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                            Log.e("BLEAdvertise_STUDENT", "BLUETOOTH_ADVERTISE izni eksik")
                            requestPermissions(arrayOf(BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION))
                            return@postDelayed
                        }
                    }

                    bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
                    if (bluetoothLeAdvertiser == null) {
                        showBluetoothNotSupportedDialog()
                        return@postDelayed
                    }

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

                    // Yayın ayarlarını optimize et
                    val settings = AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                        .setConnectable(false)
                        .setTimeout(30000) // 30 saniye
                        .build()

                    // Yayın verisini optimize et
                    val data = AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .addManufacturerData(MANUFACTURER_ID, dataBytes)
                        .build()

                    // Yayını başlat
                    bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
                    Log.d("BLEAdvertise_STUDENT", "Yoklama yayını başlatıldı")

                } catch (e: Exception) {
                    Log.e("BLEAdvertise_STUDENT", "Yayın başlatma hatası: ${e.message}")
                    handleAdvertisingError(e.message ?: "Bilinmeyen hata", course)
                }
            }, 1000) // 1 saniye bekle

        } catch (e: Exception) {
            Log.e("BLEAdvertise_STUDENT", "Yoklama gönderme hatası: ${e.message}")
            updateAdvertisingStatus("Hata: ${e.message}")
            isAdvertising = false
        }
    }

    private fun cleanupAllAdvertising() {
        try {
            // Önce tüm callback'leri temizle
            advertisingHandler.removeCallbacksAndMessages(null)
            pendingAdvertisingRunnable?.let { advertisingHandler.removeCallbacks(it) }
            
            // Mevcut yayını durdur
            try {
                bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
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
                    // Alternatif olarak genel ayarlar sayfasını aç
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("İptal") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun startActiveCoursesCheck() {
        Log.d("StudentActivity", "Aktif ders kontrolü başlatılıyor")
        
        // Firebase üzerinden ders kontrolü
        lifecycleScope.launch {
            firebaseManager.getActiveCourses().collect { courses ->
                Log.d("StudentActivity", "Firebase'den gelen ders sayısı: ${courses.size}")
                updateCourseList(courses)
            }
        }

        // Bluetooth taraması için Timer
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
        }, 0, 30000) // 30 saniye
    }

    private fun updateCourseList(firebaseCourses: List<Course>) {
        val allCourses = mutableSetOf<Course>()
        
        // Firebase'den gelen dersleri ekle
        allCourses.addAll(firebaseCourses)
        
        // Bluetooth ile bulunan dersleri Firebase ile eşleştir
        activeCourses.forEach { bluetoothCourse ->
            // Aynı isme sahip Firebase dersi var mı kontrol et
            val matchingFirebaseCourse = firebaseCourses.find { it.name == bluetoothCourse.name }
            if (matchingFirebaseCourse != null) {
                // Firebase'deki ID'yi kullan
                allCourses.add(bluetoothCourse.copy(id = matchingFirebaseCourse.id))
            } else {
                // Eşleşen ders bulunamadıysa Bluetooth'tan gelen dersi ekle
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

        // RecyclerView'ı güncelle
        courseAdapter.submitList(allCourses.toList())
    }

    private val courseScanCallback = object : ScanCallback() {
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

            // Manufacturer specific data'yı kontrol et
            val manufacturerData = scanRecord.getManufacturerSpecificData(0x0000)
            if (manufacturerData != null) {
                try {
                    val dataString = String(manufacturerData, Charset.forName("UTF-8"))
                    Log.d("BLEScan", "Alınan veri: $dataString")
                    
                    // "C|courseId|courseName|isActive" formatındaki veriyi parçala
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
                            
                            // Eğer bu ders daha önce eklenmemişse ekle
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
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Tarama zaten başlatılmış"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Uygulama kaydı başarısız"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Dahili hata"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Özellik desteklenmiyor"
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

        // Bluetooth durumu değişikliklerini dinle
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        
        // Başlangıç durumunu kontrol et
        updateBluetoothStatus(bluetoothAdapter.isEnabled)

        // İzinleri kontrol et
        if (checkBluetoothPermissions()) {
            startInitialScan()
        } else {
            requestPermissions(arrayOf(BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

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
            // Tarama başlamadan önce listeyi temizle
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

                // Belirtilen süre sonra taramayı durdur
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

    private fun stopScanning() {
        Log.d("BLEScan", "stopScanning çağrıldı")
        
        if (!isScanning) {
            Log.d("BLEScan", "Tarama zaten durdurulmuş")
            return
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12 ve üzeri için izin kontrolü
                if (ActivityCompat.checkSelfPermission(this, BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothAdapter.bluetoothLeScanner?.stopScan(courseScanCallback)
                }
            } else {
                // Android 11 ve altı için doğrudan durdur
                bluetoothAdapter.bluetoothLeScanner?.stopScan(courseScanCallback)
            }
            
            isScanning = false
            
            // UI güncelle
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
                    // Kullanıcı bilgileri alındıktan sonra ders kontrolünü başlat
                    startActiveCoursesCheck()
                } else {
                    Log.e("StudentActivity", "Kullanıcı bilgileri null")
                    Toast.makeText(this@StudentActivity, "Kullanıcı bilgileri alınamadı", Toast.LENGTH_SHORT).show()
                    // Oturumu kapat ve login ekranına yönlendir
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
            
            // Daha uzun bir bekleme süresi ile tekrar dene
            advertisingHandler.postDelayed({
                currentUser?.let { user ->
                    startSimpleAdvertising(course, user)
                }
            }, 3000) // 3 saniye bekle
            
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

    private fun disableGoogleServices() {
        // Fonksiyon içeriğini boşaltıyoruz
        Log.d("Services", "Google servisleri devre dışı bırakma işlemi kaldırıldı")
    }

    private fun performLogout() {
        try {
            // Firebase oturumunu kapat
            firebaseManager.logout()
            // Session'ı temizle
            sessionManager.clearSession()
            // Login ekranına yönlendir
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
        
        // Gece yarısında listeyi temizle
        val lastResetTime = prefs.getLong("last_reset_time", 0)
        val currentTime = System.currentTimeMillis()
        if (shouldResetAttendance(lastResetTime, currentTime)) {
            resetAttendedCourses(currentTime)
        }
    }

    private fun shouldResetAttendance(lastResetTime: Long, currentTime: Long): Boolean {
        val lastResetDate = java.util.Calendar.getInstance().apply { timeInMillis = lastResetTime }
        val currentDate = java.util.Calendar.getInstance().apply { timeInMillis = currentTime }
        
        // Eğer farklı günlerse veya son sıfırlama zamanı 0 ise true döndür
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
