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

    @Query("""
        SELECT * FROM attendance_records 
        WHERE courseId = :courseId 
        AND date(timestamp/1000, 'unixepoch', 'localtime') = date('now', 'localtime')
        GROUP BY studentNumber
        HAVING timestamp = MAX(timestamp)
        ORDER BY timestamp DESC
    """)
    fun getAttendanceByCourseId(courseId: Int): Flow<List<AttendanceRecord>>

    @Query("SELECT * FROM attendance_records WHERE date(timestamp/1000, 'unixepoch') = date(:date/1000, 'unixepoch')")
    fun getAttendanceByDate(date: Date): Flow<List<AttendanceRecord>>

    @Query("SELECT * FROM attendance_records WHERE deviceAddress = :deviceAddress ORDER BY timestamp DESC")
    fun getAttendanceForStudent(deviceAddress: String): Flow<List<AttendanceRecord>>

    @Query("DELETE FROM attendance_records WHERE courseId = :courseId")
    suspend fun deleteAttendanceForCourse(courseId: Int)

    @Query("SELECT COUNT(*) FROM attendance_records WHERE courseId = :courseId AND deviceAddress = :deviceAddress")
    suspend fun hasExistingAttendance(courseId: Int, deviceAddress: String): Boolean

    @Query("SELECT COUNT(*) FROM attendance_records WHERE courseId = :courseId AND studentNumber = :studentNumber AND date(timestamp/1000, 'unixepoch') = date(:date/1000, 'unixepoch')")
    suspend fun hasStudentAttendedToday(courseId: Int, studentNumber: String?, date: Date): Boolean

    @Query("""
        SELECT * FROM attendance_records 
        WHERE courseId = :courseId 
        AND studentNumber = :studentNumber 
        AND date(timestamp/1000, 'unixepoch', 'localtime') = date(:date/1000, 'unixepoch', 'localtime') 
        LIMIT 1
    """)
    suspend fun getStudentAttendanceToday(courseId: Int, studentNumber: String, date: Date): AttendanceRecord?

    @Query("""
        SELECT * FROM attendance_records 
        WHERE courseId = :courseId 
        AND studentNumber = :studentNumber 
        AND date(timestamp/1000, 'unixepoch', 'localtime') = date('now', 'localtime')
        LIMIT 1
    """)
    suspend fun getAttendanceByStudentNumber(courseId: Int, studentNumber: String): AttendanceRecord?
} 