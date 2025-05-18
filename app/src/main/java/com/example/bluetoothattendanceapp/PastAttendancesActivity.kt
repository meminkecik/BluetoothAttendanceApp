package com.example.bluetoothattendanceapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bluetoothattendanceapp.adapter.PastAttendanceAdapter
import com.example.bluetoothattendanceapp.data.Course
import com.example.bluetoothattendanceapp.databinding.ActivityPastAttendancesBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.*

class PastAttendancesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPastAttendancesBinding
    private lateinit var adapter: PastAttendanceAdapter
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var databaseReference: DatabaseReference

    companion object {
        private const val TAG = "PastAttendancesActivity"
        const val EXTRA_COURSE_ID = "extra_course_id"
        const val EXTRA_COURSE_NAME = "extra_course_name"
        const val EXTRA_COURSE_DATE = "extra_course_date"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPastAttendancesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Toolbar'ı ayarla
        binding.toolbar.title = "Geçmiş Yoklamalar"
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { onBackPressed() }

        // Firebase başlatma
        firebaseAuth = FirebaseAuth.getInstance()
        databaseReference = FirebaseDatabase.getInstance().reference
        Log.d(TAG, "Firebase bağlantısı başlatıldı")

        setupRecyclerView()
        loadPastAttendances()
    }

    private fun setupRecyclerView() {
        adapter = PastAttendanceAdapter { item ->
            val intent = Intent(this, AttendanceDetailsActivity::class.java).apply {
                putExtra(EXTRA_COURSE_ID, item.sessionId)
            }
            startActivity(intent)
        }

        binding.rvPastAttendances.apply {
            layoutManager = LinearLayoutManager(this@PastAttendancesActivity)
            adapter = this@PastAttendancesActivity.adapter
        }
    }

    private fun loadPastAttendances() {
        Log.i(TAG, "Geçmiş yoklamaları yükleme işlemi başlatıldı")
        lifecycleScope.launch {
            try {
                val currentUser = firebaseAuth.currentUser
                if (currentUser == null) {
                    Log.w(TAG, "Oturum açmış kullanıcı bulunamadı")
                    Toast.makeText(this@PastAttendancesActivity, "Lütfen tekrar giriş yapın", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                val teacherId = currentUser.uid
                Log.d(TAG, "Öğretmen ID: $teacherId için yoklamalar yükleniyor")

                val coursesSnapshot = withContext(Dispatchers.IO) {
                    Log.d(TAG, "Firebase'den kurs verileri çekiliyor...")
                    databaseReference.child("attendance_sessions")
                        .orderByChild("teacherId")
                        .equalTo(teacherId)
                        .get()
                        .await()
                }

                Log.d(TAG, "Toplam ${coursesSnapshot.childrenCount} kurs bulundu")
                val attendanceItems = mutableListOf<PastAttendanceItem>()

                for (courseSnapshot in coursesSnapshot.children) {
                    val sessionId = courseSnapshot.key ?: continue
                    
                    val course = Course(
                        id = courseSnapshot.child("courseId").getValue(Int::class.java) ?: -1,
                        name = courseSnapshot.child("courseName").getValue(String::class.java) ?: "",
                        teacherId = courseSnapshot.child("teacherId").getValue(String::class.java) ?: "",
                        createdAt = Date(courseSnapshot.child("date").getValue(Long::class.java) ?: 0),
                        isActive = courseSnapshot.child("isActive").getValue(Boolean::class.java) ?: false
                    )
                    
                    Log.d(TAG, "Kurs bilgileri: ID=${course.id}, Ad=${course.name}")
                    
                    val attendeesSnapshot = courseSnapshot.child("attendees")
                    val studentCount = attendeesSnapshot.childrenCount.toInt()

                    Log.d(TAG, "${course.id} ID'li kursta toplam $studentCount öğrenci bulundu")

                    attendanceItems.add(
                        PastAttendanceItem(
                            course = course,
                            studentCount = studentCount,
                            isActive = course.isActive,
                            sessionId = sessionId
                        )
                    )
                }

                val sortedItems = attendanceItems.sortedByDescending { it.course.createdAt }
                Log.i(TAG, "Toplam ${sortedItems.size} yoklama kaydı işlendi")

                withContext(Dispatchers.Main) {
                    if (sortedItems.isEmpty()) {
                        Log.d(TAG, "Gösterilecek yoklama kaydı bulunamadı")
                        binding.tvEmpty.visibility = View.VISIBLE
                        binding.rvPastAttendances.visibility = View.GONE
                    } else {
                        Log.d(TAG, "Yoklama kayıtları başarıyla yüklendi ve gösteriliyor")
                        binding.tvEmpty.visibility = View.GONE
                        binding.rvPastAttendances.visibility = View.VISIBLE
                        adapter.submitList(sortedItems)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Veri yükleme hatası: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PastAttendancesActivity, "Veriler yüklenirken hata oluştu: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.rvPastAttendances.visibility = View.GONE
                }
            }
        }
    }
}

data class PastAttendanceItem(
    val course: Course,
    val studentCount: Int,
    val isActive: Boolean,
    val sessionId: String
) 