package com.example.bluetoothattendanceapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.Index
import java.util.Date

@Entity(
    tableName = "attendance_records",
    indices = [
        Index(
            value = ["studentId", "timestamp"],
            unique = true  // Aynı öğrencinin aynı gün için tek kaydı olabilir
        )
    ]
)
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    
    @ColumnInfo(name = "courseId")
    val courseId: Int,
    
    @ColumnInfo(name = "studentId")
    val studentId: String,
    
    @ColumnInfo(name = "studentName")
    val studentName: String,
    
    @ColumnInfo(name = "studentSurname")
    val studentSurname: String,
    
    @ColumnInfo(name = "studentNumber")
    val studentNumber: String,
    
    @ColumnInfo(name = "deviceAddress")
    val deviceAddress: String,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Date
) 