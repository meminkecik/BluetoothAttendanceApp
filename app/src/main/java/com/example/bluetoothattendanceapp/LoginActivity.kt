package com.example.bluetoothattendanceapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.bluetoothattendanceapp.data.AttendanceDatabase
import com.example.bluetoothattendanceapp.data.UserType
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import com.example.bluetoothattendanceapp.utils.SessionManager
import com.example.bluetoothattendanceapp.utils.FirebaseManager
import android.app.AlertDialog

class LoginActivity : AppCompatActivity() {
    private lateinit var sessionManager: SessionManager
    private lateinit var firebaseManager: FirebaseManager
    private lateinit var loadingDialog: AlertDialog
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var registerLink: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        sessionManager = SessionManager(this)
        firebaseManager = FirebaseManager()
        setupLoadingDialog()

        // UI elemanlarını bağla
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginButton = findViewById(R.id.loginButton)
        registerLink = findViewById(R.id.registerLink)

        // Login butonuna tıklama
        loginButton.setOnClickListener {
            val email = emailInput.text.toString()
            val password = passwordInput.text.toString()

            if (!validateInputs(email, password)) {
                return@setOnClickListener
            }

            loadingDialog.show()
            lifecycleScope.launch {
                try {
                    val result = firebaseManager.loginUser(email, password)
                    result.onSuccess { user ->
                        sessionManager.createLoginSession(user.id, user.userType)
                        
                        val intent = when (user.userType) {
                            UserType.STUDENT -> Intent(this@LoginActivity, StudentActivity::class.java)
                            UserType.TEACHER -> Intent(this@LoginActivity, TeacherModeActivity::class.java)
                        }
                        intent.putExtra("USER_ID", user.id)
                        startActivity(intent)
                        finish()
                    }.onFailure { e ->
                        Toast.makeText(this@LoginActivity, 
                            "Giriş hatası: ${e.message}", 
                            Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    loadingDialog.dismiss()
                }
            }
        }

        registerLink.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun validateInputs(email: String, password: String): Boolean {
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Lütfen tüm alanları doldurun", Toast.LENGTH_SHORT).show()
            return false
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Geçerli bir email adresi girin", Toast.LENGTH_SHORT).show()
            return false
        }

        return true
    }

    private fun setupLoadingDialog() {
        loadingDialog = AlertDialog.Builder(this)
            .setView(layoutInflater.inflate(R.layout.dialog_loading, null))
            .setCancelable(false)
            .create()
    }
} 