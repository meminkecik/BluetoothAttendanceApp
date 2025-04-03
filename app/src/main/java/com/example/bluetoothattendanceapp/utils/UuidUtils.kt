package com.example.bluetoothattendanceapp.utils

import android.os.ParcelUuid
import android.util.Log

object UuidUtils {
    // Yoklama durumu bildirimi için UUID
    private const val STATUS_UUID = "00000001-0000-1000-8000-00805F9B34FB"

    fun getStatusUuid(): String {
        return STATUS_UUID
    }

    // Base UUID formatı
    private const val BASE_UUID = "00000000-0000-1000-8000-00805F9B34FB"
    
    // Öğrenci ID'sinden UUID oluştur
    fun generateStudentUuid(studentId: String): String {
        // Öğrenci ID'sinden UUID oluştur
        val idBytes = studentId.toByteArray()
        val uuidBytes = BASE_UUID.replace("-", "").chunked(2).map { 
            it.toInt(16).toByte() 
        }.toByteArray()

        // İlk 8 byte'ı öğrenci ID'si ile değiştir
        for (i in 0 until minOf(8, idBytes.size)) {
            uuidBytes[i] = idBytes[i]
        }

        // UUID formatına dönüştür
        return buildString {
            uuidBytes.forEachIndexed { index, byte ->
                append(String.format("%02X", byte))
                when (index) {
                    3, 5, 7, 9 -> append("-")
                }
            }
        }
    }

    // UUID'den öğrenci ID'sini çıkar (opsiyonel)
    fun extractStudentId(uuid: String): String? {
        try {
            // UUID'nin ilk 8 karakterini al (öğrenci ID'si)
            return uuid.replace("-", "").take(16)
        } catch (e: Exception) {
            Log.e("UuidUtils", "UUID ayrıştırma hatası: ${e.message}")
            return null
        }
    }

    fun getBaseUuidWithMask(): Pair<ParcelUuid, ParcelUuid> {
        return ParcelUuid.fromString(BASE_UUID) to ParcelUuid.fromString("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF")
    }
} 