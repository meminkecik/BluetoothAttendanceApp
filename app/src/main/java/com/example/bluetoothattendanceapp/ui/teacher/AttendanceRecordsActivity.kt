package com.example.bluetoothattendanceapp.ui.teacher

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bluetoothattendanceapp.R
import com.example.bluetoothattendanceapp.data.local.AttendanceDatabase
import com.example.bluetoothattendanceapp.data.model.AttendanceRecord
import com.example.bluetoothattendanceapp.databinding.ActivityAttendanceRecordsBinding
import com.example.bluetoothattendanceapp.ui.common.AttendanceRecordsAdapter
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AttendanceRecordsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAttendanceRecordsBinding
    private lateinit var recordsAdapter: AttendanceRecordsAdapter
    private lateinit var database: AttendanceDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceRecordsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.apply {
            title = getString(R.string.attendance_records)
            setNavigationOnClickListener { onBackPressed() }
            setNavigationIcon(R.drawable.ic_back)
        }

        setupRecyclerView()
        setupDatabase()
        loadAttendanceRecords()
    }

    private fun setupRecyclerView() {
        recordsAdapter = AttendanceRecordsAdapter()
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@AttendanceRecordsActivity)
            adapter = recordsAdapter
        }
    }

    private fun setupDatabase() {
        database = AttendanceDatabase.getDatabase(this)
    }

    private fun loadAttendanceRecords() {
        lifecycleScope.launch {
            try {
                Log.d("AttendanceRecords", "Yoklama kayıtları yükleniyor...")
                
                val today = Date()

                database.attendanceDao().getAttendanceByDate(today)
                    .catch { e ->
                        Log.e("AttendanceRecords", "Kayıtlar yüklenirken hata oluştu", e)
                    }
                    .collect { records: List<AttendanceRecord> ->
                        Log.d("AttendanceRecords", "Toplam ${records.size} kayıt bulundu")
                        
                        val groupedRecords = records.groupBy { it.courseId }
                        
                        groupedRecords.forEach { (courseId, courseRecords) ->
                            Log.d("AttendanceRecords", """
                                Ders ID: $courseId
                                Katılımcı Sayısı: ${courseRecords.size}
                                Katılımcılar:
                            """.trimIndent())
                            
                            courseRecords.forEach { record ->
                                Log.d("AttendanceRecords", """
                                    - ${record.studentName} ${record.studentSurname}
                                    - Numara: ${record.studentNumber}
                                    - Saat: ${SimpleDateFormat("HH:mm:ss", Locale("tr")).format(record.timestamp)}
                                """.trimIndent())
                            }
                        }
                        
                        recordsAdapter.submitList(records)
                    }
            } catch (e: Exception) {
                Log.e("AttendanceRecords", "Veritabanı hatası", e)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
} 