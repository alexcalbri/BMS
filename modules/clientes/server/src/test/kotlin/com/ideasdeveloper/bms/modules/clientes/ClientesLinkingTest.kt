package com.ideasdeveloper.bms.modules.clientes

import com.ideasdeveloper.bms.server.modules.CustomerReference
import com.ideasdeveloper.bms.server.modules.CustomerSql
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Pure rules of the third delivery (policy of names, references without foreign key, visibility, cursor, logs); no database.
class ClientesLinkingTest {
    private val group = setOf("alfa", "beta")

    private fun row(name: String, email: String = "ana@ejemplo.com", at: String = "2024-01-01T00:00:00Z") =
        ClientesSharing.Row(UUID.randomUUID(), name, email, "", "active", "general", "ABCDEFGH", "{}", Instant.parse(at))

    // ---- A. Same email: merge by itself only with the same name ----------------------------------

    @Test
    fun sameEmailAndSameNormalizedNameMerges() {
        val incoming = ClientesMerge.Candidate(row("Ana  Perez", at = "2024-05-01T00:00:00Z"), "alfa")
        val existing = ClientesMerge.Candidate(row(" ana perez ", at = "2022-05-01T00:00:00Z"), "beta")
        val decision = ClientesMerge.decide(incoming, existing, group, separated = false)
        assertIs<ClientesMerge.Decision.Merge>(decision)
        assertEquals(existing.row.id, decision.winner.row.id)
        assertEquals(incoming.row.id, decision.loser.row.id)
    }

    @Test
    fun sameEmailAndDifferentNameIsReviewAndNothingIsMerged() {
        val incoming = ClientesMerge.Candidate(row("Ana Perez"), "alfa")
        val existing = ClientesMerge.Candidate(row("Ana Maria Perez"), "beta")
        assertIs<ClientesMerge.Decision.NameDiffers>(ClientesMerge.decide(incoming, existing, group, separated = false))
        // The same company never merges its own duplicates, and no email never matches.
        assertIs<ClientesMerge.Decision.Copy>(ClientesMerge.decide(incoming, ClientesMerge.Candidate(existing.row, "alfa"), group, separated = false))
        assertIs<ClientesMerge.Decision.Copy>(ClientesMerge.decide(ClientesMerge.Candidate(row("A", email = ""), "alfa"), existing, group, separated = false))
    }

    @Test
    fun reviewTextsSayNothingWasTouched() {
        val text = ClientesMerge.nameDiffersReason("Ana Perez", "Ana Maria Perez", "Beta", "ana@ejemplo.com")
        assertContains(text, "Fusiona a mano o deja separados")
        assertContains(ClientesMerge.editConflictReason("Ana", "Ana Maria", "Beta", "ana@ejemplo.com"), "El cambio no se llevo a esa empresa")
        assertEquals("copy", ClientesMerge.failureCode("ventas"))
        assertEquals("accounts", ClientesMerge.failureCode("customer_accounts"))
    }

    // ---- B. Columns without a foreign key follow the merge ---------------------------------------

    @Test
    fun declaredReferencesJoinTheCatalogForeignKeysWithoutRepeating() {
        val catalog = listOf(
            ClientesMerge.ForeignKey("public", "customer_history", "customer_id", "id"),
            ClientesMerge.ForeignKey("public", "ventas", "customer_id", "venta_id"),
        )
        val declared = ClientesMerge.fromDeclared(
            listOf(
                CustomerReference("tareas_tasks", "customer_id"),
                // Also a real foreign key: it is listed once, with the catalog's key.
                CustomerReference("ventas", "customer_id", "id"),
                CustomerReference("reservas", "cliente", "reserva_id"),
            )
        )
        val union = ClientesMerge.unionReferences(catalog, declared)
        assertEquals(listOf("customer_history", "ventas", "tareas_tasks", "reservas"), union.map { it.table })
        assertEquals("venta_id", union.first { it.table == "ventas" }.pk)
        assertEquals("id", union.first { it.table == "tareas_tasks" }.pk)
        assertEquals("public", union.first { it.table == "reservas" }.schema)
    }

    @Test
    fun aDeclaredColumnIsRepointedAndMayBeRevertedButAnUnknownOneMayNot() {
        val union = ClientesMerge.unionReferences(
            listOf(ClientesMerge.ForeignKey("public", "customer_history", "customer_id", "id")),
            ClientesMerge.fromDeclared(listOf(CustomerReference("tareas_tasks", "customer_id"))),
        )
        val tareas = union.first { it.table == "tareas_tasks" }
        // Repointed like any foreign key (identifiers quoted) and remembered with its key column.
        assertEquals("""UPDATE "public"."tareas_tasks" SET "customer_id" = ? WHERE "customer_id" = ? RETURNING "id"::text""", ClientesMerge.repointSql(tareas))
        assertTrue(ClientesMerge.repointable(union).any { it.table == "tareas_tasks" })
        val moved = ClientesMerge.movedFor("alfa", tareas, listOf("t1", "t2"))
        assertTrue(moved.reversible)
        // Separar's allowed list holds both kinds; a tampered log or an undeclared table is refused.
        assertTrue(ClientesMerge.allowed(moved, union))
        assertTrue(ClientesMerge.allowed(ClientesMerge.movedFor("alfa", union.first(), listOf("a")), union))
        assertFalse(ClientesMerge.allowed(ClientesMerge.movedFor("alfa", ClientesMerge.ForeignKey("public", "otra", "customer_id", "id"), listOf("a")), union))
        assertFalse(ClientesMerge.allowed(moved, listOf(union.first())))
    }

    // ---- C1. Only the copies of the companies linked now are visible -----------------------------

    @Test
    fun copiesAreVisibleOnlyWhileTheirOriginIsInTheLinkOriginsTable() {
        assertEquals(CustomerSql.Visible, ClientesSharing.Visible)
        assertContains(ClientesSharing.Visible, "customer_link_origins")
        // Unlinking removes the origin row (nothing is rewritten) and the company sees no copy of it any more.
        val current = setOf("beta", "gamma")
        val wanted = ClientesLinking.desiredOrigins(listOf("alfa", "beta"), "alfa")
        val (add, remove) = ClientesLinking.originChanges(current, wanted)
        assertEquals(setOf("beta"), wanted)
        assertTrue(add.isEmpty())
        assertEquals(setOf("gamma"), remove)
    }

    @Test
    fun originsOfALoneCompanyAreEmptyAndAnUpToDateTableIsNotWritten() {
        assertTrue(ClientesLinking.desiredOrigins(listOf("alfa"), "alfa").isEmpty())
        assertTrue(ClientesLinking.desiredOrigins(emptyList(), "alfa").isEmpty())
        assertEquals(setOf("beta", "gamma"), ClientesLinking.desiredOrigins(listOf("alfa", "beta", "gamma"), "alfa"))
        val (add, remove) = ClientesLinking.originChanges(setOf("beta"), setOf("beta"))
        assertTrue(add.isEmpty() && remove.isEmpty())
    }

    // ---- C3. An email change that duplicates another customer ------------------------------------

    @Test
    fun emailChangeMessageNamesTheOtherCustomerAndCompany() {
        val message = ClientesSharing.emailConflictMessage("Ana Perez", "Beta", "ana@ejemplo.com")
        assertContains(message, "ana@ejemplo.com")
        assertContains(message, "Ana Perez")
        assertContains(message, "Beta")
    }

    @Test
    fun anEditedEmailThatMatchesAnotherCustomerFollowsTheSamePolicy() {
        val edited = ClientesMerge.Candidate(row("Ana Perez", at = "2024-02-01T00:00:00Z"), "alfa")
        val sameName = ClientesMerge.Candidate(row("ana perez", at = "2023-02-01T00:00:00Z"), "beta")
        val otherName = ClientesMerge.Candidate(row("Luisa Gomez"), "beta")
        assertIs<ClientesMerge.Decision.Merge>(ClientesMerge.decide(edited, sameName, group, separated = false))
        assertIs<ClientesMerge.Decision.NameDiffers>(ClientesMerge.decide(edited, otherName, group, separated = false))
        assertIs<ClientesMerge.Decision.Skip>(ClientesMerge.decide(edited, otherName, group, separated = true))
    }

    // ---- C4. Logs carry no customer data ---------------------------------------------------------

    @Test
    fun logDescriptionHasClassAndSqlStateButNeverTheMessage() {
        val failure = java.sql.SQLException("ERROR: duplicate key Detail: Key (primary_email)=(ana@ejemplo.com) already exists.", "23505")
        val text = ClientesLinking.describe(RuntimeException("wrapper", failure))
        assertContains(text, "SQLException")
        assertContains(text, "23505")
        assertFalse(text.contains("ana@ejemplo.com"))
        assertEquals("IllegalStateException", ClientesLinking.describe(IllegalStateException("x@y.com")))
        assertEquals("-", ClientesLinking.describe(null))
    }

    // ---- D. Cursor of the first copy and batches ---------------------------------------------------

    @Test
    fun aDestinationThatIsDownKeepsItsCursor() {
        val cursor = UUID.randomUUID()
        val last = UUID.randomUUID()
        assertEquals(last, ClientesLinking.cursorAfter(cursor, last, reached = true))
        assertEquals(cursor, ClientesLinking.cursorAfter(cursor, last, reached = false))
        // A batch that did nothing keeps it too; the first batch of a new destination starts at NULL.
        assertEquals(cursor, ClientesLinking.cursorAfter(cursor, null, reached = true))
        assertNull(ClientesLinking.cursorAfter(null, null, reached = false))
        assertEquals(last, ClientesLinking.cursorAfter(null, last, reached = true))
    }

    @Test
    fun batchesAreShortSoTheLockIsReleasedOften() {
        assertTrue(ClientesLinking.BatchSize <= 100)
        assertContains(ClientesLinking.pendingSql(withCursor = false), "LIMIT ${ClientesLinking.BatchSize}")
    }

    @Test
    fun onlyTheOwnerLearnsWhoUsesTheEmail() {
        val generic = ClientesSharing.emailConflictMessage("Ana Perez", "Beta", "ana@ejemplo.com", owner = false)
        assertEquals(ClientesSharing.GenericEmailConflict, generic)
        assertFalse(generic.contains("Ana") || generic.contains("Beta"))
    }

    @Test
    fun aUuidKeyIsRevertedAsUuidAndOtherKeysAsText() {
        val uuidKey = ClientesMerge.ForeignKey("public", "t", "customer_id", "id", pkUuid = true)
        assertEquals("""UPDATE "public"."t" SET "customer_id" = ? WHERE "customer_id" = ? AND "id" = ANY (CAST(? AS uuid[]))""", ClientesMerge.revertSql(uuidKey))
        assertContains(ClientesMerge.revertSql(ClientesMerge.ForeignKey("public", "t", "customer_id", "id")), "\"id\"::text = ANY (?)")
    }

    @Test
    fun theBusyMessageIsInSpanishWithoutAccents() {
        assertEquals("Hay una copia en curso, intenta en un minuto.", ClientesLinking.BusyMessage)
    }
}
