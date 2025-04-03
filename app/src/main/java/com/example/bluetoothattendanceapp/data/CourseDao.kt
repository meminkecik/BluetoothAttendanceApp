package com.example.bluetoothattendanceapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses WHERE is_active = 1 ORDER BY created_at DESC")
    fun getActiveCourses(): Flow<List<Course>>

    @Query("SELECT * FROM courses WHERE teacher_id = :teacherId")
    fun getCoursesByTeacher(teacherId: String): Flow<List<Course>>

    @Insert
    suspend fun insertCourse(course: Course): Long

    @Update
    suspend fun updateCourse(course: Course)

    @Query("UPDATE courses SET is_active = :isActive WHERE id = :courseId")
    suspend fun updateCourseStatus(courseId: Int, isActive: Boolean)

    @Query("UPDATE courses SET is_active = 0")
    suspend fun deactivateAllCourses()

    @Query("SELECT * FROM courses WHERE id = :courseId")
    suspend fun getCourseById(courseId: Int): Course

    @Query("SELECT * FROM courses ORDER BY created_at DESC")
    suspend fun getAllCourses(): List<Course>
} 