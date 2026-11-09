package com.baonplan.finance

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baonplan.finance.security.AppLockHelper
import com.baonplan.finance.ui.BaonPlanRoot
import com.baonplan.finance.ui.BaonPlanViewModel
import com.baonplan.finance.ui.theme.BaonPlanTheme
import com.baonplan.finance.widget.BaonWidgetProvider

class MainActivity : ComponentActivity() {

  private var pendingQuickAction by mutableStateOf<String?>(null)
  private var isUnlocked by mutableStateOf(false)
  private var suppressNextRelock = false
  private var currentAppLockEnabled = false
  private var lockMessage by mutableStateOf<String?>(null)
  private var pendingCredentialOnSuccess: (() -> Unit)? = null

  private val credentialLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    val action = pendingCredentialOnSuccess
    pendingCredentialOnSuccess = null
    if (result.resultCode == Activity.RESULT_OK) {
      lockMessage = null
      action?.invoke()
    } else {
      lockMessage = "Verification cancelled"
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    pendingQuickAction = intent?.getStringExtra(BaonWidgetProvider.EXTRA_QUICK_ACTION)

    val app = application as BaonPlanApp
    val factory = BaonPlanViewModel.Factory(app.repository)

    setContent {
      val vm: BaonPlanViewModel = viewModel(factory = factory)
      val uiState by vm.uiState.collectAsStateWithLifecycle()
      currentAppLockEnabled = uiState.preferences.appLockEnabled

      val locked = !uiState.isLoading && uiState.preferences.appLockEnabled && !isUnlocked

      LaunchedEffect(locked) {
        if (locked) {
          if (!AppLockHelper.canUseDeviceLock(this@MainActivity)) {
            lockMessage = null
            isUnlocked = true
            vm.setAppLockEnabled(false)
            vm.emitMessage("No device PIN/biometric set — App lock disabled")
          } else {
            requestDeviceAuth(
              title = "Unlock BaonPlan",
              subtitle = "Verify with fingerprint, face, or device PIN",
              onSuccess = {
                lockMessage = null
                isUnlocked = true
              },
              onError = { msg -> lockMessage = msg }
            )
          }
        }
      }

      BaonPlanTheme(themeMode = uiState.preferences.themeMode) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          when {
            uiState.isLoading -> {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
              }
            }
            locked -> {
              Column(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Icon(
                  imageVector = Icons.Outlined.Lock,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                  text = "BaonPlan is locked",
                  style = MaterialTheme.typography.headlineSmall,
                  color = MaterialTheme.colorScheme.onBackground,
                  textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = "Verify with your device PIN or biometrics to view your budget and debts.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center
                )
                if (!lockMessage.isNullOrBlank()) {
                  Spacer(modifier = Modifier.height(10.dp))
                  Text(
                    text = lockMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                  )
                }
                Spacer(modifier = Modifier.height(22.dp))
                Button(
                  onClick = {
                    lockMessage = null
                    requestDeviceAuth(
                      title = "Unlock BaonPlan",
                      subtitle = "Verify with fingerprint, face, or device PIN",
                      onSuccess = {
                        lockMessage = null
                        isUnlocked = true
                      },
                      onError = { msg -> lockMessage = msg }
                    )
                  },
                  shape = RoundedCornerShape(12.dp)
                ) {
                  Text("Unlock")
                }
              }
            }
            else -> {
              BaonPlanRoot(
                viewModel = vm,
                pendingQuickAction = pendingQuickAction,
                onConsumeQuickAction = { pendingQuickAction = null },
                onSuppressNextRelock = { suppressNextRelock = true },
                onToggleAppLock = { enable ->
                  if (!enable) {
                    isUnlocked = true
                    lockMessage = null
                    vm.setAppLockEnabled(false)
                  } else {
                    requestDeviceAuth(
                      title = "Enable App Lock",
                      subtitle = "Verify with fingerprint or device PIN",
                      onSuccess = {
                        isUnlocked = true
                        lockMessage = null
                        vm.setAppLockEnabled(true)
                      },
                      onError = { msg ->
                        if (!msg.isNullOrBlank()) {
                          vm.emitMessage(msg)
                        }
                      }
                    )
                  }
                }
              )
            }
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.getStringExtra(BaonWidgetProvider.EXTRA_QUICK_ACTION)?.let { action ->
      pendingQuickAction = action
    }
  }

  override fun onResume() {
    super.onResume()
    suppressNextRelock = false
  }

  override fun onStop() {
    super.onStop()
    if (!isChangingConfigurations && !suppressNextRelock && currentAppLockEnabled) {
      isUnlocked = false
    }
  }

  private fun requestDeviceAuth(
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onError: (String?) -> Unit
  ) {
    AppLockHelper.authenticate(
      activity = this,
      title = title,
      subtitle = subtitle,
      onSuccess = onSuccess,
      onFallbackCredentialIntent = { credIntent ->
        suppressNextRelock = true
        pendingCredentialOnSuccess = onSuccess
        credentialLauncher.launch(credIntent)
      },
      onFailureOrCancel = onError
    )
  }
}
