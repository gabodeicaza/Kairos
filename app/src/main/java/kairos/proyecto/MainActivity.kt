package kairos.proyecto

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import kairos.proyecto.di.KairosApp
import kairos.proyecto.domain.model.StepCategory
import kairos.proyecto.ui.MainViewModel
import kairos.proyecto.ui.screens.AlertsScreen
import kairos.proyecto.ui.screens.DashboardScreen
import kairos.proyecto.ui.screens.OnboardingScreen
import kairos.proyecto.ui.screens.PlanScreen
import kairos.proyecto.ui.screens.SettingsScreen
import kairos.proyecto.ui.theme.KairosTheme

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Inicio", Icons.Filled.Home),
    PLAN("Plan", Icons.Filled.CheckCircle),
    ALERTS("Alertas", Icons.Filled.Notifications),
    SETTINGS("Ajustes", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* sin permiso, la política simplemente no envía */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val container = (application as KairosApp).container
        setContent {
            KairosTheme {
                val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(container))
                val state by vm.state.collectAsState()
                var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
                var planFilter by remember { mutableStateOf<StepCategory?>(null) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        // Sin barra durante el registro: la persona aún no tiene tablero.
                        if (state.onboarded) {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                Tab.entries.forEach { t ->
                                    NavigationBarItem(
                                        selected = tab == t,
                                        onClick = { tab = t },
                                        icon = { Icon(t.icon, contentDescription = t.label) },
                                        label = { Text(t.label) },
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        when {
                            !state.onboarded -> OnboardingScreen(vm::completeOnboarding)
                            tab == Tab.HOME -> DashboardScreen(
                                state, vm::refresh, vm::toggleStep,
                                onOpenPlan = { planFilter = it; tab = Tab.PLAN },
                                onOpenAlerts = { tab = Tab.ALERTS },
                            )
                            tab == Tab.PLAN -> PlanScreen(state, planFilter, { planFilter = it }, vm::toggleStep, vm::onProductClick)
                            tab == Tab.ALERTS -> AlertsScreen(state)
                            else -> SettingsScreen(
                                state, vm::updateNotificationPrefs, vm::setLocationMode, vm::setBehaviorSharing,
                                vm::advanceSimulation, vm::resetSimulation, vm::deleteAllData,
                            )
                        }
                    }
                }
            }
        }
    }
}
