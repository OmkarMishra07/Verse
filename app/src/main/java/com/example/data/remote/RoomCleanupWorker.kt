package com.example.data.remote

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class RoomCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val roomId = inputData.getString("roomId") ?: return Result.failure()
        Log.d("RoomCleanupWorker", "Running background cleanup check for room: $roomId")

        try {
            val rtdb = FirebaseDatabase.getInstance("https://verse-d9583-default-rtdb.asia-southeast1.firebasedatabase.app")
            val rtdbStateRef = rtdb.getReference("jamming_rooms/$roomId/state")

            // Check participants in RTDB
            val snapshot = rtdbStateRef.child("participants").get().await()
            val participantsCount = snapshot.childrenCount

            if (participantsCount == 0L) {
                // Room is still empty, verify emptySince expiration
                val emptySinceSnapshot = rtdbStateRef.child("emptySince").get().await()
                val emptySince = emptySinceSnapshot.getValue(Long::class.java) ?: 0L
                if (emptySince > 0L) {
                    val serverTimeOffsetSnapshot = rtdb.getReference(".info/serverTimeOffset").get().await()
                    val serverTimeOffset = serverTimeOffsetSnapshot.getValue(Long::class.java) ?: 0L
                    val trueTime = System.currentTimeMillis() + serverTimeOffset
                    val elapsed = trueTime - emptySince

                    // 20 minutes grace period (1200000 ms)
                    if (elapsed >= 20 * 60 * 1000L - 10000L) { // Allow 10s leeway
                        Log.d("RoomCleanupWorker", "Room $roomId expired. Deleting...")
                        FirebaseFirestore.getInstance().collection("jamming_rooms").document(roomId).delete().await()
                        rtdb.getReference("jamming_rooms/$roomId").removeValue().await()
                    } else {
                        Log.d("RoomCleanupWorker", "Room $roomId empty but not yet expired. Elapsed: ${elapsed}ms")
                    }
                }
            } else {
                Log.d("RoomCleanupWorker", "Room $roomId is active with $participantsCount participants. Skipping deletion.")
            }
            return Result.success()
        } catch (e: Exception) {
            Log.e("RoomCleanupWorker", "Error cleaning up room $roomId", e)
            return Result.retry()
        }
    }
}
