package com.example.bluetoothattendanceapp.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "courses")
data class Course(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Int = 0,

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "teacher_id")
    val teacherId: String = "",

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date()
) {
    constructor() : this(0, "", "", false, Date())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Course) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        return id
    }
} 