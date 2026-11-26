# I18N module guide (distilled from `modules/clientes`, the pilot)

Read I18N.md §2-§5, §10b first. Work only in `modules/<id>/` (extension: see 7). Run tests: `/home/user/tools/bms-test.sh <worktree> "*<Id>*"` (then `"*I18n*"`, then full run).

## 1. Find the texts
```
cd modules/<id>/server/src/main/kotlin/com/ideasdeveloper/bms/modules/<id>
grep -n '"[^"]*[a-z] [a-z][^"]*"' *.kt | grep -v 'tr(\|msg(\|log\.\|SELECT\|UPDATE\|INSERT\|DELETE\|WHERE'   # prose literals left
grep -n 'UiText(\|UiButton(\|UiCard(\|title =\|label =\|emptyText\|placeholder\|confirm\|UiNotice(\|UiScreen(\|ErrorResponse(\|Exception("' *.kt
```
Ignore log lines, SQL, ids, ops, routes, stored rows (see 2), MCP tools (stay Spanish).

## 2. Wrap (one literal per call, one line, no `$`, no `+`)
- `UiText("Correo: $it")` -> `UiText(tr("Correo: {0}", it))`.  Quotes stay escaped: `tr("Ir a \"{0}\"", x)`.
- Long texts built with `"a " + "b"` -> ONE literal on one line (join them yourself, keep the exact spaces).
- Plurals/conditionals: one `tr` per branch, never `tr("...")+if`:
  `if (n == 1) tr("{0} registro", n) else tr("{0} registros", n)`.
  Optional suffix: `base + " " + tr("Segunda frase.")` (no leading space inside the key).
- `buildString { append("$a ... $b") }` -> `append(tr("{0} ... {1}", a, b))`; pieces `append(tr(", {0} fusionados", n))`.
- `when (code) { "x" -> "Texto" }` -> `"x" -> tr("Texto")` (the `else -> fallback` stays plain).
- Text that is passed as a parameter (`failure(status, code, "msg")`, `useDb(..., "msg")`): wrap at the CALL SITE.
- A helper that spliced a word (`"se $verb desde..."`): replace the parameter by a Boolean and write two full literals.
- `const val X = "Texto"` / `val X = "Texto"` shown to users -> `val X = msg("Texto")` (const cannot call msg) and `tr(X)` where shown.
  Existing tests comparing `X` with the Spanish text keep passing (msg returns it unchanged).
- Exceptions whose `.message` is shown in a notice/error JSON: `throw MyException(tr("Texto {0}", x))`.
  If code compares `failure.message == X`, compare with `tr(X)` (same language in the same request).
- Do NOT wrap: customer data, option values / ids / params / `op` / tab ids (§5), SQL, log lines,
  and texts that are STORED in the database and read back later (activity rows, merge reasons, job errors,
  author names like "Sistema"): they stay as written (§1). A stored generic text with no placeholders may be marked
  `msg("...")` and shown with `tr(stored)` (falls back to the stored text when unknown).
- `UiField.options` untouched (Core fills `optionLabels` via `localizedPayload`). Labels/placeholders/help: `tr`.
- Definition (displayName, permissions, view fields, actions) stays plain Spanish in the `ModuleDefinition`.

## 3. Response helpers
Every place that serializes UI: `UiJson.encodeToString(UiScreen.serializer(), localizedPayload(screen))`
(`import com.ideasdeveloper.bms.server.i18n.localizedPayload`). `grep -n "UiJson.encodeToString" *.kt` must show none without it.
Error JSON: `ErrorResponse(code, tr("..."))` (code stays). Call `tr` inside the request, not in a late callback.

## 4. Background jobs, notifications, mail
`notifier.notify(tenant, userId, target) { NotificationText(tr("..."), tr("... {0}", x)) }`; jobs/mail to staff:
`I18n.withLanguage(access.recipientLanguage(tenant, userId)) { ... }`. Jobs run in `es`: what they store stays Spanish. Staff notes shown as `UiTimelineEntry` use `access.textTranslation.noteTranslations(...)`
(§8); notes in a plain `UiListItem` have no `translation` field: report it, do not invent one.

## 5. Catalog `modules/<id>/server/src/main/resources/modules/<id>/i18n/en.json`
Flat object, keys sorted (code-point order), `{n}` identical, glossary §4. Generate the key list, then translate:
```
python3 - <<'EOF'   # prints every key of the module (tr/msg literals); add the definition texts by hand
import re,glob,json
C=re.compile(r'(?<![\w.])(tr|msg)\(\s*"((?:[^"\\\n]|\\.)*)"'); k=set()
for f in glob.glob('modules/<id>/server/src/main/kotlin/**/*.kt',recursive=True):
    for m in C.finditer(open(f,encoding='utf-8').read()): k.add(json.loads('"'+m.group(2)+'"'))
print("\n".join(json.dumps(x,ensure_ascii=False) for x in sorted(k)))
EOF
```
Every key your package uses goes in YOUR file, also when Core or another package has it (the check demands it).
Your package owns its translations: lookup is your catalog first, then Core's, then any other (I18N.md §2), so
translate for your context ("Alta" as a priority is "High", whatever Core says); reuse the existing wording of
identical sentences ("Cancelled", "You do not have permission to view …"). Write the file with
`json.dump(d, f, ensure_ascii=False, indent=2)` over `dict(sorted(d.items()))` + trailing newline.
The definition texts (name, description, permission titles/descriptions, field labels/help, action titles) go in too.
`tr(variable)` texts that no literal lists: `assertModule(id, definition, extraTexts = listOf(...))`.

## 6. Tests (`modules/<id>/server/src/test/kotlin/.../<Id>I18nTest.kt`, copy `ClientesI18nTest.kt`)
- `@BeforeTest fun loadCatalog() = loadModuleCatalog(<Id>ServerModule)` (no ModuleRegistry in unit tests).
- `@Test fun catalogIsComplete() = I18nCatalogCheck.assertModule("<id>", <Id>ServerModule.definition)`
  (extension: `I18nCatalogCheck.assertExtension("<id>", <Ext>.manifest)`).
- English output: `I18n.withLanguage("en") { screenFn(...) }`; assert labels/messages in English AND that `params`,
  ids and ops equal the Spanish ones. Errors: call a function that throws before touching the DB (permission check);
  a `Connection` stub: `Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> error("unused") } as Connection`.
- Existing tests must pass unchanged (`es`). Catalog failures list missing/unused keys and `$`/`+` problems.

## 7. Version and docs
`modules/<id>/module.json`: version minor+1 and `"minCoreVersion": "0.10.0"`; the same in the `ModuleDefinition`
(`version = "x.y.z", minCoreVersion = "0.10.0"`; the build fails if module.json and code versions differ).
README: add a short "Languages" section. Nothing else to register: resources under `server/src/main/resources/modules/<id>/`
are picked up by the build; `ModuleRegistry` loads the catalog; the validator only requires that path.
Extensions: `extensions/<id>/extension.json` + version in the extension class, catalog at
`extensions/<id>/server/src/main/resources/extensions/<id>/i18n/en.json`, `loadExtensionCatalog(ext)` in tests.

## 8. Commit
`git add modules/<id> && GIT_AUTHOR_NAME=alexcalbri GIT_AUTHOR_EMAIL=164930758+alexcalbri@users.noreply.github.com GIT_COMMITTER_NAME=alexcalbri GIT_COMMITTER_EMAIL=164930758+alexcalbri@users.noreply.github.com git commit -m "feat(<id>): X.Y.Z - modulo bilingue es/en"`
(never commit `build.gradle.kts`/`gradle-daemon-jvm.properties`; no trailers; no push).

## 9. Extensions (from `extensions/monday`, first extension done)
- Files: sources `extensions/<id>/server/src/main/kotlin/...`, catalog `extensions/<id>/server/src/main/resources/extensions/<id>/i18n/en.json`
  (namespaced path is mandatory), test `server/src/test/kotlin/.../<Id>I18nTest.kt`. Imports: `com.ideasdeveloper.bms.server.i18n.{tr,msg}`.
- Extensions do NOT serialize UI: they return `List<UiNode>`, `UiNotice`, `CrmImportSourceResult`, texts... to the host module, which calls
  `localizedPayload`. Your job is only `tr(...)` at the place each text is created (inside the request, so the language is the viewer's).
- Manifest: bump `version` (minor) in BOTH `extension.json` and the `ExtensionManifest(version = ...)` in code (a test compares them; if an existing
  test asserts the old version string, change only that number). Extension manifests have NO `minCoreVersion` (only `minModuleVersion`): do not invent it;
  set `minModuleVersion` (BOTH files) to the first bilingual release of the host module (the one with
  `minCoreVersion` 0.10.0), and say "needs Core 0.10.0" in the README ("Idiomas" section, like the module guide item 7).
- Your `tr` calls use your extension's catalog (namespace `extensions/<id>`, from your Kotlin package). The host
  localizes the nodes you return with `localizedFor(yourSource, nodes)`, so select options are looked up in your catalog too.
- Catalog must also list the manifest texts (displayName, description, settings label/help, uninstall option labels) with NO `tr` in code:
  `I18nCatalogCheck.assertExtension` adds them from `manifest`. Texts used in `uninstallPreview` items, section labels, MCP-free screens: `tr(...)`.
- Test:
  ```kotlin
  @BeforeTest fun loadCatalog() = loadExtensionCatalog(MyServerExtension)   // no ExtensionRegistry in unit tests
  @Test fun catalogIsComplete() = I18nCatalogCheck.assertExtension("<id>", MyServerExtension.manifest)
  @Test fun english() { I18n.withLanguage("en") { assertEquals("...", assertFailsWith<X> { fnThatThrows() }.message) } }
  ```
  The host module's own catalog (e.g. CRM) is not loaded in your test: assert only your texts.
- Pitfalls: (1) `val x = listOf(Section(..., "Texto"))` / `override val label = "..."` evaluate at class load: turn into `get() =` with `tr`.
  (2) Data from the external system (monday names, columns, updates, error text from the remote API) is never translated; pass it as `{0}`.
  (3) Texts written to DB rows / job errors by background jobs stay Spanish; if shown later use `tr(stored)` (mark the literal with `msg`).
  (4) `UiField.options`/params/ops/ids stay Spanish; labels only. (5) MCP tools stay Spanish. (6) Never add build artifacts or files outside the
  extension folder: a changed folder makes the package "sin firma" and it must be re-authorized (nothing to do in code).
- Run: `/home/user/tools/bms-test.sh <worktree> "*<Id>*"`. Commit only `extensions/<id>` (+ nothing else).
