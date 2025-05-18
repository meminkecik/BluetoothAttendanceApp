package com.example.bluetoothattendanceapp.utils

import com.example.bluetoothattendanceapp.data.User
import com.example.bluetoothattendanceapp.data.UserType
import com.example.bluetoothattendanceapp.data.AttendanceSession
import com.example.bluetoothattendanceapp.data.AttendanceRecord
import com.example.bluetoothattendanceapp.data.FirebaseAttendanceRecord
import com.example.bluetoothattendanceapp.data.UserEntity
import com.example.bluetoothattendanceapp.data.AttendanceDatabase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import android.util.Log
import android.content.Context
import com.example.bluetoothattendanceapp.data.Course
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class FirebaseManager(private val context: Context? = null) {
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    private val usersRef = database.child("users")
    private val attendanceRef = database.child("attendance_sessions")
    private val localDatabase by lazy { context?.let { AttendanceDatabase.getDatabase(it) } }

    companion object {
        private const val TAG = "FirebaseManager"
    }

    suspend fun registerUser(
        email: String,
        password: String,
        username: String,
        name: String,
        surname: String,
        userType: UserType,
        studentNumber: String? = null
    ): Result<User> {
        return try {
            Log.d("Firebase", """
                Kullanıcı kaydı başlatılıyor:
                - Email: $email
                - Kullanıcı Adı: $username
                - Ad: $name
                - Soyad: $surname
                - Tip: $userType
                - Öğrenci No: $studentNumber
            """.trimIndent())

            // Önce Authentication ile kullanıcı oluştur
            val authResult = auth.createUserWithEmailAndPassword(email, password).await()
            val uid = authResult.user?.uid ?: throw Exception("Kullanıcı ID oluşturulamadı")

            // Kullanıcı bilgilerini hazırla
            val userData = hashMapOf(
                "id" to uid,
                "username" to username,
                "email" to email,
                "name" to name,
                "surname" to surname,
                "userType" to userType.toString(),
                "studentNumber" to studentNumber
            )

            Log.d("Firebase", "Kullanıcı verileri hazırlandı: $userData")

            // Realtime Database'e kullanıcı bilgilerini kaydet
            usersRef.child(uid).setValue(userData).await()
            Log.d("Firebase", "Kullanıcı verileri Firebase'e kaydedildi")

            val user = User(
                id = uid,
                username = username,
                email = email,
                name = name,
                surname = surname,
                userType = userType,
                studentNumber = studentNumber
            )

            // Yerel veritabanına da kaydet
            context?.let {
                try {
                    val userEntity = UserEntity.fromUser(user)
                    localDatabase?.userDao()?.insertUser(userEntity)
                    Log.d("Firebase", "Kullanıcı yerel veritabanına kaydedildi")
                } catch (e: Exception) {
                    Log.e("Firebase", "Yerel veritabanına kayıt hatası: ${e.message}")
                }
            }

            Result.success(user)
        } catch (e: Exception) {
            Log.e("Firebase", "Kullanıcı kaydı başarısız: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun loginUser(email: String, password: String): Result<User> {
        return try {
            // Firebase Authentication ile giriş yap
            val authResult = auth.signInWithEmailAndPassword(email, password).await()
            val uid = authResult.user?.uid ?: throw Exception("Kullanıcı bulunamadı")

            // Kullanıcı bilgilerini Realtime Database'den al
            val snapshot = usersRef.child(uid).get().await()
            val user = snapshot.getValue(User::class.java) 
                ?: throw Exception("Kullanıcı bilgileri bulunamadı")

            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCurrentUser(): User? = withContext(Dispatchers.IO) {
        try {
            val user = auth.currentUser ?: return@withContext null
            val snapshot = usersRef.child(user.uid).get().await()
            
            val userData = snapshot.getValue(User::class.java)
            if (userData == null) {
                Log.e(TAG, "Kullanıcı verisi dönüştürülemedi")
                return@withContext null
            }
            
            Log.d(TAG, "Kullanıcı bilgileri başarıyla alındı: ${userData.email}")
            userData
        } catch (e: Exception) {
            Log.e(TAG, "Mevcut kullanıcı bilgileri alınamadı: ${e.message}", e)
            null
        }
    }

    fun logout() {
        auth.signOut()
    }

    suspend fun isUsernameExists(username: String): Boolean {
        val snapshot = usersRef.orderByChild("username").equalTo(username).get().await()
        return !snapshot.exists()  // isEmpty() yerine exists() kullanıyoruz
    }

    suspend fun createAttendanceSession(
        teacherId: String,
        courseName: String,
        courseId: Int
    ): Result<String> {
        return try {
            // Parametre kontrolleri
            if (teacherId.isBlank()) {
                return Result.failure(Exception("Öğretmen ID'si boş olamaz"))
            }
            
            if (courseName.isBlank()) {
                return Result.failure(Exception("Ders adı boş olamaz"))
            }
            
            if (courseId <= 0) {
                return Result.failure(Exception("Geçersiz ders ID: $courseId"))
            }

            val sessionId = attendanceRef.push().key ?: throw Exception("Session ID oluşturulamadı")
            
            // Veriyi doğrudan HashMap olarak oluştur
            val sessionData = hashMapOf(
                "id" to sessionId,
                "teacherId" to teacherId,
                "courseName" to courseName,
                "courseId" to courseId,  // Int olarak gönder
                "date" to System.currentTimeMillis(),
                "isActive" to true,
                "attendees" to HashMap<String, Any>()
            )
            
            // Veriyi kaydet
            attendanceRef.child(sessionId).setValue(sessionData).await()
            
            Log.d(TAG, """
                Yoklama oturumu oluşturuldu:
                - Session ID: $sessionId
                - Ders Adı: $courseName
                - Ders ID: $courseId
                - Öğretmen ID: $teacherId
            """.trimIndent())
            
            Result.success(sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Yoklama oturumu oluşturma hatası: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun addAttendanceRecord(
        sessionId: String,
        record: FirebaseAttendanceRecord
    ): Result<Unit> {
        return try {
            attendanceRef.child(sessionId)
                .child("attendees")
                .child(record.studentId)
                .setValue(record)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun closeAttendanceSession(sessionId: String): Result<Unit> {
        return try {
            attendanceRef.child(sessionId)
                .child("isActive")
                .setValue(false)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTeacherAttendanceSessions(teacherId: String): Result<List<AttendanceSession>> {
        return try {
            val snapshot = attendanceRef
                .orderByChild("teacherId")
                .equalTo(teacherId)
                .get()
                .await()

            val sessions = snapshot.children.mapNotNull { 
                it.getValue(AttendanceSession::class.java) 
            }
            Result.success(sessions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCurrentUserDetails(): User? {
        val firebaseUser = auth.currentUser ?: return null
        return try {
            Log.d("FirebaseManager", "Kullanıcı bilgileri alınıyor. UID: ${firebaseUser.uid}")
            val snapshot = usersRef.child(firebaseUser.uid).get().await()
            
            if (!snapshot.exists()) {
                Log.e("FirebaseManager", "Kullanıcı verisi bulunamadı")
                return null
            }
            
            val user = snapshot.getValue(User::class.java)
            if (user == null) {
                Log.e("FirebaseManager", "Kullanıcı verisi dönüştürülemedi")
                return null
            }
            
            Log.d("FirebaseManager", "Kullanıcı bilgileri başarıyla alındı: ${user.email}")
            user
        } catch (e: Exception) {
            Log.e("FirebaseManager", "Kullanıcı bilgileri alınırken hata: ${e.message}")
            null
        }
    }

    suspend fun updateCourseStatus(courseId: Int, isActive: Boolean) {
        try {
            database.child("courses")
                .child(courseId.toString())
                .child("isActive")
                .setValue(isActive)
                .await()
        } catch (e: Exception) {
            Log.e("Firebase", "Ders durumu güncellenemedi: ${e.message}")
            throw e
        }
    }

    suspend fun getAttendanceRecordsForCourse(courseId: Int): List<AttendanceRecord> {
        return try {
            val snapshot = database.child("attendance")
                .child(courseId.toString())
                .get()
                .await()

            if (snapshot.exists()) {
                snapshot.children.mapNotNull { 
                    it.getValue(AttendanceRecord::class.java) 
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("Firebase", "Yoklama kayıtları alınamadı: ${e.message}")
            emptyList()
        }
    }

    suspend fun getStudentByNumber(studentNumber: String): User? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Öğrenci aranıyor: $studentNumber")
            
            val snapshot = usersRef.child("users")
                .orderByChild("studentNumber")
                .equalTo(studentNumber)
                .get()
                .await()

            if (!snapshot.exists()) {
                Log.d(TAG, "Öğrenci bulunamadı: $studentNumber")
                return@withContext null
            }

            val studentData = snapshot.children.first()
            val student = User(
                id = studentData.key ?: "",
                name = studentData.child("name").getValue(String::class.java) ?: "",
                surname = studentData.child("surname").getValue(String::class.java) ?: "",
                studentNumber = studentData.child("studentNumber").getValue(String::class.java) ?: "",
                userType = UserType.valueOf(studentData.child("userType").getValue(String::class.java) ?: "STUDENT")
            )

            Log.d(TAG, "Öğrenci bulundu: ${student.name} ${student.surname}")
            student
        } catch (e: Exception) {
            Log.e(TAG, "Öğrenci bilgileri alınamadı: ${e.message}", e)
            null
        }
    }

    fun getActiveCourses() = callbackFlow {
        val coursesRef = database.child("courses")
        val listener = coursesRef.orderByChild("isActive")
            .equalTo(true)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val activeCourses = mutableListOf<Course>()
                    for (courseSnapshot in snapshot.children) {
                        courseSnapshot.getValue(Course::class.java)?.let { course ->
                            if (course.isActive) {
                                activeCourses.add(course)
                            }
                        }
                    }
                    trySend(activeCourses)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("Firebase", "Aktif dersler alınamadı: ${error.message}")
                    trySend(emptyList())
                }
            })

        awaitClose {
            coursesRef.removeEventListener(listener)
        }
    }

    suspend fun syncUsersToLocalDb() {
        context?.let { ctx ->
            try {
                Log.d("Firebase", "Kullanıcı verileri senkronize ediliyor...")
                
                // Tüm kullanıcıları Firebase'den al
                val snapshot = usersRef.get().await()
                val users = mutableListOf<User>()
                
                snapshot.children.forEach { userSnapshot ->
                    try {
                        val user = userSnapshot.getValue(User::class.java)
                        if (user != null) {
                            users.add(user)
                            Log.d("Firebase", """
                                Kullanıcı verisi alındı:
                                - ID: ${user.id}
                                - Ad: ${user.name}
                                - Soyad: ${user.surname}
                                - Öğrenci No: ${user.studentNumber}
                            """.trimIndent())
                        }
                    } catch (e: Exception) {
                        Log.e("Firebase", "Kullanıcı verisi dönüştürme hatası: ${e.message}")
                    }
                }
                
                // Yerel veritabanına kaydet
                users.forEach { user ->
                    try {
                        val userEntity = UserEntity.fromUser(user)
                        localDatabase?.userDao()?.insertUser(userEntity)
                        Log.d("Firebase", "Kullanıcı yerel veritabanına senkronize edildi: ${user.name} ${user.surname}")
                    } catch (e: Exception) {
                        Log.e("Firebase", "Kullanıcı senkronizasyon hatası: ${e.message}")
                    }
                }
                
                Log.d("Firebase", "Toplam ${users.size} kullanıcı senkronize edildi")
            } catch (e: Exception) {
                Log.e("Firebase", "Senkronizasyon hatası: ${e.message}")
                e.printStackTrace()
            }
        }
    }
} 