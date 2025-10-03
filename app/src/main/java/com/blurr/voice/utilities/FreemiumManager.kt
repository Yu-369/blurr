package com.blurr.voice.utilities

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.firestore
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.awaitCustomerInfo
import kotlinx.coroutines.tasks.await
import java.util.Calendar

class FreemiumManager {

    private val db = Firebase.firestore
    private val auth = Firebase.auth

    companion object {
        const val DAILY_TASK_LIMIT = 100 // Set your daily task limit here
    }

    private suspend fun isUserSubscribed(): Boolean {
        return true
    }

    private fun isSameDay(timestamp: Timestamp): Boolean {
        val calendar1 = Calendar.getInstance()
        calendar1.time = timestamp.toDate()
        val calendar2 = Calendar.getInstance() // Current time

        return calendar1.get(Calendar.YEAR) == calendar2.get(Calendar.YEAR) &&
                calendar1.get(Calendar.DAY_OF_YEAR) == calendar2.get(Calendar.DAY_OF_YEAR)
    }

    private suspend fun resetDailyTasksIfNeeded(uid: String) {
        val userDocRef = db.collection("users").document(uid)
        try {
            db.runTransaction { transaction ->
                val snapshot = transaction.get(userDocRef)
                val lastReset = snapshot.getTimestamp("tasksResetDate")

                if (lastReset == null || !isSameDay(lastReset)) {
                    Log.d("FreemiumManager", "New day detected. Resetting tasks for user $uid.")
                    transaction.update(userDocRef, "tasksRemaining", DAILY_TASK_LIMIT)
                    transaction.update(userDocRef, "tasksResetDate", FieldValue.serverTimestamp())
                }
            }.await()
        } catch (e: Exception) {
            Log.e("FreemiumManager", "Error in daily task reset transaction", e)
        }
    }

    suspend fun provisionUserIfNeeded() {
        val currentUser = auth.currentUser ?: return
        val userDocRef = db.collection("users").document(currentUser.uid)

        try {
            val document = userDocRef.get().await()
            if (!document.exists()) {
                Log.d("FreemiumManager", "Provisioning new user: ${currentUser.uid}")
                val newUser = hashMapOf(
                    "email" to currentUser.email,
                    "plan" to "free",
                    "tasksRemaining" to DAILY_TASK_LIMIT,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "tasksResetDate" to FieldValue.serverTimestamp() // Set initial reset date
                )
                userDocRef.set(newUser).await()
            } else {
                // For existing users, check if they need the new daily limit fields
                if (!document.contains("tasksResetDate")) {
                    Log.d("FreemiumManager", "Migrating existing user ${currentUser.uid} to daily limit system.")
                    userDocRef.update(mapOf(
                        "tasksRemaining" to DAILY_TASK_LIMIT,
                        "tasksResetDate" to FieldValue.serverTimestamp()
                    )).await()
                } else {
                    // This is a good place to reset their tasks if it's a new day
                    resetDailyTasksIfNeeded(currentUser.uid)
                }
            }
        } catch (e: Exception) {
            Log.e("FreemiumManager", "Error provisioning user", e)
        }
    }

    suspend fun getTasksRemaining(): Long? {
        if (isUserSubscribed()) return Long.MAX_VALUE
        val currentUser = auth.currentUser ?: return null
        resetDailyTasksIfNeeded(currentUser.uid) // Ensure tasks are up-to-date before fetching
        return try {
            val document = db.collection("users").document(currentUser.uid).get().await()
            document.getLong("tasksRemaining")
        } catch (e: Exception) {
            Log.e("FreemiumManager", "Error fetching tasks remaining", e)
            null
        }
    }

    suspend fun canPerformTask(): Boolean {
        if (isUserSubscribed()) return true
        val currentUser = auth.currentUser ?: return false
        resetDailyTasksIfNeeded(currentUser.uid) // Crucial check before verifying task count

        return try {
            val document = db.collection("users").document(currentUser.uid).get().await()
            val tasksRemaining = document.getLong("tasksRemaining") ?: 0
            Log.d("FreemiumManager", "User has $tasksRemaining tasks remaining today.")
            tasksRemaining > 0
        } catch (e: Exception) {
            Log.e("FreemiumManager", "Error fetching user task count", e)
            false
        }
    }

    suspend fun decrementTaskCount() {
        if (isUserSubscribed()) return
        val currentUser = auth.currentUser ?: return

        val userDocRef = db.collection("users").document(currentUser.uid)
        resetDailyTasksIfNeeded(currentUser.uid) // Ensure we don't decrement on a stale count

        // Decrement only if tasks are remaining
        val tasksRemaining = getTasksRemaining()
        if (tasksRemaining != null && tasksRemaining > 0) {
            userDocRef.update("tasksRemaining", FieldValue.increment(-1))
                .addOnSuccessListener {
                    Log.d("FreemiumManager", "Successfully decremented task count for user ${currentUser.uid}.")
                }
                .addOnFailureListener { e ->
                    Log.e("FreemiumManager", "Failed to decrement task count.", e)
                }
        } else {
            Log.w("FreemiumManager", "Attempted to decrement task count, but none were left.")
        }
    }
}
