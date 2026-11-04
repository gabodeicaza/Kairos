package kairos.proyecto.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kairos.proyecto.R
import kairos.proyecto.data.local.LocalSettings
import kairos.proyecto.domain.model.Alert
import kairos.proyecto.domain.decision.nextAction
import kairos.proyecto.domain.model.Checklist
import kairos.proyecto.domain.notification.NotificationKind
import kairos.proyecto.domain.notification.NotificationPolicy
import kairos.proyecto.domain.notification.SendRecord
import java.util.Calendar

/**
 * Aplica [NotificationPolicy] (tope de 3 diarias en alertas, 1 semanal, tope configurable, horario de silencio)
 * y muestra notificaciones personalizadas y transparentes: cada texto explica por qué se envía.
 *
 * Integración push pendiente: un FirebaseMessagingService (requiere google-services.json y la dependencia
 * firebase-messaging) debe llamar a [notifyAlert] al recibir un mensaje del backend. Mientras tanto, la app
 * la invoca localmente al refrescar las alertas.
 */
class NotificationCoordinator(
    private val context: Context,
    private val settings: LocalSettings,
    private val policy: NotificationPolicy = NotificationPolicy(),
) {
    fun notifyAlert(alert: Alert, checklist: Checklist, postalCode: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val top = checklist.nextAction(settings.completedSteps, nowMillis)
        val hoursToImpact = alert.expectedImpactMillis?.let { (it - nowMillis) / 3_600_000 }?.takeIf { it > 0 }
        val text = buildString {
            if (alert.simulated) append("[SIMULADO] ")
            append(alert.headline.removePrefix("[SIMULADO] "))
            if (hoursToImpact != null) append(". Impacto estimado en ~$hoursToImpact h")
            if (top != null) append(". Tu siguiente acción (≈${top.estimatedMinutes} min): ${top.title}.")
            append(" Te avisamos porque tu código postal $postalCode está en la zona. Puedes limitar los avisos en Ajustes.")
        }
        return send(NotificationKind.ALERT, alert.severity, "Alerta ${alert.severity.label}: ${alert.hazard.label}", text, nowMillis, alert.id.hashCode())
    }

    fun notifyWeeklyPreparedness(postalCode: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        send(
            NotificationKind.WEEKLY_PREPAREDNESS, null, "Tu preparación semanal",
            "Dedica 10 minutos a revisar tu kit y tu ruta de evacuación en el código postal $postalCode. " +
                "Es tu aviso semanal; puedes desactivarlo en Ajustes.",
            nowMillis, WEEKLY_ID,
        )

    private fun send(kind: NotificationKind, severity: kairos.proyecto.domain.model.Severity?, title: String, text: String, nowMillis: Long, id: Int): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val dayStart = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val history = settings.sendHistory
        val allowed = policy.canSend(kind, severity, settings.notificationPrefs, history, nowMillis, cal.get(Calendar.HOUR_OF_DAY), dayStart)
        if (!allowed || !hasPermission()) return false

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Alertas y preparación", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
        )
        // Conserva solo la última semana de historial.
        settings.sendHistory = (history + SendRecord(kind, nowMillis)).filter { nowMillis - it.epochMillis < 7L * 24 * 3600 * 1000 }
        return true
    }

    private fun hasPermission() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CHANNEL_ID = "alerts"
        const val WEEKLY_ID = 1001
    }
}
