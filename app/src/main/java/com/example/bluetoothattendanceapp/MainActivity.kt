package com.example.bluetoothattendanceapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialButton>(R.id.btnTeacherMode).setOnClickListener {
            startActivity(Intent(this, TeacherModeActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnStudentMode).setOnClickListener {
            val studentPrefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
            val hasStudentInfo = studentPrefs.contains("student_id")

            val intent = if (hasStudentInfo) {
                Intent(this, StudentActivity::class.java)
            } else {
                Intent(this, StudentLoginActivity::class.java)
            }
            startActivity(intent)
        }
    }
} 