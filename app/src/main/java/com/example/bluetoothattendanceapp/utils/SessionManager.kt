package com.example.bluetoothattendanceapp.utils

import android.content.Context
import android.content.SharedPreferences
import com.example.bluetoothattendanceapp.data.UserType

class SessionManager(private val context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val editor: SharedPreferences.Editor = sharedPreferences.edit()

    companion object {
        private const val PREF_NAME = "BluetoothAttendanceSession"
        private const val KEY_IS_LOGGED_IN = "isLoggedIn"
        private const val KEY_USER_ID = "userId"
        private const val KEY_USER_TYPE = "userType"
    }

    fun createLoginSession(userId: String, userType: UserType) {
        editor.apply {
            putBoolean(KEY_IS_LOGGED_IN, true)
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_TYPE, userType.toString())
            apply()
        }
    }

    fun clearSession() {
        editor.apply {
            clear()
            apply()
        }
    }

    fun isLoggedIn(): Boolean = sharedPreferences.getBoolean(KEY_IS_LOGGED_IN, false)

    fun getUserId(): String? = sharedPreferences.getString(KEY_USER_ID, null)

    fun getUserType(): UserType {
        val userTypeStr = sharedPreferences.getString(KEY_USER_TYPE, UserType.STUDENT.toString())
        return UserType.valueOf(userTypeStr ?: UserType.STUDENT.toString())
    }
} 