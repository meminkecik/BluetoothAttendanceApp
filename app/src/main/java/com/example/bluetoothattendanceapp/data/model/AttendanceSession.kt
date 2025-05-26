package com.example.bluetoothattendanceapp.data.model

data class AttendanceSession(
    val id: String = "",
    val teacherId: String = "",
    val courseName: String = "",
    val courseId: Int = 0,
    val date: Long = 0,
    val isActive: Boolean = true,
    val attendees: Map<String, FirebaseAttendanceRecord> = emptyMap()
)

data class FirebaseAttendanceRecord(
    val studentId: String = "",
    val studentName: String = "",
    val studentSurname: String = "",
    val studentNumber: String = "",
    val timestamp: Long = 0
)