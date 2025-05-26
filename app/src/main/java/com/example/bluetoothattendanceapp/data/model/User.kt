package com.example.bluetoothattendanceapp.data.model

import androidx.room.Entity

@Entity(tableName = "users")
data class User(
    val id: String = "",
    val username: String = "",
    val email: String? = null,
    val userType: UserType = UserType.STUDENT,
    val name: String = "",
    val surname: String = "",
    val studentNumber: String? = null
)

enum class UserType {
    STUDENT,
    TEACHER
} 