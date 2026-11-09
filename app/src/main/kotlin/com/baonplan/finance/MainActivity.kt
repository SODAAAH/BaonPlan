package com.baonplan.finance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baonplan.finance.ui.BaonPlanRoot
import com.baonplan.finance.ui.BaonPlanViewModel
import com.baonplan.finance.ui.theme.BaonPlanTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val app = application as BaonPlanApp
    val factory = BaonPlanViewModel.Factory(app.repository)

    setContent {
      val vm: BaonPlanViewModel = viewModel(factory = factory)
      val uiState by vm.uiState.collectAsStateWithLifecycle()

      BaonPlanTheme(themeMode = uiState.preferences.themeMode) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          BaonPlanRoot(viewModel = vm)
        }
      }
    }
  }
}
