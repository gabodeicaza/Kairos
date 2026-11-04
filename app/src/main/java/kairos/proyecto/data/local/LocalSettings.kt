package kairos.proyecto.data.local

import android.content.Context
import kairos.proyecto.domain.notification.NotificationKind
import kairos.proyecto.domain.notification.NotificationPrefs
import kairos.proyecto.domain.notification.SendRecord
import kairos.proyecto.domain.privacy.LocationMode
import kairos.proyecto.domain.privacy.PrivacyConsent

/**
 * Preferencias NO identificables (límites de notificación, consentimientos, historial de envíos).
 * Los datos personales y de ubicación del hogar viven en [kairos.proyecto.data.privacy.SecureStore].
 */
class LocalSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var notificationPrefs: NotificationPrefs
        get() = NotificationPrefs(
            maxAlertsPerDay = prefs.getInt("max_alerts", NotificationPrefs.MAX_ALERTS_PER_DAY),
            weeklyPreparednessEnabled = prefs.getBoolean("weekly", true),
        )
        set(v) = prefs.edit()
            .putInt("max_alerts", v.maxAlertsPerDay.coerceIn(0, NotificationPrefs.MAX_ALERTS_PER_DAY))
            .putBoolean("weekly", v.weeklyPreparednessEnabled).apply()

    var consent: PrivacyConsent
        get() = PrivacyConsent(
            locationMode = prefs.getString("loc_mode", null)?.let { runCatching { LocationMode.valueOf(it) }.getOrNull() }
                ?: LocationMode.APPROXIMATE,
            exactGpsConsentAtMillis = prefs.getLong("gps_consent", -1).takeIf { it >= 0 },
            privacyNoticeVersion = prefs.getString("notice_version", null),
            behaviorSharingOptIn = prefs.getBoolean("share_behavior", false),
        )
        set(v) = prefs.edit()
            .putString("loc_mode", v.locationMode.name)
            .putLong("gps_consent", v.exactGpsConsentAtMillis ?: -1)
            .putString("notice_version", v.privacyNoticeVersion)
            .putBoolean("share_behavior", v.behaviorSharingOptIn).apply()

    var sendHistory: List<SendRecord>
        get() = prefs.getString("history", "").orEmpty().split(',').mapNotNull { item ->
            val (k, t) = item.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            SendRecord(runCatching { NotificationKind.valueOf(k) }.getOrNull() ?: return@mapNotNull null, t.toLongOrNull() ?: return@mapNotNull null)
        }
        set(v) = prefs.edit().putString("history", v.joinToString(",") { "${it.kind.name}:${it.epochMillis}" }).apply()

    /** Alertas ya notificadas, para no repetir el aviso en cada refresco. */
    var notifiedAlertIds: Set<String>
        get() = prefs.getStringSet("notified", emptySet()).orEmpty()
        set(v) = prefs.edit().putStringSet("notified", v).apply()

    /** Pasos completados, como claves `alertId:stepId` (ver [kairos.proyecto.domain.decision.stepKey]). */
    var completedSteps: Set<String>
        get() = prefs.getStringSet("completed", emptySet()).orEmpty()
        set(v) = prefs.edit().putStringSet("completed", v).apply()

    /** Inicio del escenario simulado (ancla de todas las alertas simuladas). Se fija en el primer acceso. */
    var scenarioStartMillis: Long
        get() = prefs.getLong("scenario_start", -1).takeIf { it >= 0 } ?: System.currentTimeMillis().also { scenarioStartMillis = it }
        set(v) = prefs.edit().putLong("scenario_start", v).apply()

    /** Reportes de telemetría ya enviados, como `alertId:díaEpoch`, para enviar uno por alerta y día. */
    var reportedKeys: Set<String>
        get() = prefs.getStringSet("reported", emptySet()).orEmpty()
        set(v) = prefs.edit().putStringSet("reported", v).apply()

    fun wipe() = prefs.edit().clear().apply()
}
