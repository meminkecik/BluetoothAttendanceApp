package com.example.bluetoothattendanceapp.ui.auth

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.example.bluetoothattendanceapp.R
import com.example.bluetoothattendanceapp.data.model.UserType
import com.example.bluetoothattendanceapp.ui.student.StudentActivity
import com.example.bluetoothattendanceapp.ui.teacher.TeacherModeActivity
import com.example.bluetoothattendanceapp.utils.SessionManager

class SplashActivity : AppCompatActivity() {
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        sessionManager = SessionManager(this)

        Handler(Looper.getMainLooper()).postDelayed({
            checkLoginStatus()
        }, 2000)
    }

    private fun checkLoginStatus() {
        try {
            if (sessionManager.isLoggedIn()) {
                when (sessionManager.getUserType()) {
                    UserType.TEACHER -> {
                        startActivity(Intent(this, TeacherModeActivity::class.java).apply {
                            putExtra("USER_ID", sessionManager.getUserId())
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        })
                    }
                    UserType.STUDENT -> {
                        startActivity(Intent(this, StudentActivity::class.java).apply {
                            putExtra("USER_ID", sessionManager.getUserId())
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        })
                    }
                }
            } else {
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
            }
        } catch (e: Exception) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        } finally {
            finish()
        }
    }
} 