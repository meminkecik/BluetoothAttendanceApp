package com.example.bluetoothattendanceapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
import com.example.bluetoothattendanceapp.data.User
import android.bluetooth.le.ScanSettings

class TeacherAttendanceActivity : AppCompatActivity() {
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

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val scanRecord = result.scanRecord
                val manufacturerData = scanRecord?.getManufacturerSpecificData(0x0000)
                
                if (manufacturerData != null) {
                    Log.d("Attendance", "Ham veri alındı: ${manufacturerData.contentToString()}")
                    
                    val attendanceData = String(manufacturerData, StandardCharsets.UTF_8)
                    Log.d("Attendance", "Çözümlenen veri: $attendanceData")
                    
                    val parts = attendanceData.split("|")
                    if (parts.size == 2) {
                        val studentNumber = parts[0]
                        val receivedCourseId = parts[1].toInt()
                        
                        Log.d("Attendance", "Öğrenci No: $studentNumber, Ders ID: $receivedCourseId")
                        
                        // Gelen ders ID'sini kontrol et
                        if (receivedCourseId == courseId) {
                            lifecycleScope.launch {
                                try {
                                    firebaseManager.getStudentByNumber(studentNumber)?.let { student: User ->
                                        Log.d("Attendance", "Öğrenci bulundu: ${student.name} ${student.surname}")
                                        
                                        // Öğrencinin daha önce yoklamaya katılıp katılmadığını kontrol et
                                        val hasExistingAttendance = database.attendanceDao()
                                            .hasExistingAttendance(courseId, result.device.address)
                                        
                                        if (!hasExistingAttendance) {
                                            val record = AttendanceRecord(
                                                studentNumber = student.studentNumber,
                                                studentName = student.name,
                                                studentSurname = student.surname,
                                                deviceAddress = result.device.address,
                                                courseId = courseId,
                                                timestamp = Date()
                                            )
                                            
                                            addAttendanceRecord(record)
                                            
                                            runOnUiThread {
                                                binding.txtStatus.text = "${student.name} ${student.surname} yoklamaya katıldı"
                                                Toast.makeText(
                                                    this@TeacherAttendanceActivity,
                                                    "${student.name} ${student.surname} yoklamaya katıldı",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        } else {
                                            Log.d("Attendance", "Öğrenci zaten yoklamaya katılmış")
                                        }
                                    } ?: run {
                                        Log.e("Attendance", "Öğrenci bulunamadı: $studentNumber")
                                    }
                                } catch (e: Exception) {
                                    Log.e("Attendance", "Öğrenci bilgileri alınamadı: ${e.message}")
                                }
                            }
                        } else {
                            Log.d("Attendance", "Farklı ders ID'si: Beklenen=$courseId, Gelen=$receivedCourseId")
                        }
                    } else {
                        Log.e("Attendance", "Geçersiz veri formatı: $attendanceData")
                    }
                }
            } catch (e: Exception) {
                Log.e("Attendance", "Tarama sonucu işlenirken hata: ${e.message}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("Attendance", "Tarama hatası: $errorCode")
            runOnUiThread {
                binding.txtStatus.text = "Tarama hatası: $errorCode"
            }
        }
    }

    companion object {
        const val EXTRA_COURSE_ID = "extra_course_id"
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

        courseId = intent.getIntExtra(EXTRA_COURSE_ID, -1)
        if (courseId == -1) {
            finish()
            return
        }

        // Course bilgisini yükle ve yoklamayı başlat
        lifecycleScope.launch {
            try {
                course = database.courseDao().getCourseById(courseId)
                title = "Yoklama - ${course.name}"
                loadAttendanceRecords()
                startAttendance() // Yoklamayı başlat
                createAttendanceSession()
            } catch (e: Exception) {
                Log.e("TeacherAttendance", "Ders bilgisi yüklenemedi: ${e.message}")
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
            database.attendanceDao().getAttendanceForCourse(courseId).collect { records ->
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
                database.attendanceDao().getAttendanceForCourse(courseId).collect { records ->
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
                database.attendanceDao().getAttendanceForCourse(courseId).collect { records ->
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
        if (isAttendanceActive) {
            stopAttendance()
        }
        super.onDestroy()
        // Aktivite kapanırken taramayı durdur
        if (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    private fun observeAttendanceRecords() {
        lifecycleScope.launch {
            try {
                database.attendanceDao().getAttendanceForCourse(courseId).collect { records ->
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
                database.attendanceDao().getAttendanceForCourse(courseId).collect { records ->
                    deviceAdapter.updateDevices(records.map { record ->
                        DeviceAdapter.BluetoothDevice(
                            name = "${record.studentName} ${record.studentSurname} (${record.studentNumber})",
                            address = record.deviceAddress,
                            timestamp = record.timestamp
                        )
                    })
                }
            } catch (e: Exception) {
                Log.e("Attendance", "Yoklama listesi güncellenemedi: ${e.message}")
            }
        }
    }

    private fun refreshAttendanceList() {
        lifecycleScope.launch {
            try {
                database.attendanceDao().getAttendanceForCourse(courseId).collect { records ->
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
                val teacherId = firebaseManager.getCurrentUser()?.id ?: return@launch
                firebaseManager.createAttendanceSession(
                    teacherId = teacherId,
                    courseName = course.name,
                    courseId = course.id
                ).onSuccess { id ->
                    sessionId = id
                    Log.d("TeacherAttendance", "Yoklama oturumu oluşturuldu: $id")
                }.onFailure { exception ->
                    Log.e("TeacherAttendance", "Yoklama oturumu oluşturulamadı: ${exception.message}")
                    Toast.makeText(
                        this@TeacherAttendanceActivity,
                        "Yoklama oturumu oluşturulamadı: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e("TeacherAttendance", "Yoklama oturumu oluşturma hatası: ${e.message}")
                Toast.makeText(
                    this@TeacherAttendanceActivity,
                    "Yoklama oturumu oluşturma hatası: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    // Öğrenci yoklama kaydı eklendiğinde Firebase'e de kaydet
    private fun addAttendanceRecord(record: AttendanceRecord) {
        lifecycleScope.launch {
            sessionId?.let { id ->
                // Room veritabanına kaydet
                database.attendanceDao().insertAttendance(record)
                
                // Firebase'e kaydet
                val firebaseRecord = FirebaseAttendanceRecord(
                    studentId = record.deviceAddress, // veya başka bir unique ID
                    studentName = record.studentName,
                    studentSurname = record.studentSurname,
                    studentNumber = record.studentNumber ?: "",
                    timestamp = record.timestamp.time
                )
                
                firebaseManager.addAttendanceRecord(id, firebaseRecord)
                    .onFailure { exception ->
                        Toast.makeText(
                            this@TeacherAttendanceActivity,
                            "Yoklama kaydedilemedi: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
        }
    }

    private fun startAttendance() {
        isAttendanceActive = true
        binding.btnEndAttendance.visibility = View.VISIBLE
        
        // Bluetooth taramasını başlat
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bluetoothAdapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
            Log.d("TeacherAttendance", "Yoklama taraması başlatıldı")
        }
    }

    private fun stopAttendance() {
        if (!isAttendanceActive) return

        isAttendanceActive = false
        binding.btnEndAttendance.visibility = View.GONE

        // Bluetooth taramasını durdur
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
            Log.d("TeacherAttendance", "Yoklama taraması durduruldu")
        }

        // Firebase'deki oturumu kapat
        lifecycleScope.launch {
            sessionId?.let { sid ->
                try {
                    firebaseManager.closeAttendanceSession(sid)
                    Log.d("TeacherAttendance", "Firebase oturumu kapatıldı")
                } catch (e: Exception) {
                    Log.e("TeacherAttendance", "Oturum kapatma hatası: ${e.message}")
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