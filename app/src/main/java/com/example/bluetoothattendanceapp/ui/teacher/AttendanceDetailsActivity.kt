package com.example.bluetoothattendanceapp.ui.teacher

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bluetoothattendanceapp.R
import com.example.bluetoothattendanceapp.databinding.ActivityAttendanceDetailsBinding
import com.example.bluetoothattendanceapp.ui.common.AttendanceDetailsAdapter
import com.google.firebase.database.FirebaseDatabase
import com.itextpdf.kernel.pdf.PdfWriter
import com.opencsv.CSVWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AttendanceDetailsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAttendanceDetailsBinding
    private lateinit var adapter: AttendanceDetailsAdapter
    private lateinit var sessionId: String
    private val database = FirebaseDatabase.getInstance().reference
    private var courseName: String = ""
    private var attendanceDate: Long = 0
    private var attendees: List<AttendeeInfo> = emptyList()

    companion object {
        private const val TAG = "AttendanceDetails"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Yoklama Detayları"

        sessionId = intent.getStringExtra(PastAttendancesActivity.EXTRA_COURSE_ID) ?: run {
            Toast.makeText(this, "Geçersiz oturum", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupRecyclerView()
        loadAttendanceDetails()
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
        lifecycleScope.launch {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
                val fileName = "Yoklama_${dateFormat.format(Date(attendanceDate))}.pdf"
                val filePath = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

                val pdfWriter = PdfWriter(filePath)
                val pdf = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
                val document = com.itextpdf.layout.Document(pdf)

                document.add(
                    com.itextpdf.layout.element.Paragraph(
                        "$courseName Dersi - Yoklama Listesi\n${
                            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(attendanceDate))
                        }"
                    )
                )
                document.add(com.itextpdf.layout.element.Paragraph(" "))

                val table = com.itextpdf.layout.element.Table(4).useAllAvailableWidth()

                arrayOf("Sıra", "Ad", "Soyad", "Öğrenci No").forEach { header ->
                    table.addCell(
                        com.itextpdf.layout.element.Cell().add(
                            com.itextpdf.layout.element.Paragraph(header)
                        ).setBold()
                    )
                }

                attendees.forEachIndexed { index, attendee ->
                    table.addCell(
                        com.itextpdf.layout.element.Cell().add(
                            com.itextpdf.layout.element.Paragraph((index + 1).toString())
                        )
                    )
                    table.addCell(
                        com.itextpdf.layout.element.Cell().add(
                            com.itextpdf.layout.element.Paragraph(attendee.studentName)
                        )
                    )
                    table.addCell(
                        com.itextpdf.layout.element.Cell().add(
                            com.itextpdf.layout.element.Paragraph(attendee.studentSurname)
                        )
                    )
                    table.addCell(
                        com.itextpdf.layout.element.Cell().add(
                            com.itextpdf.layout.element.Paragraph(attendee.studentNumber)
                        )
                    )
                }

                document.add(table)
                document.close()

                shareFile(filePath, "application/pdf")
                Toast.makeText(this@AttendanceDetailsActivity, "PDF dosyası oluşturuldu", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e(TAG, "PDF oluşturma hatası: ${e.message}", e)
                Toast.makeText(this@AttendanceDetailsActivity, "PDF oluşturma hatası: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportToCSV() {
        lifecycleScope.launch {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
                val fileName = "Yoklama_${dateFormat.format(Date(attendanceDate))}.csv"
                val filePath = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

                FileWriter(filePath).use { writer ->
                    val csvWriter = CSVWriter(writer)

                    csvWriter.writeNext(arrayOf("Sıra", "Ad", "Soyad", "Öğrenci No", "Katılım Saati"))

                    attendees.forEachIndexed { index, attendee ->
                        csvWriter.writeNext(arrayOf(
                            (index + 1).toString(),
                            attendee.studentName,
                            attendee.studentSurname,
                            attendee.studentNumber,
                            attendee.timestamp
                        ))
                    }

                    csvWriter.writeNext(arrayOf("Ders", courseName))
                    csvWriter.writeNext(arrayOf("Tarih", SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(attendanceDate))))
                    csvWriter.writeNext(arrayOf())
                }

                shareFile(filePath, "text/csv")
                Toast.makeText(this@AttendanceDetailsActivity, "CSV dosyası oluşturuldu", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e(TAG, "CSV oluşturma hatası: ${e.message}", e)
                Toast.makeText(this@AttendanceDetailsActivity, "CSV oluşturma hatası: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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

    private fun setupRecyclerView() {
        adapter = AttendanceDetailsAdapter()
        binding.rvAttendanceDetails.apply {
            layoutManager = LinearLayoutManager(this@AttendanceDetailsActivity)
            adapter = this@AttendanceDetailsActivity.adapter
        }
    }

    @SuppressLint("SetTextI18n")
    private fun loadAttendanceDetails() {
        lifecycleScope.launch {
            try {
                val sessionSnapshot = withContext(Dispatchers.IO) {
                    database.child("attendance_sessions")
                        .child(sessionId)
                        .get()
                        .await()
                }

                if (!sessionSnapshot.exists()) {
                    Log.e(TAG, "Oturum bulunamadı: $sessionId")
                    Toast.makeText(this@AttendanceDetailsActivity, "Yoklama oturumu bulunamadı", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                courseName = sessionSnapshot.child("courseName").getValue(String::class.java) ?: "Bilinmeyen Ders"
                attendanceDate = sessionSnapshot.child("date").getValue(Long::class.java) ?: System.currentTimeMillis()
                val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(attendanceDate))

                binding.tvCourseName.text = courseName
                binding.tvDate.text = date

                val attendeesList = mutableListOf<AttendeeInfo>()
                val attendeesSnapshot = sessionSnapshot.child("attendees")
                
                attendeesSnapshot.children.forEach { attendeeSnapshot ->
                    val studentNumber = attendeeSnapshot.child("studentNumber").getValue(String::class.java)
                    val studentName = attendeeSnapshot.child("studentName").getValue(String::class.java)
                    val studentSurname = attendeeSnapshot.child("studentSurname").getValue(String::class.java)
                    val timestamp = attendeeSnapshot.child("timestamp").getValue(Long::class.java)

                    if (studentNumber != null && studentName != null && studentSurname != null && timestamp != null) {
                        attendeesList.add(
                            AttendeeInfo(
                                studentNumber = studentNumber,
                                studentName = studentName,
                                studentSurname = studentSurname,
                                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
                            )
                        )
                    }
                }

                attendees = attendeesList.sortedBy { it.studentName }
                binding.tvAttendeeCount.text = "Toplam ${attendees.size} Katılımcı"

                if (attendees.isEmpty()) {
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.rvAttendanceDetails.visibility = View.GONE
                } else {
                    binding.tvEmpty.visibility = View.GONE
                    binding.rvAttendanceDetails.visibility = View.VISIBLE
                    adapter.submitList(attendees)
                }

                Log.d(TAG, "Yoklama detayları başarıyla yüklendi: $courseName, ${attendees.size} katılımcı")

            } catch (e: Exception) {
                Log.e(TAG, "Yoklama detayları yüklenirken hata: ${e.message}")
                Toast.makeText(this@AttendanceDetailsActivity, "Yoklama detayları yüklenemedi", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

data class AttendeeInfo(
    val studentNumber: String,
    val studentName: String,
    val studentSurname: String,
    val timestamp: String
) 