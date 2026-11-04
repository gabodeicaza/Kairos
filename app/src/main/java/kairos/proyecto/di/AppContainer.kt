package kairos.proyecto.di

import android.app.Application
import android.content.Context
import kairos.proyecto.data.alerts.AlertRepository
import kairos.proyecto.data.alerts.NasaEonetSource
import kairos.proyecto.data.alerts.SimulatedCivilProtectionGuerreroSource
import kairos.proyecto.data.alerts.SimulatedSmnSource
import kairos.proyecto.data.catalog.StaticZoneCatalog
import kairos.proyecto.data.local.LocalSettings
import kairos.proyecto.data.privacy.SecureStore
import kairos.proyecto.data.sync.NoOpSyncAdapter
import kairos.proyecto.data.telemetry.DataLogger
import kairos.proyecto.data.telemetry.SimulatedTelemetryUploader
import kairos.proyecto.data.telemetry.TelemetryService
import kairos.proyecto.data.sync.SyncAdapter
import kairos.proyecto.domain.business.MicroinsuranceAdvisor
import kairos.proyecto.domain.decision.ChecklistGenerator
import kairos.proyecto.domain.decision.RuleBasedChecklistEngine
import kairos.proyecto.domain.privacy.LocationPolicy
import kairos.proyecto.domain.risk.RiskClassifier
import kairos.proyecto.domain.risk.ZoneCatalog
import kairos.proyecto.notifications.NotificationCoordinator

/**
 * Inyección de dependencias manual (constructor injection + un único contenedor): suficiente para el esqueleto
 * y sin procesadores de anotaciones. Cada punto de integración externo se cambia aquí.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val zoneCatalog: ZoneCatalog = StaticZoneCatalog()
    val riskClassifier = RiskClassifier(zoneCatalog)

    /** INTEGRACIÓN BACKEND: sustituir por una implementación remota que consuma el motor del servidor. */
    val checklistGenerator: ChecklistGenerator = RuleBasedChecklistEngine()

    /** INTEGRACIÓN FUENTES: reemplazar los adaptadores simulados por los oficiales. */
    val settings = LocalSettings(app)
    val alertRepository = AlertRepository(
        app,
        listOf(
            SimulatedCivilProtectionGuerreroSource { settings.scenarioStartMillis },
            SimulatedSmnSource { settings.scenarioStartMillis },
            NasaEonetSource(), // REAL: eventos abiertos de NASA EONET
        ),
    )

    val secureStore = SecureStore(app)

    /** INTEGRACIÓN BACKEND: sustituir [telemetryUploader] por el cliente real de la API de telemetría. */
    val telemetryUploader = SimulatedTelemetryUploader(app)
    val telemetry = TelemetryService(DataLogger(app), telemetryUploader, settings)
    val locationPolicy = LocationPolicy()
    val microinsuranceAdvisor = MicroinsuranceAdvisor()
    val notifications = NotificationCoordinator(app, settings)

    /** INTEGRACIÓN BACKEND: sincronización opcional; hoy no hace nada. */
    val syncAdapter: SyncAdapter = NoOpSyncAdapter()
}

class KairosApp : Application() {
    val container by lazy { AppContainer(this) }
}
