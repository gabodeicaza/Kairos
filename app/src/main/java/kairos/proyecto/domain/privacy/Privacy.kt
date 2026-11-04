package kairos.proyecto.domain.privacy

import kairos.proyecto.domain.business.AnonymizedIndicator
import kairos.proyecto.domain.model.HouseholdProfile

/** Modos de ubicación del hogar. El valor por defecto es el menos invasivo. */
enum class LocationMode {
    /** Código postal / colonia. Por defecto; no requiere permiso de ubicación. */
    APPROXIMATE,
    /** GPS exacto. Solo con consentimiento explícito y registrado. */
    EXACT_GPS,
    /** Ubicación capturada únicamente mientras hay una alerta activa. */
    ONLY_DURING_ALERT,
}

data class PrivacyConsent(
    val locationMode: LocationMode = LocationMode.APPROXIMATE,
    val exactGpsConsentAtMillis: Long? = null,
    /** Versión del aviso de privacidad aceptado (LFPDPPP: el consentimiento se liga al aviso). */
    val privacyNoticeVersion: String? = null,
    /** Opt-in (apagado por defecto) para enviar reportes de comportamiento ya anonimizados. */
    val behaviorSharingOptIn: Boolean = false,
)

class LocationPolicy {

    /** ¿Se puede capturar ubicación del dispositivo en este momento? */
    fun canCaptureDeviceLocation(consent: PrivacyConsent, hasActiveAlert: Boolean): Boolean =
        consent.privacyNoticeVersion != null && when (consent.locationMode) {
            LocationMode.APPROXIMATE -> false
            LocationMode.EXACT_GPS -> consent.exactGpsConsentAtMillis != null
            LocationMode.ONLY_DURING_ALERT -> hasActiveAlert
        }

    /** Cambiar a GPS exacto exige consentimiento explícito; sin él se conserva el modo actual. */
    fun withMode(current: PrivacyConsent, mode: LocationMode, explicitConsent: Boolean, nowMillis: Long): PrivacyConsent =
        when {
            mode == LocationMode.EXACT_GPS && !explicitConsent -> current
            mode == LocationMode.EXACT_GPS -> current.copy(locationMode = mode, exactGpsConsentAtMillis = nowMillis)
            else -> current.copy(locationMode = mode, exactGpsConsentAtMillis = null)
        }
}

/** Hogar ya despojado de identificadores, listo para agregarse. */
data class AnonymousHousehold(val regionId: String, val postalCode: String, val profile: HouseholdProfile)

/**
 * Agrega hogares en indicadores por código postal. Suprime grupos menores a [minGroupSize]
 * (k-anonimato) para que ningún indicador permita reidentificar un hogar.
 */
class Anonymizer(private val minGroupSize: Int = 10) {
    fun aggregate(households: List<AnonymousHousehold>): List<AnonymizedIndicator> =
        households.groupBy { it.regionId to it.postalCode }
            .filter { it.value.size >= minGroupSize }
            .map { (key, group) ->
                val n = group.size.toDouble()
                AnonymizedIndicator(
                    regionId = key.first,
                    postalCode = key.second,
                    households = group.size,
                    groundFloorShare = group.count { it.profile.groundFloor } / n,
                    withChildrenShare = group.count { it.profile.hasChildren } / n,
                    withElderlyShare = group.count { it.profile.hasElderly } / n,
                    withPetsShare = group.count { it.profile.hasPets } / n,
                )
            }
}
