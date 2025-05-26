package com.example.bluetoothattendanceapp.utils

object UuidUtils {
    private const val STATUS_UUID = "00000001-0000-1000-8000-00805F9B34FB"

    fun getStatusUuid(): String {
        return STATUS_UUID
    }
}