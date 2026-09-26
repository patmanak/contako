package com.patmanak.contako.qa

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.data.android.provider.CanonicalPhotoBinaryLoader
import com.patmanak.contako.data.android.provider.AndroidDisplayPhotoBinaryLoader
import com.patmanak.contako.data.android.provider.productionAndroidProjectionCoordinator
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.sync.AndroidBoundedPageResult
import com.patmanak.contako.data.sync.AndroidInteroperabilityContext
import com.patmanak.contako.data.sync.AndroidProjectionRepairAggregate
import com.patmanak.contako.data.sync.AndroidProjectionReplanAggregate
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly gated, category-only diagnostic for an owner-authorized existing candidate. */
@RunWith(AndroidJUnit4::class)
class ProjectionRepairAggregateDiagnosticDeviceTest {
    @Test
    fun traverseExistingCandidateWithoutEmittingStableIdentifiers() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Existing-candidate projection diagnostic was not explicitly enabled",
            InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT) == "true",
        )
        val database = ContakoDatabase.create(instrumentation.targetContext)
        try {
            val accountId = readOnlyAccountId(database)
            val account = requireNotNull(database.androidProjectionLedgerDao().getAccount(accountId))
            val androidAccountName = requireNotNull(account.androidAccountName)
            val aggregate = AndroidProjectionRepairAggregate()
            val replans = AndroidProjectionReplanAggregate()
            val skips = linkedMapOf<String, Int>()
            val coordinator = productionAndroidProjectionCoordinator(
                database = database,
                contentResolver = instrumentation.targetContext.contentResolver,
                binaryLoader = CanonicalPhotoBinaryLoader,
                skipObserver = { _, result ->
                    val name = result.safeName()
                    skips[name] = skips.getOrDefault(name, 0) + 1
                },
                repairObserver = aggregate,
                replanObserver = replans,
            )
            val context = AndroidInteroperabilityContext(
                account = AccountScope(accountId),
                androidAccountName = androidAccountName,
                accountRevision = account.revision,
                providerEpoch = account.providerEpoch,
            )
            val outcomes = linkedMapOf<String, Int>()
            var afterKey: String? = null
            var pages = 0
            var items = 0
            do {
                val (page, result) = coordinator.projectPage(context, afterKey)
                pages++
                require(pages <= MAX_PAGES) { "Projection diagnostic page bound exceeded" }
                items += page.itemCount
                val outcome = result.safeName()
                outcomes[outcome] = outcomes.getOrDefault(outcome, 0) + 1
                afterKey = page.nextKey
            } while (afterKey != null)

            val categories = aggregate.snapshotAndReset()
            val replanCategories = replans.snapshotAndReset()
            assertTrue(categories.values.sum() <= items)
            val summary = buildString {
                append("CONTAKO_PROJECTION_REPAIRS pages=").append(pages)
                append(" items=").append(items)
                append(" categories=")
                append(categories.entries.sortedBy { it.key.name }.joinToString(",") { (category, count) ->
                    "${category.name.lowercase()}=$count"
                }.ifEmpty { "none" })
                append(" outcomes=")
                append(outcomes.entries.joinToString(",") { (outcome, count) -> "$outcome=$count" })
                append(" skips=")
                append(skips.entries.joinToString(",") { (outcome, count) -> "$outcome=$count" }.ifEmpty { "none" })
                append(" replans=")
                append(replanCategories.entries.sortedBy { it.key.name }.joinToString(",") { (category, count) ->
                    "${category.name.lowercase()}=$count"
                }.ifEmpty { "none" })
                append(" photo_probe=").append(photoProbe(database, androidAccountName))
                append(" durable=").append(durableStateSummary(database))
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$summary\n") })
        } finally {
            database.close()
        }
    }

    private fun readOnlyAccountId(database: ContakoDatabase): String {
        database.openHelper.readableDatabase.query(
            "SELECT account_id FROM android_projection_accounts ORDER BY account_id LIMIT 2",
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Exactly one projection account is required" }
            val accountId = cursor.getString(0)
            require(!cursor.moveToNext()) { "Exactly one projection account is required" }
            return accountId
        }
    }

    private fun durableStateSummary(database: ContakoDatabase): String = listOf(
        "contacts" to stateCounts(database, "android_projection_ledger", "projection_state"),
        "memberships" to stateCounts(
            database,
            "android_group_membership_projection_ledger",
            "projection_state",
        ),
        "groups" to stateCounts(database, "android_group_projection_ledger", "projection_state"),
        "bindings" to stateCounts(database, "android_provider_row_bindings", "state"),
        "group_journals" to stateCounts(database, "android_group_provider_write_journal", "state"),
        "photo_journals" to rowCount(database, "android_photo_provider_write_journal").toString(),
    ).joinToString("+") { (name, counts) -> "$name:$counts" }

    private fun stateCounts(database: ContakoDatabase, table: String, column: String): String =
        database.openHelper.readableDatabase.query(
            "SELECT $column, COUNT(*) FROM $table GROUP BY $column ORDER BY $column",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add("${cursor.getString(0).lowercase()}=${cursor.getInt(1)}")
            }.joinToString(",").ifEmpty { "none" }
        }

    private fun rowCount(database: ContakoDatabase, table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun AndroidBoundedPageResult.safeName(): String = when (this) {
        AndroidBoundedPageResult.Applied -> "applied"
        AndroidBoundedPageResult.PartiallyApplied -> "partially_applied"
        AndroidBoundedPageResult.ReplanRequired -> "replan_required"
        AndroidBoundedPageResult.RepairRequired -> "repair_required"
        AndroidBoundedPageResult.LocalPersistenceFailure -> "local_persistence_failure"
    }

    /** Payload-free capability probe for the one prepared photo command, when one exists. */
    private fun photoProbe(database: ContakoDatabase, accountName: String): String {
        val prepared = database.openHelper.readableDatabase.query(
            "SELECT raw_contact_locator, binary_reference " +
                "FROM android_photo_provider_write_journal WHERE state = 'PREPARED' LIMIT 2",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return "none"
            val result = cursor.getLong(0) to cursor.getString(1)
            if (cursor.moveToNext()) return "multiple"
            result
        }
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val rawUri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, prepared.first)
            .buildUpon().appendPath(ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY).build()
        val contactId = resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts._ID} = ? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} = ?",
            arrayOf(prepared.first.toString(), accountName),
            null,
        )?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
        val aggregateUri = contactId?.let {
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, it)
                .buildUpon().appendPath(ContactsContract.Contacts.Photo.DISPLAY_PHOTO).build()
        }
        val canonical = AndroidDisplayPhotoBinaryLoader(CanonicalPhotoBinaryLoader).load(prepared.second)
        return listOf(
            "canonical-${imageClass(canonical)}",
            "raw-${imageClass(readBounded(resolver, rawUri))}",
            "aggregate-${imageClass(aggregateUri?.let { readBounded(resolver, it) })}",
        ).joinToString("+")
    }

    private fun readBounded(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
    ): ByteArray? = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.createInputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1_024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_PHOTO_PROBE_BYTES)
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
    }.getOrNull()

    private fun imageClass(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return "missing"
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "invalid"
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        val edgeClass = when {
            edge <= 16 -> "le16"
            edge <= 32 -> "le32"
            edge <= 64 -> "le64"
            edge <= 128 -> "le128"
            edge <= 256 -> "le256"
            edge <= 512 -> "le512"
            else -> "gt512"
        }
        val byteClass = when {
            bytes.size <= 1 * 1_024 -> "le1k"
            bytes.size <= 4 * 1_024 -> "le4k"
            bytes.size <= 16 * 1_024 -> "le16k"
            else -> "gt16k"
        }
        val aspectClass = when {
            minOf(bounds.outWidth, bounds.outHeight) <= 4 -> "ultrathin"
            minOf(bounds.outWidth, bounds.outHeight) <= 16 -> "thin"
            else -> "regular"
        }
        return "valid-$edgeClass-$byteClass-$aspectClass"
    }

    private companion object {
        const val ENABLE_ARGUMENT = "contakoProjectionRepairDiagnostic"
        const val MAX_PAGES = 10
        const val MAX_PHOTO_PROBE_BYTES = 10 * 1_024 * 1_024
    }
}
