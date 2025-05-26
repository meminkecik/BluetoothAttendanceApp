package com.example.bluetoothattendanceapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.bluetoothattendanceapp.data.model.UserEntity

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Query("SELECT * FROM users WHERE id = :userId LIMIT 1")
    suspend fun getUserById(userId: String): UserEntity?

    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun getUserByEmail(email: String): UserEntity?

    @Query("SELECT * FROM users WHERE studentNumber = :studentNumber LIMIT 1")
    suspend fun getUserByStudentNumber(studentNumber: String): UserEntity?

    @Query("DELETE FROM users WHERE id = :userId")
    suspend fun deleteUser(userId: String)
} 