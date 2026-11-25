# compras_monday

Importa tableros de monday al modulo Compras como solicitudes de compra (con sus archivos como adjuntos). Lee el tablero con el
token de la empresa, mapea sus columnas, deja una vista previa y aplica las filas por lotes desde el trabajo periodico del modulo.
Una importacion a la vez por empresa; todo paso es idempotente (cada fila se reconoce por `monday:<id>`).

## Tablas

`ext_compras_monday__settings` (token sellado), `ext_compras_monday__jobs` y `ext_compras_monday__rows`. Migracion:
`migrations/V001__staging.sql`. Cada pasada purga las importaciones terminadas, fallidas o canceladas de mas de 30 dias.

## Version 1.0.1 (rendimiento)

- Tope de respuesta de monday: 5 MB.
- El listado de tableros tiene un presupuesto total de 30 s (si se acaba, se devuelve lo leido).
- Los enlaces de descarga de los archivos se piden de 50 en 50 (`assets(ids:[...])`) por cada lote de filas, no uno por archivo; un archivo que no venga en el grupo pide su propio enlace.
- La lectura baja la pagina (100, 50, 25, 10) ante una respuesta de mas de 5 MB o de complejidad excesiva; a 10 falla con un mensaje claro. Los enlaces en grupo se saltan si quedan menos de 5 s de corrida.
