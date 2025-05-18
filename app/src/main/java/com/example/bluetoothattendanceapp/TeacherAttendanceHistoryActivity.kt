package com.example.bluetoothattendanceapp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bluetoothattendanceapp.adapters.AttendanceSessionAdapter
import com.example.bluetoothattendanceapp.databinding.ActivityTeacherAttendanceHistoryBinding
import com.example.bluetoothattendanceapp.utils.FirebaseManager
import kotlinx.coroutines.launch

class TeacherAttendanceHistoryActivity : AppCompatActivity() {
    private lateinit var firebaseManager: FirebaseManager
    private lateinit var binding: ActivityTeacherAttendanceHistoryBinding
    private val sessionAdapter = AttendanceSessionAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeacherAttendanceHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        title = "Yoklama Geçmişi"
        firebaseManager = FirebaseManager()
        setupRecyclerView()
        loadAttendanceSessions()
    }

    private fun setupRecyclerView() {
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@TeacherAttendanceHistoryActivity)
            adapter = sessionAdapter
        }
    }

    private fun loadAttendanceSessions() {
        lifecycleScope.launch {
            val teacherId = firebaseManager.getCurrentUser()?.id ?: return@launch
            firebaseManager.getTeacherAttendanceSessions(teacherId)
                .onSuccess { sessions ->
                    if (sessions.isEmpty()) {
                        Toast.makeText(
                            this@TeacherAttendanceHistoryActivity,
                            "Henüz yoklama kaydı bulunmuyor",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    sessionAdapter.submitList(sessions.sortedByDescending { it.date })
                }
                .onFailure { exception ->
                    Toast.makeText(
                        this@TeacherAttendanceHistoryActivity,
                        "Yoklama geçmişi yüklenemedi: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }
} 