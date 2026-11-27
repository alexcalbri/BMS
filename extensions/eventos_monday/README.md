# Extension `eventos_monday`

Imports a [monday.com](https://monday.com) board (for example "Vista Mar Eventos") into the **Eventos** module as private
events (minimum module version 0.1.0). Each board item becomes a private event with its client. It is an import source of the
module (`EventosImportSource`) and has no routes of its own.

## What it imports

| monday | Eventos |
|---|---|
| Item | Private event (`import_key = monday:<item id>`) |
| Item name | Event title and, when there is no contact column, the client name |
| Contact (text or people) | Client (reused by visible email, otherwise created with type `eventos`) |
| Email / Phone | Client email / phone |
| Owner / Responsable (people) | Event responsible, matched by **email** (`users(ids)`); unmatched names are added to the notes ("Responsable en Monday: ...") |
| Message (text / long text) | Description |
| Notes (text / long text) | Notes |
| Date (with time if present) or timeline | Start (and end, for a timeline). monday stores the time in UTC; it is converted to the server time zone. No time means 00:00 |
| Follow up (date), Booking date (date) | Added to the notes ("Seguimiento: ...", "Reservado el: ...") |
| Status | See below |
| Amount / price / total (numbers) | Agreed amount |
| Guests / pax / invitados (numbers) | Guests |
| Group | Space of the event (editable per group in the mapping screen) |
| Other columns with a value | Notes, as `Column: value` |

Status (case, accents and Spanish/English ignored):

- `Booked`, `Reservado`, `Confirmado`, `Confirmed`: **confirmed** (published), or **done** if the date already passed.
- `Done`, `Realizado`, `Completado`, `Completed`: **done**, or **confirmed** if the date is still ahead.
- `Cancel*`, `Perdido`, `Lost`: **cancelled**.
- Anything else, or empty: **draft** (shown as "Tentativo" for private events).
- A group called `Booked` / `Reservados` forces confirmed (or done if the date passed), unless the item is cancelled.

The space comes from the group title without the word "Eventos"/"Events" ("Vista Mar Eventos" -> "Vista Mar", "Costa Azul Eventos" ->
"Costa Azul"). Groups such as "Upcoming Events" or "Booked" give no space unless the mapping screen sets one. Column titles are
suggested in Spanish and English and can be changed before reading the board. Items without a start date are skipped.

There are no attachments, subitems or payments in this import.

## Permissions

- Module: `eventos.import` ("Importar datos") for the Importar screen and the MCP tools. Every tool checks the user's live
  permission and that the extension is still enabled in the company.
- Apply or cancel an import: only who started it, or users with `eventos.manage`.
- Extension (extension.json): `network.outbound:api.monday.com`, `secrets.write`, `eventos.import.write`.

## Flow (Eventos > Importar > Monday)

1. Save the monday API token (password field that is never prefilled; stored sealed with the company key; only "configured" is shown).
2. Load boards and pick one.
3. Review the column mapping (suggested by title and type) and the space of each group.
4. **Read board**: the module job reads one page per step (saved cursor), up to 20,000 items.
5. Preview: events, new clients, events per space, responsibles without a user and problems.
6. **Apply**: 100 rows per run (every minute). Shows progress; at the end, the counts and the rows with problems.
7. **Cancel** at any step: what was already imported is kept.

**Warning:** re-importing the same board updates the events already imported and overwrites changes made in Eventos to those
fields (title, description, notes, space, dates, status, responsible, amount and guests).

Only one import can be open (reading, in review or applying) per company. The preview summary is computed once, in batches,
when reading finishes. Temporary monday failures (network, timeout, 429, 5xx) are retried up to 5 times in a row; permanent ones
(rejected token, missing board, more than 20,000 items, endless reading) leave the import in error. Every call respects the run
budget and does not start with less than 5 s left.

It is idempotent: importing again updates events by their key without duplicating them. Every error is stored in the job or
the row and in the server log.

## MCP tools (published by the module as `eventos_<name>`)

`monday_list_boards` (read only), `monday_read_board(board_id)` (suggested mapping), `monday_import_status(job_id?)` (read
only) and `monday_apply(job_id)`. None of them receives or returns the token; it is saved only from the app.

## Tables

`ext_eventos_monday__settings` (sealed token), `ext_eventos_monday__jobs`, `ext_eventos_monday__rows` and
`ext_eventos_monday__people` (monday users used to match responsibles). Migrations: `V001__settings.sql` and `V002__rendimiento.sql`.

## Uninstall

The token is always deleted. The `import_history` option (default: delete) removes jobs, rows and people read; with "keep" they
stay. Imported events, clients and payments are never deleted.

## Limits

- Up to 20,000 items per board and one import in progress at a time.
- The extension has no scheduled jobs of its own: the Eventos module calls `runPending` from its `import-runner` job.

## 1.2.0 (activity log, needs Core 0.12.0 and Eventos 0.4.0)

- The rows of an import in the company activity log carry who started it ("Importacion (Ana Ruiz)"; "Usuario desconocido" when that user no longer exists) and, when it ends, one totals row with the source (Monday and the board) and the numbers.
- Also recorded: the monday token (only that it was saved, changed or deleted, never its value), starting, confirming and cancelling an import, and the old imports the cleanup deletes (the row is written before the delete).

## 1.0.1 (read performance)

- Adaptive paging: on a complexity error (or a response over 5 MB) the page is halved, minimum 10, and the size is stored in the
  job (`page_size`); an expired cursor rereads the board from scratch once (`restarts`). The extension asks for no subitems or
  updates, only columns.
- Response read cap lowered to 5 MB (was 20 MB); board listing has a 30 s total budget.
- The runner applies batches of 100 rows until the run budget is spent (was one batch per minute); the applier's permission is
  checked live at the start of each batch.
- Every run deletes (up to 5) done, failed or cancelled imports older than 30 days, with their rows and people.
- Migration `V002__rendimiento.sql`: `page_size` and `restarts` columns and a partial index of pending rows.
- Retries of transient failures wait longer each time (2, 4, 8... min, cap 30); shrinking the page for complexity or a response over 5 MB is not an attempt and, at 10 per page, gives a clear error.

## Idiomas

Bilingüe español/inglés (1.1.0, necesita Core 0.10.0): los textos del código usan `tr("Texto en español")` y el catálogo inglés está en `server/src/main/resources/extensions/eventos_monday/i18n/en.json`. Lo que viene de monday (nombres de tableros, grupos, columnas, elementos, clientes, responsables) no se traduce. Lo que se guarda al importar (notas del evento, motivos y errores de las filas) queda en español.
