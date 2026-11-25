package com.ideasdeveloper.bms.server.modules

/**
 * A column of a module or extension table that stores a customers.id without a foreign key. Clientes repoints it when it
 * merges two customers and moves it back on "Separar". Names must match ^[a-z][a-z0-9_]{0,62}$; keyColumn is the table's
 * single-column primary key.
 */
data class CustomerReference(val table: String, val column: String, val keyColumn: String = "id") {
    companion object {
        // Same shape as a plain lowercase PostgreSQL identifier, so names are safe to place in SQL.
        val NamePattern = Regex("^[a-z][a-z0-9_]{0,62}$")

        /** Spanish description of what is wrong with [reference], or null when all its names are valid. */
        fun problem(reference: CustomerReference): String? = listOf(
            "tabla" to reference.table,
            "columna" to reference.column,
            "llave" to reference.keyColumn,
        ).firstOrNull { (_, name) -> !NamePattern.matches(name) }
            ?.let { (label, name) -> "El nombre de $label '$name' no es valido." }

        // Tables the Core owns or that hold customers: never a valid target, whatever the prefix.
        private val ForbiddenTable = Regex("^(customers|customer_.*|application_.*|tenant_.*|schema_migrations|business_owner.*)$")

        /**
         * Like [problem], and the table must belong to its owner: a module may use only tables starting with
         * "<moduleId>_", an extension (extensionId not null) only "ext_<extensionId>__" (as MigrationTableValidator).
         */
        fun problemFor(reference: CustomerReference, moduleId: String, extensionId: String?): String? {
            problem(reference)?.let { return it }
            val prefix = if (extensionId == null) "${moduleId}_" else "ext_${extensionId}__"
            return when {
                ForbiddenTable.matches(reference.table) -> "La tabla '${reference.table}' no se puede declarar."
                !reference.table.startsWith(prefix) -> "La tabla '${reference.table}' debe empezar por '$prefix'."
                else -> null
            }
        }
    }
}

/** The references of one module (extensionId null) or extension that is installed; used by ModuleAccessService. */
data class CustomerReferenceSource(val moduleId: String, val extensionId: String?, val references: List<CustomerReference>)

/**
 * SQL condition of the customers a company may see: its own and the copies of companies linked with it now.
 * Use it in every module query on customers (table alias-free); [visible] when the query has a JOIN.
 */
object CustomerSql {
    const val Visible = "(origin_company_code = '' OR (shared AND origin_company_code IN (SELECT company_code FROM customer_link_origins)))"

    private val AliasPattern = Regex("^[a-z][a-z0-9_]{0,30}$")

    /** [Visible] with its columns qualified by [alias] (a plain lowercase identifier, else IllegalArgumentException). */
    fun visible(alias: String): String {
        require(AliasPattern.matches(alias)) { "Alias de tabla no valido." }
        return "($alias.origin_company_code = '' OR ($alias.shared AND $alias.origin_company_code IN (SELECT company_code FROM customer_link_origins)))"
    }
}
