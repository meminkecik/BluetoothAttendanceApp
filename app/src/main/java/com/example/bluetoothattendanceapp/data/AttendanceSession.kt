package com.example.bluetoothattendanceapp.data

data class AttendanceSession(
    val id: String = "",
    val teacherId: String = "",
    val courseName: String = "",
    val courseId: Int = 0,
    val date: Long = 0,
    val isActive: Boolean = true,
    val attendees: Map<String, FirebaseAttendanceRecord> = emptyMap()
) {
    // Firebase için boş constructor
    constructor() : this("", "", "", 0, 0, true)
}

data class FirebaseAttendanceRecord(
    val studentId: String = "",
    val studentName: String = "",
    val studentSurname: String = "",
    val studentNumber: String = "",
    val timestamp: Long = 0
) {
    // Firebase için boş constructor
    constructor() : this("", "", "", "", 0)
} 