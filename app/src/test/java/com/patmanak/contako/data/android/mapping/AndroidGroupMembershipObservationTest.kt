package com.patmanak.contako.data.android.mapping

import com.patmanak.contako.data.android.provider.AndroidOwnedGroupMembershipRow
import com.patmanak.contako.data.android.provider.AndroidOwnedDataRow
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRowPage
import com.patmanak.contako.data.android.provider.AndroidOwnedGroupRow
import com.patmanak.contako.data.android.provider.AndroidProviderAccountName
import com.patmanak.contako.data.android.provider.AndroidProviderMimeRouter
import com.patmanak.contako.data.android.provider.GroupMembershipRows
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGroupMembershipObservationTest {
    private val decoder = AndroidGroupMembershipObservationDecoder()

    @Test
    fun `complete trusted catalog resolves duplicate titles by stable identity`() {
        val groups = listOf(group(11, "group-a", "remote-a", "Same"), group(12, "group-b", "remote-b", "Same"))
        val result = decoder.decode(
            catalog = complete(groups),
            expectedRawContactId = RAW_CONTACT_ID,
            memberships = routed(membership(102, 12), membership(101, 11)),
            trustedBindings = listOf(binding(11, "group-a", "remote-a"), binding(12, "group-b", "remote-b")),
        )

        assertEquals(sortedSetOf("group-a", "group-b"), result.canonicalGroupIds)
        assertFalse(result.toString().contains("group-a"))
    }

    @Test
    fun `same versioned Data batch routes contact and membership before trusted resolution`() {
        val contactRow = dataRow(100, RAW_CONTACT_ID, null, mimeType = "vnd.android.cursor.item/name")
        val membershipRow = dataRow(101, RAW_CONTACT_ID, "11")
        val routed = AndroidProviderMimeRouter().route(RAW_CONTACT_ID, listOf(contactRow, membershipRow))

        val resolved = decoder.decode(
            catalog = complete(listOf(group(11, "group-a", "remote-a"))),
            expectedRawContactId = RAW_CONTACT_ID,
            memberships = routed.groupMembershipRows,
            trustedBindings = listOf(binding(11, "group-a", "remote-a")),
        )

        assertEquals(listOf(contactRow), routed.contactRows.rows)
        assertEquals(setOf("group-a"), resolved.canonicalGroupIds)
    }

    @Test
    fun `incomplete page chain cannot construct a trusted catalog`() {
        val first = group(11, "group-a", "remote-a")
        assertTrue(runCatching {
            AndroidCompleteGroupCatalog.fromExhaustivePages(
                ACCOUNT,
                PROVIDER_EPOCH,
                listOf(AndroidOwnedGroupRowPage(ANDROID_ACCOUNT, 0, listOf(first), 11)),
            )
        }.isFailure)

        val smallPages = (1L..8L).map { locator ->
            AndroidOwnedGroupRowPage(
                accountName = ANDROID_ACCOUNT,
                requestedAfterGroupRowId = locator - 1,
                groups = listOf(group(locator, "group-$locator", "remote-$locator")),
                nextAfterGroupRowId = locator.takeIf { locator < 8 },
            )
        }
        assertEquals(
            8,
            AndroidCompleteGroupCatalog.fromExhaustivePages(ACCOUNT, PROVIDER_EPOCH, smallPages).rows.size,
        )
    }

    @Test
    fun `unknown deleted or untrusted group fails closed with redacted diagnostics`() {
        assertFailure(AndroidGroupMembershipDecodeFailure.UNKNOWN_GROUP) {
            decoder.decode(complete(emptyList()), RAW_CONTACT_ID, routed(membership(101, 11)), emptyList())
        }
        assertFailure(AndroidGroupMembershipDecodeFailure.DELETED_GROUP) {
            decoder.decode(
                complete(listOf(group(11, "group-a", "remote-a", deleted = true))),
                RAW_CONTACT_ID,
                routed(membership(101, 11)),
                listOf(binding(11, "group-a", "remote-a")),
            )
        }
        val error = assertFailure(AndroidGroupMembershipDecodeFailure.UNTRUSTED_GROUP_IDENTITY) {
            decoder.decode(
                complete(listOf(group(11, "hostile-claim", "remote-a"))),
                RAW_CONTACT_ID,
                routed(membership(101, 11)),
                listOf(binding(11, "group-a", "remote-a")),
            )
        }
        assertFalse(error.message.orEmpty().contains("hostile-claim"))
        assertFalse(error.toString().contains("group-a"))
    }

    @Test
    fun `duplicate provider or logical membership rows fail before resolution`() {
        val catalog = listOf(group(11, "group-a", "remote-a"))
        val bindings = listOf(binding(11, "group-a", "remote-a"))
        assertFailure(AndroidGroupMembershipDecodeFailure.DUPLICATE_PROVIDER_ROW) {
            decoder.decode(
                complete(catalog),
                RAW_CONTACT_ID,
                routed(membership(101, 11), membership(101, 11)),
                bindings,
            )
        }
        assertFailure(AndroidGroupMembershipDecodeFailure.DUPLICATE_MEMBERSHIP) {
            decoder.decode(
                complete(catalog),
                RAW_CONTACT_ID,
                routed(membership(101, 11), membership(102, 11)),
                bindings,
            )
        }
    }

    @Test
    fun `mixed raw contacts stale account epoch or source fail before resolution`() {
        val catalog = complete(listOf(group(11, "group-a", "remote-a")))
        assertFailure(AndroidGroupMembershipDecodeFailure.MIXED_RAW_CONTACTS) {
            decoder.decode(
                catalog,
                RAW_CONTACT_ID,
                routed(membership(101, 11), membership(102, 11, rawContactId = 99)),
                listOf(binding(11, "group-a", "remote-a")),
            )
        }
        assertFailure(AndroidGroupMembershipDecodeFailure.UNTRUSTED_GROUP_IDENTITY) {
            decoder.decode(
                catalog,
                RAW_CONTACT_ID,
                routed(membership(101, 11)),
                listOf(binding(11, "group-a", "remote-a", providerEpoch = PROVIDER_EPOCH + 1)),
            )
        }
        assertFailure(AndroidGroupMembershipDecodeFailure.UNTRUSTED_GROUP_IDENTITY) {
            decoder.decode(
                catalog,
                RAW_CONTACT_ID,
                routed(membership(101, 11)),
                listOf(binding(11, "group-a", null)),
            )
        }
        val localCatalog = complete(listOf(group(12, "local-group", sourceId = null)))
        assertEquals(
            setOf("local-group"),
            decoder.decode(
                localCatalog,
                RAW_CONTACT_ID,
                routed(membership(103, 12)),
                listOf(binding(12, "local-group", null)),
            ).canonicalGroupIds,
        )
    }

    @Test
    fun `membership and identity bounds fail before allocation-heavy fingerprinting`() {
        val catalog = complete(listOf(group(11, "group-a", "remote-a")))
        val rows = (1L..129L).map { dataId -> membership(dataId, 11) }
        assertFailure(AndroidGroupMembershipDecodeFailure.BOUND_EXCEEDED) {
            decoder.decode(catalog, RAW_CONTACT_ID, routed(*rows.toTypedArray()), listOf(binding(11, "group-a", "remote-a")))
        }
        assertTrue(runCatching {
            AndroidTrustedGroupBinding(ACCOUNT, ANDROID_ACCOUNT, PROVIDER_EPOCH, "x".repeat(4_097), 11, 1, null)
        }.isFailure)
    }

    @Test
    fun `routed membership requires a strict positive Data1 group locator`() {
        val catalog = complete(emptyList())
        listOf(null, "", " 11", "011", "-1", "not-a-locator").forEach { encoded ->
            assertFailure(AndroidGroupMembershipDecodeFailure.MALFORMED_MEMBERSHIP_ROW) {
                decoder.decode(
                    catalog,
                    RAW_CONTACT_ID,
                    GroupMembershipRows(listOf(dataRow(101, RAW_CONTACT_ID, encoded))),
                    emptyList(),
                )
            }
        }
    }

    @Test
    fun `observation fingerprint ignores membership order and provider locators but detects membership changes`() {
        val mapper = CanonicalAndroidContactMapper()
        val contact = AndroidContactSnapshot("contact", emptyList())
        val first = observation(contact, linkedSetOf("group-b", "group-a")).fingerprint(mapper)
        val reordered = observation(contact, linkedSetOf("group-a", "group-b")).fingerprint(mapper)
        val changed = observation(contact, setOf("group-a")).fingerprint(mapper)

        assertEquals(first, reordered)
        assertNotEquals(first, changed)
        assertTrue(first.sha256Hex.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(
            first,
            observation(
                contact,
                linkedSetOf("group-b", "group-a"),
                preferredEmailId = "another-email",
            ).fingerprint(mapper),
        )
    }

    @Test
    fun `empty membership observation remains explicit and deterministic for no-email repair`() {
        val mapper = CanonicalAndroidContactMapper()
        val observation = AndroidContactObservation.fromCanonicalContext(
            contact = AndroidContactSnapshot("contact-without-email", emptyList()),
            canonical = CanonicalContact(accountId = "account", id = "contact-without-email"),
            observedCanonicalGroupIds = emptySet(),
        )

        assertEquals(observation.fingerprint(mapper), observation.fingerprint(mapper))
        assertTrue(observation.toString().contains("groupCount=0"))
        val impossibleObservedAssignment = AndroidContactObservation.fromCanonicalContext(
            contact = AndroidContactSnapshot("contact-without-email", emptyList()),
            canonical = CanonicalContact(accountId = "account", id = "contact-without-email"),
            observedCanonicalGroupIds = setOf("group-a"),
        )
        assertNotEquals(observation.fingerprint(mapper), impossibleObservedAssignment.fingerprint(mapper))
    }

    private fun group(
        locator: Long,
        canonicalId: String,
        sourceId: String?,
        title: String = "Group",
        deleted: Boolean = false,
    ) = AndroidOwnedGroupRow(
        groupRowId = locator,
        canonicalGroupIdClaim = canonicalId,
        sourceIdentity = sourceId,
        title = title,
        dirty = false,
        deleted = deleted,
        visible = true,
        shouldSync = true,
        version = 1,
    )

    private fun membership(
        dataRowId: Long,
        groupRowId: Long,
        rawContactId: Long = RAW_CONTACT_ID,
    ) = AndroidOwnedGroupMembershipRow(
        dataRowId = dataRowId,
        rawContactId = rawContactId,
        groupRowId = groupRowId,
    )

    private fun routed(vararg memberships: AndroidOwnedGroupMembershipRow) = GroupMembershipRows(
        memberships.map { membership ->
            dataRow(
                dataRowId = membership.dataRowId,
                rawContactId = membership.rawContactId,
                encodedGroupLocator = membership.groupRowId.toString(),
            )
        },
    )

    private fun dataRow(
        dataRowId: Long,
        rawContactId: Long,
        encodedGroupLocator: String?,
        mimeType: String = "vnd.android.cursor.item/group_membership",
    ) = AndroidOwnedDataRow(
        dataRowId = dataRowId,
        rawContactId = rawContactId,
        mimeType = mimeType,
        canonicalValueId = null,
        canonicalOrder = null,
        linkedValueIdsEncoding = null,
        isPrimary = false,
        isSuperPrimary = false,
        stringSlots = listOf(encodedGroupLocator) + List(13) { null },
        binarySlot = null,
    )

    private fun binding(
        locator: Long,
        canonicalId: String,
        sourceId: String?,
        providerEpoch: Long = PROVIDER_EPOCH,
    ) = AndroidTrustedGroupBinding(
        account = ACCOUNT,
        androidAccountName = ANDROID_ACCOUNT,
        providerEpoch = providerEpoch,
        canonicalGroupId = canonicalId,
        groupRowId = locator,
        expectedProviderVersion = 1,
        sourceIdentity = sourceId,
    )

    private fun complete(rows: List<AndroidOwnedGroupRow>) = AndroidCompleteGroupCatalog.fromExhaustivePages(
        account = ACCOUNT,
        providerEpoch = PROVIDER_EPOCH,
        pages = listOf(AndroidOwnedGroupRowPage(ANDROID_ACCOUNT, 0, rows, null)),
    )

    private fun observation(
        contact: AndroidContactSnapshot,
        groups: Set<String>,
        preferredEmailId: String = "email-primary",
    ) = AndroidContactObservation.fromCanonicalContext(
        contact = contact,
        canonical = CanonicalContact(
            accountId = "account",
            id = contact.canonicalContactId,
            values = listOf(
                ContactValue(
                    id = preferredEmailId,
                    kind = ContactValueKind.EMAIL,
                    value = "preferred@example.test",
                    order = 0,
                ),
            ),
        ),
        observedCanonicalGroupIds = groups,
    )

    private fun assertFailure(
        expected: AndroidGroupMembershipDecodeFailure,
        block: () -> Unit,
    ): AndroidGroupMembershipDecodeException {
        val error = requireNotNull(runCatching(block).exceptionOrNull() as? AndroidGroupMembershipDecodeException)
        assertEquals(expected, error.category)
        return error
    }

    private companion object {
        val ACCOUNT = AccountScope("account")
        val ANDROID_ACCOUNT = AndroidProviderAccountName("android-account")
        const val PROVIDER_EPOCH = 3L
        const val RAW_CONTACT_ID = 41L
    }
}
