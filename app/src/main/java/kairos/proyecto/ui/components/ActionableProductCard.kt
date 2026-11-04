package kairos.proyecto.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kairos.proyecto.domain.model.ProductSuggestion

/**
 * Compra opcional ligada a un paso que requiere infraestructura física. Se dibuja aparte de la acción de
 * supervivencia (borde, etiqueta "Opcional" y aviso de afiliado) para que nunca parezca parte obligatoria del paso.
 */
@Composable
fun ActionableProductCard(product: ProductSuggestion, onOpenLink: () -> Unit, modifier: Modifier = Modifier) {
    var opened by remember { mutableStateOf(false) }
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("OPCIONAL · Compra con proveedor afiliado (SIMULADO)", style = MaterialTheme.typography.labelSmall)
            Text(product.name, style = MaterialTheme.typography.titleSmall)
            Text("${product.provider} · $${product.priceMxn.toInt()} MXN", style = MaterialTheme.typography.bodySmall)
            Text(
                "Puedes cumplir este paso sin comprar nada. Si compras por este enlace, la app recibe una comisión.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { opened = true; onOpenLink() }) { Text("Ver con ${product.provider}") }
            if (opened) Text("Enlace simulado registrado; no se abrió ningún sitio real.", style = MaterialTheme.typography.labelSmall)
        }
    }
}
