package com.example.bluetoothattendanceapp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.example.bluetoothattendanceapp.data.UserType
import com.example.bluetoothattendanceapp.utils.SessionManager

class SplashActivity : AppCompatActivity() {
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        sessionManager = SessionManager(this)

        // 2 saniye bekleyip kontrol et
        Handler(Looper.getMainLooper()).postDelayed({
            checkLoginStatus()
        }, 2000)
    }

    private fun checkLoginStatus() {
        try {
            if (sessionManager.isLoggedIn()) {
                // Kullanıcı tipine göre yönlendir
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
                    else -> {
                        startActivity(Intent(this, LoginActivity::class.java).apply {
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
            // Hata durumunda login ekranına yönlendir
            startActivity(Intent(this, LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        } finally {
            finish()
        }
    }
} 