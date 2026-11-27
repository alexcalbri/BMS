# Company storage quota (Core, SECURITY_FIXES.md B9)

Every file a module stores for a company goes through `ModuleFiles` (`access.files(companyCode, moduleId)`).
Core enforces three limits there, so a module that already uses `ModuleFiles.put` is protected without
changes; a module only adds the early check and the error mapping below.

## Limits

| Limit | Where it is set | Default |
|---|---|---|
| Storage quota per company (all modules together) | Server owner: default for every company and an override per company (routes below) | 100 GiB per company; `0` = unlimited |
| Free-disk floor of the files volume | `company.minFreeDiskBytes` / `COMPANY_FILES_MIN_FREE_BYTES` | 2 GiB |
| Uploads in progress at the same time per company | `company.maxConcurrentUploads` / `COMPANY_FILES_MAX_CONCURRENT_UPLOADS` | 4 |

Usage is what the company's folder holds on disk (`{filesRoot}/{company}/**`), measured by walking it at most
every 10 minutes and adjusted in memory by every `put`/`delete` in between. The per-file maximum of each module
(`MaxFileBytes`, e.g. `CrmAttachments.MaxFileBytes`) still applies and stays the module's setting.

## API for modules (`com.ideasdeveloper.bms.server.modules`)

```kotlin
class ModuleFiles {
    /**
     * Cheap pre-check before reading an upload body: throws [StorageQuotaException] when the company is
     * over its quota, when [expectedBytes] (the Content-Length, null when unknown) does not fit in what is
     * left, or when the files volume is under the free-disk floor. Does not reserve anything.
     */
    fun checkRoom(expectedBytes: Long? = null)

    /**
     * Unchanged signature. Now also: refuses with [StorageQuotaException] (code STORAGE_BUSY) when the
     * company already has `maxConcurrentUploads` uploads in progress; stops the stream with
     * [StorageQuotaException] as soon as it crosses min(maxBytes, quota left) or the free-disk floor is
     * reached (checked every 8 MiB). A stopped upload leaves no file. [FileTooLargeException] keeps
     * meaning "over maxBytes".
     */
    fun put(name: String, contentType: String, content: InputStream, maxBytes: Long): StoredFileRef

    /** Deleting gives the space back to the quota at once. */
    fun delete(file: StoredFileRef)

    /** What the company uses and may use now. */
    fun usage(): StorageUsage
}

data class StorageUsage(val usedBytes: Long, val quotaBytes: Long?, val freeDiskBytes: Long?)
// quotaBytes null = unlimited; freeDiskBytes null = unknown (non-local backend)

class StorageQuotaException(val code: String, val httpStatus: Int, message: String) : Exception(message)
// code / httpStatus / message (already translated with tr, Spanish source):
//   STORAGE_QUOTA_EXCEEDED  413  "La empresa alcanzo su espacio de almacenamiento ({0} de {1})."
//   STORAGE_DISK_FULL       507  "El servidor no tiene espacio libre suficiente para guardar archivos."
//   STORAGE_BUSY            429  "Hay demasiadas subidas en curso para esta empresa. Espera a que terminen."
```

Module upload route pattern (what CRM, Tareas, Propietarios, Compras and Mantenimiento should do):

```kotlin
val files = access.files(permissions.companyCode, MyModule.id)
try {
    files.checkRoom(call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull())
    val stored = files.put(name, type, body, MaxFileBytes)
    ...
} catch (refused: StorageQuotaException) {
    return call.respond(HttpStatusCode.fromValue(refused.httpStatus), MyErrorResponse(refused.code, refused.message.orEmpty()))
}
```

Pending (uploaded but not yet saved with their form) CRM attachments count in the quota from the moment they
are stored; the CRM's own cleanup of abandoned uploads gives the space back through `delete`.

## Server owner routes (owner only, `ServerRoutePolicies`, audited)

- `GET /server/storage` → `{defaultQuotaBytes, minFreeDiskBytes, freeDiskBytes, totalDiskBytes, companies:
  [{code, name, usedBytes, quotaBytes, customQuotaBytes}]}` (`quotaBytes` effective, `0` unlimited;
  `customQuotaBytes` null = follows the default).
- `PUT /server/storage {defaultQuotaBytes}` → same body as GET. Audit `storage.default_quota_changed`.
- `PUT /companies/{code}/storage-quota {quotaBytes}` (`null` = back to the default, `0` = unlimited) →
  `{code, name, usedBytes, quotaBytes, customQuotaBytes}`. Audit `company.storage_quota_changed`.

Errors `{code, message}`: `INVALID_QUOTA` (400, negative or over 1 PiB), `COMPANY_NOT_FOUND` (404). Lowering a
quota under the current usage is allowed: new uploads are refused until files are removed. Stored in
`server_settings` (`storage.quota.default`, `storage.quota.company.<code>`); no migration.
