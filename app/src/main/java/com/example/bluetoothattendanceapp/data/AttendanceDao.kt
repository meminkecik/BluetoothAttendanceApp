package com.example.bluetoothattendanceapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import androidx.room.OnConflictStrategy
import java.util.Date

@Dao
interface AttendanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttendance(attendance: AttendanceRecord)

    @Query("SELECT * FROM attendance_records WHERE courseId = :courseId ORDER BY timestamp DESC")
    fun getAttendanceForCourse(courseId: Int): Flow<List<AttendanceRecord>>

    @Query("""
        SELECT * FROM attendance_records 
        WHERE date(timestamp/1000, 'unixepoch') = date(:date/1000, 'unixepoch')
        ORDER BY timestamp DESC
    """)
    fun getAttendanceByDate(date: Date): Flow<List<AttendanceRecord>>

    @Query("SELECT * FROM attendance_records WHERE studentId = :studentId ORDER BY timestamp DESC")
    fun getAttendanceForStudent(studentId: String): Flow<List<AttendanceRecord>>

    @Query("DELETE FROM attendance_records WHERE courseId = :courseId")
    suspend fun deleteAttendanceForCourse(courseId: Int)
} 