# compras_monday

Importa tableros de monday al modulo Compras como solicitudes de compra (con sus archivos como adjuntos). Lee el tablero con el
token de la empresa, mapea sus columnas, deja una vista previa y aplica las filas por lotes desde el trabajo periodico del modulo.
Una importacion a la vez por empresa; todo paso es idempotente (cada fila se reconoce por `monday:<id>`).

## Tablas

`ext_compras_monday__settings` (token sellado), `ext_compras_monday__jobs` y `ext_compras_monday__rows`. Migracion:
`migrations/V001__staging.sql`. Cada pasada purga las importaciones terminadas, fallidas o canceladas de mas de 30 dias.

## Version 1.2.0 (registro de actividad)

La importacion deja una fila con sus totales y cada solicitud creada o cotizacion o archivo importado queda en el registro de actividad a nombre de "Importacion (quien la inicio)". Guardar o quitar el token de monday queda registrado sin su valor. Necesita compras 0.4.0 (Core 0.12.0).

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
