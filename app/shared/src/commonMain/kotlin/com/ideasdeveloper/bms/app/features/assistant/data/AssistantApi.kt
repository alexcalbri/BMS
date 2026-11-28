package com.ideasdeveloper.bms.app.features.assistant.data

import com.ideasdeveloper.bms.AssistantCompanySettingsRequest
import com.ideasdeveloper.bms.AssistantCompanySettingsResponse
import com.ideasdeveloper.bms.AssistantCompanyUsageResponse
import com.ideasdeveloper.bms.AssistantConversationDetailResponse
import com.ideasdeveloper.bms.AssistantConversationListResponse
import com.ideasdeveloper.bms.AssistantConversationResponse
import com.ideasdeveloper.bms.AssistantCreateConversationRequest
import com.ideasdeveloper.bms.AssistantDecisionRequest
import com.ideasdeveloper.bms.AssistantKeyRequest
import com.ideasdeveloper.bms.AssistantSendMessageRequest
import com.ideasdeveloper.bms.AssistantServerSettingsRequest
import com.ideasdeveloper.bms.AssistantServerSettingsResponse
import com.ideasdeveloper.bms.AssistantServerUsageResponse
import com.ideasdeveloper.bms.AssistantStatusResponse
import com.ideasdeveloper.bms.AssistantUpdateConversationRequest
import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.session.SessionExpiry
import com.ideasdeveloper.bms.app.core.session.SessionStore
import com.ideasdeveloper.bms.app.core.version.installAppVersionHeader
import com.ideasdeveloper.bms.app.features.company.data.CompanyErrorResponse
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

// Version del Core desde la que existe el asistente (GLOBAL_AGENT.md, "Contract").
const val AssistantMinCore = "0.14.0"

// Cliente del asistente global: rutas de la empresa (/companies/me/assistant) y del servidor (/server/assistant).
// Los errores se lanzan como CompanyException con status y code del servidor. Las respuestas de mensajes y confirmaciones
// son Server-Sent Events y se leen de forma incremental (ver [sendMessage] y [decide]).
class AssistantApi(serverUrl: String) {
    private val baseUrl = serverUrl.trim().trimEnd('/')

    init {
        Url(baseUrl)
    }

    private val client = HttpClient {
        installAppVersionHeader()
        expectSuccess = false
        followRedirects = false
        install(SessionExpiry)
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
    }

    private fun HttpRequestBuilder.company(token: String, companyCode: String) {
        bearerAuth(token)
        header("X-Company-Code", companyCode)
    }

    // Una respuesta puede tardar minutos (el servidor corta un turno a los 10) y el servidor manda un ": ping" cada 15 s:
    // el plazo total es amplio y el de inactividad cubre varios pings.
    private fun HttpRequestBuilder.streamTimeouts() {
        timeout {
            requestTimeoutMillis = 15 * 60_000L
            socketTimeoutMillis = 2 * 60_000L
        }
    }

    // Estado del asistente para el usuario; un 404 (Core anterior) o cualquier otro fallo es null: la app no muestra nada.
    suspend fun statusOrNull(token: String, companyCode: String): AssistantStatusResponse? = try {
        val response = client.get("$baseUrl/companies/me/assistant/status") { company(token, companyCode) }
        if (response.status == HttpStatusCode.OK) response.body() else null
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
    }

    suspend fun status(token: String, companyCode: String): AssistantStatusResponse = call(tr("No se pudo consultar el asistente.")) {
        client.get("$baseUrl/companies/me/assistant/status") { company(token, companyCode) }
    }

    // Lo pendiente de la persona (confirmaciones y respuestas sin ver); un 404 (Core anterior) llega como CompanyException con status 404.
    suspend fun attention(token: String, companyCode: String): AssistantAttention = call(tr("No se pudo consultar el asistente.")) {
        client.get("$baseUrl/companies/me/assistant/attention") { company(token, companyCode) }
    }

    // Marca una conversacion como vista (POST .../conversations/{id}/read, sin cuerpo, 204).
    suspend fun markRead(token: String, companyCode: String, id: String) {
        send(tr("No se pudo consultar el asistente.")) {
            client.post("$baseUrl/companies/me/assistant/conversations/${id.encodeURLPathPart()}/read") { company(token, companyCode) }
        }
    }

    suspend fun conversations(token: String, companyCode: String, archived: Boolean, before: String?): AssistantConversationListResponse =
        call(tr("No se pudieron cargar las conversaciones.")) {
            client.get("$baseUrl/companies/me/assistant/conversations") {
                company(token, companyCode)
                parameter("archived", archived)
                parameter("limit", 50)
                if (before != null) parameter("before", before)
            }
        }

    suspend fun createConversation(token: String, companyCode: String, title: String?): AssistantConversationResponse =
        call(tr("No se pudo crear la conversacion.")) {
            client.post("$baseUrl/companies/me/assistant/conversations") {
                company(token, companyCode)
                contentType(ContentType.Application.Json)
                setBody(AssistantCreateConversationRequest(title))
            }
        }

    suspend fun conversation(token: String, companyCode: String, id: String): AssistantConversationDetailResponse =
        call(tr("No se pudo cargar la conversacion.")) {
            client.get("$baseUrl/companies/me/assistant/conversations/${id.encodeURLPathPart()}") { company(token, companyCode) }
        }

    suspend fun updateConversation(token: String, companyCode: String, id: String, title: String?, archived: Boolean?): AssistantConversationResponse =
        call(tr("No se pudo guardar la conversacion.")) {
            client.put("$baseUrl/companies/me/assistant/conversations/${id.encodeURLPathPart()}") {
                company(token, companyCode)
                contentType(ContentType.Application.Json)
                setBody(AssistantUpdateConversationRequest(title = title, archived = archived))
            }
        }

    suspend fun deleteConversation(token: String, companyCode: String, id: String) {
        send(tr("No se pudo eliminar la conversacion.")) {
            client.delete("$baseUrl/companies/me/assistant/conversations/${id.encodeURLPathPart()}") { company(token, companyCode) }
        }
    }

    // Ajustes de la empresa (solo el business owner); un 404 se interpreta como Core anterior.
    suspend fun settings(token: String, companyCode: String): AssistantCompanySettingsResponse =
        call(tr("No se pudo cargar la configuracion del asistente.")) {
            client.get("$baseUrl/companies/me/assistant/settings") { company(token, companyCode) }
        }

    suspend fun updateSettings(token: String, companyCode: String, request: AssistantCompanySettingsRequest): AssistantCompanySettingsResponse =
        call(tr("No se pudo guardar la configuracion del asistente.")) {
            client.put("$baseUrl/companies/me/assistant/settings") {
                company(token, companyCode)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    suspend fun usage(token: String, companyCode: String, month: String?): AssistantCompanyUsageResponse =
        call(tr("No se pudo cargar el uso del asistente.")) {
            client.get("$baseUrl/companies/me/assistant/usage") {
                company(token, companyCode)
                if (month != null) parameter("month", month)
            }
        }

    // Envia un mensaje y lee la respuesta en directo: cada evento llega a [onEvent] segun se recibe. Devuelve true si el
    // flujo termino con "done"; false si la conexion se cerro antes (turno interrumpido). Cancelar la corrutina cierra el flujo.
    // Un rechazo previo al flujo (JSON con code/message) se lanza como CompanyException.
    suspend fun sendMessage(
        token: String,
        companyCode: String,
        conversationId: String,
        text: String,
        onEvent: suspend (AssistantEvent) -> Unit,
    ): Boolean = stream(
        tr("No se pudo enviar el mensaje."),
        client.preparePost("$baseUrl/companies/me/assistant/conversations/${conversationId.encodeURLPathPart()}/messages") {
            company(token, companyCode)
            streamTimeouts()
            contentType(ContentType.Application.Json)
            setBody(AssistantSendMessageRequest(text))
        },
        onEvent,
    )

    // Aprueba o rechaza una accion pendiente; la respuesta es otro flujo (el turno continua) y se lee igual que en [sendMessage].
    suspend fun decide(
        token: String,
        companyCode: String,
        confirmationId: String,
        approve: Boolean,
        onEvent: suspend (AssistantEvent) -> Unit,
    ): Boolean = stream(
        tr("No se pudo enviar la decision."),
        client.preparePost("$baseUrl/companies/me/assistant/confirmations/${confirmationId.encodeURLPathPart()}") {
            company(token, companyCode)
            streamTimeouts()
            contentType(ContentType.Application.Json)
            setBody(AssistantDecisionRequest(if (approve) "approve" else "reject"))
        },
        onEvent,
    )

    // Lee el cuerpo como canal y reparte los cuadros SSE en cuanto llegan. Los motores de Ktor de todos los destinos
    // entregan el cuerpo por trozos; si alguno lo guardara entero, los eventos llegarian igual, todos juntos al final.
    private suspend fun stream(failure: String, statement: HttpStatement, onEvent: suspend (AssistantEvent) -> Unit): Boolean {
        var finished = false
        try {
            statement.execute { response ->
                if (!response.status.isSuccess()) throw failed(response, failure)
                val channel = response.bodyAsChannel()
                val parser = SseParser()
                while (true) {
                    val line = channel.readLineOrNull() ?: break
                    val event = parser.feed(line) ?: continue
                    if (event is AssistantEvent.Done) finished = true
                    onEvent(event)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException(tr("No se pudo comunicar con el servidor."), network = true)
        }
        return finished
    }

    // Una conexion que se corta a mitad del flujo no es un error: es un turno interrumpido (el llamador lo ve por falta de "done").
    private suspend fun ByteReadChannel.readLineOrNull(): String? = try {
        readUTF8Line()
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
    }

    // Ajustes del servidor (permiso server.assistant.manage).
    suspend fun serverSettings(token: String): AssistantServerSettingsResponse = call(tr("No se pudo cargar la configuracion del asistente.")) {
        client.get("$baseUrl/server/assistant/settings") { bearerAuth(token) }
    }

    suspend fun updateServerSettings(token: String, request: AssistantServerSettingsRequest): AssistantServerSettingsResponse =
        call(tr("No se pudo guardar la configuracion del asistente.")) {
            client.put("$baseUrl/server/assistant/settings") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    suspend fun setServerKey(token: String, apiKey: String): AssistantServerSettingsResponse = call(tr("No se pudo guardar la clave.")) {
        client.put("$baseUrl/server/assistant/key") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(AssistantKeyRequest(apiKey))
        }
    }

    suspend fun removeServerKey(token: String): AssistantServerSettingsResponse = call(tr("No se pudo quitar la clave.")) {
        client.delete("$baseUrl/server/assistant/key") { bearerAuth(token) }
    }

    suspend fun serverCompanies(token: String): List<AssistantServerCompanyRow> = call<AssistantServerCompanyRowsResponse>(tr("No se pudieron cargar las empresas.")) {
        client.get("$baseUrl/server/assistant/companies") { bearerAuth(token) }
    }.companies

    // monthlyTokens null = el limite por defecto del servidor. companyEnabled y acceptDisclosure null = no se envian (no cambian).
    suspend fun updateServerCompany(
        token: String,
        code: String,
        allowed: Boolean,
        monthlyTokens: Long?,
        companyEnabled: Boolean? = null,
        acceptDisclosure: Boolean? = null,
    ): AssistantServerCompanyRow =
        call(tr("No se pudo guardar la empresa.")) {
            client.put("$baseUrl/server/assistant/companies/${code.encodeURLPathPart()}") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(AssistantServerCompanyUpdateRequest(allowed, monthlyTokens, companyEnabled, acceptDisclosure))
            }
        }

    suspend fun serverUsage(token: String, month: String?): AssistantServerUsageResponse = call(tr("No se pudo cargar el uso del asistente.")) {
        client.get("$baseUrl/server/assistant/usage") {
            bearerAuth(token)
            if (month != null) parameter("month", month)
        }
    }

    // Respuesta 2xx como [T]; si no, CompanyException con el mensaje y el codigo del servidor.
    private suspend inline fun <reified T> call(failure: String, request: () -> HttpResponse): T {
        try {
            val response = request()
            if (response.status.isSuccess()) return response.body()
            throw failed(response, failure)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException(tr("No se pudo comunicar con el servidor."), network = true)
        }
    }

    // Igual que [call] cuando no se necesita el cuerpo de la respuesta.
    private suspend inline fun send(failure: String, request: () -> HttpResponse) {
        try {
            val response = request()
            if (response.status.isSuccess()) return
            throw failed(response, failure)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException(tr("No se pudo comunicar con el servidor."), network = true)
        }
    }

    private suspend fun failed(response: HttpResponse, failure: String): CompanyException {
        val error = runCatching { response.body<CompanyErrorResponse>() }.getOrNull()
        if (error?.code == "TWO_FACTOR_SETUP_REQUIRED") SessionStore.update { it.copy(twoFactorSetupRequired = true) }
        val message = error?.message
            ?: if (response.status == HttpStatusCode.Forbidden) tr("No tienes permiso para esta accion.") else failure
        return CompanyException(message, status = response.status.value, code = error?.code)
    }

    fun close() {
        client.close()
    }
}

suspend inline fun <T> AssistantApi.use(block: (AssistantApi) -> T): T = try {
    block(this)
} finally {
    close()
}
