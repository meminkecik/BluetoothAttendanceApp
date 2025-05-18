package com.example.bluetoothattendanceapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    val id: String = "", // Firebase UID
    val username: String = "",
    val email: String? = null,
    val userType: UserType = UserType.STUDENT,
    val name: String = "",
    val surname: String = "",
    val studentNumber: String? = null
) {
    // Firebase için boş constructor
    constructor() : this(
        id = "",
        username = "",
        email = null,
        userType = UserType.STUDENT,
        name = "",
        surname = "",
        studentNumber = null
    )
}

enum class UserType {
    STUDENT,
    TEACHER
} 