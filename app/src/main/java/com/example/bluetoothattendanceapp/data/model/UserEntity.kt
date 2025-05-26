package com.example.bluetoothattendanceapp.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val id: String,
    val username: String,
    val email: String?,
    val userType: UserType,
    val name: String,
    val surname: String,
    val studentNumber: String?
) {
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