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

    @Query("SELECT * FROM attendance_records ORDER BY timestamp DESC")
    fun getAllAttendanceRecords(): Flow<List<AttendanceRecord>>

    @Query("""
        SELECT * FROM attendance_records 
        WHERE datetime(timestamp/1000, 'unixepoch', 'localtime') >= datetime(:date/1000, 'unixepoch', 'localtime', '-1 day')
        ORDER BY timestamp DESC
    """)
    fun getAttendanceByDate(date: Date): Flow<List<AttendanceRecord>>

    @Query("SELECT * FROM attendance_records WHERE deviceAddress = :deviceAddress ORDER BY timestamp DESC")
    fun getAttendanceForStudent(deviceAddress: String): Flow<List<AttendanceRecord>>

    @Query("DELETE FROM attendance_records WHERE courseId = :courseId")
    suspend fun deleteAttendanceForCourse(courseId: Int)

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM attendance_records 
            WHERE courseId = :courseId 
            AND deviceAddress = :deviceAddress
            AND datetime(timestamp/1000, 'unixepoch', 'localtime') >= datetime('now', 'localtime', '-1 day')
        )
    """)
    suspend fun hasExistingAttendance(courseId: Int, deviceAddress: String): Boolean
} 