package kairos.proyecto.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kairos.proyecto.domain.model.HouseholdProfile
import kairos.proyecto.domain.privacy.LocationMode
import kairos.proyecto.ui.components.KCard
import kairos.proyecto.ui.pilotRegionNames

@Composable
fun OnboardingScreen(
    onDone: (
        postal: String, colonia: String, address: String, HouseholdProfile,
        LocationMode, gpsConsent: Boolean, shareBehavior: Boolean,
    ) -> Unit,
) {
    var postal by remember { mutableStateOf("") }
    var colonia by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var ground by remember { mutableStateOf(false) }
    var kids by remember { mutableStateOf(false) }
    var elders by remember { mutableStateOf(false) }
    var pets by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(LocationMode.APPROXIMATE) }
    var gpsConsent by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Bienvenida/o a Kairos", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Te diremos qué hacer, paso a paso y a tiempo. Piloto: $pilotRegionNames",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        KCard {
            Text("Tu zona", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(postal, { postal = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), label = { Text("Código postal") }, singleLine = true)
            OutlinedTextField(colonia, { colonia = it }, Modifier.fillMaxWidth(), label = { Text("Colonia (opcional)") }, singleLine = true)
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Dirección (opcional, se guarda cifrada)") })
        }

        KCard {
            Text("Tu hogar", style = MaterialTheme.typography.titleMedium)
            LabeledCheck("Vivo en planta baja", ground) { ground = it }
            LabeledCheck("Hay niñas o niños", kids) { kids = it }
            LabeledCheck("Hay personas adultas mayores", elders) { elders = it }
            LabeledCheck("Tengo mascotas", pets) { pets = it }
        }

        KCard {
            Text("Ubicación", style = MaterialTheme.typography.titleMedium)
            LocationModePicker(mode, { mode = it }, gpsConsent) { gpsConsent = it }
        }

        KCard {
            Text("Privacidad", style = MaterialTheme.typography.titleMedium)
            Text(
                "Aviso de privacidad (borrador): usamos tu código postal, el perfil de tu hogar y, si lo das, tu dirección, " +
                    "solo para personalizar alertas y checklists. Se guardan cifrados en tu teléfono. Puedes borrarlos en Ajustes.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LabeledCheck("Acepto el aviso de privacidad", accepted) { accepted = it }
            LabeledCheck("Opcional: compartir estadísticas anónimas de uso de mi checklist", share) { share = it }
            Text(
                "Se anonimizan en tu teléfono (sin dirección, sin código postal completo y sin identificadores). Puedes cambiarlo cuando quieras.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            enabled = accepted && postal.length == 5 && (mode != LocationMode.EXACT_GPS || gpsConsent),
            onClick = { onDone(postal, colonia, address, HouseholdProfile(ground, kids, elders, pets), mode, gpsConsent, share) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continuar") }
    }
}
