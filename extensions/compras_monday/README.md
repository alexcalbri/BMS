# compras_monday

Importa tableros de monday al modulo Compras como solicitudes de compra (con sus archivos como adjuntos). Lee el tablero con el
token de la empresa, mapea sus columnas, deja una vista previa y aplica las filas por lotes desde el trabajo periodico del modulo.
Una importacion a la vez por empresa; todo paso es idempotente (cada fila se reconoce por `monday:<id>`).

## Tablas

`ext_compras_monday__settings` (token sellado), `ext_compras_monday__jobs` y `ext_compras_monday__rows`. Migraciones:
`migrations/V001__staging.sql` y `migrations/V002__applied_by_agent.sql` (nombre del agente de IA que confirmo la importacion). Cada pasada purga las importaciones terminadas, fallidas o canceladas de mas de 30 dias.

## Version 1.3.1 (errores de esquema)

Un error de esquema de la base (SQLState 42: una tabla o columna que no existe porque una migracion no se aplico) ya no se registra cada minuto: sube al trabajo del modulo Compras (0.5.1), que espera cada vez mas antes de reintentar y lo registra una sola vez. Los demas errores de base siguen reintentandose en la proxima corrida.

## Version 1.3.0 (registro de actividad)

Todo cambio queda en el registro de actividad de la empresa, en la misma transaccion que el cambio: guardar, cambiar o borrar el token de monday (sin su valor), iniciar, confirmar y cancelar una importacion, la fila de totales al cerrarla (si se detiene por falta de permiso tambien la deja) y cada importacion antigua que borra la limpieza (la fila va antes del borrado). Cada solicitud, cotizacion, departamento y archivo importado queda a nombre de "Importacion (quien la confirmo)"; si la confirmo un agente de IA (`monday_apply_import`), el nombre del agente se guarda en el trabajo (`applied_by_agent`) y las filas dicen "Importacion (Agente de <usuario>)". Las importaciones anteriores a la migracion V002 usan solo al usuario. Los elementos de monday sin cotizacion ni archivo no llevan filas aparte. Necesita compras 0.4.0 (Core 0.12.0).

## Version 1.1.0 (bilingue)

- Textos en español e inglés: el código usa `tr("Texto en español")` y el catálogo inglés está en `server/src/main/resources/extensions/compras_monday/i18n/en.json`. Necesita Core 0.10.0.
- Lo que viene de monday (nombres de tableros, grupos, columnas, elementos, estados) no se traduce. Los datos guardados al importar (motivos, problemas, errores de la importación) quedan en español.

## Version 1.0.2 (estados de revision)

- Los estados de monday "Pending Review", "In review", "Under review", "Pendiente de revision", "En revision" y "Por revisar"
  pasan a **pendiente de aprobacion**. Antes daban "Estado desconocido" y la solicitud quedaba en borrador. Volver a importar el
  tablero actualiza las solicitudes ya importadas.

## Version 1.0.1 (rendimiento)

- Tope de respuesta de monday: 5 MB.
- El listado de tableros tiene un presupuesto total de 30 s (si se acaba, se devuelve lo leido).
- Los enlaces de descarga de los archivos se piden de 50 en 50 (`assets(ids:[...])`) por cada lote de filas, no uno por archivo; un archivo que no venga en el grupo pide su propio enlace.
- La lectura baja la pagina (100, 50, 25, 10) ante una respuesta de mas de 5 MB o de complejidad excesiva; a 10 falla con un mensaje claro. Los enlaces en grupo se saltan si quedan menos de 5 s de corrida.
