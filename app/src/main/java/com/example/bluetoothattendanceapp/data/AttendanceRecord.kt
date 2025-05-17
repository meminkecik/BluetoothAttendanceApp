package com.example.bluetoothattendanceapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "attendance_records")
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val courseId: Int,
    val studentName: String,
    val studentSurname: String,
    val studentNumber: String?,
    val deviceAddress: String,
    val timestamp: Date
) 