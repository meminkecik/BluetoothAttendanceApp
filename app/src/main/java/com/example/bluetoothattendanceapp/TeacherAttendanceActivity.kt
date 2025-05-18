package com.example.bluetoothattendanceapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.bluetoothattendanceapp.data.AttendanceDatabase
import com.example.bluetoothattendanceapp.data.AttendanceRecord
import com.example.bluetoothattendanceapp.data.Course
import com.example.bluetoothattendanceapp.data.FirebaseAttendanceRecord
import com.example.bluetoothattendanceapp.databinding.ActivityTeacherAttendanceBinding
import com.example.bluetoothattendanceapp.utils.FirebaseManager
import com.itextpdf.kernel.pdf.PdfWriter
import com.opencsv.CSVWriter
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import java.nio.charset.StandardCharsets
import android.bluetooth.le.ScanSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

class TeacherAttendanceActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "TeacherAttendance"
        private const val DUPLICATE_PREVENTION_INTERVAL = 30000L // 30 saniye
        private const val MAX_DAILY_PROCESS_COUNT = 3 // Günlük maksimum işlem sayısı
        const val EXTRA_COURSE_ID = "extra_course_id"
    }

    private lateinit var binding: ActivityTeacherAttendanceBinding
    private lateinit var database: AttendanceDatabase
    private lateinit var tableLayout: TableLayout
    private lateinit var course: Course
    private lateinit var deviceAdapter: DeviceAdapter
    private var courseId: Int = -1
    private lateinit var firebaseManager: FirebaseManager
    private var sessionId: String? = null
    private var isAttendanceActive = false
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val databaseReference = FirebaseDatabase.getInstance().reference
    
    private val mutex = Mutex()
    private val processedStudents = mutableMapOf<String, ProcessingStatus>()
    private val processedSignals = mutableMapOf<String, Long>() // studentNumber -> timestamp
    private val SIGNAL_PROCESS_INTERVAL = 10000L // 10 saniye

    private data class ProcessingStatus(
        val timestamp: Long,
        var isProcessing: Boolean = false,
        var hasAttendedToday: Boolean = false,
        var lastProcessedTimestamp: Long = 0,
        var processCount: Int = 0,
        var deviceAddress: String? = null
    )

    private suspend fun processAttendance(
        studentNumber: String,
        deviceAddress: String,
        receivedCourseId: Int
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Yoklama işlemi başlatıldı - Öğrenci No: $studentNumber, Cihaz: $deviceAddress")
        
        // Önce öğrencinin kaydının olup olmadığını kontrol et
        val existingRecord = database.attendanceDao().getStudentAttendanceToday(courseId, studentNumber, Date())
        if (existingRecord != null) {
            Log.d(TAG, "Öğrenci bugün zaten kayıtlı: $studentNumber")
            return@withContext false
        }

        mutex.withLock {
            val status = processedStudents[studentNumber]
            val currentTime = System.currentTimeMillis()
            
            // Eğer öğrenci zaten işlendiyse veya bugün yoklamaya katıldıysa işlemi atla
            if (status?.hasAttendedToday == true) {
                Log.d(TAG, "Öğrenci $studentNumber bugün zaten yoklamaya katıldı")
                return@withLock false
            }
            
            if (status?.isProcessing == true) {
                Log.d(TAG, "Öğrenci $studentNumber için işlem devam ediyor")
                return@withLock false
            }
            
            // Aynı cihazdan gelen sinyalleri kontrol et
            if (status?.deviceAddress != null && status.deviceAddress != deviceAddress) {
                Log.w(TAG, "Öğrenci $studentNumber farklı bir cihazdan sinyal gönderiyor. Eski: ${status.deviceAddress}, Yeni: $deviceAddress")
                return@withLock false
            }
            
            // Son işlemden sonra geçen süreyi kontrol et
            if (status != null && currentTime - status.lastProcessedTimestamp < DUPLICATE_PREVENTION_INTERVAL) {
                Log.d(TAG, "Öğrenci $studentNumber son ${DUPLICATE_PREVENTION_INTERVAL/1000} saniye içinde işlendi")
                return@withLock false
            }
            
            // Günlük işlem sayısını kontrol et
            if (status != null && status.processCount >= MAX_DAILY_PROCESS_COUNT) {
                Log.w(TAG, "Öğrenci $studentNumber için günlük maksimum işlem sayısına (${MAX_DAILY_PROCESS_COUNT}) ulaşıldı")
                return@withLock false
            }
            
            try {
                Log.d(TAG, "Öğrenci $studentNumber için yoklama kaydı oluşturuluyor...")
                
                // İşlem durumunu güncelle
                processedStudents[studentNumber] = ProcessingStatus(
                    timestamp = currentTime,
                    isProcessing = true,
                    deviceAddress = deviceAddress,
                    lastProcessedTimestamp = if (status != null) status.lastProcessedTimestamp else 0,
                    processCount = if (status != null) status.processCount + 1 else 1
                )
                
                // Öğrencinin bugün yoklamaya katılıp katılmadığını kontrol et
                val hasAttendedToday = database.attendanceDao()
                    .hasStudentAttendedToday(courseId, studentNumber, Date())
                
                if (hasAttendedToday) {
                    Log.d(TAG, "Öğrenci $studentNumber bugün zaten yoklamaya katılmış")
                    processedStudents[studentNumber] = ProcessingStatus(
                        timestamp = currentTime,
                        isProcessing = false,
                        hasAttendedToday = true,
                        deviceAddress = deviceAddress,
                        lastProcessedTimestamp = currentTime,
                        processCount = if (status != null) status.processCount else 1
                    )
                    return@withLock false
                }
                
                Log.d(TAG, "Firebase'den öğrenci bilgileri alınıyor: $studentNumber")
                val student = firebaseManager.getStudentByNumber(studentNumber)
                
                if (student?.studentNumber == null) {
                    Log.e(TAG, "Öğrenci numarası bulunamadı: $studentNumber")
                    processedStudents.remove(studentNumber)
                    return@withLock false
                }
                
                Log.d(TAG, "Öğrenci bilgileri alındı: ${student.name} ${student.surname}")
                
                val record = AttendanceRecord(
                    studentNumber = student.studentNumber,
                    studentName = student.name,
                    studentSurname = student.surname,
                    deviceAddress = deviceAddress,
                    courseId = courseId,
                    timestamp = Date()
                )
                
                // Önce veritabanına kaydet
                Log.d(TAG, "Yoklama kaydı yerel veritabanına kaydediliyor...")
                database.attendanceDao().insertAttendance(record)
                Log.d(TAG, "Yoklama kaydı yerel veritabanına kaydedildi")
                
                // Firebase'e kaydet
                sessionId?.let { sid ->
                    Log.d(TAG, """
                        Firebase'e gönderilen yoklama verisi:
                        Session ID: $sid
                        Öğrenci No: ${record.studentNumber}
                        Ad: ${record.studentName}
                        Soyad: ${record.studentSurname}
                        Cihaz: ${record.deviceAddress}
                        Timestamp: ${record.timestamp}
                    """.trimIndent())

                    val firebaseRecord = FirebaseAttendanceRecord(
                        studentId = record.deviceAddress,
                        studentName = record.studentName,
                        studentSurname = record.studentSurname,
                        studentNumber = record.studentNumber ?: "",
                        timestamp = record.timestamp.time
                    )

                    val attendeeData = hashMapOf<String, Any>(
                        "studentNumber" to firebaseRecord.studentNumber,
                        "studentName" to firebaseRecord.studentName,
                        "studentSurname" to firebaseRecord.studentSurname,
                        "timestamp" to firebaseRecord.timestamp
                    )

                    Log.d(TAG, "Firebase'e gönderilen veri yapısı: $attendeeData")
                    
                    databaseReference
                        .child("attendance_sessions")
                        .child(sid)
                        .child("attendees")
                        .child(studentNumber)
                        .setValue(attendeeData)
                        .await()

                    Log.d(TAG, "Yoklama kaydı Firebase'e başarıyla gönderildi")
                }
                
                // İşlem durumunu güncelle
                processedStudents[studentNumber] = ProcessingStatus(
                    timestamp = currentTime,
                    isProcessing = false,
                    hasAttendedToday = true,
                    deviceAddress = deviceAddress,
                    lastProcessedTimestamp = currentTime,
                    processCount = if (status != null) status.processCount + 1 else 1
                )
                
                Log.i(TAG, "Yoklama işlemi başarıyla tamamlandı - Öğrenci: ${student.name} ${student.surname}")
                
                withContext(Dispatchers.Main) {
                    binding.txtStatus.text = "${student.name} ${student.surname} yoklamaya katıldı"
                    Toast.makeText(
                        this@TeacherAttendanceActivity,
                        "${student.name} ${student.surname} yoklamaya katıldı",
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // Listeyi güncelle
                    updateAttendanceList()
                }
                
                true
            } catch (e: Exception) {
                Log.e(TAG, "Yoklama işlemi sırasında hata: ${e.message}", e)
                processedStudents[studentNumber]?.isProcessing = false
                false
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isAttendanceActive) {
                Log.d(TAG, "Yoklama durduruldu, yeni kayıtlar alınmıyor")
                return
            }

            try {
                val scanRecord = result.scanRecord
                val manufacturerData = scanRecord?.getManufacturerSpecificData(0x0000)
                
                if (manufacturerData != null) {
                    val attendanceData = String(manufacturerData, StandardCharsets.UTF_8)
                    Log.d(TAG, "Ham veri: $attendanceData")
                    
                    // Veri formatı kontrolü
                    if (!attendanceData.startsWith("STUDENT|")) {
                        Log.d(TAG, "Geçersiz veri formatı: STUDENT| ile başlamıyor")
                        return
                    }
                    
                    val parts = attendanceData.split("|")
                    if (parts.size < 3) {
                        Log.d(TAG, "Geçersiz veri formatı: Eksik bilgi")
                        return
                    }

                    val studentNumber = parts[1].trim()
                    val receivedCourseId = try {
                        parts[2].toInt()
                    } catch (e: NumberFormatException) {
                        Log.e(TAG, "Geçersiz ders ID formatı: ${parts[2]}")
                        return
                    }

                    // MAC adresi kontrolü
                    val deviceAddress = result.device?.address
                    if (deviceAddress == null) {
                        Log.e(TAG, "Cihaz adresi alınamadı")
                        return
                    }

                    // RSSI kontrolü
                    if (result.rssi < -80) {
                        Log.d(TAG, "Zayıf sinyal gücü: ${result.rssi} dBm")
                        return
                    }

                    // Sinyal işleme kontrolü
                    val currentTime = System.currentTimeMillis()
                    val lastProcessTime = processedSignals[studentNumber] ?: 0L

                    if (currentTime - lastProcessTime < SIGNAL_PROCESS_INTERVAL) {
                        Log.d(TAG, "Son işlemden bu yana yeterli süre geçmedi: $studentNumber")
                        return
                    }

                    // Ders ID kontrolü
                    if (receivedCourseId != courseId) {
                        Log.d(TAG, "Ders ID'leri eşleşmiyor: Beklenen=$courseId, Gelen=$receivedCourseId")
                        return
                    }

                    // Öğrenci numarası formatı kontrolü
                    if (studentNumber.length < 5) {
                        Log.e(TAG, "Geçersiz öğrenci numarası formatı: $studentNumber")
                        return
                    }

                    // Sinyal işleme zamanını güncelle
                    processedSignals[studentNumber] = currentTime

                    Log.d(TAG, """
                        Sinyal işleniyor:
                        - Öğrenci No: $studentNumber
                        - Ders ID: $receivedCourseId
                        - MAC Adresi: $deviceAddress
                        - RSSI: ${result.rssi} dBm
                    """.trimIndent())

                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            // Önce yerel veritabanında kontrol et
                            val hasAttendedToday = database.attendanceDao()
                                .hasStudentAttendedToday(courseId, studentNumber, Date())

                            if (hasAttendedToday) {
                                Log.d(TAG, "Öğrenci bugün zaten yoklamaya katılmış: $studentNumber")
                                return@launch
                            }

                            // Yoklamayı işle
                            val success = processAttendance(studentNumber, deviceAddress, receivedCourseId)
                            if (success) {
                                Log.d(TAG, "Yoklama başarıyla kaydedildi: $studentNumber")
                            } else {
                                Log.e(TAG, "Yoklama kaydedilemedi: $studentNumber")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Yoklama işleme hatası: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tarama sonucu işlenirken hata: ${e.message}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Tarama hatası: $errorCode")
            runOnUiThread {
                binding.txtStatus.text = "Tarama hatası: $errorCode"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeacherAttendanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        database = AttendanceDatabase.getDatabase(this)
        tableLayout = binding.tableLayout
        deviceAdapter = DeviceAdapter()
        firebaseManager = FirebaseManager()
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        // Başlık satırını ekle
        addHeaderRow()

        // Course ID'yi al ve course bilgilerini yükle
        courseId = intent.getIntExtra(EXTRA_COURSE_ID, -1)
        if (courseId == -1) {
            Log.e(TAG, "Geçersiz course ID")
            Toast.makeText(this, "Geçersiz ders bilgisi", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Course bilgisini yükle ve yoklamayı başlat
        lifecycleScope.launch {
            try {
                course = database.courseDao().getCourseById(courseId)
                Log.d(TAG, "Course bilgileri yüklendi - Ad: ${course.name}, ID: ${course.id}")
                
                if (course.name.isBlank()) {
                    Log.e(TAG, "Ders adı boş olamaz")
                    Toast.makeText(this@TeacherAttendanceActivity, "Ders adı boş olamaz", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                title = "Yoklama - ${course.name}"
                loadAttendanceRecords()
                startAttendance() // Yoklamayı başlat
                createAttendanceSession()
            } catch (e: Exception) {
                Log.e(TAG, "Ders bilgisi yüklenemedi: ${e.message}")
                Toast.makeText(this@TeacherAttendanceActivity, "Ders bilgisi yüklenemedi", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun setupUI() {
        binding.apply {
            btnEndAttendance.setOnClickListener {
                showEndAttendanceDialog()
            }
            
            // Başlangıçta buton gizli
            btnEndAttendance.visibility = View.GONE
        }
    }

    private fun addHeaderRow() {
        val headerRow = TableRow(this).apply {
            setBackgroundColor(getColor(R.color.header_background))
            layoutParams = TableRow.LayoutParams(
                TableRow.LayoutParams.MATCH_PARENT,
                TableRow.LayoutParams.WRAP_CONTENT
            )
        }

        // Başlık sütunlarını ekle
        val headers = arrayOf("Sıra", "Ad", "Soyad", "Öğrenci No")
        headers.forEach { header ->
            TextView(this).apply {
                text = header
                setTextColor(getColor(R.color.header_text))
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(16, 16, 16, 16)
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            }.also { headerRow.addView(it) }
        }

        tableLayout.addView(headerRow)
    }

    private fun loadAttendanceRecords() {
        lifecycleScope.launch {
            database.attendanceDao().getAttendanceByCourseId(courseId).collect { records: List<AttendanceRecord> ->
                tableLayout.removeViews(1, tableLayout.childCount - 1) // Başlık hariç temizle
                records.forEachIndexed { index, record ->
                    addDataRow(index + 1, record)
                }
            }
        }
    }

    private fun addDataRow(rowNum: Int, record: AttendanceRecord) {
        val tableRow = TableRow(this).apply {
            layoutParams = TableRow.LayoutParams(
                TableRow.LayoutParams.MATCH_PARENT,
                TableRow.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(if (rowNum % 2 == 0) 
                getColor(R.color.row_even) 
            else 
                getColor(R.color.row_odd)
            )
        }

        // Sütun verilerini ekle
        val columns = arrayOf(
            rowNum.toString(),
            record.studentName,
            record.studentSurname,
            record.studentNumber ?: "-"
        )

        columns.forEach { text ->
            TextView(this).apply {
                this.text = text
                setTextColor(getColor(R.color.text_color))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(16, 12, 16, 12)
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            }.also { tableRow.addView(it) }
        }

        tableLayout.addView(tableRow)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.attendance_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_pdf -> {
                exportToPdf()
                true
            }
            R.id.action_export_csv -> {
                exportToCSV()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportToPdf() {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
            val fileName = "Yoklama_${dateFormat.format(Date())}.pdf"
            val filePath = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

            // iText PDF kütüphanesini kullan
            val pdfWriter = PdfWriter(filePath)
            val pdf = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
            val document = com.itextpdf.layout.Document(pdf)

            // Başlık ekle
            document.add(
                com.itextpdf.layout.element.Paragraph(
                    "${course.name} Dersi - Yoklama Listesi\n${
                        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                    }"
                )
            )
            document.add(com.itextpdf.layout.element.Paragraph(" ")) // Boşluk

            // Tablo oluştur
            val table = com.itextpdf.layout.element.Table(4).useAllAvailableWidth()

            // Başlık satırı
            arrayOf("Sıra", "Ad", "Soyad", "Öğrenci No").forEach { header ->
                table.addCell(
                    com.itextpdf.layout.element.Cell().add(
                        com.itextpdf.layout.element.Paragraph(header)
                    ).setBold()
                )
            }

            // Verileri ekle
            lifecycleScope.launch {
                database.attendanceDao().getAttendanceByCourseId(courseId).collect { records: List<AttendanceRecord> ->
                    records.forEachIndexed { index, record ->
                        table.addCell(
                            com.itextpdf.layout.element.Cell().add(
                                com.itextpdf.layout.element.Paragraph((index + 1).toString())
                            )
                        )
                        table.addCell(
                            com.itextpdf.layout.element.Cell().add(
                                com.itextpdf.layout.element.Paragraph(record.studentName)
                            )
                        )
                        table.addCell(
                            com.itextpdf.layout.element.Cell().add(
                                com.itextpdf.layout.element.Paragraph(record.studentSurname)
                            )
                        )
                        table.addCell(
                            com.itextpdf.layout.element.Cell().add(
                                com.itextpdf.layout.element.Paragraph(record.studentNumber ?: "-")
                            )
                        )
                    }

                    document.add(table)
                    document.close()

                    // Dosyayı paylaş
                    shareFile(filePath, "application/pdf")
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "PDF oluşturma hatası: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportToCSV() {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
            val fileName = "Yoklama_${dateFormat.format(Date())}.csv"
            val filePath = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

            lifecycleScope.launch {
                database.attendanceDao().getAttendanceByCourseId(courseId).collect { records: List<AttendanceRecord> ->
                    FileWriter(filePath).use { writer ->
                        val csvWriter = CSVWriter(writer)

                        // Başlık satırı
                        csvWriter.writeNext(arrayOf("Sıra", "Ad", "Soyad", "Öğrenci No"))

                        // Verileri yaz
                        records.forEachIndexed { index, record ->
                            csvWriter.writeNext(arrayOf(
                                (index + 1).toString(),
                                record.studentName,
                                record.studentSurname,
                                record.studentNumber ?: "-"
                            ))
                        }

                        csvWriter.writeNext(arrayOf("Ders", course.name))
                        csvWriter.writeNext(arrayOf("Tarih", SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())))
                        csvWriter.writeNext(arrayOf()) // Boş satır
                    }

                    // Dosyayı paylaş
                    shareFile(filePath, "text/csv")
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "CSV oluşturma hatası: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareFile(file: File, mimeType: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${packageName}.provider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        startActivity(Intent.createChooser(intent, "Dosyayı Paylaş"))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Activity sonlandırılıyor")
        stopAttendance()
        processedSignals.clear()
        // Aktivite kapanırken taramayı durdur
        if (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
            Log.d(TAG, "Bluetooth taraması durduruldu")
        }
    }

    private fun observeAttendanceRecords() {
        lifecycleScope.launch {
            try {
                database.attendanceDao().getAttendanceByCourseId(courseId).collect { records: List<AttendanceRecord> ->
                    Log.d("Attendance", "Ders yoklamaları alındı: ${records.size} kayıt")
                    deviceAdapter.updateDevices(records.map { record ->
                        DeviceAdapter.BluetoothDevice(
                            name = "${record.studentName} ${record.studentSurname} (${record.studentNumber})",
                            address = record.deviceAddress,
                            timestamp = record.timestamp
                        )
                    })
                }
            } catch (e: Exception) {
                Log.e("Attendance", "Yoklama kayıtları alınamadı: ${e.message}")
            }
        }
    }

    private fun updateAttendanceList() {
        lifecycleScope.launch {
            try {
                database.attendanceDao().getAttendanceByCourseId(courseId).collect { allRecords ->
                    // Öğrenci numarasına göre grupla ve her öğrenciden sadece bir kayıt al
                    val uniqueRecords = allRecords
                        .filter { it.studentNumber != null }
                        .groupBy { it.studentNumber!! }
                        .mapValues { (_, records) -> records.first() }
                        .values
                        .toList()
                        .sortedBy { it.studentName }
                    
                    // Tabloyu temizle (başlık hariç)
                    tableLayout.removeViews(1, tableLayout.childCount - 1)
                    
                    // Sadece unique kayıtları ekle
                    uniqueRecords.forEachIndexed { index, record ->
                        addDataRow(index + 1, record)
                    }
                    
                    // Toplam katılımı göster
                    binding.txtStatus.text = "Toplam Katılım: ${uniqueRecords.size} öğrenci"
                }
            } catch (e: Exception) {
                Log.e("Attendance", "Yoklama listesi güncellenemedi: ${e.message}")
                binding.txtStatus.text = "Liste güncellenirken hata oluştu"
            }
        }
    }

    private fun refreshAttendanceList() {
        lifecycleScope.launch {
            try {
                database.attendanceDao().getAttendanceByCourseId(courseId).collect { records: List<AttendanceRecord> ->
                    deviceAdapter.updateDevices(records.map { record ->
                        DeviceAdapter.BluetoothDevice(
                            name = "${record.studentName} ${record.studentSurname} (${record.studentNumber})",
                            address = record.deviceAddress,
                            timestamp = record.timestamp
                        )
                    })
                }
            } catch (e: Exception) {
                Log.e("Attendance", "Yoklama listesi yenilenemedi: ${e.message}")
            }
        }
    }

    private fun createAttendanceSession() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Yoklama oturumu oluşturuluyor - Ders: ${course.name}, ID: ${course.id}")
                
                val teacherId = firebaseManager.getCurrentUser()?.id ?: run {
                    Log.e(TAG, "Öğretmen ID'si alınamadı")
                    Toast.makeText(this@TeacherAttendanceActivity, "Öğretmen bilgisi alınamadı", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                // Course bilgilerini kontrol et
                if (course.id <= 0 || course.name.isBlank()) {
                    Log.e(TAG, "Geçersiz ders bilgileri - ID: ${course.id}, Ad: ${course.name}")
                    Toast.makeText(this@TeacherAttendanceActivity, "Geçersiz ders bilgileri", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                val sessionData = hashMapOf(
                    "teacherId" to teacherId,
                    "courseName" to course.name.trim(),
                    "courseId" to course.id,
                    "date" to Date().time,
                    "isActive" to true,
                    "attendees" to HashMap<String, Any>()
                )

                Log.d(TAG, "Oturum verisi hazırlandı: $sessionData")

                val sessionRef = databaseReference.child("attendance_sessions").push()
                sessionRef.setValue(sessionData).await()
                sessionId = sessionRef.key

                Log.d(TAG, "Yoklama oturumu başarıyla oluşturuldu: $sessionId")
                Log.d(TAG, "Ders Bilgileri - Ad: ${course.name}, ID: ${course.id}")

            } catch (e: Exception) {
                Log.e(TAG, "Yoklama oturumu oluşturma hatası: ${e.message}", e)
                Toast.makeText(
                    this@TeacherAttendanceActivity,
                    "Yoklama oturumu oluşturma hatası: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun startAttendance() {
        Log.d(TAG, "Yoklama başlatılıyor")
        isAttendanceActive = true
        binding.btnEndAttendance.visibility = View.VISIBLE
        
        // Bluetooth taramasını başlat
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bluetoothAdapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
            Log.d(TAG, "Bluetooth taraması başlatıldı")
        } else {
            Log.e(TAG, "Bluetooth tarama izni yok")
        }
    }

    private fun stopAttendance() {
        if (!isAttendanceActive) {
            Log.d(TAG, "Yoklama zaten durdurulmuş durumda")
            return
        }

        Log.d(TAG, "Yoklama durduruluyor")
        isAttendanceActive = false
        binding.btnEndAttendance.visibility = View.GONE

        // Bluetooth taramasını durdur
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
            Log.d(TAG, "Bluetooth taraması durduruldu")
        }

        // İşlem durumlarını temizle
        processedStudents.clear()
        processedSignals.clear()
        Log.d(TAG, "İşlem durumları temizlendi")

        // Firebase'deki oturumu kapat
        lifecycleScope.launch {
            sessionId?.let { sid ->
                try {
                    Log.d(TAG, "Firebase oturumu kapatılıyor: $sid")
                    firebaseManager.closeAttendanceSession(sid)
                    Log.d(TAG, "Firebase oturumu başarıyla kapatıldı")
                    
                    // Son durumu göster
                    updateAttendanceList()
                } catch (e: Exception) {
                    Log.e(TAG, "Oturum kapatma hatası: ${e.message}")
                }
            }
        }
    }

    private fun showEndAttendanceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Yoklamayı Bitir")
            .setMessage("Yoklamayı bitirmek istediğinizden emin misiniz?")
            .setPositiveButton("Evet") { _, _ ->
                stopAttendance()
                finish()
            }
            .setNegativeButton("Hayır", null)
            .show()
    }

    override fun onPause() {
        // Aktivite arka plana alındığında yoklamayı durdurmuyoruz
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Aktivite tekrar ön plana geldiğinde, eğer yoklama hala aktifse devam et
        if (isAttendanceActive) {
            startAttendance()
        }
    }
} 