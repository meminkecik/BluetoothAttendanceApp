package com.example.bluetoothattendanceapp

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.bluetoothattendanceapp.data.AttendanceDatabase
import com.example.bluetoothattendanceapp.data.AttendanceRecord
import com.example.bluetoothattendanceapp.data.Course
import com.itextpdf.kernel.pdf.PdfWriter
import com.opencsv.CSVWriter
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class TeacherAttendanceActivity : AppCompatActivity() {
    private lateinit var database: AttendanceDatabase
    private lateinit var tableLayout: TableLayout
    private lateinit var course: Course
    private lateinit var deviceAdapter: DeviceAdapter
    private var courseId: Int = -1

    companion object {
        const val EXTRA_COURSE_ID = "extra_course_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_attendance)

        database = AttendanceDatabase.getDatabase(this)
        tableLayout = findViewById(R.id.tableLayout)
        deviceAdapter = DeviceAdapter()

        // Başlık satırını ekle
        addHeaderRow()

        courseId = intent.getIntExtra(EXTRA_COURSE_ID, -1)
        if (courseId == -1) {
            finish()
            return
        }

        lifecycleScope.launch {
            course = database.courseDao().getCourseById(courseId)
            title = "Yoklama - ${course.name}"
            loadAttendanceRecords()
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
        super.onDestroy()
        lifecycleScope.launch {
            try {
                database.courseDao().updateCourseStatus(course.id, false)
                Log.d("TeacherAttendance", "Ders kapatıldı: ${course.name} (ID: ${course.id})")
                Toast.makeText(this@TeacherAttendanceActivity, "Yoklama sonlandırıldı", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("TeacherAttendance", "Ders kapatma hatası: ${e.message}")
            }
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
} 