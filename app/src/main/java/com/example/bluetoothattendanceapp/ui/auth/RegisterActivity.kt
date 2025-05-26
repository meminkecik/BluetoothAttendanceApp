package com.example.bluetoothattendanceapp.ui.auth

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.bluetoothattendanceapp.R
import com.example.bluetoothattendanceapp.data.model.UserType
import com.example.bluetoothattendanceapp.data.remote.FirebaseManager
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {
    private lateinit var firebaseManager: FirebaseManager
    private lateinit var loadingDialog: AlertDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        firebaseManager = FirebaseManager()
        setupLoadingDialog()

        val emailInput = findViewById<EditText>(R.id.emailInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val nameInput = findViewById<EditText>(R.id.nameInput)
        val surnameInput = findViewById<EditText>(R.id.surnameInput)
        val studentNumberInput = findViewById<EditText>(R.id.studentNumberInput)
        val usernameInput = findViewById<EditText>(R.id.usernameInput)
        val userTypeRadioGroup = findViewById<RadioGroup>(R.id.userTypeRadioGroup)
        val registerButton = findViewById<Button>(R.id.registerButton)

        userTypeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.studentRadio -> {
                    studentNumberInput.visibility = View.VISIBLE
                    emailInput.visibility = View.GONE
                    usernameInput.visibility = View.VISIBLE
                }
                R.id.teacherRadio -> {
                    studentNumberInput.visibility = View.GONE
                    emailInput.visibility = View.VISIBLE
                    usernameInput.visibility = View.GONE
                }
            }
        }

        registerButton.setOnClickListener {
            val isTeacher = userTypeRadioGroup.checkedRadioButtonId == R.id.teacherRadio
            val email = if (isTeacher) emailInput.text.toString().trim() else ""
            val username = if (!isTeacher) usernameInput.text.toString().trim() else ""
            val password = passwordInput.text.toString().trim()
            val name = nameInput.text.toString().trim()
            val surname = surnameInput.text.toString().trim()
            val studentNumber = studentNumberInput.text.toString().trim()
            val userType = if (isTeacher) UserType.TEACHER else UserType.STUDENT

            if (!validateInputs(
                    email = if (isTeacher) email else username,
                    password = password,
                    name = name,
                    surname = surname,
                    studentNumber = studentNumber,
                    isTeacher = isTeacher
                )) {
                return@setOnClickListener
            }

            loadingDialog.show()
            lifecycleScope.launch {
                try {
                    val result = firebaseManager.registerUser(
                        email = if (isTeacher) email else "$username@student.com",
                        password = password,
                        username = if (isTeacher) email else username,
                        name = name,
                        surname = surname,
                        userType = userType,
                        studentNumber = if (!isTeacher) studentNumber else null
                    )

                    result.onSuccess {
                        Toast.makeText(this@RegisterActivity, "Kayıt başarılı", Toast.LENGTH_SHORT).show()
                        finish()
                    }.onFailure { e ->
                        Toast.makeText(this@RegisterActivity, 
                            "Kayıt hatası: ${e.message}", 
                            Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    loadingDialog.dismiss()
                }
            }
        }
    }

    private fun validateInputs(
        email: String,
        password: String,
        name: String,
        surname: String,
        studentNumber: String,
        isTeacher: Boolean
    ): Boolean {
        val confirmPassword = findViewById<EditText>(R.id.confirmPasswordInput).text.toString().trim()

        if (password.isEmpty() || confirmPassword.isEmpty() || name.isEmpty() || surname.isEmpty()) {
            Toast.makeText(this, "Lütfen tüm alanları doldurun", Toast.LENGTH_SHORT).show()
            return false
        }

        if (isTeacher && email.isEmpty()) {
            Toast.makeText(this, "Lütfen e-posta adresini girin", Toast.LENGTH_SHORT).show()
            return false
        }

        if (!isTeacher && studentNumber.isEmpty()) {
            Toast.makeText(this, "Lütfen öğrenci numarasını girin", Toast.LENGTH_SHORT).show()
            return false
        }

        if (password != confirmPassword) {
            Toast.makeText(this, "Şifreler eşleşmiyor", Toast.LENGTH_SHORT).show()
            return false
        }

        if (isTeacher && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Geçerli bir e-posta adresi girin", Toast.LENGTH_SHORT).show()
            return false
        }

        if (password.length < 6) {
            Toast.makeText(this, "Şifre en az 6 karakter olmalıdır", Toast.LENGTH_SHORT).show()
            return false
        }

        return true
    }

    @SuppressLint("InflateParams")
    private fun setupLoadingDialog() {
        loadingDialog = AlertDialog.Builder(this)
            .setView(layoutInflater.inflate(R.layout.dialog_loading, null))
            .setCancelable(false)
            .create()
    }

} 