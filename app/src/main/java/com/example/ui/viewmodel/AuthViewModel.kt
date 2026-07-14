package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
class AuthViewModel : ViewModel() {

    private val auth = FirebaseAuth.getInstance()

    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private var userDocListener: com.google.firebase.firestore.ListenerRegistration? = null

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        if (user != null) {
            // Check if they are an unverified email/password user
            val isEmailPass = user.providerData.any { it.providerId == "password" }
            if (isEmailPass && !user.isEmailVerified) {
                // Keep them out of the main app until verified
                _currentUser.value = null
                removeUserListener()
            } else {
                _currentUser.value = user
                attachUserListener(user.uid)
            }
        } else {
            _currentUser.value = null
            removeUserListener()
        }
    }

    private var banJob: kotlinx.coroutines.Job? = null

    private fun attachUserListener(uid: String) {
        if (userDocListener != null) return // Already listening
        
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val userRef = db.collection("users").document(uid)

        userDocListener = userRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                return@addSnapshotListener
            }
            if (snapshot != null) {
                // If the user's document doesn't exist or is missing the isAllowed field, create it once.
                if (!snapshot.exists() || !snapshot.contains("isAllowed")) {
                    val currentUser = auth.currentUser
                    if (currentUser != null) {
                        val userData = mapOf(
                            "email" to (currentUser.email ?: ""),
                            "displayName" to (currentUser.displayName ?: ""),
                            "isAllowed" to true
                        )
                        userRef.set(userData, com.google.firebase.firestore.SetOptions.merge())
                    }
                } else {
                    val isAllowed = snapshot.getBoolean("isAllowed") ?: true
                    if (!isAllowed) {
                        // If we read 'false' (banned), start a 1.5s countdown before kicking.
                        // This fixes a bug where a stale local cache remembers they were banned
                        // and instantly kicks them before the server can tell the app they are un-banned.
                        if (banJob == null || !banJob!!.isActive) {
                            banJob = viewModelScope.launch {
                                kotlinx.coroutines.delay(1500L)
                                signOut()
                            }
                        }
                    } else {
                        // If they are allowed, immediately cancel any pending ban job (server corrected the cache!)
                        banJob?.cancel()
                    }
                }
            }
        }
    }

    private fun removeUserListener() {
        userDocListener?.remove()
        userDocListener = null
    }

    init {
        val initialUser = auth.currentUser
        if (initialUser != null && initialUser.providerData.any { it.providerId == "password" } && !initialUser.isEmailVerified) {
            _currentUser.value = null
        } else {
            _currentUser.value = initialUser
            if (initialUser != null) attachUserListener(initialUser.uid)
        }
        auth.addAuthStateListener(authStateListener)
    }

    /** Called immediately after sign-in succeeds — bypasses AuthStateListener delay. */
    fun onUserSignedIn(user: FirebaseUser) {
        _currentUser.value = user
        attachUserListener(user.uid)
    }

    fun signOut() {
        auth.signOut()
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authStateListener)
        super.onCleared()
    }
}
