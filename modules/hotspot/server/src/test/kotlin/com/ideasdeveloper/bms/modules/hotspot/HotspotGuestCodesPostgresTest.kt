package com.ideasdeveloper.bms.modules.hotspot

import com.ideasdeveloper.bms.modules.hotspot.HotspotPortalPages.FormValues
import com.ideasdeveloper.bms.modules.hotspot.HotspotPortalPages.GuestContext
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guest form against a real PostgreSQL (BMS_TEST_PG_URL of a DISPOSABLE server; the test creates and drops its own database):
 * no customer before the code is entered, codes limited per device and per address without one guest locking out another.
 */
class HotspotGuestCodesPostgresTest {
    private val url = (System.getenv("BMS_TEST_PG_URL") ?: System.getenv("BMS_ACTIVITY_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val name = "bms_hotspot_codes_${System.nanoTime()}"
    private var tenant: Connection? = null

    private fun urlOf(db: String) = url!!.replace(Regex("/[^/?]+(\\?|$)"), "/$db$1")

    private fun sorted(directory: String) = File(root, directory).list()!!.filter { it.endsWith(".sql") }.sorted().map { "$directory/$it" }

    private fun open(): Connection? {
        if (url == null) return null
        DriverManager.getConnection(url).use { admin -> admin.createStatement().use { it.execute("CREATE DATABASE $name") } }
        val connection = DriverManager.getConnection(urlOf(name))
        tenant = connection
        val files = sorted("database/core/migrations") + sorted("database/tenant/migrations") + sorted("modules/clientes/migrations") +
            sorted("modules/crm/migrations") + sorted("modules/hotspot/migrations")
        files.forEach { path ->
            val sql = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
            connection.createStatement().use { it.execute(sql) }
        }
        return connection
    }

    @AfterTest
    fun drop() {
        tenant?.close()
        tenant = null
        if (url != null) DriverManager.getConnection(url).use { admin -> admin.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") } }
    }

    private fun scalar(db: Connection, sql: String) = db.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }

    private fun guest(mac: String) = GuestContext(mac, "", "WiFi Hotel", "")

    private fun pending(db: Connection, email: String, mac: String, at: Instant = Instant.now()): UUID {
        val codeId = UUID.randomUUID()
        HotspotGuestCodes.insertPending(
            db, UUID.randomUUID(), codeId, FormValues(name = "Ana", email = email, phone = "+50588887777"), null, guest(mac),
            "0".repeat(64), at.plusSeconds(900),
        )
        return codeId
    }

    @Test
    fun noCustomerIsCreatedUntilTheCodeIsEntered() {
        val db = open() ?: return
        val customersBefore = scalar(db, "SELECT count(*) FROM customers")
        val codeId = pending(db, "ana@example.com", "aa:bb:cc:dd:ee:01")
        assertEquals(customersBefore, scalar(db, "SELECT count(*) FROM customers"))
        assertEquals("1", scalar(db, "SELECT count(*) FROM hotspot_registrations WHERE customer_id IS NULL AND user_id IS NULL"))
        val registrationId = UUID.fromString(scalar(db, "SELECT registration_id FROM hotspot_codes WHERE id = '$codeId'")!!)

        // The code is entered: now the guest is a customer, linked to the registration.
        val customerId = HotspotGuestCodes.linkVerifiedCustomer(db, registrationId, "Ana", "ana@example.com", "+50588887777")
        assertEquals("${customersBefore!!.toInt() + 1}", scalar(db, "SELECT count(*) FROM customers"))
        assertEquals(customerId.toString(), scalar(db, "SELECT customer_id FROM hotspot_registrations WHERE id = '$registrationId'"))
        // A second guest with the same address reuses that customer.
        val other = UUID.fromString(scalar(db, "SELECT registration_id FROM hotspot_codes WHERE id = '${pending(db, "ana@example.com", "aa:bb:cc:dd:ee:02")}'")!!)
        assertEquals(customerId, HotspotGuestCodes.linkVerifiedCustomer(db, other, "Ana", "ana@example.com", "+50588887777"))
        assertEquals("${customersBefore.toInt() + 1}", scalar(db, "SELECT count(*) FROM customers"))
    }

    @Test
    fun aPendingCodeBelongsToTheDeviceThatAskedForIt() {
        val db = open() ?: return
        val victim = "victima@example.com"
        val attackerCode = pending(db, victim, "aa:bb:cc:dd:ee:99")
        // The device of the real owner of the address does not inherit the code that another device requested.
        assertNull(HotspotGuestCodes.recentPending(db, victim, "aa:bb:cc:dd:ee:01"))
        assertEquals(attackerCode, HotspotGuestCodes.recentPending(db, victim, "aa:bb:cc:dd:ee:99"))
        assertEquals(attackerCode, HotspotGuestCodes.recentPending(db, victim.uppercase(), "aa:bb:cc:dd:ee:99"))
        // Used codes and codes older than the resend interval are not "recent".
        db.createStatement().use { it.execute("UPDATE hotspot_codes SET created_at = CURRENT_TIMESTAMP - interval '2 minutes'") }
        assertNull(HotspotGuestCodes.recentPending(db, victim, "aa:bb:cc:dd:ee:99"))
    }

    @Test
    fun limitsAreKeyedPerDeviceAndPerAddress() {
        val db = open() ?: return
        val mac = "aa:bb:cc:dd:ee:01"
        repeat(HotspotGuestCodes.MaxPerDevice - 1) { pending(db, "uno$it@example.com", mac) }
        assertFalse(HotspotGuestCodes.overSendLimit(db, "nuevo@example.com", mac))
        pending(db, "ultimo@example.com", mac)
        // That device is out of codes for the hour, whatever address it types...
        assertTrue(HotspotGuestCodes.overSendLimit(db, "otro@example.com", mac))
        // ...but another device is not affected by it.
        assertFalse(HotspotGuestCodes.overSendLimit(db, "otro@example.com", "aa:bb:cc:dd:ee:02"))

        // One mailbox cannot be flooded from many devices either.
        repeat(HotspotGuestCodes.MaxPerEmail - 1) { pending(db, "buzon@example.com", "aa:bb:cc:00:00:%02x".format(it)) }
        assertFalse(HotspotGuestCodes.overSendLimit(db, "buzon@example.com", "aa:bb:cc:ff:ff:01"))
        pending(db, "buzon@example.com", "aa:bb:cc:00:01:00")
        assertTrue(HotspotGuestCodes.overSendLimit(db, "BUZON@example.com", "aa:bb:cc:ff:ff:01"))
        // The counts expire with the hour.
        db.createStatement().use { it.execute("UPDATE hotspot_codes SET created_at = CURRENT_TIMESTAMP - interval '2 hours'") }
        assertFalse(HotspotGuestCodes.overSendLimit(db, "buzon@example.com", mac))
        assertNotEquals(0, HotspotGuestCodes.MaxPerEmail)
    }
}
