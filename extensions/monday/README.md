# Extensión `monday` (CRM) — importar y enviar

Una sola extensión hace las dos direcciones entre el CRM y monday.com. Versión 1.1.0 (necesita CRM 0.29.0 o superior).

- **Importar**: lee tableros de monday con el token de la empresa y los deja como leads (con notas y archivos). Pantalla Importar del CRM.
- **Enviar** (1.1.0): manda a monday los leads nuevos, sus cambios, sus notas (con menciones) y sus archivos. Pantalla **Envío a monday** (sección `monday_outbound`, permiso `crm.settings`).

Permisos de la extensión: `network.outbound:api.monday.com`, `secrets.write`, `crm.leads.create`, `crm.leads.update`, `crm.fields.create`, `crm.messages.write`, `crm.leads.export` y `connections.monday`.

Datos propios: `ext_monday__settings` (token sellado) y `ext_monday__outbound` (configuración por tablero, migración `V002__outbound.sql`). La cola de envíos y los enlaces viven en el Core (`outbound_events`, `outbound_links`).

## Token y conexión compartida

El token se guarda sellado (nunca se muestra; la pantalla solo enseña una máscara). En la tarjeta de monday de Importar, la casilla **Usar este token en todas las extensiones de monday de la empresa** lo guarda también como conexión compartida (`integration_connections`, servicio `monday`). Para enviar se usa primero el token propio de la extensión y, si no hay, el compartido.

- "Quitar token" borra solo el token propio.
- "Quitar conexión compartida" borra la compartida.
- Declarar el permiso `connections.monday` equivale a poder usar el token de monday de **toda la empresa**: instala solo extensiones de monday en las que confíes.
- Al desinstalar la extensión **no** se borra la conexión compartida (otras extensiones de monday la pueden estar usando); la vista previa lo avisa.

## Qué se envía

Por cada lead, solo si el envío del tablero está activo:

| Evento del CRM | En monday |
| --- | --- |
| Lead nuevo | Un elemento con el nombre y todas las columnas mapeadas |
| Cambio en un lead | Se actualizan solo las columnas de los campos que cambiaron |
| Nota (con autor y menciones) | Una actualización del elemento, con el autor en negrita |
| Archivo del lead | Una columna de archivos, o una actualización "Archivo: nombre" con el archivo adjunto |

**Qué no se envía (decisión de seguridad):** borrar o archivar un lead no se propaga nunca a monday. Nada de lo que llega de monday vuelve a monday (`import:monday`), y lo que llega por otras importaciones solo se envía si se marca "Enviar también lo que llega por importación".

## Configuración por tablero

Apagada por defecto: sin fila en `ext_monday__outbound` no se envía nada. En la pantalla se elige el tablero, el grupo de los elementos nuevos, qué avisos se envían (altas, cambios, notas, archivos, importaciones), el mapeo de campos a columnas del tablero (solo columnas compatibles), la columna de archivos, la columna clave, el tamaño máximo de archivo y el país de los teléfonos. "Sugerir desde la importación" propone columnas con el mapeo de la última importación de monday, sin guardar. Cambiar de tablero y guardar carga sus columnas.

## Idempotencia

Un lead se enlaza con su elemento de monday por tres vías, en este orden: el enlace guardado, su `import_key` (`monday:<id>`) y, si hay **columna clave** y es un reintento, una búsqueda por esa columna (evita duplicar un elemento si el envío se cortó tras crearlo). Al crear un elemento se guarda el enlace y el `import_key` del lead (si no está ocupado por otro lead).

**Columna clave: muy recomendable.** Si la conexión se corta justo después de crear el elemento en monday, sin columna clave el reintento puede crear un duplicado. Elige una columna de texto del tablero como clave.

**Orden y bloqueos.** Un fallo de **alta** bloquea las notas y los cambios de ese lead hasta que se reintente o se descarte. Los demás fallos (un cambio, una nota o un archivo) no bloquean nada. "Reintentar" también sirve para los pendientes que ya tuvieron un error.

## Menciones

Las menciones de una nota se resuelven por **correo** contra los usuarios de monday (la respuesta se guarda 1 hora por token). Si una persona no tiene usuario en monday, queda el texto "@Nombre" y el envío termina con un aviso (visible en "Últimos enviados").

## Archivos

Se suben por streaming (sin cargarlos en memoria) a `https://api.monday.com/v2/file`. Tamaño máximo configurable por tablero (25 MB por defecto, entre 1 y 500); uno mayor falla con el motivo.

## Límites de monday y reintentos

- monday responde 429, `COMPLEXITY_BUDGET_EXHAUSTED` u otros límites: el aviso vuelve a la cola sin gastar intento y espera lo que indique monday (o 60 s); ese tablero no recibe más en esa pasada. El límite diario espera hasta la medianoche UTC.
- Errores pasajeros (red, 5xx): espera creciente de 1 minuto duplicándose hasta 6 horas, con **8 intentos**; al agotarlos el envío queda "fallido" con su razón.
- Token rechazado o valor que monday no acepta: falla sin reintentar.
- El orden por lead se respeta: un alta pendiente o fallida detiene las notas y cambios de ese lead. Desde la pantalla se puede reintentar o descartar cada fallido.
- La cola se procesa cada minuto en un trabajo en segundo plano; un fallo de monday nunca bloquea al usuario que guarda el lead.

## Herramientas MCP

Las de importación (`list_monday_boards`, `import_monday`) y las del envío (`monday_outbound_status`, `monday_outbound_boards`, `monday_outbound_configure`, `monday_outbound_retry`, `monday_outbound_discard`), todas con el permiso `crm.settings` de quien conectó el agente. Ninguna recibe ni devuelve el token.

## Desinstalar

Se borran la cola de envíos y los enlaces con monday (`OutboundQueue.forget`), la configuración por tablero y el token propio. Se conservan los leads y su `import_key`, y la conexión compartida. Las opciones de campos creados e historial de importaciones siguen como antes.

## No probado contra monday real

Todo el envío se probó con un cliente simulado. Sin verificar contra una cuenta real: los formatos de cada tipo de columna, la subida multipart de archivos, la resolución de menciones por correo, la lectura de los códigos de límite y el comportamiento de la columna clave. Probar primero con un tablero de pruebas.
