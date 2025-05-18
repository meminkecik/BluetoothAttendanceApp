package com.example.bluetoothattendanceapp.util

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        PREF_NAME,
        Context.MODE_PRIVATE
    )

    fun saveTeacherId(teacherId: String) {
        sharedPreferences.edit().putString(KEY_TEACHER_ID, teacherId).apply()
    }

    fun getTeacherId(): String {
        return sharedPreferences.getString(KEY_TEACHER_ID, "") ?: ""
    }

    fun clearTeacherData() {
        sharedPreferences.edit().remove(KEY_TEACHER_ID).apply()
    }

    companion object {
        private const val PREF_NAME = "BluetoothAttendancePrefs"
        private const val KEY_TEACHER_ID = "teacher_id"
    }
} 