package kairos.proyecto.domain.notification

import kairos.proyecto.domain.model.Severity

enum class NotificationKind { ALERT, WEEKLY_PREPAREDNESS }

data class NotificationPrefs(
    /** Tope diario durante alertas; configurable por la persona usuaria entre 0 y [MAX_ALERTS_PER_DAY]. */
    val maxAlertsPerDay: Int = MAX_ALERTS_PER_DAY,
    val weeklyPreparednessEnabled: Boolean = true,
    /** Horario de silencio (hora local, [inicio, fin) cruzando medianoche). Las alertas ROJAS lo ignoran. */
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 7,
) {
    companion object {
        const val MAX_ALERTS_PER_DAY = 3
    }
}

/** Historial mínimo de envíos para aplicar los límites. */
data class SendRecord(val kind: NotificationKind, val epochMillis: Long)

class NotificationPolicy {

    fun canSend(
        kind: NotificationKind,
        severity: Severity?,
        prefs: NotificationPrefs,
        history: List<SendRecord>,
        nowMillis: Long,
        localHour: Int,
        localDayStartMillis: Long,
    ): Boolean = when (kind) {
        NotificationKind.ALERT -> {
            val cap = prefs.maxAlertsPerDay.coerceIn(0, NotificationPrefs.MAX_ALERTS_PER_DAY)
            val sentToday = history.count { it.kind == kind && it.epochMillis >= localDayStartMillis }
            // Las rojas rompen el horario de silencio, pero nunca el tope diario.
            sentToday < cap && (severity == Severity.RED || !inQuietHours(prefs, localHour))
        }
        NotificationKind.WEEKLY_PREPAREDNESS -> {
            val sentThisWeek = history.any { it.kind == kind && nowMillis - it.epochMillis < WEEK_MILLIS }
            prefs.weeklyPreparednessEnabled && !sentThisWeek && !inQuietHours(prefs, localHour)
        }
    }

    private fun inQuietHours(p: NotificationPrefs, hour: Int) =
        if (p.quietStartHour <= p.quietEndHour) hour in p.quietStartHour until p.quietEndHour
        else hour >= p.quietStartHour || hour < p.quietEndHour

    private companion object {
        const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
