package io.github.evilgenius79.shh.ui

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.evilgenius79.shh.R

/**
 * Shown instead of the app until the user proves they can unlock the device. Uses the system's
 * own prompt (fingerprint, face, or the screen lock as a fallback), so no credential ever passes
 * through this app.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val activity = LocalActivity.current
    var failed by remember { mutableStateOf<String?>(null) }

    // Before Android 10 the system prompt cannot fall back to the screen lock; the keyguard's own
    // confirmation screen does the job there.
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) onUnlocked() else failed = "Not unlocked"
    }

    fun authenticate() {
        val host = activity ?: return
        val keyguard = ContextCompat.getSystemService(host, KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) {
            // The screen lock was removed after the setting was turned on; nothing can be asked.
            onUnlocked()
            return
        }
        failed = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val intent = keyguard.createConfirmDeviceCredentialIntent("Unlock Shh", null)
            if (intent != null) legacyLauncher.launch(intent) else onUnlocked()
            return
        }
        val builder = BiometricPrompt.Builder(host).setTitle("Unlock Shh")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(),
            host.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    failed = errString.toString()
                }
            },
        )
    }

    LaunchedEffect(Unit) { authenticate() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            AppIcon(R.drawable.ic_lock, null, Modifier.size(56.dp), MaterialTheme.colorScheme.primary)
            Text("Shh is locked", style = MaterialTheme.typography.titleMedium)
            failed?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(onClick = ::authenticate) { Text("Unlock") }
        }
    }
}
