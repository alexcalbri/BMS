package com.ideasdeveloper.bms.extensions.inmobiliaria

import com.ideasdeveloper.bms.modules.crm.CrmAudit
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import java.sql.Connection
import java.util.UUID

/**
 * What the extension writes in the company activity log (ACTIVITY_LOG.md): properties, deals (with their parties), the deal
 * stages and the settings. Every record is made inside the transaction of the change by [InmobiliariaService] with the actor of
 * the session (the person, "Agente de <usuario>", or the import).
 */
object InmobiliariaAudit {
    const val PropertyType = "property"
    const val DealType = "deal"

    val PropertyLabels = linkedMapOf(
        "code" to "Codigo", "title" to "Titulo", "kind" to "Tipo", "zone" to "Zona", "development" to "Desarrollo", "status" to "Estado",
        "listingAgent" to "Agente del listado", "ownerCustomer" to "Vendedor", "lotAreaM2" to "Area del lote (m2)", "builtAreaM2" to "Area construida (m2)",
        "bedrooms" to "Habitaciones", "bathrooms" to "Banos", "price" to "Precio", "currency" to "Moneda", "description" to "Descripcion", "notes" to "Notas",
        "wordpressListingId" to "Listado del sitio", "publishedOn" to "Fecha de publicacion", "soldOn" to "Fecha de venta",
    )

    val DealLabels = linkedMapOf(
        "stage" to "Etapa", "buyerLead" to "Lead comprador", "buyerCustomer" to "Cliente comprador", "agreedPrice" to "Precio acordado",
        "commissionPct" to "Comision (%)", "commissionAmount" to "Comision (monto)", "currency" to "Moneda", "estimatedCloseOn" to "Cierre estimado",
        "closedOn" to "Cierre real", "comments" to "Comentarios", "parties" to "Partes",
    )

    fun propertyLabel(property: Property) = "${property.code} - ${property.title}"

    private fun name(tenant: Connection, sql: String, id: UUID?): String? = id?.let {
        tenant.prepareStatement(sql).use { query ->
            query.setObject(1, it)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else it.toString() }
        }
    }

    private fun userName(tenant: Connection, id: UUID?) = name(tenant, "SELECT postgres_role FROM application_users WHERE id = ?", id)

    private fun customerName(tenant: Connection, id: UUID?) = name(tenant, "SELECT display_name FROM customers WHERE id = ?", id)

    private fun leadName(tenant: Connection, id: UUID?) =
        name(tenant, "SELECT c.display_name FROM crm_leads l JOIN customers c ON c.id = l.customer_id WHERE l.id = ?", id)

    fun propertyValues(tenant: Connection, property: Property): Map<String, Any?> = mapOf(
        "code" to property.code, "title" to property.title, "kind" to property.kind.label, "zone" to property.zone, "development" to property.development,
        "status" to property.status.label, "listingAgent" to userName(tenant, property.listingAgentId), "ownerCustomer" to customerName(tenant, property.ownerCustomerId), "lotAreaM2" to property.lotAreaM2,
        "builtAreaM2" to property.builtAreaM2, "bedrooms" to property.bedrooms, "bathrooms" to property.bathrooms, "price" to property.price,
        "currency" to property.currency, "description" to property.description, "notes" to property.notes, "wordpressListingId" to property.wordpressListingId,
        "publishedOn" to property.publishedOn, "soldOn" to property.soldOn,
    )

    fun dealLabel(deal: Deal) = "Transaccion de ${deal.propertyCode} - ${deal.propertyTitle}".trim()

    fun dealValues(tenant: Connection, deal: Deal): Map<String, Any?> = mapOf(
        "stage" to deal.stageName, "buyerLead" to leadName(tenant, deal.buyerLeadId), "buyerCustomer" to customerName(tenant, deal.buyerCustomerId), "agreedPrice" to deal.agreedPrice,
        "commissionPct" to deal.commissionPct, "commissionAmount" to deal.commissionAmount, "currency" to deal.currency, "estimatedCloseOn" to deal.estimatedCloseOn,
        "closedOn" to deal.closedOn, "comments" to deal.comments,
        "parties" to deal.parties.map { "${it.role.label}: ${it.name.ifEmpty { userName(tenant, it.userId) ?: customerName(tenant, it.customerId).orEmpty() }} (${it.sharePct.stripTrailingZeros().toPlainString()}%)" },
    )

    fun created(tenant: Connection, actor: ActivityActor, type: String, id: UUID, label: String, noun: String, values: Map<String, Any?>, labels: Map<String, String>) =
        CrmAudit.record(tenant, actor, type, id.toString(), label, ActivityActions.Created, "Creo $noun $label.", ActivityChanges.created(values, labels))

    fun updated(tenant: Connection, actor: ActivityActor, type: String, id: UUID, label: String, noun: String, before: Map<String, Any?>, after: Map<String, Any?>, labels: Map<String, String>) {
        val changes = ActivityChanges.diff(before, after, labels)
        if (changes.isNotEmpty()) CrmAudit.record(tenant, actor, type, id.toString(), label, ActivityActions.Updated, "Modifico $noun $label.", changes)
    }

    fun status(tenant: Connection, actor: ActivityActor, type: String, id: UUID, label: String, summary: String, changes: List<ActivityChange>) =
        CrmAudit.record(tenant, actor, type, id.toString(), label, ActivityActions.StatusChanged, summary, changes)
}
