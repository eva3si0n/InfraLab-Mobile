package com.eva3si0n.infralab

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eva3si0n.infralab.ui.AppNavigation
import com.eva3si0n.infralab.ui.AppViewModel
import com.eva3si0n.infralab.ui.theme.InfraLabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App is always dark → force light icons on transparent system bars,
        // otherwise the status-bar icons render dark-on-dark and disappear.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            InfraLabTheme {
                val vm: AppViewModel = viewModel()

                // Initial load + foreground auto-refresh
                LaunchedEffect(Unit) {
                    vm.refreshAll()
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        vm.startAutoRefresh()
                        try {
                            kotlinx.coroutines.awaitCancellation()
                        } finally {
                            vm.stopAutoRefresh()
                        }
                    }
                }

                AppNavigation(vm)
            }
        }
    }
}
