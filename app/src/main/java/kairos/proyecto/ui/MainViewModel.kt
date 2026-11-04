package kairos.proyecto.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kairos.proyecto.data.privacy.SecureStore
import kairos.proyecto.di.AppContainer
import kairos.proyecto.domain.business.MicroinsuranceOffer
import kairos.proyecto.domain.decision.completedCount
import kairos.proyecto.domain.decision.isCompleted
import kairos.proyecto.domain.decision.stepKey
import kairos.proyecto.domain.model.Alert
import kairos.proyecto.domain.model.Checklist
import kairos.proyecto.domain.model.HouseholdProfile
import kairos.proyecto.domain.model.Regions
import kairos.proyecto.domain.notification.NotificationPrefs
import kairos.proyecto.domain.privacy.LocationMode
import kairos.proyecto.domain.privacy.PrivacyConsent
import kairos.proyecto.domain.telemetry.InteractionEvent
import kairos.proyecto.domain.telemetry.InteractionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val PRIVACY_NOTICE_VERSION = "2026-10-borrador"

data class AlertWithChecklist(val alert: Alert, val checklist: Checklist)

data class UiState(
    val onboarded: Boolean = false,
    val postalCode: String = "",
    val colonia: String = "",
    val zoneFound: Boolean = true,
    /** Colonia y región de la zona del catálogo, p. ej. "Centro · Suroeste (Acapulco, Guerrero)". */
    val zoneLabel: String = "",
    val profile: HouseholdProfile = HouseholdProfile(),
    val consent: PrivacyConsent = PrivacyConsent(),
    val notificationPrefs: NotificationPrefs = NotificationPrefs(),
    /** Todos los checklists de alertas activas que afectan al hogar. */
    val items: List<AlertWithChecklist> = emptyList(),
    /** Microseguro: opción separada de los checklists. */
    val insurance: MicroinsuranceOffer? = null,
    /** Si aún no hay oferta pero el escenario califica: pasos que faltan para desbloquearla. */
    val insuranceStepsLeft: Int? = null,
    /** Pasos completados (claves `alertId:stepId`). */
    val completed: Set<String> = emptySet(),
    /** Reloj con el que se calculó el estado; define qué fases están desbloqueadas. */
    val nowMillis: Long = 0,
    val simulatedHours: Double = 0.0,
    val localEvents: Int = 0,
    val reportsSent: Int = 0,
    val loading: Boolean = false,
)

class MainViewModel(private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        val postal = c.secureStore.get(SecureStore.POSTAL_CODE)
        if (postal != null) {
            _state.update {
                it.copy(
                    onboarded = true, postalCode = postal,
                    colonia = c.secureStore.get(SecureStore.COLONIA).orEmpty(),
                    profile = decodeProfile(c.secureStore.get(SecureStore.HOUSEHOLD_PROFILE)),
                )
            }
        }
        _state.update { it.copy(consent = c.settings.consent, notificationPrefs = c.settings.notificationPrefs) }
        if (postal != null) refresh()
    }

    fun completeOnboarding(
        postalCode: String, colonia: String, address: String,
        profile: HouseholdProfile, mode: LocationMode, gpsConsent: Boolean, shareBehavior: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val consent = c.locationPolicy.withMode(
            PrivacyConsent(privacyNoticeVersion = PRIVACY_NOTICE_VERSION, behaviorSharingOptIn = shareBehavior), mode, gpsConsent, now,
        )
        c.settings.consent = consent
        c.secureStore.put(SecureStore.POSTAL_CODE, postalCode.trim())
        c.secureStore.put(SecureStore.COLONIA, colonia.trim().ifEmpty { null })
        c.secureStore.put(SecureStore.HOME_ADDRESS, address.trim().ifEmpty { null })
        c.secureStore.put(SecureStore.HOUSEHOLD_PROFILE, encodeProfile(profile))
        _state.update { it.copy(onboarded = true, postalCode = postalCode.trim(), colonia = colonia.trim(), profile = profile, consent = consent) }
        viewModelScope.launch { c.syncAdapter.pushProfile(postalCode.trim(), profile) }
        refresh()
    }


    /** Recalcula alertas, checklists y fases. La UI lo invoca cada minuto para desbloquear fases con el tiempo. */
    fun refresh() {
        val s = _state.value
        val zone = c.riskClassifier.findZone(s.postalCode, s.colonia.ifEmpty { null })
        if (zone == null) { _state.update { it.copy(zoneFound = false, items = emptyList(), insurance = null, insuranceStepsLeft = null) }; return }
        val regionName = Regions.PILOT.firstOrNull { it.id == zone.regionId }?.name.orEmpty()
        _state.update { it.copy(loading = true, zoneFound = true, zoneLabel = "${zone.colonia} · $regionName") }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val alerts = c.alertRepository.refresh(zone.regionId, now).filter { it.affects(s.postalCode) }
            val items = alerts.map { a ->
                AlertWithChecklist(a, c.checklistGenerator.generate(s.profile, a, c.riskClassifier.classify(zone, a.hazard)))
            }.sortedByDescending { it.alert.severity }

            // Una escalada (misma alerta, mayor severidad) vuelve a notificar; la política limita el volumen.
            val notified = c.settings.notifiedAlertIds
            items.filter { "${it.alert.id}:${it.alert.severity}" !in notified }.forEach { (alert, checklist) ->
                c.notifications.notifyAlert(alert, checklist, s.postalCode, now)
            }
            c.settings.notifiedAlertIds = items.map { "${it.alert.id}:${it.alert.severity}" }.toSet()

            val completed = c.settings.completedSteps
            items.forEach { c.telemetry.flushDaily(it.alert, it.checklist, completed, s.profile, s.postalCode, now) }
            _state.update {
                it.copy(
                    items = items, completed = completed, nowMillis = now,
                    insurance = insuranceFor(items, completed), insuranceStepsLeft = stepsLeft(items, completed),
                    simulatedHours = (now - c.settings.scenarioStartMillis) / 3_600_000.0,
                    localEvents = c.telemetry.localEventCount(), reportsSent = c.telemetryUploader.sentCount(), loading = false,
                )
            }
        }
    }

    /** El microseguro se calcula sobre la alerta más grave, solo si su checklist está completo, y se muestra aparte. */
    private fun insuranceFor(items: List<AlertWithChecklist>, completed: Set<String>) =
        items.firstOrNull()?.let {
            c.microinsuranceAdvisor.offerFor(it.checklist.zoneRisk, it.alert.severity, it.checklist.isCompleted(completed))
        }

    private fun stepsLeft(items: List<AlertWithChecklist>, completed: Set<String>): Int? {
        val top = items.firstOrNull() ?: return null
        if (!c.microinsuranceAdvisor.scenarioQualifies(top.checklist.zoneRisk, top.alert.severity)) return null
        val left = top.checklist.steps.size - top.checklist.completedCount(completed)
        return left.takeIf { it > 0 }
    }

    private fun logEvent(type: InteractionType, item: AlertWithChecklist, stepId: String) {
        val step = item.checklist.steps.firstOrNull { it.id == stepId } ?: return
        c.telemetry.record(
            InteractionEvent(type, item.alert.id, item.alert.hazard, item.alert.severity, stepId, step.phase,
                System.currentTimeMillis(), item.alert.issuedAtMillis)
        )
    }

    fun toggleStep(alertId: String, stepId: String) {
        val key = stepKey(alertId, stepId)
        val s = _state.value
        val nowDone = key !in s.completed
        val completed = if (nowDone) s.completed + key else s.completed - key
        c.settings.completedSteps = completed
        val item = s.items.firstOrNull { it.alert.id == alertId }
        item?.let { logEvent(if (nowDone) InteractionType.STEP_COMPLETED else InteractionType.STEP_UNCOMPLETED, it, stepId) }
        _state.update {
            it.copy(
                completed = completed, insurance = insuranceFor(it.items, completed), insuranceStepsLeft = stepsLeft(it.items, completed),
                localEvents = c.telemetry.localEventCount(),
            )
        }
        item?.let { viewModelScope.launch { c.syncAdapter.pushChecklistProgress(it.checklist, completed) } }
    }

    /** Clic en un producto afiliado (enlace SIMULADO): solo se registra la interacción. */
    fun onProductClick(alertId: String, stepId: String) {
        _state.value.items.firstOrNull { it.alert.id == alertId }?.let { logEvent(InteractionType.PRODUCT_CLICKED, it, stepId) }
        _state.update { it.copy(localEvents = c.telemetry.localEventCount()) }
    }

    fun updateNotificationPrefs(prefs: NotificationPrefs) {
        c.settings.notificationPrefs = prefs
        _state.update { it.copy(notificationPrefs = c.settings.notificationPrefs) }
    }

    fun setLocationMode(mode: LocationMode, explicitConsent: Boolean) {
        val consent = c.locationPolicy.withMode(_state.value.consent, mode, explicitConsent, System.currentTimeMillis())
        c.settings.consent = consent
        _state.update { it.copy(consent = consent) }
    }

    fun setBehaviorSharing(optIn: Boolean) {
        val consent = _state.value.consent.copy(behaviorSharingOptIn = optIn)
        c.settings.consent = consent
        _state.update { it.copy(consent = consent) }
    }

    /** Demo del piloto: adelanta el reloj del escenario simulado para ver la escalada y el desbloqueo de fases. */
    fun advanceSimulation(hours: Int) {
        c.settings.scenarioStartMillis -= hours * 3_600_000L
        refresh()
    }

    fun resetSimulation() {
        c.settings.scenarioStartMillis = System.currentTimeMillis()
        c.settings.completedSteps = emptySet()
        c.settings.notifiedAlertIds = emptySet()
        refresh()
    }

    /** Derecho de cancelación (ARCO): borra todos los datos personales y de comportamiento del dispositivo. */
    fun deleteAllData() {
        c.secureStore.wipe(); c.settings.wipe(); c.telemetry.wipe(); c.telemetryUploader.wipe()
        _state.value = UiState()
    }

    private fun encodeProfile(p: HouseholdProfile) = "${p.groundFloor},${p.hasChildren},${p.hasElderly},${p.hasPets}"
    private fun decodeProfile(s: String?): HouseholdProfile {
        val v = s?.split(',')?.map { it.toBoolean() } ?: return HouseholdProfile()
        return when (v.size) {
            3 -> HouseholdProfile(v[0], v[1], v[2])
            4 -> HouseholdProfile(v[0], v[1], v[2], v[3])
            else -> HouseholdProfile()
        }
    }

    class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(c) as T
    }
}

val pilotRegionNames: String get() = Regions.PILOT.joinToString(" · ") { it.name }
