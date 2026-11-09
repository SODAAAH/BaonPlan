package com.baonplan.finance.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

object AppLockHelper {

  fun canUseDeviceLock(context: Context): Boolean {
    val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val bm = context.getSystemService(BiometricManager::class.java)
      if (bm != null) {
        val status = bm.canAuthenticate(
          BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (status == BiometricManager.BIOMETRIC_SUCCESS) return true
      }
    }
    return keyguard?.isDeviceSecure == true
  }

  @Suppress("DEPRECATION")
  fun authenticate(
    activity: ComponentActivity,
    title: String = "Unlock BaonPlan",
    subtitle: String = "Verify with fingerprint, face, or device PIN",
    onSuccess: () -> Unit,
    onFallbackCredentialIntent: (Intent) -> Unit,
    onFailureOrCancel: (String?) -> Unit
  ) {
    if (!canUseDeviceLock(activity)) {
      onFailureOrCancel("Set up a device PIN, pattern, or fingerprint in Android Settings first.")
      return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      try {
        val prompt = BiometricPrompt.Builder(activity)
          .setTitle(title)
          .setSubtitle(subtitle)
          .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
          )
          .build()

        prompt.authenticate(
          CancellationSignal(),
          ContextCompat.getMainExecutor(activity),
          object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
              onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
              onFailureOrCancel(errString?.toString())
            }
          }
        )
        return
      } catch (_: Exception) {
        // Fall through to Keyguard credential prompt if BiometricPrompt fails on vendor ROM
      }
    }

    val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val credentialIntent = keyguard?.createConfirmDeviceCredentialIntent(title, subtitle)
    if (credentialIntent != null) {
      onFallbackCredentialIntent(credentialIntent)
    } else {
      onFailureOrCancel("Device lock prompt is unavailable on this device.")
    }
  }
}
