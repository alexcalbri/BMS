# Activity log — how a module adopts it (Core 0.12.0)

Spec: `ACTIVITY_LOG.md`. Every snippet below compiles (`ActivityLogGuideExamplesTest`). Imports:
`com.ideasdeveloper.bms.server.activity.{ActivityActor, ActivityActions, ActivityChange, ActivityChanges, ActivityEntry, ActivityLog}`.

**1. The actor — never build a person by hand.**
- Screen/route: `val permissions = call.requireModuleAccess(access, "crm") ?: return@post` → `permissions.activityActor`.
- MCP tool: `call.activityActor` (already "Agente de <usuario>", switcher and owner group included).
- Your own actor class (`CrmActor`…): add `val activity: ActivityActor` and fill it from `permissions.activityActor`; keep the old `name` for your histories.
- Importer: `ActivityActor.import(permissions.activityActor)`. Automation: `ActivityActor.automation(rule.name, startedBy = permissions.activityActor)`
  (`startedBy = null` when no person pressed a button). Job (`ModuleJob`): `ActivityActor.job("Recordatorios")`.
  Webhook / inbound integration: `ActivityActor.integration("Sitio web")` (or "Cloudbeds"…).
- A job that keeps who started it: store `actor.toStoredJson()` and rebuild it with `ActivityActor.restoreImport(stored)`
  (always an import, validated); `ActivityActor.restore` is deprecated. Names that contain the log's own markers
  ("Agente de", "(desde el servidor)"…) are quoted by Core; screens and agents read `viaAgent`, `fromServer` and
  `actorKind`, never markers inside a name.

**2. Record in the SAME connection and transaction, before `commit()`. Never catch its exception.**
```kotlin
// before
tenant.autoCommit = false
insertLead(tenant, lead)
tenant.commit()
// after: the row goes between the change and the commit (your transaction helper works the same way)
tenant.autoCommit = false
insertLead(tenant, lead)
access.activity.record(tenant, permissions.activityActor, ActivityEntry(
    moduleId = "crm", entityType = "lead", entityId = lead.id.toString(), entityLabel = lead.name,
    action = ActivityActions.Created, summary = "Creo el lead ${lead.name}.",
    changes = ActivityChanges.created(mapOf("name" to lead.name, "email" to lead.email), LeadLabels),
))
tenant.commit()
```
`access.activity` is `ActivityLog`; with no `access` at hand call `ActivityLog.record(...)` (same thing).

**3. Edit with field diff.** Read the row before changing it; labels are Spanish and fixed in code:
```kotlin
val LeadLabels = mapOf("name" to "Nombre", "email" to "Correo", "stage" to "Etapa")
val before = mapOf("name" to old.name, "email" to old.email, "stage" to old.stageName)
val after = mapOf("name" to new.name, "email" to new.email, "stage" to new.stageName)
val changes = ActivityChanges.diff(before, after, LeadLabels)
if (changes.isNotEmpty()) ActivityLog.record(tenant, actor, ActivityEntry("crm", "lead", id.toString(), new.name,
    ActivityActions.Updated, "Modifico el lead ${new.name}.", changes))
```
A stage/status change is its own row: `ActivityActions.StatusChanged`, "Movio el lead X a la etapa Y.". Assignment: `Assigned`.

**4. Delete: write the row BEFORE the DELETE** (and before any cascade removes the history), with the key data:
```kotlin
ActivityLog.record(tenant, actor, ActivityEntry("crm", "lead", id.toString(), old.name, ActivityActions.Deleted,
    "Elimino el lead ${old.name}.", ActivityChanges.deleted(mapOf("name" to old.name, "email" to old.email), LeadLabels)))
tenant.prepareStatement("DELETE FROM crm_leads WHERE id = ?").use { it.setObject(1, id); it.executeUpdate() }
```
Archive / restore: `Archived` / `Restored`. Notes and comments: `Commented` (text in `changes`, cut to 500 by Core).

**5. Settings** (stages, fields, templates, integrations…): `entityType = "settings"` (or the setting's kind),
`ActivityActions.SettingsChanged`. Secrets: pass their keys in `secrets` — only "changed" is stored:
```kotlin
val changes = ActivityChanges.diff(oldSettings, newSettings, mapOf("url" to "URL", "apiKey" to "Clave API"), secrets = setOf("apiKey"))
```
Names containing password/token/secret/api_key/clave are masked even if you forget; do not rely on it.

**6. Attachments:** `AttachmentAdded` / `AttachmentRemoved` with `changes = listOf(ActivityChange("file", "Archivo", null, "contrato.pdf (24 KB)"))`.
Never the content. **Imports:** one row `ActivityActions.Imported`, "Importo 120 leads (3 con error).", actor
`ActivityActor.import(permissions.activityActor)`; the per-record rows the importer writes use that same actor.
**Exports / bulk downloads:** `ActivityActions.Exported`, "Exporto 340 clientes a CSV." (the one read that is logged).

**7. Text rules.** `summary`, labels and values are stored in Spanish as plain literals: never `tr(...)`, never the request
language. One sentence, past tense without subject ("Creo…", "Modifico…"). Do not log reads, passwords, tokens, codes,
file contents, whole documents or one row per item of a bulk change beyond what §4 of the spec lists. Keep writing your
own histories (lead activity…) exactly as today.

**8. Test it** (no database needed; works with the module's fake connection):
```kotlin
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent

val permissions = ActivityLogTesting.permissions(granted = setOf("crm.leads.edit")).throughAgent("Claude")
val rows = ActivityLogTesting.rows { CrmService.updateLead(fakeTenant, permissions, id, input) }
assertEquals(ActivityActions.Updated, rows.single().action)
assertTrue(rows.single().display.startsWith("Agente de "))
assertEquals("b@x.com", rows.single().change("email")!!.new)
```
`ActivityLogTesting.writeTools(MyServerModule)` / `writeRoutes(MyServerModule)` list every write tool and route: assert
each appears in your test. Route tests through `testApplication` use `ActivityLogTesting.captureEverywhere { … }`.
Required per module: create, edit (diff), delete (row exists after the record is gone), a setting without secret values, a
call through an agent.

**9. Version bump.** Minor bump of the module (`module.json` and `definition.version`) with `"minCoreVersion": "0.12.0"`;
extensions: minor bump and `minModuleVersion` = the new module version. No migration is needed: `activity_log` is Core's.
