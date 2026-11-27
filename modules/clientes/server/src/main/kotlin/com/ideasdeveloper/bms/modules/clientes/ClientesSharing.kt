package com.ideasdeveloper.bms.modules.clientes

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.CustomerSql
import com.ideasdeveloper.bms.server.modules.GroupCompany
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * One customer record for the companies of the same business owner (V007). The customer belongs to
 * the company where it was created: there origin_company_code is '' and [Visible] always holds.
 * Linked companies (ClientesLinking, Core company_links) copy every customer, with the same id and
 * customer code, to each other (origin_company_code = the origin, shared = TRUE); every edit then
 * reaches every copy. A copy is visible only while its origin is in customer_link_origins (the companies
 * linked now, V010), so unlinking hides every copy at once; it is hidden, not deleted, because other
 * modules may reference it.
 * General notes live only in the origin company; each company keeps its own history.
 */
object ClientesSharing {
    private val log = org.slf4j.LoggerFactory.getLogger(ClientesSharing::class.java)

    // SQL condition of the customers this company sees: its own and the copies of the companies linked with it now (Core CustomerSql).
    const val Visible = CustomerSql.Visible

    const val SharePermission = "clientes.share"

    class Customer(
        val id: UUID,
        val name: String,
        val email: String,
        val phone: String,
        val code: String,
        // Company where the customer was created.
        val origin: String,
        val shared: Boolean,
        val archived: Boolean,
        // Whether this company may see it (its own customer or a shared copy).
        val visible: Boolean,
        // "", "unverified" or "verified" (see V012).
        val emailStatus: String = "",
    )

    // Every column copied between companies (created_at too, so the oldest customer wins a merge).
    class Row(
        val id: UUID,
        val displayName: String,
        val email: String,
        val phone: String,
        val status: String,
        val customerType: String,
        val code: String,
        val attributes: String,
        val createdAt: Instant,
    )

    // Columns read by [readRow].
    const val RowColumns = "id, display_name, primary_email, primary_phone, status, customer_type, customer_code, flexible_attributes::text AS attributes, created_at"

    fun readRow(rows: ResultSet): Row = Row(
        id = rows.getObject("id", UUID::class.java),
        displayName = rows.getString("display_name"),
        email = rows.getString("primary_email"),
        phone = rows.getString("primary_phone"),
        status = rows.getString("status"),
        customerType = rows.getString("customer_type"),
        code = rows.getString("customer_code"),
        attributes = rows.getString("attributes"),
        createdAt = rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
    )

    fun customer(tenant: Connection, id: UUID, currentCode: String): Customer? = tenant.prepareStatement(
        """
        SELECT id, display_name, primary_email, primary_phone, customer_code, origin_company_code, shared, status, email_status, $Visible AS visible
        FROM customers WHERE id = ?
        """.trimIndent()
    ).use { query ->
        query.setObject(1, id)
        query.executeQuery().use { rows ->
            if (!rows.next()) return@use null
            val origin = rows.getString("origin_company_code")
            val shared = rows.getBoolean("shared")
            Customer(
                id = id,
                name = rows.getString("display_name"),
                email = rows.getString("primary_email"),
                phone = rows.getString("primary_phone"),
                code = rows.getString("customer_code"),
                origin = origin.ifBlank { currentCode },
                shared = shared,
                archived = rows.getString("status") == "archived",
                // The origin must be linked now: a copy of an unlinked company is not visible even when shared stayed TRUE.
                visible = rows.getBoolean("visible"),
                emailStatus = rows.getString("email_status").orEmpty(),
            )
        }
    }

    // Whether the customer row exists and this company may see it.
    fun visible(tenant: Connection, id: UUID): Boolean =
        tenant.prepareStatement("SELECT 1 FROM customers WHERE id = ? AND $Visible").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { it.next() }
        }

    // Whether the customer was created in this company (only there it is shared or archived).
    fun isOrigin(tenant: Connection, id: UUID): Boolean =
        tenant.prepareStatement("SELECT 1 FROM customers WHERE id = ? AND origin_company_code = ''").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { it.next() }
        }

    /**
     * After an edit or archive in this company, copies the customer's data to the other copies of a
     * shared customer (the origin and the companies it is shared with). Nothing for a customer that
     * is not shared. A row that already has the same data is not rewritten. When a destination has ANOTHER
     * active customer with the new email the change is not written over it: the same name merges the two
     * (ClientesMerge), another name leaves a review "edit" and that destination keeps its row. When the group
     * lock is not free within a moment the change is left to the job: the origin goes back to shared = FALSE
     * (the pending pass copies it) and a copy leaves a review "edit". Returns the names of the companies that
     * could not be updated.
     */
    fun propagate(access: ModuleAccessService, tenant: Connection, currentCode: String, id: UUID, by: ActivityActor): List<String> {
        val customer = customer(tenant, id, currentCode) ?: return emptyList()
        if (!customer.shared || !customer.visible) return emptyList()
        val row = row(tenant, id) ?: return emptyList()
        // Only the companies linked right now: a company that was unlinked never receives data.
        val group = access.linkedCompanies(currentCode, ClientesLinking.LinkPurpose)
        val failed = mutableListOf<String>()
        val ran = ClientesLinking.withShortLock(access, currentCode) {
            propagateLocked(access, tenant, currentCode, customer, row, group, failed, ClientesLog.sharingActor(by))
            true
        }
        if (ran == null) ClientesLinking.deferEdit(tenant, currentCode, customer.origin, row, ClientesLog.sharingActor(by))
        return failed
    }

    private fun propagateLocked(
        access: ModuleAccessService,
        tenant: Connection,
        currentCode: String,
        customer: Customer,
        row: Row,
        group: List<GroupCompany>,
        failed: MutableList<String>,
        actor: ActivityActor,
    ) {
        val groupCodes = group.map { it.code }.toSet()
        val keyCache = HashMap<String, List<ClientesMerge.ForeignKey>>()
        for (company in group.filter { it.code != currentCode }) {
            val outcome = try {
                access.tenantConnectionForCompany(company.code)?.use { target ->
                    if (!supportsSharing(target) || !ClientesLinking.supportsLinking(target)) return@use PropagateOutcome.Updated
                    propagateOne(access, tenant, currentCode, customer, row, group, groupCodes, company, target, keyCache, actor)
                } ?: PropagateOutcome.Failed
            } catch (failure: ClientesMerge.CompanyUnavailableException) {
                log.warn("Could not propagate customer {} to company {}: {}", row.id, company.code, failure.message)
                PropagateOutcome.Failed
            } catch (failure: Exception) {
                log.warn("Could not propagate customer {} to company {} ({})", row.id, company.code, ClientesLinking.describe(failure))
                PropagateOutcome.Failed
            }
            when (outcome) {
                PropagateOutcome.Failed -> failed += company.name
                // The edited customer was merged away: the merge already archived it everywhere, nothing else may be written.
                PropagateOutcome.MergedAway -> return
                else -> Unit
            }
        }
    }

    private enum class PropagateOutcome { Updated, Failed, MergedAway }

    private fun propagateOne(
        access: ModuleAccessService,
        tenant: Connection,
        currentCode: String,
        customer: Customer,
        row: Row,
        group: List<GroupCompany>,
        groupCodes: Set<String>,
        company: GroupCompany,
        target: Connection,
        keyCache: MutableMap<String, List<ClientesMerge.ForeignKey>>,
        actor: ActivityActor,
    ): PropagateOutcome {
        if (row.status != "archived" && row.email.isNotBlank()) {
            val incoming = ClientesMerge.Candidate(row, customer.origin)
            val (existing, decision) = ClientesLinking.decisionFor(access, group, currentCode, tenant, mapOf(currentCode to tenant, company.code to target), company.code, incoming)
            when (decision) {
                is ClientesMerge.Decision.Merge -> {
                    val result = ClientesMerge.merge(
                        access, group, mapOf(currentCode to tenant, company.code to target),
                        decision.loser, decision.winner, row.email, ClientesAuthor.system(actor), userCompany = null, keyCache = keyCache,
                    )
                    return when {
                        result is ClientesMerge.Result.Merged && decision.loser.row.id == row.id -> PropagateOutcome.MergedAway
                        // Review: the pair is waiting for the owner; this destination keeps its row.
                        else -> PropagateOutcome.Updated
                    }
                }
                is ClientesMerge.Decision.NameDiffers -> {
                    ClientesMerge.recordPairReview(
                        tenant, incoming, existing!!, company.code, row.email, "edit",
                        ClientesMerge.editConflictReason(row.displayName, existing.row.displayName, company.name, row.email), actor,
                    )
                    return PropagateOutcome.Updated
                }
                else -> Unit
            }
        }
        // The copy of this company is changed by itself: its own activity log says what changed (inside the transaction of the change).
        val previous = row(target, row.id)
        ClientesLog.atomically(target) { writeCopyUpdate(target, row, previous, currentCode, actor) }
        return PropagateOutcome.Updated
    }

    private fun writeCopyUpdate(target: Connection, row: Row, previous: Row?, fromCompany: String, actor: ActivityActor) {
        val written = target.prepareStatement(
            """
            UPDATE customers SET display_name = ?, primary_email = ?, primary_phone = ?, status = ?, customer_type = ?,
                flexible_attributes = CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND (display_name, primary_email, primary_phone, status, customer_type, flexible_attributes)
                IS DISTINCT FROM (CAST(? AS text), CAST(? AS text), CAST(? AS text), CAST(? AS text), CAST(? AS text), CAST(? AS jsonb))
            """.trimIndent()
        ).use { update ->
            update.setString(1, row.displayName)
            update.setString(2, row.email)
            update.setString(3, row.phone)
            update.setString(4, row.status)
            update.setString(5, row.customerType)
            update.setString(6, row.attributes)
            update.setObject(7, row.id)
            update.setString(8, row.displayName)
            update.setString(9, row.email)
            update.setString(10, row.phone)
            update.setString(11, row.status)
            update.setString(12, row.customerType)
            update.setString(13, row.attributes)
            update.executeUpdate()
        }
        if (written > 0) ClientesLog.copyWritten(target, actor, previous, row, fromCompany)
    }

    /**
     * Before changing the email of [customerId] to [newEmail]: the message to refuse the change (409) when another
     * ACTIVE customer visible in any company linked with [currentCode] (this one included) already uses it, else null.
     * [owner] is whether the editor is the business owner: only then the message names the other customer and company. A company that cannot be opened is skipped (it is logged): propagate decides again when the change reaches it.
     */
    fun emailChangeConflict(access: ModuleAccessService, tenant: Connection, currentCode: String, customerId: UUID, newEmail: String, owner: Boolean): String? {
        if (newEmail.isBlank()) return null
        for (company in access.linkedCompanies(currentCode, ClientesLinking.LinkPurpose)) {
            val found = try {
                if (company.code == currentCode) emailOwner(tenant, customerId, newEmail)
                else access.tenantConnectionForCompany(company.code)?.use { emailOwner(it, customerId, newEmail) }
            } catch (failure: Exception) {
                log.warn("Could not check the email in company {} ({})", company.code, ClientesLinking.describe(failure))
                null
            }
            if (found != null) return emailConflictMessage(found, company.name, newEmail, owner)
        }
        return null
    }

    /** Name of another active, visible customer of this database with [email] (not [customerId]), or null. */
    private fun emailOwner(db: Connection, customerId: UUID, email: String): String? {
        if (!ClientesLinking.supportsLinking(db)) return null
        return db.prepareStatement(
            "SELECT display_name FROM customers WHERE lower(primary_email) = lower(?) AND id <> ? AND status <> 'archived' AND $Visible ORDER BY created_at, id LIMIT 1"
        ).use { query ->
            query.setString(1, email.trim())
            query.setObject(2, customerId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    // Only the business owner learns who uses the email and in which company; anyone else gets this.
    val GenericEmailConflict = msg("Ya existe un cliente con ese correo en una empresa vinculada.")

    /** Text that refuses an email change ([owner]: with the name of the other customer and its company). */
    fun emailConflictMessage(customerName: String, companyName: String, email: String, owner: Boolean = true): String =
        if (!owner) tr(GenericEmailConflict) else tr("El correo {0} ya lo usa el cliente \"{1}\" ({2}). Usa otro correo o fusiona los dos clientes antes de cambiarlo.", email, customerName, companyName)

    /**
     * Companies whose tab of the customer the user sees: the business owner every company of the
     * group where the customer is (the origin and, when shared, the others); anyone else only the
     * current company, the one they are part of.
     */
    fun tabs(access: ModuleAccessService, tenant: Connection, currentCode: String, customer: Customer, businessOwner: Boolean): List<GroupCompany> {
        val group = access.groupCompanies(currentCode)
        val current = group.firstOrNull { it.code == currentCode }
            ?: access.companyOf(tenant)?.let { GroupCompany(currentCode, it.name) }
            ?: GroupCompany(currentCode, currentCode)
        if (!businessOwner) return listOf(current)
        val linkedCodes = access.linkedCompanies(currentCode, ClientesLinking.LinkPurpose).map { it.code }.toSet()
        // Only linked companies: the data of one that is not linked is never read.
        val others = group.filter { it.code != currentCode && it.code in linkedCodes }.filter { company ->
            company.code == customer.origin || (customer.shared && access.tenantConnectionForCompany(company.code)?.use { target ->
                supportsSharing(target) && visible(target, customer.id)
            } == true)
        }
        return listOf(current) + others
    }

    // Name of a company of the group, or its code when it is no longer in the group.
    fun companyName(access: ModuleAccessService, currentCode: String, code: String): String =
        access.groupCompanies(currentCode).firstOrNull { it.code == code }?.name ?: code

    /**
     * Runs [block] on the database of [code]: this one when it is the current company. Null when it cannot be opened.
     * Another company is opened only when it is linked with the current one right now (Core company_links): a customer
     * whose origin is not linked is treated as not visible ("Cliente no encontrado.").
     */
    fun <T> onCompany(access: ModuleAccessService, tenant: Connection, currentCode: String, code: String, block: (Connection) -> T): T? {
        if (code == currentCode) return block(tenant)
        if (access.linkedCompanies(currentCode, ClientesLinking.LinkPurpose).none { it.code == code }) throw ClientesSharingException(tr("Cliente no encontrado."))
        return access.tenantConnectionForCompany(code)?.use(block)
    }

    // Whether a company's database already has the V007 columns (its Clientes is up to date).
    fun supportsSharing(tenant: Connection): Boolean =
        tenant.createStatement().use { statement ->
            statement.executeQuery("SELECT to_regclass('customer_history') IS NOT NULL").use { rows -> rows.next() && rows.getBoolean(1) }
        }

    /** Inserts or refreshes the copy of [row] in [target]; a refresh keeps the copy's own created_at and writes nothing when the row is already the same. */
    fun upsertCopy(target: Connection, row: Row, origin: String, shared: Boolean) {
        target.prepareStatement(
            """
            INSERT INTO customers (id, display_name, primary_email, primary_phone, status, customer_type, customer_code, flexible_attributes, origin_company_code, shared, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET display_name = EXCLUDED.display_name, primary_email = EXCLUDED.primary_email,
                primary_phone = EXCLUDED.primary_phone, status = EXCLUDED.status, customer_type = EXCLUDED.customer_type,
                flexible_attributes = EXCLUDED.flexible_attributes, origin_company_code = EXCLUDED.origin_company_code,
                shared = EXCLUDED.shared, updated_at = CURRENT_TIMESTAMP
            WHERE (customers.display_name, customers.primary_email, customers.primary_phone, customers.status, customers.customer_type,
                    customers.flexible_attributes, customers.origin_company_code, customers.shared)
                IS DISTINCT FROM (EXCLUDED.display_name, EXCLUDED.primary_email, EXCLUDED.primary_phone, EXCLUDED.status, EXCLUDED.customer_type,
                    EXCLUDED.flexible_attributes, EXCLUDED.origin_company_code, EXCLUDED.shared)
            """.trimIndent()
        ).use { upsert ->
            upsert.setObject(1, row.id)
            upsert.setString(2, row.displayName)
            upsert.setString(3, row.email)
            upsert.setString(4, row.phone)
            upsert.setString(5, row.status)
            upsert.setString(6, row.customerType)
            upsert.setString(7, row.code)
            upsert.setString(8, row.attributes)
            upsert.setString(9, origin)
            upsert.setBoolean(10, shared)
            upsert.setObject(11, OffsetDateTime.ofInstant(row.createdAt, ZoneOffset.UTC))
            upsert.executeUpdate()
        }
    }

    fun row(tenant: Connection, id: UUID): Row? = tenant.prepareStatement("SELECT $RowColumns FROM customers WHERE id = ?").use { query ->
        query.setObject(1, id)
        query.executeQuery().use { rows -> if (rows.next()) readRow(rows) else null }
    }
}

open class ClientesSharingException(message: String) : Exception(message)
