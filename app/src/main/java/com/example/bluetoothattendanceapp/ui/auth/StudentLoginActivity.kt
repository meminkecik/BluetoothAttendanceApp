package com.example.bluetoothattendanceapp.ui.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.bluetoothattendanceapp.R
import com.example.bluetoothattendanceapp.ui.student.StudentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class StudentLoginActivity : AppCompatActivity() {
    private lateinit var studentPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student_login)

        studentPrefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)

        loadSavedData()

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            saveStudentData()
        }
    }

    private fun loadSavedData() {
        findViewById<TextInputEditText>(R.id.nameInput).setText(studentPrefs.getString("name", ""))
        findViewById<TextInputEditText>(R.id.surnameInput).setText(studentPrefs.getString("surname", ""))
        findViewById<TextInputEditText>(R.id.studentNumberInput).setText(studentPrefs.getString("student_number", ""))
    }

    private fun saveStudentData() {
        val name = findViewById<TextInputEditText>(R.id.nameInput).text.toString()
        val surname = findViewById<TextInputEditText>(R.id.surnameInput).text.toString()
        val studentNumber = findViewById<TextInputEditText>(R.id.studentNumberInput).text.toString()

        if (name.isBlank() || surname.isBlank()) {
            Toast.makeText(this, getString(R.string.fill_required_fields), Toast.LENGTH_SHORT).show()
            return
        }

        val studentId = generateStudentId(name, surname)

        studentPrefs.edit().apply {
            putString("student_id", studentId)
            putString("name", name)
            putString("surname", surname)
            putString("student_number", studentNumber)
            apply()
        }

        startActivity(Intent(this, StudentActivity::class.java))
        finish()
    }

    private fun generateStudentId(name: String, surname: String): String {
        val timestamp = System.currentTimeMillis()
        val initials = "${name.firstOrNull()?.uppercase() ?: ""}${surname.firstOrNull()?.uppercase() ?: ""}"
        return "$initials$timestamp"
    }
} 