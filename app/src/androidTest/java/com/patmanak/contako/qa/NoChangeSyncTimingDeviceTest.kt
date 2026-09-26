package com.patmanak.contako.qa

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.proton.GateCAuthDiagnostic
import com.patmanak.contako.data.proton.GateCAuthDiagnosticEvent
import com.patmanak.contako.data.proton.GateCAuthPhase
import com.patmanak.contako.data.proton.GateCDataMutationClass
import com.patmanak.contako.data.proton.GateCLocalRequestBudgetExceeded
import com.patmanak.contako.data.proton.GateCRequestAudit
import com.patmanak.contako.data.proton.GateCRequestClass
import com.patmanak.contako.data.proton.ProtonGateCRuntime
import com.patmanak.contako.ui.SyncActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly gated 5+30 ordinary no-change timing campaign on the retained candidate. */
@RunWith(AndroidJUnit4::class)
class NoChangeSyncTimingDeviceTest {
    @Test
    fun convergedDirectoryMeetsOrdinaryNoChangeGate() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "No-change timing was not explicitly enabled",
            InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT) == "true",
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.READ_CONTACTS,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.WRITE_CONTACTS,
        )
        val application = instrumentation.targetContext.applicationContext as ContakoApplication
        val audit = NoChangeRequestAudit(MAX_TOTAL_REQUESTS)
        val proton = ProtonGateCRuntime.createForInstrumentedGateC(
            instrumentation.targetContext,
            audit,
            NoChangeAuthDiagnostic,
        )
        val database = ContakoDatabase.create(instrumentation.targetContext)
        try {
            val account = requireNotNull(database.androidProjectionLedgerDao().getAccount(proton.accountScope.value))
            val androidAccountName = requireNotNull(account.androidAccountName)
            assertEquals(EXPECTED_CONTACTS, cleanDurableSnapshot(database).contactCount)

            val warmups = mutableListOf<NoChangeSample>()
            val samples = mutableListOf<NoChangeSample>()
            repeat(WARMUP_COUNT + SAMPLE_COUNT) { index ->
                val beforeDurable = cleanDurableSnapshot(database)
                val beforeProvider = providerVersionSnapshot(application, androidAccountName)
                val beforeAudit = audit.snapshot()
                val startedAt = SystemClock.elapsedRealtime()
                val completion = async {
                    var observedRunning = false
                    application.syncRecoveryDataSource.observeActivity(proton.accountScope.value)
                        .onEach { if (it == SyncActivity.RUNNING) observedRunning = true }
                        .first { observedRunning && it == SyncActivity.IDLE }
                }
                application.syncRecoveryDataSource.requestSync(proton.accountScope.value)
                withTimeout(PASS_TIMEOUT_MILLIS) { completion.await() }
                val durationMillis = SystemClock.elapsedRealtime() - startedAt
                val afterAudit = audit.snapshot()
                val afterProvider = providerVersionSnapshot(application, androidAccountName)
                val afterDurable = cleanDurableSnapshot(database)
                val auditDelta = afterAudit.deltaFrom(beforeAudit)
                val requestCount = auditDelta.requests
                assertTrue(
                    "REQUEST_CEILING_SAMPLE_${index + 1}_" + auditDelta.safeSummary(),
                    requestCount <= MAX_REQUESTS_PER_PASS,
                )
                assertTrue(
                    "REQUEST_CLASS_SAMPLE_${index + 1}_" + auditDelta.safeSummary(),
                    auditDelta.contacts <= MAX_CONTACT_REQUESTS_PER_PASS &&
                        auditDelta.groups <= MAX_GROUP_REQUESTS_PER_PASS &&
                        auditDelta.other == 0 && auditDelta.revokes == 0,
                )
                assertEquals("REMOTE_MUTATION_SAMPLE_${index + 1}", beforeAudit.mutations, afterAudit.mutations)
                assertEquals("CANONICAL_CHANGED_SAMPLE_${index + 1}", beforeDurable, afterDurable)
                assertEquals("PROVIDER_CHANGED_SAMPLE_${index + 1}", beforeProvider, afterProvider)
                val sample = NoChangeSample(durationMillis, requestCount, auditDelta.contacts, auditDelta.groups)
                if (index < WARMUP_COUNT) warmups += sample else samples += sample
            }
            val p95 = samples.map(NoChangeSample::durationMillis).sorted()[P95_INDEX]
            assertTrue("NO_CHANGE_P95_${p95}ms", p95 <= MAX_P95_MILLIS)
            val summary = buildString {
                append("CONTAKO_NO_CHANGE_TIMING warmups=")
                append(warmups.joinToString(",") { it.durationMillis.toString() })
                append(" samples=").append(samples.joinToString(",") { it.durationMillis.toString() })
                append(" requests=").append(samples.joinToString(",") { it.requestCount.toString() })
                append(" contact_requests=").append(samples.joinToString(",") { it.contactRequests.toString() })
                append(" group_requests=").append(samples.joinToString(",") { it.groupRequests.toString() })
                append(" p95_ms=").append(p95)
                append(" canonical_changes=0 provider_changes=0 mutations=0")
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$summary\n") })
        } finally {
            database.close()
        }
    }

    private fun cleanDurableSnapshot(database: ContakoDatabase): DurableSnapshot {
        val contacts = cleanCount(database, "android_projection_ledger")
        val memberships = cleanCount(database, "android_group_membership_projection_ledger")
        val groups = cleanCount(database, "android_group_projection_ledger")
        val pendingBindings = scalar(
            database,
            "SELECT COUNT(*) FROM android_provider_row_bindings WHERE state != 'ATTACHED'",
        )
        val photoJournals = scalar(database, "SELECT COUNT(*) FROM android_photo_provider_write_journal")
        check(contacts.second == 0 && memberships.second == 0 && groups.second == 0)
        check(pendingBindings == 0 && photoJournals == 0)
        val contactRevisionSum = scalar(database, "SELECT COALESCE(SUM(revision), 0) FROM contacts")
        val groupRevisionSum = scalar(database, "SELECT COALESCE(SUM(revision), 0) FROM contact_groups")
        return DurableSnapshot(contacts.first, memberships.first, groups.first, contactRevisionSum, groupRevisionSum)
    }

    private fun cleanCount(database: ContakoDatabase, table: String): Pair<Int, Int> =
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*), COALESCE(SUM(CASE WHEN projection_state != 'CLEAN' THEN 1 ELSE 0 END), 0) " +
                "FROM $table",
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0) to cursor.getInt(1)
        }

    private fun scalar(database: ContakoDatabase, query: String): Int =
        database.openHelper.readableDatabase.query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun providerVersionSnapshot(
        application: ContakoApplication,
        accountName: String,
    ): ProviderVersionSnapshot = ProviderVersionSnapshot(
        rawContacts = providerCountAndVersion(
            application,
            ContactsContract.RawContacts.CONTENT_URI,
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.VERSION,
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
            accountName,
        ),
        groups = providerCountAndVersion(
            application,
            ContactsContract.Groups.CONTENT_URI,
            ContactsContract.Groups._ID,
            ContactsContract.Groups.VERSION,
            "${ContactsContract.Groups.ACCOUNT_NAME} = ? AND ${ContactsContract.Groups.ACCOUNT_TYPE} = ?",
            accountName,
        ),
    )

    private fun providerCountAndVersion(
        application: ContakoApplication,
        uri: android.net.Uri,
        idColumn: String,
        versionColumn: String,
        selection: String,
        accountName: String,
    ): CountAndVersion {
        var count = 0
        var versionSum = 0L
        application.contentResolver.query(
            uri,
            arrayOf(idColumn, versionColumn),
            selection,
            arrayOf(accountName, ContakoAndroidAccountContract.ACCOUNT_TYPE),
            "$idColumn ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                count++
                versionSum = Math.addExact(versionSum, cursor.getLong(1))
            }
        } ?: error("PROVIDER_QUERY_UNAVAILABLE")
        return CountAndVersion(count, versionSum)
    }

    private data class NoChangeSample(
        val durationMillis: Long,
        val requestCount: Int,
        val contactRequests: Int,
        val groupRequests: Int,
    )
    private data class CountAndVersion(val count: Int, val versionSum: Long)
    private data class ProviderVersionSnapshot(val rawContacts: CountAndVersion, val groups: CountAndVersion)
    private data class DurableSnapshot(
        val contactCount: Int,
        val membershipCount: Int,
        val groupCount: Int,
        val contactRevisionSum: Int,
        val groupRevisionSum: Int,
    )

    private companion object {
        const val ENABLE_ARGUMENT = "contakoNoChangeTiming"
        const val EXPECTED_CONTACTS = 101
        const val WARMUP_COUNT = 5
        const val SAMPLE_COUNT = 30
        const val P95_INDEX = 28
        const val MAX_P95_MILLIS = 5_000L
        // The retained 101-contact directory requires two bounded Proton Core contact-inventory
        // requests, followed by one group-inventory request. This ceiling is dataset-specific.
        const val MAX_CONTACT_REQUESTS_PER_PASS = 2
        const val MAX_GROUP_REQUESTS_PER_PASS = 1
        const val MAX_REQUESTS_PER_PASS = MAX_CONTACT_REQUESTS_PER_PASS + MAX_GROUP_REQUESTS_PER_PASS
        const val MAX_TOTAL_REQUESTS = (WARMUP_COUNT + SAMPLE_COUNT) * MAX_REQUESTS_PER_PASS
        const val PASS_TIMEOUT_MILLIS = 60_000L
    }
}

private object NoChangeAuthDiagnostic : GateCAuthDiagnostic {
    override fun onPhase(phase: GateCAuthPhase) = Unit
    override fun onFailure(event: GateCAuthDiagnosticEvent) = Unit
}

private class NoChangeRequestAudit(private val maximumRequests: Int) : GateCRequestAudit {
    private var requests = 0
    private var mutations = 0
    private var contacts = 0
    private var groups = 0
    private var other = 0
    private var revokes = 0

    @Synchronized
    override fun onRequest(requestClass: GateCRequestClass) {
        requests++
        when (requestClass) {
            GateCRequestClass.CONTACT -> contacts++
            GateCRequestClass.GROUP -> groups++
            GateCRequestClass.OTHER -> other++
            GateCRequestClass.SESSION_REVOKE -> revokes++
        }
        if (requests > maximumRequests) throw GateCLocalRequestBudgetExceeded()
    }

    @Synchronized
    override fun onDataMutationAttempt(mutationClass: GateCDataMutationClass) {
        mutations++
        throw GateCLocalRequestBudgetExceeded()
    }

    @Synchronized
    fun snapshot(): NoChangeAuditSnapshot = NoChangeAuditSnapshot(
        requests,
        mutations,
        contacts,
        groups,
        other,
        revokes,
    )
}

private data class NoChangeAuditSnapshot(
    val requests: Int,
    val mutations: Int,
    val contacts: Int,
    val groups: Int,
    val other: Int,
    val revokes: Int,
) {
    fun deltaFrom(before: NoChangeAuditSnapshot): NoChangeAuditSnapshot = NoChangeAuditSnapshot(
        requests - before.requests,
        mutations - before.mutations,
        contacts - before.contacts,
        groups - before.groups,
        other - before.other,
        revokes - before.revokes,
    )

    fun safeSummary(): String =
        "total=${requests}_contact=${contacts}_group=${groups}_other=${other}_revoke=${revokes}"
}
