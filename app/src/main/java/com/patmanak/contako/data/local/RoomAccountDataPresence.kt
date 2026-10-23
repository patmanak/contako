package com.patmanak.contako.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owner admission must include all account-scoped intent, receipts and deferred imports. */
internal suspend fun ContakoDatabase.hasAccountScopedData(): Boolean = withContext(Dispatchers.IO) {
    val sql = openHelper.readableDatabase
    val tables = sql.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
        buildList { while (rows.moveToNext()) add(rows.getString(0)) }
    }
    tables.any { table ->
        val quoted = "\"${table.replace("\"", "\"\"")}\""
        val scoped = sql.query("PRAGMA table_info($quoted)").use { rows ->
            var found = false
            while (rows.moveToNext()) if (rows.getString(1) == "account_id") found = true
            found
        }
        scoped && sql.query("SELECT 1 FROM $quoted LIMIT 1").use { it.moveToFirst() }
    }
}
