# Privacidad y datos recopilados (borrador, LFPDPPP)

> Borrador técnico. Requiere revisión legal antes de publicarse como aviso de privacidad.

| Dato | Para qué | Dónde se guarda | Sale del dispositivo |
|---|---|---|---|
| Código postal / colonia | Clasificar el riesgo de la zona (catálogo en el dispositivo) y filtrar alertas | `SecureStore` (cifrado AES-256-GCM, Android Keystore) | No (la sincronización está desactivada) |
| Dirección del hogar (opcional) | Personalización futura y apoyo en emergencias | `SecureStore`, cifrada | No |
| Perfil del hogar (planta baja, niños, adultos mayores, mascotas) | Generar el checklist personalizado | `SecureStore`, cifrado | Solo agregado y anonimizado, cuando exista backend |
| Coordenadas GPS | Solo si la persona elige GPS exacto (consentimiento explícito) o durante una alerta activa | No se capturan en este esqueleto; `LocationPolicy` define cuándo se permitiría | No |
| Eventos de interacción con checklists (paso completado/desmarcado, clic en producto, con hora) | Medir pasos completados, ignorados y tiempo de respuesta tras la alerta | `DataLogger`, solo en el dispositivo (últimos 500) | No |
| Reporte de comportamiento anonimizado (uno por alerta y día) | Inteligencia de comportamiento para tableros B2G/B2B | Se genera en el dispositivo | Solo con opt-in (apagado por defecto); hoy el envío es SIMULADO |
| Preferencias de notificación, consentimientos, historial de envíos | Respetar límites y demostrar consentimiento | `LocalSettings` (sin datos identificables) | No |

## Telemetría de comportamiento (DaaS)
- Los eventos crudos nunca salen del teléfono. El `BehaviorReport` no incluye identificador de persona o dispositivo, dirección, código postal completo (solo 3 dígitos), ids de alerta o paso, ni horas exactas (solo el día y un rango de tiempo de respuesta). Su `reportId` es aleatorio y distinto en cada envío.
- Requiere opt-in explícito, separado del aviso de privacidad, y se puede apagar en Ajustes. "Borrar todos mis datos" elimina también los eventos y el historial de envíos.
- El servidor debe aplicar además supresión de grupos pequeños (k-anonimato) antes de mostrar indicadores a Protección Civil o a la aseguradora.
- Los clics en productos afiliados se registran como evento, nunca con datos del producto comprado ni de la persona.

## Principios aplicados
- **Finalidad y minimización:** solo se piden datos necesarios; dirección y colonia son opcionales; ubicación aproximada por defecto.
- **Consentimiento:** el aviso se acepta en el registro y se guarda su versión (`PrivacyConsent.privacyNoticeVersion`); GPS exacto exige consentimiento aparte con fecha.
- **Derechos ARCO:** *Cancelación* implementada (Ajustes → "Borrar todos mis datos"). Acceso, rectificación (se corrige repitiendo el registro) y oposición (límites de notificación) parcialmente cubiertos; falta flujo formal ante el responsable.
- **Anonimización:** `Anonymizer` agrega por código postal y suprime grupos con menos de 10 hogares (k-anonimato). El panel B2B/B2G solo recibe `AnonymizedIndicator`.
- **Sin respaldos:** `allowBackup=false`; la clave del Keystore no es transferible.
- **Transparencia:** cada notificación explica por qué se envía y cómo limitarla.

## Pendiente para producción
Aviso de privacidad definitivo (integral y simplificado), designación del responsable/contacto ARCO, contratos con encargados y aseguradoras (transferencias), cifrado en tránsito y retención de datos en backend, y evaluación de impacto.
