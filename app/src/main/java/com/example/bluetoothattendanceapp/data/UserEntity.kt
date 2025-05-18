package com.example.bluetoothattendanceapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val id: String, // Firebase UID'yi primary key olarak kullanıyoruz
    val username: String,
    val email: String?,
    val userType: UserType,
    val name: String,
    val surname: String,
    val studentNumber: String?
) {
    // User modelinden UserEntity oluşturmak için
    companion object {
        fun fromUser(user: User): UserEntity {
            return UserEntity(
                id = user.id,
                username = user.username,
                email = user.email,
                userType = user.userType,
                name = user.name,
                surname = user.surname,
                studentNumber = user.studentNumber
            )
        }
    }

    // UserEntity'den User modeli oluşturmak için
    fun toUser(): User {
        return User(
            id = id,
            username = username,
            email = email,
            userType = userType,
            name = name,
            surname = surname,
            studentNumber = studentNumber
        )
    }
} 