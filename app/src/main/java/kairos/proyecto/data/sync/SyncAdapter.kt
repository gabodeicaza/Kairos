package kairos.proyecto.data.sync

import kairos.proyecto.domain.model.Checklist
import kairos.proyecto.domain.model.HouseholdProfile

/**
 * Sincronización opcional con el backend propio, desacoplada del resto de la app.
 * Solo viaja información no identificable salvo consentimiento: nunca la dirección ni coordenadas.
 */
interface SyncAdapter {
    /** Sube el perfil de vulnerabilidad agregable (sin dirección) junto al código postal. */
    suspend fun pushProfile(postalCode: String, profile: HouseholdProfile)

    /** Registra avance del checklist para los indicadores anonimizados del panel. */
    suspend fun pushChecklistProgress(checklist: Checklist, completedStepIds: Set<String>)
}

/** Implementación vigente: no hace nada (la app funciona 100 % offline). Reemplazar al existir el backend. */
class NoOpSyncAdapter : SyncAdapter {
    override suspend fun pushProfile(postalCode: String, profile: HouseholdProfile) = Unit
    override suspend fun pushChecklistProgress(checklist: Checklist, completedStepIds: Set<String>) = Unit
}
