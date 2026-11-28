package com.ideasdeveloper.bms.server.extensions

import java.sql.Connection

/** Runs [block] in one transaction of this connection: commit on success, rollback and rethrow on failure. */
internal fun <T> Connection.inTransaction(block: () -> T): T {
    val previous = autoCommit
    autoCommit = false
    try {
        val result = block()
        commit()
        return result
    } catch (failure: Throwable) {
        runCatching { rollback() }
        throw failure
    } finally {
        autoCommit = previous
    }
}
