package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuthViewModel : ViewModel() {

    private val auth = FirebaseAuth.getInstance()

    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        if (user != null) {
            // Check if they are an unverified email/password user
            val isEmailPass = user.providerData.any { it.providerId == "password" }
            if (isEmailPass && !user.isEmailVerified) {
                // Keep them out of the main app until verified
                _currentUser.value = null
            } else {
                _currentUser.value = user
            }
        } else {
            _currentUser.value = null
        }
    }

    init {
        val initialUser = auth.currentUser
        if (initialUser != null && initialUser.providerData.any { it.providerId == "password" } && !initialUser.isEmailVerified) {
            _currentUser.value = null
        } else {
            _currentUser.value = initialUser
        }
        auth.addAuthStateListener(authStateListener)
    }

    /** Called immediately after sign-in succeeds — bypasses AuthStateListener delay. */
    fun onUserSignedIn(user: FirebaseUser) {
        _currentUser.value = user
    }

    fun signOut() {
        auth.signOut()
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authStateListener)
        super.onCleared()
    }
}
