package com.ideasdeveloper.bms.server.company.application

import com.ideasdeveloper.bms.server.company.api.CompanySummaryResponse
import com.ideasdeveloper.bms.server.company.api.CreateCompanyRequest
import com.ideasdeveloper.bms.server.company.api.ServerModuleCatalogResponse
import com.ideasdeveloper.bms.server.company.api.ServerModuleResponse
import com.ideasdeveloper.bms.server.company.api.UpdateCompanyRequest
import com.ideasdeveloper.bms.server.modules.McpSchema
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.modules.McpPreviewRow
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.McpToolException
import com.ideasdeveloper.bms.server.modules.RegisteredMcpTool
import com.ideasdeveloper.bms.server.modules.RegisteredModuleMcp
import com.ideasdeveloper.bms.server.modules.ServerMcpModuleId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * The server's own MCP: what a server owner's AI agent can do with the server itself (companies and
 * modules), next to the MCP of each module. Only server owners see these tools (UserModuleAccess).
 * Credentials never go through it: a company created here gets a random business owner password
 * that nobody sees, and the server owner sets the real one in the app.
 */
object ServerMcpTools {
    const val Name = "Servidor"
    // Not a module permission: UserModuleAccess grants the server tools to server owners only.
    private const val Permission = "server_owner"

    private val json = Json { encodeDefaults = true }
    private val random = SecureRandom()

    private const val Instructions =
        "Administras el servidor como su dueno. Las empresas (servidor_list_companies) tienen cada una su propia base de datos; " +
            "el espacio del servidor no aparece en esa lista. Para trabajar dentro de una empresa con las herramientas de los modulos, " +
            "pasa su codigo en el argumento acting_company; el modulo debe estar activo en esa empresa (servidor_set_company_module). " +
            "Un modulo debe estar instalado en el servidor (desde la app; el agente no instala modulos) antes de poder activarlo en una empresa. " +
            "Las contrasenas nunca pasan por aqui: una empresa creada con servidor_create_company no tiene contrasena utilizable " +
            "hasta que el business owner la pone con el codigo que le llega a su correo (\"Olvidaste tu contrasena\" en el login) " +
            "o el dueno del servidor la pone desde la app (Empresas > Editar > Contrasena del business owner); avisale."

    /**
     * [extensions] adds more server tools (the extension tools); it receives the function that gives
     * the role of the server owner of a call, and is built once at startup, so it must not touch
     * the server until a tool runs.
     */
    fun mcp(
        provisioning: CompanyProvisioningService,
        extensions: ((McpToolCall) -> String) -> List<McpTool> = { emptyList() },
        extensionInstructions: String = "",
        // Read-only audit tools (list_server_audit, list_company_activity; ServerAuditMcpTools).
        auditTools: List<McpTool> = emptyList(),
    ): RegisteredModuleMcp =
        RegisteredModuleMcp(
            ServerMcpModuleId, Name,
            listOf(Instructions, extensionInstructions, if (auditTools.isEmpty()) "" else AuditInstructions).filter { it.isNotBlank() }.joinToString("\n\n"),
            (toolsWith({ provisioning }, extensions) + auditTools).map { RegisteredMcpTool(ServerMcpModuleId, Name, it) },
        )

    private const val AuditInstructions =
        "Para auditar: servidor_list_server_audit lista la auditoria del servidor y servidor_list_company_activity el registro de actividad " +
            "de las empresas (quien hizo cada cambio). Son de solo lectura y cada consulta queda registrada."

    // [provisioning] is only called when a tool runs.
    fun tools(provisioning: () -> CompanyProvisioningService): List<McpTool> = toolsWith(provisioning) { emptyList() }

    fun toolsWith(provisioning: () -> CompanyProvisioningService, extensions: ((McpToolCall) -> String) -> List<McpTool>): List<McpTool> {
        // Every change runs as the server owner's own role, like the app's requests.
        fun role(call: McpToolCall): String = call.permissions.userId?.let { provisioning().serverOwnerRole(it) }
            ?: throw McpToolException("Solo un dueno del servidor activo puede usar esta herramienta.")

        // The owner of the call is who the audit names.
        fun actor(call: McpToolCall): com.ideasdeveloper.bms.server.admins.AuditActor =
            com.ideasdeveloper.bms.server.admins.AuditActor.owner(role(call), call.permissions.userId)

        fun <T> run(block: () -> T): T = try {
            block()
        } catch (failure: CompanyProvisioningException) {
            throw McpToolException(failure.message.orEmpty())
        }

        fun existing(call: McpToolCall): CompanySummaryResponse {
            val code = call.requiredString("code", 63)
            return provisioning().listCompanies().firstOrNull { it.code == code }
                ?: throw McpToolException("Empresa no encontrada: $code.")
        }

        // What a confirmation card says about a company or a module (plain reads of the control data; no request, no write).
        fun brief(call: McpToolCall, key: String = "code"): CompanyProvisioningService.CompanyBrief {
            val target = call.requiredString(key, 63)
            return provisioning().companyBrief(target) ?: throw McpToolException("Empresa no encontrada: $target.")
        }
        fun companyLabel(company: CompanyProvisioningService.CompanyBrief) = "${company.name} (${company.code})"
        fun moduleLabel(call: McpToolCall): String {
            val target = call.requiredString("module_id", 63)
            return provisioning().moduleBrief(target)?.let { "${it.displayName} (${it.moduleId}, version ${it.version})" } ?: throw McpToolException("Modulo no reconocido: $target.")
        }
        fun row(label: String, value: String) = McpPreviewRow(label, value)

        val code = "code" to McpSchema.string("Codigo de la empresa (servidor_list_companies).")
        val moduleId = "module_id" to McpSchema.string("Id del modulo (servidor_list_modules).")
        val color = { what: String -> McpSchema.string("Color $what en formato #RRGGBB.") }

        return listOf(
            McpTool(
                name = "list_companies",
                title = "Listar empresas",
                description = "Empresas del servidor con su estado y los modulos activos en cada una.",
                permission = Permission,
                readOnly = true,
            ) { companies(provisioning().listCompanies()) },
            McpTool(
                name = "create_company",
                title = "Crear empresa",
                description = "Crea una empresa con su base de datos y su usuario business owner (con nombre y correo). El business owner " +
                    "pone su contrasena con \"Olvidaste tu contrasena\" en el login (le llega un codigo a su correo), o el dueno del " +
                    "servidor se la pone desde la app; no pidas ni envies contrasenas.",
                permission = Permission,
                inputSchema = McpSchema.obj(
                    mapOf(
                        "code" to McpSchema.string("Codigo unico: minusculas, numeros o guion bajo, iniciando con letra."),
                        "name" to McpSchema.string("Nombre de la empresa."),
                        "business_owner_username" to McpSchema.string("Usuario del business owner: minusculas, numeros o guion bajo."),
                        "business_owner_name" to McpSchema.string("Nombre completo del business owner."),
                        "business_owner_email" to McpSchema.string("Correo del business owner; recibe los codigos para poner y recuperar su contrasena."),
                        "logo_url" to McpSchema.string("URL del logo (opcional)."),
                        "primary_color" to color("primario"),
                        "secondary_color" to color("secundario"),
                        "accent_color" to color("de acento"),
                    ),
                    required = listOf("code", "name", "business_owner_username", "business_owner_name", "business_owner_email"),
                ),
                preview = { call ->
                    val target = call.requiredString("code", 63)
                    listOfNotNull(
                        row("Empresa que se crea", "${call.requiredString("name", 200)} (codigo $target)"),
                        provisioning().companyBrief(target)?.let { row("Aviso", "Ya existe una empresa con el codigo $target: no se creara.") },
                        row("Business owner", "${call.requiredString("business_owner_name", 120)}, usuario ${call.requiredString("business_owner_username", 63)}"),
                        row("Correo del business owner", call.requiredString("business_owner_email", 254)),
                        row(
                            "Que pasa",
                            "Se crea una base de datos nueva para la empresa y el usuario del business owner (con todos los permisos de la empresa). " +
                                "Nadie conoce su contrasena: la pone el business owner con \"Olvidaste tu contrasena\" en el login (el codigo llega a ese correo) " +
                                "o el dueno del servidor se la pone desde la app. Quien controle ese correo controla la empresa.",
                        ),
                    )
                },
            ) { call ->
                val created = run {
                    provisioning().createCompany(
                        CreateCompanyRequest(
                            code = call.requiredString("code", 63),
                            name = call.requiredString("name", 200),
                            businessOwnerUsername = call.requiredString("business_owner_username", 63),
                            businessOwnerName = call.requiredString("business_owner_name", 120),
                            businessOwnerEmail = call.requiredString("business_owner_email", 254),
                            // Nobody learns it; the server owner sets the real password in the app.
                            businessOwnerPassword = randomPassword(),
                            logoUrl = call.string("logo_url", 2_000).ifEmpty { null },
                            primaryColor = call.string("primary_color", 7).ifEmpty { "#667EEA" },
                            secondaryColor = call.string("secondary_color", 7).ifEmpty { "#764BA2" },
                            accentColor = call.string("accent_color", 7).ifEmpty { "#FFFFFF" },
                        ),
                        role(call),
                        actor(call),
                    )
                }
                buildJsonObject {
                    put("code", created.code)
                    put("name", created.name)
                    put("businessOwnerUsername", created.businessOwnerUsername)
                    put(
                        "nextStep",
                        "El business owner aun no tiene contrasena: puede ponerla con \"Olvidaste tu contrasena\" en el login usando el " +
                            "codigo de empresa ${created.code} (le llega un codigo a su correo, si el servidor tiene SMTP), o el dueno del " +
                            "servidor se la pone en la app (Empresas > Editar > Contrasena del business owner).",
                    )
                }
            },
            McpTool(
                name = "update_company",
                title = "Editar empresa",
                description = "Cambia nombre, logo o colores de una empresa. Solo cambia los campos enviados.",
                permission = Permission,
                inputSchema = McpSchema.obj(
                    mapOf(
                        code,
                        "name" to McpSchema.string("Nuevo nombre."),
                        "logo_url" to McpSchema.string("URL del logo; vacio para quitarlo."),
                        "primary_color" to color("primario"),
                        "secondary_color" to color("secundario"),
                        "accent_color" to color("de acento"),
                    ),
                    required = listOf("code"),
                ),
                preview = { call ->
                    val company = brief(call)
                    listOf(row("Empresa", companyLabel(company))) + listOf(
                        "name" to "Nuevo nombre", "logo_url" to "Nuevo logo (URL; vacio lo quita)", "primary_color" to "Color primario",
                        "secondary_color" to "Color secundario", "accent_color" to "Color de acento",
                    ).filter { (key, _) -> call.has(key) }.map { (key, label) -> row(label, call.string(key, 2_000).ifEmpty { "(vacio)" }) }
                },
            ) { call ->
                val current = existing(call)
                val updated = run {
                    provisioning().updateCompany(
                        current.code,
                        UpdateCompanyRequest(
                            name = if (call.has("name")) call.string("name", 200) else current.name,
                            logoUrl = if (call.has("logo_url")) call.string("logo_url", 2_000).ifEmpty { null } else current.logoUrl,
                            primaryColor = if (call.has("primary_color")) call.string("primary_color", 7) else current.primaryColor,
                            secondaryColor = if (call.has("secondary_color")) call.string("secondary_color", 7) else current.secondaryColor,
                            accentColor = if (call.has("accent_color")) call.string("accent_color", 7) else current.accentColor,
                        ),
                        role(call),
                        actor(call),
                    )
                }
                companyJson(updated)
            },
            McpTool(
                name = "set_company_active",
                title = "Activar o desactivar empresa",
                description = "Desactiva una empresa (nadie puede entrar) o la reactiva. Sus datos se conservan.",
                permission = Permission,
                inputSchema = McpSchema.obj(
                    mapOf(code, "active" to McpSchema.boolean("true para activar, false para desactivar.")),
                    required = listOf("code", "active"),
                ),
                preview = { call ->
                    val company = brief(call)
                    val activate = call.boolean("active")
                    listOf(
                        row("Empresa", companyLabel(company)),
                        row("Estado ahora", if (company.isActive) "Activa" else "Desactivada"),
                        row(
                            "Que pasa",
                            if (activate) "Se reactiva: sus usuarios pueden entrar otra vez."
                            else "Se desactiva: nadie puede entrar a esta empresa, ni sus agentes de IA ni sus integraciones hasta que se reactive. Sus datos se conservan.",
                        ),
                    )
                },
            ) { call ->
                companyJson(run { provisioning().setCompanyActive(call.requiredString("code", 63), call.boolean("active"), role(call), actor(call)) })
            },
            McpTool(
                name = "delete_company",
                title = "Eliminar empresa",
                description = "Elimina una empresa desactivada junto con su base de datos. No se puede deshacer; confirma con el usuario antes.",
                permission = Permission,
                inputSchema = McpSchema.obj(mapOf(code), required = listOf("code")),
                destructive = true,
                preview = { call ->
                    val company = brief(call)
                    listOf(
                        row("Empresa que se elimina", companyLabel(company)),
                        row("Estado ahora", if (company.isActive) "Activa: no se puede eliminar hasta desactivarla; la herramienta se negara." else "Desactivada"),
                        row(
                            "Que pasa",
                            "Se elimina la empresa y su base de datos completa (${company.databaseName}): clientes, leads, usuarios, archivos y su registro de actividad. " +
                                "No se puede deshacer.",
                        ),
                    )
                },
            ) { call ->
                val target = call.requiredString("code", 63)
                run { provisioning().deleteInactiveCompany(target, role(call), actor(call)) }
                buildJsonObject { put("deleted", target) }
            },
            McpTool(
                name = "set_company_module",
                title = "Activar modulo en empresa",
                description = "Activa o desactiva un modulo instalado en una empresa. Al activarlo se preparan sus tablas.",
                permission = Permission,
                inputSchema = McpSchema.obj(
                    mapOf(code, moduleId, "enabled" to McpSchema.boolean("true para activar, false para desactivar.")),
                    required = listOf("code", "module_id", "enabled"),
                ),
                preview = { call ->
                    val company = brief(call)
                    val enable = call.boolean("enabled")
                    listOf(
                        row("Empresa", companyLabel(company)),
                        row("Modulo", moduleLabel(call)),
                        row(
                            "Que pasa",
                            if (enable) "Se activa en la empresa y se preparan sus tablas; sus usuarios podran usarlo segun sus permisos."
                            else "Se desactiva en la empresa: sus usuarios dejan de verlo y sus agentes de IA de usarlo. Los datos del modulo no se borran.",
                        ),
                    )
                },
            ) { call ->
                companyJson(
                    run {
                        provisioning().setModuleEnabled(call.requiredString("code", 63), call.requiredString("module_id", 63), call.boolean("enabled"), role(call), actor(call))
                    }
                )
            },
            McpTool(
                name = "list_modules",
                title = "Listar modulos del servidor",
                description = "Modulos base, instalados y disponibles en el catalogo, con su version, la ultima del catalogo, " +
                    "en cuantas empresas estan activos y el estado de una instalacion en curso.",
                permission = Permission,
                readOnly = true,
            ) { json.encodeToJsonElement(ListSerializer(ServerModuleResponse.serializer()), provisioning().listServerModules()) },
            // install_module no se expone por MCP: instalar un modulo compila y ejecuta codigo en el servidor
            // y los modulos aun no tienen firma. Solo se hace desde la app.
            McpTool(
                name = "remove_module",
                title = "Quitar modulo del servidor",
                description = "Quita un modulo opcional del servidor. Solo funciona si no esta activo en ninguna empresa ni en el espacio del servidor.",
                permission = Permission,
                inputSchema = McpSchema.obj(mapOf(moduleId), required = listOf("module_id")),
                destructive = true,
                preview = { call ->
                    val target = call.requiredString("module_id", 63)
                    val module = provisioning().moduleBrief(target, withUsage = true) ?: throw McpToolException("Modulo no reconocido: $target.")
                    listOf(
                        row("Modulo que se quita del servidor", "${module.displayName} (${module.moduleId}, version ${module.version})"),
                        row("Activo en", "${module.activeCompanyCount ?: 0} empresa(s) o en el espacio del servidor" + if ((module.activeCompanyCount ?: 0) > 0) ": la herramienta se negara hasta desactivarlo en todas." else ""),
                        row("Que pasa", if (module.locked) "Es un modulo base: no se puede quitar." else "Se quita su codigo del servidor; para usarlo otra vez hay que instalarlo de nuevo desde el catalogo."),
                    )
                },
            ) { call ->
                val target = call.requiredString("module_id", 63)
                run { provisioning().deleteServerModule(target, actor(call)) }
                buildJsonObject { put("removed", target) }
            },
            McpTool(
                name = "set_workspace_module",
                title = "Usar modulo en el espacio del servidor",
                description = "Activa o desactiva un modulo instalado en el espacio del servidor (la empresa propia del dueno del servidor).",
                permission = Permission,
                inputSchema = McpSchema.obj(
                    mapOf(moduleId, "enabled" to McpSchema.boolean("true para activar, false para desactivar.")),
                    required = listOf("module_id", "enabled"),
                ),
                preview = { call ->
                    listOf(
                        row("Modulo", moduleLabel(call)),
                        row(
                            "Que pasa",
                            if (call.boolean("enabled")) "Se activa en el espacio del servidor (el del dueno del servidor y su personal)."
                            else "Se desactiva en el espacio del servidor: dejan de verlo quienes trabajan alli. Los datos del modulo no se borran.",
                        ),
                    )
                },
            ) { call ->
                json.encodeToJsonElement(
                    ServerModuleResponse.serializer(),
                    run { provisioning().setServerModuleUsage(call.requiredString("module_id", 63), call.boolean("enabled"), role(call), actor(call)) },
                )
            },
            McpTool(
                name = "get_module_catalog",
                title = "Ver catalogo de modulos",
                description = "URL del catalogo de donde se instalan los modulos opcionales.",
                permission = Permission,
                readOnly = true,
            ) { json.encodeToJsonElement(ServerModuleCatalogResponse.serializer(), provisioning().serverModuleCatalog()) },
            // set_module_catalog no se expone por MCP: cambiar el catalogo permite instalar codigo ajeno.
            // Solo se hace desde la app.
        ) + extensions(::role)
    }

    private fun companyJson(company: CompanySummaryResponse): JsonElement =
        json.encodeToJsonElement(CompanySummaryResponse.serializer(), company)

    private fun companies(companies: List<CompanySummaryResponse>): JsonElement =
        json.encodeToJsonElement(ListSerializer(CompanySummaryResponse.serializer()), companies)

    private fun randomPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        return (1..40).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
