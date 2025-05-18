package com.example.bluetoothattendanceapp

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bluetoothattendanceapp.adapter.AttendanceDetailsAdapter
import com.example.bluetoothattendanceapp.databinding.ActivityAttendanceDetailsBinding
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class AttendanceDetailsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAttendanceDetailsBinding
    private lateinit var adapter: AttendanceDetailsAdapter
    private lateinit var sessionId: String
    private val database = FirebaseDatabase.getInstance().reference

    companion object {
        private const val TAG = "AttendanceDetails"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Toolbar'ı ayarla
        binding.toolbar.title = "Yoklama Detayları"
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { onBackPressed() }

        // Intent'ten verileri al
        sessionId = intent.getStringExtra(PastAttendancesActivity.EXTRA_COURSE_ID) ?: run {
            Toast.makeText(this, "Geçersiz oturum", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupRecyclerView()
        loadAttendanceDetails()
    }

    private fun setupRecyclerView() {
        adapter = AttendanceDetailsAdapter()
        binding.rvAttendanceDetails.apply {
            layoutManager = LinearLayoutManager(this@AttendanceDetailsActivity)
            adapter = this@AttendanceDetailsActivity.adapter
        }
    }

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

                // Oturum bilgilerini al
                val courseName = sessionSnapshot.child("courseName").getValue(String::class.java) ?: "Bilinmeyen Ders"
                val date = sessionSnapshot.child("date").getValue(Long::class.java)?.let { 
                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(it))
                } ?: "Tarih bilgisi yok"

                // Başlık bilgilerini güncelle
                binding.tvCourseName.text = courseName
                binding.tvDate.text = date

                // Katılımcıları al
                val attendees = mutableListOf<AttendeeInfo>()
                val attendeesSnapshot = sessionSnapshot.child("attendees")
                
                attendeesSnapshot.children.forEach { attendeeSnapshot ->
                    val studentNumber = attendeeSnapshot.child("studentNumber").getValue(String::class.java)
                    val studentName = attendeeSnapshot.child("studentName").getValue(String::class.java)
                    val studentSurname = attendeeSnapshot.child("studentSurname").getValue(String::class.java)
                    val timestamp = attendeeSnapshot.child("timestamp").getValue(Long::class.java)

                    if (studentNumber != null && studentName != null && studentSurname != null && timestamp != null) {
                        attendees.add(
                            AttendeeInfo(
                                studentNumber = studentNumber,
                                studentName = studentName,
                                studentSurname = studentSurname,
                                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
                            )
                        )
                    }
                }

                // Katılımcı sayısını göster
                binding.tvAttendeeCount.text = "Toplam ${attendees.size} Katılımcı"

                if (attendees.isEmpty()) {
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.rvAttendanceDetails.visibility = View.GONE
                } else {
                    binding.tvEmpty.visibility = View.GONE
                    binding.rvAttendanceDetails.visibility = View.VISIBLE
                    adapter.submitList(attendees.sortedBy { it.studentName })
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