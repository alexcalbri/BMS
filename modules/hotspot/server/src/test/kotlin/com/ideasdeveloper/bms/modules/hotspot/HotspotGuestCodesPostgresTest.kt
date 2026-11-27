package com.ideasdeveloper.bms.modules.hotspot

import com.ideasdeveloper.bms.modules.hotspot.HotspotPortalPages.FormValues
import com.ideasdeveloper.bms.modules.hotspot.HotspotGuestCodes.Activation
import com.ideasdeveloper.bms.modules.hotspot.HotspotPortalPages.GuestContext
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guest form against a real PostgreSQL (BMS_TEST_PG_URL of a DISPOSABLE server; the test creates and drops its own database):
 * one session per code shared by at most 2 devices, the wait before an address can connect again, and the limits on codes.
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

    private fun mac(n: Int) = "aa:bb:cc:dd:%02x:%02x".format(n / 256, n % 256)

    /** What the form does when it is sent: the customer exists, then the registration with its code. */
    private fun requestCode(db: Connection, email: String, mac: String, at: Instant = Instant.now()): UUID {
        val values = FormValues(name = "Ana", email = email, phone = "+50588887777")
        val customer = HotspotGuestCodes.existingCustomer(db, email) ?: HotspotActivity.findOrCreateGuestCustomer(db, values.name, email, values.phone)
        val codeId = UUID.randomUUID()
        HotspotGuestCodes.insertCode(db, UUID.randomUUID(), codeId, customer, values, null, guest(mac), "0".repeat(64), at.plusSeconds(900))
        return codeId
    }

    private fun record(db: Connection, codeId: UUID, email: String, mac: String, joined: Boolean, at: Instant) {
        db.prepareStatement(
            "INSERT INTO hotspot_accesses (id, registration_id, email, mac, authorized, minutes, unifi_message, joined, created_at) " +
                "SELECT ?, registration_id, ?, ?, TRUE, 60, 'ok', ?, ? FROM hotspot_codes WHERE id = ?",
        ).use {
            it.setObject(1, UUID.randomUUID()); it.setString(2, email); it.setString(3, mac); it.setBoolean(4, joined)
            it.setTimestamp(5, java.sql.Timestamp.from(at)); it.setObject(6, codeId); it.executeUpdate()
        }
    }

    @Test
    fun theCustomerExistsFromTheMomentTheFormIsSent() {
        val db = open() ?: return
        val before = scalar(db, "SELECT count(*) FROM customers")!!.toInt()
        val codeId = requestCode(db, "ana@example.com", mac(1))
        assertEquals("${before + 1}", scalar(db, "SELECT count(*) FROM customers"))
        assertNotNull(scalar(db, "SELECT customer_id FROM hotspot_registrations WHERE id = (SELECT registration_id FROM hotspot_codes WHERE id = '$codeId')"))
        // The same address reuses its customer.
        requestCode(db, "ANA@example.com", mac(2))
        assertEquals("${before + 1}", scalar(db, "SELECT count(*) FROM customers"))
    }

    private fun emailStatus(db: Connection, id: UUID) = scalar(db, "SELECT email_status FROM customers WHERE id = '$id'")

    private fun insertCustomer(db: Connection, email: String, status: String?): UUID {
        val id = UUID.randomUUID()
        db.prepareStatement("INSERT INTO customers (id, display_name, primary_email, primary_phone, status, email_status, email_verified_at) VALUES (?, 'Beto', ?, '', 'active', ?, CASE WHEN ? = 'verified' THEN now() END)").use {
            it.setObject(1, id); it.setString(2, email); it.setString(3, status); it.setString(4, status); it.executeUpdate()
        }
        return id
    }

    @Test
    fun aFormCustomerStartsUnverifiedAndIsVerifiedWhenTheCodeIsEntered() {
        val db = open() ?: return
        val codeId = requestCode(db, "ana@example.com", mac(1))
        val customerId = UUID.fromString(scalar(db, "SELECT customer_id FROM hotspot_registrations WHERE id = (SELECT registration_id FROM hotspot_codes WHERE id = '$codeId')")!!)
        assertEquals("unverified", emailStatus(db, customerId))
        assertEquals("customer", scalar(db, "SELECT entity_type FROM activity_log WHERE entity_id = '$customerId' ORDER BY occurred_at LIMIT 1"))
        assertTrue(HotspotActivity.verifyGuestEmail(db, customerId))
        assertEquals("verified", emailStatus(db, customerId))
        assertNotNull(scalar(db, "SELECT email_verified_at FROM customers WHERE id = '$customerId'"))
        // The log says who and what changed, from "sin verificar" to "verificado", and never a code.
        val row = scalar(db, "SELECT summary || ' ' || changes::text || ' ' || actor_name FROM activity_log WHERE entity_id = '$customerId' AND action = 'updated'")!!
        assertTrue(row.contains("sin verificar") && row.contains("verificado") && row.contains("Portal WiFi"), row)
        // Entering the code again changes nothing and writes nothing more.
        assertFalse(HotspotActivity.verifyGuestEmail(db, customerId))
        assertEquals("1", scalar(db, "SELECT count(*) FROM activity_log WHERE entity_id = '$customerId' AND action = 'updated'"))
    }

    @Test
    fun aReusedCustomerIsNeverDowngraded() {
        val db = open() ?: return
        val verified = insertCustomer(db, "v@example.com", "verified")
        val unknown = insertCustomer(db, "u@example.com", null)
        assertEquals(verified, HotspotActivity.findOrCreateGuestCustomer(db, "Otro nombre", "V@example.com", "+507"))
        assertEquals(unknown, HotspotActivity.findOrCreateGuestCustomer(db, "Otro nombre", "u@example.com", "+507"))
        assertEquals("verified", emailStatus(db, verified))
        assertEquals(null, emailStatus(db, unknown))
        // The code is entered: unknown becomes verified, verified stays (and is not written again).
        assertTrue(HotspotActivity.verifyGuestEmail(db, unknown))
        assertFalse(HotspotActivity.verifyGuestEmail(db, verified))
        assertEquals("verified", emailStatus(db, unknown))
    }

    @Test
    fun theSecondDeviceEndsWhenTheFirstOneDoes() {
        val db = open() ?: return
        val codeId = requestCode(db, "ana@example.com", mac(1))
        val t0 = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val first = activate(db, codeId, mac(1), 60, t0)
        assertIs<Activation.Started>(first)
        assertEquals(t0.plus(Duration.ofMinutes(60)), first.endsAt)
        // Another device, 10 minutes later: the same end, 50 minutes left, and no wait is asked (the session is running).
        val second = HotspotGuestCodes.activate(db, codeId, mac(2), 60, t0.plus(Duration.ofMinutes(10))) { error("no wait for a joining device") }
        assertIs<Activation.Joined>(second)
        assertEquals(first.endsAt, second.endsAt)
        assertEquals(50, second.minutes)
        // The same device enters again: it is not a third one.
        assertIs<Activation.Joined>(HotspotGuestCodes.activate(db, codeId, mac(2), 60, t0.plus(Duration.ofMinutes(20))) { null })
        // A third device is refused, also right at the end of the session.
        assertIs<Activation.DeviceLimit>(HotspotGuestCodes.activate(db, codeId, mac(3), 60, t0.plus(Duration.ofMinutes(30))) { null })
        assertEquals("2", scalar(db, "SELECT count(*) FROM hotspot_code_devices WHERE code_id = '$codeId'"))
        // A device the controller refused gives its slot back; the first one gives the code back as unused.
        HotspotGuestCodes.release(db, codeId, mac(2))
        assertIs<Activation.Joined>(HotspotGuestCodes.activate(db, codeId, mac(3), 60, t0.plus(Duration.ofMinutes(31))) { null })
    }

    private fun activate(db: Connection, codeId: UUID, mac: String, minutes: Int, at: Instant) = HotspotGuestCodes.activate(db, codeId, mac, minutes, at) { null }

    @Test
    fun afterTheTimeIsOverTheCodeAndTheAddressWaitUntilTheNextDay() {
        val db = open() ?: return
        val email = "ana@example.com"
        val codeId = requestCode(db, email, mac(1))
        val t0 = Instant.now()
        assertIs<Activation.Started>(activate(db, codeId, mac(1), 60, t0))
        record(db, codeId, email, mac(1), joined = false, at = t0)
        record(db, codeId, email, mac(2), joined = true, at = t0.plus(Duration.ofMinutes(10)))
        // Time is over: the code cannot connect a device again, the same day...
        val over = HotspotGuestCodes.activate(db, codeId, mac(2), 60, t0.plus(Duration.ofMinutes(61))) { null }
        assertIs<Activation.Over>(over)
        assertIs<Activation.Over>(HotspotGuestCodes.activate(db, codeId, mac(3), 60, t0.plus(Duration.ofHours(5))) { null })
        // ...and the address gets no new session until the reconnection time (a day by default) counted from the first activation.
        val sameDay = HotspotGuestCodes.minutesUntilReconnect(db, email, 1440, t0.plus(Duration.ofHours(5)))
        assertEquals(19 * 60, sameDay)
        assertNotNull(HotspotGuestCodes.minutesUntilReconnect(db, email, 1440, t0.plus(Duration.ofHours(23))))
        // The devices that joined later did not move that wait; the next day it is allowed again.
        assertNull(HotspotGuestCodes.minutesUntilReconnect(db, email, 1440, t0.plus(Duration.ofHours(24).plusMinutes(1))))
        // The wait is asked when a new code starts its session, not before.
        val sameDayCode = requestCode(db, email, mac(1), t0.plus(Duration.ofHours(5)))
        assertEquals(Activation.ReconnectWait(19 * 60), HotspotGuestCodes.activate(db, sameDayCode, mac(1), 60, t0.plus(Duration.ofHours(5))) { HotspotGuestCodes.minutesUntilReconnect(db, email, 1440, t0.plus(Duration.ofHours(5))) })
        val nextDayCode = requestCode(db, email, mac(1), t0.plus(Duration.ofHours(25)))
        assertIs<Activation.Started>(HotspotGuestCodes.activate(db, nextDayCode, mac(1), 60, t0.plus(Duration.ofHours(25))) { HotspotGuestCodes.minutesUntilReconnect(db, email, 1440, t0.plus(Duration.ofHours(25))) })
    }

    @Test
    fun aCodeNotUsedInTimeExpires() {
        val db = open() ?: return
        val codeId = requestCode(db, "ana@example.com", mac(1), Instant.now().minusSeconds(1000))
        assertIs<Activation.Expired>(activate(db, codeId, mac(1), 60, Instant.now()))
    }

    @Test
    fun twoDevicesAtTheSameTimeNeverMakeThree() {
        val db = open() ?: return
        val codeId = requestCode(db, "ana@example.com", mac(1))
        val now = Instant.now()
        val workers = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val results = (1..workers).map { n ->
                pool.submit<Activation> {
                    DriverManager.getConnection(urlOf(name)).use { own ->
                        start.await()
                        HotspotGuestCodes.activate(own, codeId, mac(n), 60, now) { null }
                    }
                }
            }
            start.countDown()
            val outcomes = results.map { it.get() }
            assertEquals(2, outcomes.count { it is Activation.Started || it is Activation.Joined }, outcomes.toString())
            assertEquals(1, outcomes.count { it is Activation.Started })
            assertEquals(workers - 2, outcomes.count { it is Activation.DeviceLimit })
        } finally {
            pool.shutdownNow()
        }
        assertEquals("2", scalar(db, "SELECT count(*) FROM hotspot_code_devices WHERE code_id = '$codeId'"))
        // The table itself refuses a third slot whatever the code does.
        assertFailsWith<java.sql.SQLException> { db.createStatement().use { it.execute("INSERT INTO hotspot_code_devices (code_id, slot, mac) VALUES ('$codeId', 3, '${mac(99)}')") } }
    }

    @Test
    fun aLiveCodeIsShownInsteadOfSendingAnother() {
        val db = open() ?: return
        val email = "ana@example.com"
        val now = Instant.now()
        val codeId = requestCode(db, email, mac(1))
        // Just sent: shown again (any device). Older than the resend interval and not used: a new one may be sent.
        assertEquals(codeId, HotspotGuestCodes.liveCode(db, email.uppercase(), now)?.id)
        assertNull(HotspotGuestCodes.liveCode(db, email, now.plusSeconds(120)))
        // While its session runs it stays live (a second device enters it); when it ends it is not.
        activate(db, codeId, mac(1), 60, now)
        assertEquals(HotspotGuestCodes.LiveCode(codeId, true), HotspotGuestCodes.liveCode(db, email, now.plus(Duration.ofMinutes(30))))
        assertNull(HotspotGuestCodes.liveCode(db, email, now.plus(Duration.ofMinutes(61))))
    }

    @Test
    fun codesAreLimitedPerDeviceAndPerAddress() {
        val db = open() ?: return
        val device = mac(1)
        repeat(HotspotGuestCodes.MaxPerDevice - 1) { requestCode(db, "uno$it@example.com", device) }
        assertFalse(HotspotGuestCodes.overSendLimit(db, "nuevo@example.com", device))
        requestCode(db, "ultimo@example.com", device)
        assertTrue(HotspotGuestCodes.overSendLimit(db, "otro@example.com", device))
        assertFalse(HotspotGuestCodes.overSendLimit(db, "otro@example.com", mac(2)))
        // One mailbox cannot be flooded from many devices either.
        repeat(HotspotGuestCodes.MaxPerEmail - 1) { requestCode(db, "buzon@example.com", mac(100 + it)) }
        assertFalse(HotspotGuestCodes.overSendLimit(db, "buzon@example.com", mac(300)))
        requestCode(db, "buzon@example.com", mac(200))
        assertTrue(HotspotGuestCodes.overSendLimit(db, "BUZON@example.com", mac(300)))
        db.createStatement().use { it.execute("UPDATE hotspot_codes SET created_at = CURRENT_TIMESTAMP - interval '2 hours'") }
        assertFalse(HotspotGuestCodes.overSendLimit(db, "buzon@example.com", device))
    }
}
