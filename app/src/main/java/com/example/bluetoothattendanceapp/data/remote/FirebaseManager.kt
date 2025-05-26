package com.example.bluetoothattendanceapp.data.remote

import android.content.Context
import android.util.Log
import com.example.bluetoothattendanceapp.data.local.AttendanceDatabase
import com.example.bluetoothattendanceapp.data.model.AttendanceSession
import com.example.bluetoothattendanceapp.data.model.Course
import com.example.bluetoothattendanceapp.data.model.FirebaseAttendanceRecord
import com.example.bluetoothattendanceapp.data.model.User
import com.example.bluetoothattendanceapp.data.model.UserEntity
import com.example.bluetoothattendanceapp.data.model.UserType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

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

            val authResult = auth.createUserWithEmailAndPassword(email, password).await()
            val uid = authResult.user?.uid ?: throw Exception("Kullanıcı ID oluşturulamadı")

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
            val authResult = auth.signInWithEmailAndPassword(email, password).await()
            val uid = authResult.user?.uid ?: throw Exception("Kullanıcı bulunamadı")

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
        context?.let {
            try {
                Log.d("Firebase", "Kullanıcı verileri senkronize ediliyor...")
                
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