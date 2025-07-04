package com.example.bluetoothattendanceapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.bluetoothattendanceapp.data.model.AttendanceRecord
import com.example.bluetoothattendanceapp.data.model.Course
import com.example.bluetoothattendanceapp.data.model.UserEntity

@Database(
    entities = [
        Course::class,
        AttendanceRecord::class,
        UserEntity::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AttendanceDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun userDao(): UserDao

    companion object {
        @Volatile
        private var INSTANCE: AttendanceDatabase? = null

        fun getDatabase(context: Context): AttendanceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AttendanceDatabase::class.java,
                    "attendance_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
} 