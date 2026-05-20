package com.c2c

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.c2c.ui.screens.MainScreen
import com.c2c.ui.theme.*
import org.webrtc.EglBase

class MainActivity : FragmentActivity() {
    private val viewModel: CoreViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestRequiredPermissions()

        val eglContext = viewModel.webRtcManager.getEglBaseContext()

        setContent { 
            PremiumTheme { 
                AppNavigation(this, viewModel, eglContext) 
            } 
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungrantedPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungrantedPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(ungrantedPermissions.toTypedArray())
        }
    }

    fun promptAuth(onSuccess: () -> Unit) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("System Authentication")
            .setSubtitle("Identity required")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() { 
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { onSuccess() } 
        }).authenticate(promptInfo)
    }
}

@Composable
fun AppNavigation(activity: MainActivity, viewModel: CoreViewModel, eglContext: EglBase.Context) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "login") {
        composable("login") { 
            LoginScreen({ navController.navigate("main") { popUpTo("login") { inclusive = true } } }, activity) 
        }
        composable("main") { 
            MainScreen(viewModel, eglContext) 
        }
    }
}

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, activity: MainActivity) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Fingerprint, "Auth", tint = ActionBlue, modifier = Modifier.size(80.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text("CORE SYSTEM", fontSize = 28.sp, fontWeight = FontWeight.Light, color = TextPrimary, letterSpacing = 2.sp, fontFamily = UbuntuFont)
        Spacer(modifier = Modifier.height(48.dp))
        Button(
            onClick = { activity.promptAuth(onLoginSuccess) }, 
            colors = ButtonDefaults.buttonColors(containerColor = GlassSurface, contentColor = ActionBlue), 
            modifier = Modifier.height(56.dp).fillMaxWidth()
        ) {
            Icon(Icons.Rounded.LockOpen, null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("AUTHENTICATE", fontWeight = FontWeight.Medium, fontFamily = UbuntuFont)
        }
    }
}