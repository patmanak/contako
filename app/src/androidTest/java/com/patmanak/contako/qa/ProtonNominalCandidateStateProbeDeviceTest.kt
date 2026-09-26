package com.patmanak.contako.qa

import android.accounts.AccountManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.BuildConfig
import com.patmanak.contako.android.account.ContakoAndroidAccountContract
import com.patmanak.contako.data.local.ContakoDatabase
import com.patmanak.contako.data.sync.RoomSyncStatusStore
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactValueKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/** Payload-free local-state probe used only by the ordered Proton nominal gate. */
@RunWith(AndroidJUnit4::class)
class ProtonNominalCandidateStateProbeDeviceTest {
    @Test
    fun reportPayloadFreeCandidateState() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_MODE) == MODE_PROBE)
        verifyCandidateState()
    }

    internal fun verifyCandidateState() {
        require(BuildConfig.APPLICATION_ID == ProtonNominalCandidateResidueCleaner.CANDIDATE_APPLICATION_ID) {
            "PN_PROBE_CANDIDATE_ISOLATION"
        }
        require(ContakoAndroidAccountContract.ACCOUNT_TYPE == BuildConfig.APPLICATION_ID) {
            "PN_PROBE_ACCOUNT_TYPE"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as ContakoApplication
        val accountScope = application.protonGateCRuntime.accountScope.value

        val local = runBlocking {
            val contacts = application.contactRepository.observeContacts(accountScope).first()
            val groups = application.contactRepository.observeGroups(accountScope).first()
            val hydratedContacts = contacts.map { contact ->
                if (contact.displayName == RICH_ROLE ||
                    (contact.firstName == RICH_FIRST_NAME && contact.lastName == RICH_LAST_NAME)
                ) {
                    application.contactRepository.getContact(accountScope, contact.id) ?: contact
                } else {
                    contact
                }
            }
            LocalSnapshot(
                contacts = hydratedContacts,
                groups = groups,
                pending = application.contactRepository.observePendingMutationCount(accountScope).first(),
                syncState = application.syncRecoveryDataSource.observeStatus(accountScope).first(),
                syncActivity = application.syncRecoveryDataSource.observeActivity(accountScope).first().name,
            )
        }

        val androidAccountCount = AccountManager.get(context)
            .getAccountsByType(ContakoAndroidAccountContract.ACCOUNT_TYPE)
            .size
        val rawContactCount = context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
            arrayOf(ContakoAndroidAccountContract.ACCOUNT_TYPE),
            null,
        )?.use { it.count } ?: -1
        val androidGroupCount = context.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID),
            "${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND ${ContactsContract.Groups.DELETED} = 0",
            arrayOf(ContakoAndroidAccountContract.ACCOUNT_TYPE),
            null,
        )?.use { it.count } ?: -1
        val richOracleFailures = richOracleFailures(local.contacts, local.groups)
        val contactActionReasons = local.contacts
            .flatMap { it.actionRequiredReasons }
            .groupingBy { it }
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(",") { (reason, count) -> "$reason:$count" }
            .ifEmpty { "NONE" }
        val diagnosticDatabase = ContakoDatabase.create(context)
        val diagnostic = try {
            DiagnosticSnapshot(
                status = runBlocking { RoomSyncStatusStore(diagnosticDatabase).load(accountScope) },
                contacts = ledgerCounts(diagnosticDatabase, CONTACT_LEDGER_TABLE),
                groups = ledgerCounts(diagnosticDatabase, GROUP_LEDGER_TABLE),
                memberships = ledgerCounts(diagnosticDatabase, MEMBERSHIP_LEDGER_TABLE),
            )
        } finally {
            diagnosticDatabase.close()
        }
        val invariantFailures = buildList {
            if (androidAccountCount != 1) add("ANDROID_ACCOUNT_COUNT")
            if (local.contacts.size != EXPECTED_CONTACTS) add("CONTACT_COUNT")
            if (local.groups.size != EXPECTED_GROUPS) add("GROUP_COUNT")
            if (local.pending != 0) add("PENDING_MUTATIONS")
            if (local.syncState?.state?.name != "CURRENT") add("SYNC_STATE")
            if (local.syncState?.actionRequiredCount != 0) add("ACTION_REQUIRED")
            if (local.syncActivity != "IDLE") add("SYNC_ACTIVITY")
            if (diagnostic.status?.actionReason != null) add("ACTION_REASON")
            if (rawContactCount != EXPECTED_CONTACTS) add("RAW_CONTACT_COUNT")
            if (androidGroupCount != EXPECTED_GROUPS) add("ANDROID_GROUP_COUNT")
            if (diagnostic.contacts != LedgerCounts(EXPECTED_CONTACTS, EXPECTED_CONTACTS)) {
                add("CONTACT_PROJECTION_LEDGERS")
            }
            if (diagnostic.groups != LedgerCounts(EXPECTED_GROUPS, EXPECTED_GROUPS)) {
                add("GROUP_PROJECTION_LEDGERS")
            }
            if (diagnostic.memberships != LedgerCounts(EXPECTED_CONTACTS, EXPECTED_CONTACTS)) {
                add("MEMBERSHIP_PROJECTION_LEDGERS")
            }
            if (richOracleFailures.isNotEmpty()) add("RICH_ORACLE")
            if (contactActionReasons != "NONE") add("CONTACT_ACTION_REASONS")
        }

        instrumentation.sendStatus(0, Bundle().apply {
            putString("pn_probe_result", if (invariantFailures.isEmpty()) "PASS" else "FAIL")
            putString("pn_probe_failure", invariantFailures.joinToString(",").ifEmpty { "NONE" })
            putInt("pn_probe_contacts", local.contacts.size)
            putInt("pn_probe_groups", local.groups.size)
            putInt("pn_probe_pending", local.pending)
            putString("pn_probe_sync_state", local.syncState?.state?.name ?: "NONE")
            putString("pn_probe_sync_action_reason", diagnostic.status?.actionReason?.name ?: "NONE")
            putInt("pn_probe_action_required", local.syncState?.actionRequiredCount ?: -1)
            putString("pn_probe_sync_activity", local.syncActivity)
            putInt("pn_probe_android_accounts", androidAccountCount)
            putInt("pn_probe_raw_contacts", rawContactCount)
            putInt("pn_probe_android_groups", androidGroupCount)
            putInt("pn_probe_clean_contact_ledgers", diagnostic.contacts.clean)
            putInt("pn_probe_clean_group_ledgers", diagnostic.groups.clean)
            putInt("pn_probe_clean_membership_ledgers", diagnostic.memberships.clean)
            putString("pn_probe_rich_oracle", if (richOracleFailures.isEmpty()) "PASS" else "FAIL")
            putString("pn_probe_rich_failures", richOracleFailures.joinToString(",").ifEmpty { "NONE" })
            putString("pn_probe_contact_action_reasons", contactActionReasons)
        })

        assertEquals(emptyList<String>(), invariantFailures)
    }

    private fun ledgerCounts(database: ContakoDatabase, table: String): LedgerCounts {
        require(table in PROJECTION_LEDGER_TABLES)
        return database.openHelper.readableDatabase.query(
            "SELECT COUNT(*), SUM(CASE WHEN projection_state = 'CLEAN' THEN 1 ELSE 0 END) FROM $table",
        ).use { cursor ->
            check(cursor.moveToFirst())
            LedgerCounts(cursor.getInt(0), if (cursor.isNull(1)) 0 else cursor.getInt(1))
        }
    }

    internal fun richOracleFailures(
        contacts: List<CanonicalContact>,
        groups: List<ContactGroup>,
        allowProviderImageEncoding: Boolean = false,
    ): List<String> {
        val exactDisplayMatches = contacts.filter { it.displayName == RICH_ROLE }
        val structuredNameMatches = contacts.filter {
            it.firstName == RICH_FIRST_NAME && it.lastName == RICH_LAST_NAME
        }
        val contact = when {
            exactDisplayMatches.size == 1 -> exactDisplayMatches.single()
            exactDisplayMatches.size > 1 -> return listOf("CONTACT_DISPLAY_DUPLICATE")
            structuredNameMatches.size == 1 -> structuredNameMatches.single()
            structuredNameMatches.size > 1 -> return listOf("CONTACT_STRUCTURED_NAME_DUPLICATE")
            else -> return listOf("CONTACT_NOT_FOUND")
        }
        val failures = mutableListOf<String>()
        val emails = contact.valuesOf(ContactValueKind.EMAIL)
        val preferredEmails = emails.filter { it.isPrimary }
        val preferredEmail = preferredEmails.singleOrNull()
        fun requireExactValues(
            kind: ContactValueKind,
            vararg expected: RichValueExpectation,
        ) {
            val actual = contact.valuesOf(kind)
            if (actual.size != expected.size) {
                failures += "COUNT_${kind.name}"
                return
            }
            actual.zip(expected).forEachIndexed { index, (value, oracle) ->
                if (value.value != oracle.value && value.value != oracle.alternativeValue) {
                    failures += "VALUE_${kind.name}_$index"
                }
                if (value.label.orEmpty() != oracle.label) failures += "LABEL_${kind.name}_$index"
                if (value.order != oracle.order) failures += "ORDER_${kind.name}_$index"
                if (value.isPrimary != oracle.primary) failures += "PRIMARY_${kind.name}_$index"
                if (oracle.components != null && value.components != oracle.components) {
                    failures += "COMPONENTS_${kind.name}_$index"
                }
            }
        }
        if (contact.displayName != RICH_ROLE) failures += "DISPLAY_NAME"
        if (contact.firstName != RICH_FIRST_NAME) failures += "FIRST_NAME"
        if (contact.lastName != RICH_LAST_NAME) failures += "LAST_NAME"
        requireExactValues(
            ContactValueKind.STRUCTURED_NAME,
            RichValueExpectation(
                value = "$RICH_LAST_NAME;$RICH_FIRST_NAME;;;",
                primary = true,
                components = mapOf(
                    "family" to RICH_LAST_NAME,
                    "given" to RICH_FIRST_NAME,
                    "additional" to "",
                    "prefix" to "",
                    "suffix" to "",
                ),
            ),
        )
        requireExactValues(
            ContactValueKind.EMAIL,
            RichValueExpectation("$RICH_ROLE@example.test", "work", 0, true),
            RichValueExpectation("$RICH_ROLE-secondary@example.test", "home", 1),
        )
        if (preferredEmails.size != 1) failures += "EMAIL_PRIMARY_COUNT"
        requireExactValues(
            ContactValueKind.PHONE,
            RichValueExpectation("+12025550100", "work", 0, true),
            RichValueExpectation("+12025550101", "home", 1),
        )
        requireExactValues(
            ContactValueKind.POSTAL_ADDRESS,
            RichValueExpectation(
                "1 Synthetic Way, Test City, FR",
                "work",
                0,
                true,
                mapOf(
                    "po_box" to "",
                    "extended" to "",
                    "street" to "1 Synthetic Way",
                    "locality" to "Test City",
                    "region" to "",
                    "postal_code" to "",
                    "country" to "FR",
                ),
            ),
        )
        requireExactValues(
            ContactValueKind.ORGANIZATION,
            RichValueExpectation(
                "Contako QA",
                order = 0,
                primary = true,
                components = mapOf(
                    "component_0" to "Contako QA",
                    "company" to "Contako QA",
                    "department" to "",
                ),
            ),
        )
        requireExactValues(ContactValueKind.TITLE, RichValueExpectation("Nominal fixture", order = 0, primary = true))
        requireExactValues(ContactValueKind.ROLE, RichValueExpectation("Test contact", order = 0, primary = true))
        requireExactValues(ContactValueKind.NICKNAME, RichValueExpectation("PN Rich", order = 0, primary = true))
        // D-066 does not generate URL/RELATED types. Their locally authored labels do not exist
        // in this fresh remote import; imported type preservation is exercised by later edit cases.
        requireExactValues(ContactValueKind.URL, RichValueExpectation("https://example.test/contako", order = 0, primary = true))
        requireExactValues(ContactValueKind.RELATIONSHIP, RichValueExpectation("Synthetic relation", order = 0, primary = true))
        requireExactValues(
            ContactValueKind.NOTE,
            RichValueExpectation("$RICH_ROLE-primary-note", order = 0, primary = true),
            RichValueExpectation("$RICH_ROLE-preserved-note", order = 1),
        )
        // Maintained vCard serialization emits the basic form; both approved representations
        // denote the exact same date. No different year/month/day or date-time is accepted.
        requireExactValues(ContactValueKind.BIRTHDAY, RichValueExpectation("--02-29", order = 0, primary = true, alternativeValue = "--0229"))
        requireExactValues(ContactValueKind.ANNIVERSARY, RichValueExpectation("2020-09-30", order = 0, primary = true, alternativeValue = "20200930"))
        requireExactValues(ContactValueKind.LANGUAGE, RichValueExpectation("fr", order = 0, primary = true))
        requireExactValues(ContactValueKind.TIME_ZONE, RichValueExpectation("Europe/Paris", order = 0, primary = true))
        requireExactValues(ContactValueKind.GENDER, RichValueExpectation("O;synthetic", order = 0, primary = true))
        val photos = contact.valuesOf(ContactValueKind.PHOTO)
        if (photos.size != 2) failures += "PHOTO_COUNT"
        if (photos.getOrNull(0)?.order != 0 || photos.getOrNull(0)?.isPrimary != true) failures += "PHOTO_PRIMARY"
        if (photos.getOrNull(1)?.order != 1 || photos.getOrNull(1)?.isPrimary != false) failures += "PHOTO_GALLERY"
        if (photos.getOrNull(0)?.let { decodedImageColor(it.value, allowProviderImageEncoding) } != Color.rgb(109, 74, 255)) {
            failures += "PHOTO_PRIMARY_CONTENT"
        }
        if (photos.getOrNull(1)?.let { decodedImageColor(it.value, allowProviderImageEncoding) } != Color.rgb(82, 82, 204)) {
            failures += "PHOTO_GALLERY_CONTENT"
        }
        val logos = contact.valuesOf(ContactValueKind.LOGO)
        if (logos.size != 1) failures += "LOGO_COUNT"
        if (logos.singleOrNull()?.order != 0 || logos.singleOrNull()?.isPrimary != true) failures += "LOGO_PRIMARY"
        if (logos.singleOrNull()?.let { decodedImageColor(it.value) } != Color.rgb(128, 128, 255)) {
            failures += "LOGO_CONTENT"
        }
        val expectedKinds = setOf(
            ContactValueKind.STRUCTURED_NAME,
            ContactValueKind.EMAIL,
            ContactValueKind.PHONE,
            ContactValueKind.POSTAL_ADDRESS,
            ContactValueKind.ORGANIZATION,
            ContactValueKind.TITLE,
            ContactValueKind.ROLE,
            ContactValueKind.NICKNAME,
            ContactValueKind.URL,
            ContactValueKind.RELATIONSHIP,
            ContactValueKind.NOTE,
            ContactValueKind.BIRTHDAY,
            ContactValueKind.ANNIVERSARY,
            ContactValueKind.LANGUAGE,
            ContactValueKind.TIME_ZONE,
            ContactValueKind.GENDER,
            ContactValueKind.PHOTO,
            ContactValueKind.LOGO,
            ContactValueKind.UNKNOWN_VCARD_PROPERTY,
        )
        // Proton uses clear-card CATEGORIES as group-import metadata. Group membership has its
        // own exact email-identity oracle below; arbitrary standalone categories are not a
        // server-retained preservation sentinel. The encrypted unknown-property sentinel remains.
        if (contact.values.map { it.kind }.filterNot { it == ContactValueKind.CATEGORY }.toSet() != expectedKinds) {
            failures += "VALUE_KIND_SET"
        }
        val unknown = contact.valuesOf(ContactValueKind.UNKNOWN_VCARD_PROPERTY)
        if (unknown.size != 1 || unknown.singleOrNull()?.label != "X-CTK-PN" ||
            unknown.singleOrNull()?.value != "$RICH_ROLE-preserved"
        ) {
            failures += "UNKNOWN_PROPERTY_SET"
        }
        val preservation = contact.preservationEnvelope
        if (preservation == null) {
            failures += "PRESERVATION_ENVELOPE"
        } else {
            val sentinel = "X-CTK-PN:$RICH_ROLE-preserved"
            val preservedSentinel = sequenceOf(preservation.remoteBaseline.orEmpty())
                .plus(preservation.rawProperties.values.asSequence())
                .flatMap { raw -> raw.replace("\r\n ", "").lineSequence() }
                .any { line -> line == sentinel }
            if (!preservedSentinel) failures += "PRESERVATION_SENTINEL"
        }
        if (contact.actionRequiredReasons.isNotEmpty()) failures += "CONTACT_ACTION_REQUIRED"
        if (preferredEmail != null) {
            val actualMemberships = groups.flatMap { group ->
                group.memberships.filter { it.contactId == contact.id }
                    .map { group.name to it.emailValueId }
            }
            val expectedMemberships = EXPECTED_RICH_GROUPS.map { it to preferredEmail.id }.toSet()
            if (actualMemberships.size != expectedMemberships.size || actualMemberships.toSet() != expectedMemberships) {
                failures += "PREFERRED_EMAIL_MEMBERSHIP_SET"
            }
        }
        return failures
    }

    private fun decodedImageColor(dataUri: String, allowProviderImageEncoding: Boolean = false): Int? {
        if (!dataUri.startsWith("data:image/png;base64,") &&
            !(allowProviderImageEncoding && dataUri.startsWith("data:image/jpeg;base64,"))
        ) return null
        val encoded = dataUri.substringAfter(',')
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            try {
                if (bitmap.width != 32 || bitmap.height != 32) {
                    null
                } else {
                    val color = bitmap.getPixel(0, 0)
                    if ((0 until bitmap.height).all { y ->
                            (0 until bitmap.width).all { x -> bitmap.getPixel(x, y) == color }
                        }
                    ) color else null
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        const val EXPECTED_CONTACTS = 300
        const val EXPECTED_GROUPS = 20
        const val ARG_MODE = "pnCandidateStateMode"
        const val MODE_PROBE = "payload_free_probe"
        const val RICH_ROLE = "ctk-pn-base-001"
        const val RICH_FIRST_NAME = "Nominal"
        const val RICH_LAST_NAME = "001"
        const val CONTACT_LEDGER_TABLE = "android_projection_ledger"
        const val GROUP_LEDGER_TABLE = "android_group_projection_ledger"
        const val MEMBERSHIP_LEDGER_TABLE = "android_group_membership_projection_ledger"
        val PROJECTION_LEDGER_TABLES = setOf(
            CONTACT_LEDGER_TABLE,
            GROUP_LEDGER_TABLE,
            MEMBERSHIP_LEDGER_TABLE,
        )
        val EXPECTED_RICH_GROUPS = setOf("ctk-pn-label-01", "ctk-pn-label-02")
    }
}

private data class LocalSnapshot(
    val contacts: List<CanonicalContact>,
    val groups: List<ContactGroup>,
    val pending: Int,
    val syncState: com.patmanak.contako.ui.SyncDashboardSnapshot?,
    val syncActivity: String,
)

private data class LedgerCounts(val total: Int, val clean: Int)

private data class RichValueExpectation(
    val value: String,
    val label: String = "",
    val order: Int = 0,
    val primary: Boolean = false,
    val components: Map<String, String>? = null,
    val alternativeValue: String? = null,
)

private data class DiagnosticSnapshot(
    val status: com.patmanak.contako.data.sync.SyncStatusSnapshot?,
    val contacts: LedgerCounts,
    val groups: LedgerCounts,
    val memberships: LedgerCounts,
)
