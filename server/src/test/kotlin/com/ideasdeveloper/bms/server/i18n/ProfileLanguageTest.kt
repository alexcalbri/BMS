package com.ideasdeveloper.bms.server.i18n

import com.ideasdeveloper.bms.server.auth.api.UpdateAccountProfileRequest
import com.ideasdeveloper.bms.server.auth.application.AccountException
import com.ideasdeveloper.bms.server.auth.application.AccountPasswords
import com.ideasdeveloper.bms.server.auth.application.AccountService
import com.ideasdeveloper.bms.server.auth.application.KeepLanguage
import com.ideasdeveloper.bms.server.auth.domain.LoginSuccessResponse
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.api.UpdateCompanyProfileRequest
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileLanguageTest {
    // Same decoder settings as the server (Application.module).
    private val json = Json { encodeDefaults = true }

    @Test
    fun profileRequestTellsKeepFromFollowTheCompany() {
        // Older apps do not send the field: the language does not change.
        assertEquals(KeepLanguage, json.decodeFromString<UpdateAccountProfileRequest>("""{"displayName":"Ana","email":"ana@x.com"}""").language)
        // null: follow the company default.
        assertNull(json.decodeFromString<UpdateAccountProfileRequest>("""{"displayName":"Ana","email":"ana@x.com","language":null}""").language)
        assertEquals("en", json.decodeFromString<UpdateAccountProfileRequest>("""{"displayName":"Ana","email":"ana@x.com","language":"en"}""").language)
        // Company profile: left out keeps the default language.
        assertNull(json.decodeFromString<UpdateCompanyProfileRequest>("""{"name":"Acme","primaryColor":"#000000","secondaryColor":"#000000","accentColor":"#000000"}""").defaultLanguage)
    }

    @Test
    fun loginResponseCarriesTheLanguages() {
        val response = LoginSuccessResponse("u", "ana", "token", 60, language = "en", companyLanguage = "es", userLanguage = "en")
        val text = json.encodeToString(LoginSuccessResponse.serializer(), response)
        assertTrue(text.contains("\"language\":\"en\"") && text.contains("\"companyLanguage\":\"es\""), text)
        // Older servers' answers decode with the source language.
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(LoginSuccessResponse.serializer(), """{"userId":"u","username":"ana","accessToken":"t","expiresInSeconds":1}""")
        assertEquals("es", old.language)
        assertEquals("es", old.companyLanguage)
    }

    @Test
    fun anUnsupportedLanguageIsRejectedBeforeTouchingTheDatabase() {
        val noDatabase = object : DataSource {
            override fun getConnection(): Connection = error("no database in this test")
            override fun getConnection(username: String?, password: String?): Connection = error("no database in this test")
            override fun getLogWriter(): PrintWriter? = null
            override fun setLogWriter(out: PrintWriter?) = Unit
            override fun setLoginTimeout(seconds: Int) = Unit
            override fun getLoginTimeout(): Int = 0
            override fun getParentLogger(): Logger = Logger.getGlobal()
            override fun <T : Any?> unwrap(iface: Class<T>?): T = error("unwrap")
            override fun isWrapperFor(iface: Class<*>?): Boolean = false
        }
        val databases = LoginDatabases(noDatabase, Database.connect(noDatabase), "jdbc:postgresql://localhost/none", "[]", "bms", "x", "jdbc:postgresql://localhost/", 60)
        val service = AccountService(noDatabase, databases, { null }, AccountPasswords({ _, _ -> }, { _, _ -> }))
        val failure = assertFailsWith<AccountException> {
            I18n.withLanguage("en") { service.updateProfile("t".repeat(43), "acme", "Ana Perez", "ana@example.com", language = "fr") }
        }
        assertEquals("INVALID_LANGUAGE", failure.code)
        assertEquals("The language is not valid.", failure.message)
        databases.close()
    }
}
