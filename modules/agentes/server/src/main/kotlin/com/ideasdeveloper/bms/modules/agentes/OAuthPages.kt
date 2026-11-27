package com.ideasdeveloper.bms.modules.agentes

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.i18n.trIn
import com.ideasdeveloper.bms.server.modules.RegisteredMcpTool
import com.ideasdeveloper.bms.server.modules.VerifiedUser
import io.ktor.http.Parameters
import java.net.URI

/** Server-rendered pages of the OAuth authorization (sign in and consent). Every value is escaped. */
object OAuthPages {
    // Authorization parameters carried from the client's request to the sign-in form.
    private val carried = listOf("client_id", "redirect_uri", "state", "response_type", "code_challenge", "code_challenge_method", "resource", "scope")

    fun signIn(request: OAuthRequest, params: Parameters, error: String?, company: String): String {
        val hidden = carried.mapNotNull { key -> params[key]?.let { hiddenInput(key, it) } }.joinToString("")
        val client = "<strong>${esc(request.client.name)}</strong>"
        return page(
            tr("Conectar con BMS"),
            """
            <p class="lead">${tr("{0} quiere conectarse a BMS en tu nombre.", client)}</p>
            <p class="muted">${tr("Inicia sesion. El agente solo podra usar las funciones que tu usuario tiene permitidas.")}</p>
            ${error?.let { "<p class=\"error\">${esc(it)}</p>" }.orEmpty()}
            <form method="post" action="${AgentOAuth.Base}/authorize" autocomplete="on">
              <input type="hidden" name="step" value="signin">$hidden
              <label>${tr("Empresa")}
                <input name="company" value="${esc(company)}" autocapitalize="none" autocorrect="off" spellcheck="false" placeholder="${esc(tr("codigo de la empresa"))}">
              </label>
              <p class="hint">${tr("Dejalo vacio si eres el dueno del servidor.")}</p>
              <label>${tr("Usuario")}
                <input name="username" required autocapitalize="none" autocorrect="off" spellcheck="false" autocomplete="username">
              </label>
              <label>${tr("Contrasena")}
                <input name="password" type="password" required autocomplete="current-password">
              </label>
              <button type="submit">${tr("Continuar")}</button>
            </form>
            ${returnNote(request)}
            """,
        )
    }

    fun consent(ticket: String, request: OAuthRequest, user: VerifiedUser, tools: List<RegisteredMcpTool>, error: String?): String {
        val modules = tools.groupBy { it.moduleId }.map { (id, list) -> Triple(id, list.first().moduleName, list) }.sortedBy { it.second }
        val moduleChecks = modules.joinToString("") { (id, name, list) ->
            val items = list.sortedBy { it.tool.title }.joinToString("") { registered ->
                val kind = if (registered.tool.readOnly) tr("consulta") else tr("modifica")
                "<li>${esc(registered.tool.title)} <span class=\"tag ${if (registered.tool.readOnly) "read" else "write"}\">$kind</span></li>"
            }
            """
            <div class="module">
              <label class="check"><input type="checkbox" name="m_${esc(id)}" value="true" checked> ${esc(trIn(I18n.moduleNamespace(id), name))}</label>
              <ul>$items</ul>
            </div>
            """
        }
        val expirations = AgentesScreens.expirations.keys.joinToString("") { option ->
            "<option value=\"${esc(option)}\"${if (option == "90 dias") " selected" else ""}>${esc(tr(option))}</option>"
        }
        val who = when {
            user.serverOwner -> tr("dueno del servidor")
            user.serverAdmin -> tr("{0} · administrador del servidor (solo lectura de la auditoria)", esc(user.username))
            else -> tr("{0} · empresa {1}", esc(user.username), esc(user.companyCode))
        }
        val client = "<strong>${esc(request.client.name)}</strong>"
        return page(
            tr("Autorizar agente"),
            """
            <p class="lead">${tr("{0} podra usar BMS como {1}.", client, "<strong>$who</strong>")}</p>
            <p class="muted">${tr("Nunca podra hacer algo que tu usuario no tenga permitido. Si te quitan un permiso, el agente lo pierde de inmediato. Puedes revocarlo cuando quieras en Agentes IA.")}</p>
            ${error?.let { "<p class=\"error\">${esc(it)}</p>" }.orEmpty()}
            <form method="post" action="${AgentOAuth.Base}/authorize">
              <input type="hidden" name="ticket" value="${esc(ticket)}">
              <h2>${tr("Modulos que puede usar")}</h2>
              ${moduleChecks.ifEmpty { "<p class=\"error\">${tr("Aun no tienes funciones que un agente pueda usar.")}</p>" }}
              <h2>${tr("Limites")}</h2>
              <label class="check"><input type="checkbox" name="readOnly" value="true"> ${tr("Solo lectura: puede consultar, pero no crear ni modificar")}</label>
              ${if (user.serverOwner || user.serverAdmin) "" else "<label class=\"check\"><input type=\"checkbox\" name=\"groupScope\" value=\"true\"> ${tr("Tambien en las demas empresas de mi grupo (solo si eres el dueno de la empresa)")}</label>"}
              <label>${tr("La conexion vence en")}
                <select name="expires">$expirations</select>
              </label>
              <div class="actions">
                <button type="submit" name="step" value="consent">${tr("Permitir")}</button>
                <button type="submit" name="step" value="cancel" class="secondary" formnovalidate>${tr("Cancelar")}</button>
              </div>
            </form>
            ${returnNote(request)}
            """,
        )
    }

    fun error(message: String): String = page(tr("No se pudo conectar"), "<p class=\"error\">${esc(message)}</p>")

    private fun returnNote(request: OAuthRequest): String {
        val host = runCatching { URI(request.redirectUri).host }.getOrNull().orEmpty()
        // El nombre del cliente lo escribe quien lo registra: el dominio de retorno es lo unico fiable, y se avisa si no es de un servicio conocido.
        val shownHost = "<strong>${esc(host)}</strong>"
        val warning = if (isKnownRedirectHost(host)) "" else
            "<p class=\"error\">${tr("Atencion: {0} no es un servicio de IA conocido. Continua solo si reconoces ese dominio.", shownHost)}</p>"
        return "<p class=\"hint\">${tr("Al terminar volveras a {0}.", shownHost)}</p>$warning"
    }

    // Dominios de los clientes MCP habituales; localhost es el de las aplicaciones de escritorio.
    private val knownRedirectHosts = listOf("claude.ai", "claude.com", "anthropic.com", "chatgpt.com", "openai.com", "localhost", "127.0.0.1")

    internal fun isKnownRedirectHost(host: String): Boolean {
        val name = host.lowercase()
        return knownRedirectHosts.any { name == it || name.endsWith(".$it") }
    }

    private fun hiddenInput(name: String, value: String) = "<input type=\"hidden\" name=\"${esc(name)}\" value=\"${esc(value)}\">"

    fun esc(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    private fun page(title: String, body: String) = """
        <!doctype html>
        <html lang="${I18n.language()}">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="robots" content="noindex">
        <title>${esc(title)} · BMS</title>
        <style>
          :root { --bg:#f4f5f7; --card:#fff; --text:#1c1f24; --muted:#5d6470; --border:#d8dce2; --accent:#2457d6; --on-accent:#fff; --danger:#b42318; --read:#e6eefc; --write:#fdf1dc; }
          @media (prefers-color-scheme: dark) {
            :root { --bg:#14161a; --card:#1d2026; --text:#e8eaee; --muted:#9aa1ad; --border:#343943; --accent:#6d93ff; --on-accent:#0d1220; --danger:#ff8a80; --read:#22314f; --write:#43341a; }
          }
          * { box-sizing:border-box; }
          body { margin:0; background:var(--bg); color:var(--text); font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }
          main { max-width:460px; margin:40px auto; padding:0 16px; }
          .card { background:var(--card); border:1px solid var(--border); border-radius:14px; padding:24px; }
          h1 { font-size:22px; margin:0 0 12px; }
          h2 { font-size:15px; margin:20px 0 8px; }
          .brand { font-weight:600; color:var(--muted); margin-bottom:12px; }
          .lead { margin:0 0 8px; }
          .muted, .hint { color:var(--muted); font-size:14px; }
          .hint { margin:4px 0 12px; }
          .error { color:var(--danger); font-weight:500; }
          label { display:block; margin:12px 0 0; font-size:14px; font-weight:500; }
          input:not([type=checkbox]), select { display:block; width:100%; margin-top:6px; padding:10px 12px; font:inherit; color:var(--text); background:var(--bg); border:1px solid var(--border); border-radius:10px; }
          .check { display:flex; gap:8px; align-items:center; font-weight:500; }
          .module { border:1px solid var(--border); border-radius:10px; padding:10px 12px; margin:8px 0; }
          .module ul { margin:6px 0 0; padding-left:28px; font-size:14px; color:var(--muted); }
          .tag { font-size:12px; padding:1px 6px; border-radius:6px; }
          .tag.read { background:var(--read); } .tag.write { background:var(--write); }
          button { margin-top:20px; width:100%; padding:12px; font:inherit; font-weight:600; border:0; border-radius:10px; background:var(--accent); color:var(--on-accent); cursor:pointer; }
          button.secondary { background:transparent; color:var(--text); border:1px solid var(--border); }
          .actions { display:flex; gap:12px; } .actions button { flex:1; }
        </style>
        </head>
        <body>
        <main>
          <div class="card">
            <div class="brand">${tr("BMS · Agentes IA")}</div>
            <h1>${esc(title)}</h1>
            $body
          </div>
        </main>
        </body>
        </html>
    """.trimIndent()
}
