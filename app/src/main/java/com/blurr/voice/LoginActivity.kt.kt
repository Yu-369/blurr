package com.blurr.voice

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button // Changed from SignInButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.blurr.voice.utilities.FreemiumManager
import com.blurr.voice.utilities.OnboardingManager
import com.blurr.voice.utilities.UserProfileManager
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.SignInClient
import com.google.android.gms.common.api.ApiException
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    // These variables are no longer used but are kept to avoid breaking class structure.
    private lateinit var oneTapClient: SignInClient
    private lateinit var signInRequest: BeginSignInRequest
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var signInButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var googleSignInLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // --- LOGIN BYPASS ---
        // Instead of initializing Firebase and showing a login button,
        // we will immediately start the main part of the app.
        Log.d("LoginActivity", "Bypassing login and launching MainActivity directly.")
        startActivity(Intent(this, MainActivity::class.java))
        
        // Finish this activity so the user cannot navigate back to it.
        finish()
    }
    
    // The original methods below are no longer called, but we leave them
    // here to avoid any potential compilation issues.

    private fun signIn() {
        // This function is no longer called.
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        // This function is no longer called.
    }
}
