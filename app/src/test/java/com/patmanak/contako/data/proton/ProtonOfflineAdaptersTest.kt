package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventoryMetadata
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.EmailLabelMutation
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.InventoryCursor
import com.patmanak.contako.data.gateway.ProtonContactEmailLabelGateway
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteEmailGroupMembership
import com.patmanak.contako.data.gateway.RemoteEmailId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.ValidatedCompleteInventory
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichInventoryAdapterTest {
    @Test
    fun `P2-05 parses representative pages and proves an authoritative complete inventory`() = runTest {
        val fixtures = listOf(fixture("rich-inventory-page-0.json"), fixture("rich-inventory-page-1.json"))
        val calls = mutableListOf<Int>()
        val adapter = ProtonRichInventoryAdapter(
            expectedAccount = ACCOUNT,
            transport = ProtonRichInventoryWireTransport { _, page, requestedSize ->
                assertEquals(1, requestedSize)
                calls += page
                fixtures[page]
            },
            pageSize = 1,
            unitStatus = ProtonRichInventoryUnitStatus.LIVE_CONTRACT_ATTESTED,
        )

        val first = adapter.page(ACCOUNT, null).success()
        val second = adapter.page(ACCOUNT, requireNotNull(first.nextCursor)).success()
        val complete = ValidatedCompleteInventory.fromPages(listOf(first, second))

        assertEquals(listOf(0, 1), calls)
        assertEquals(2, complete.totalCount)
        val metadata = complete.contacts.first()
        assertEquals(123L, metadata.sizeBytes)
        assertEquals(200L, metadata.modifiedAtEpochSeconds)
        assertEquals(ContactInventoryVersionProvenance.REMOTE_SERVER, metadata.versionProvenance)
        assertEquals(ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION, metadata.coverage)
        assertEquals(2, metadata.groupIds.size)
        assertEquals(listOf(RemoteGroupId("fixture-email-group")), metadata.emailGroupMemberships.single().groupIds)
    }

    @Test
    fun `P2-05 missing version malformed cursor truncation and duplicate metadata fail closed`() = runTest {
        suspend fun outcome(raw: String, cursor: InventoryCursor? = null) = ProtonRichInventoryAdapter(
            ACCOUNT,
            ProtonRichInventoryWireTransport { _, _, _ -> raw },
            pageSize = 1,
        ).page(ACCOUNT, cursor)

        assertFailure(GatewayFailureCategory.MALFORMED_RESPONSE, outcome(fixture("rich-inventory-missing-version.json")))
        assertFailure(
            GatewayFailureCategory.VALIDATION_REJECTED,
            outcome(fixture("rich-inventory-page-0.json"), InventoryCursor("foreign-cursor")),
        )
        assertFailure(
            GatewayFailureCategory.MALFORMED_RESPONSE,
            outcome("""{"Code":1000,"Total":2,"Contacts":[]}"""),
        )
        assertFailure(
            GatewayFailureCategory.MALFORMED_RESPONSE,
            outcome(
                """{"Code":1000,"Total":2,"Contacts":[${contactJson("duplicate")},${contactJson("duplicate")}]}""",
            ),
        )
        assertFailure(GatewayFailureCategory.MALFORMED_RESPONSE, outcome("""{"Code":"1000","Total":0,"Contacts":[]}"""))
        assertFailure(GatewayFailureCategory.MALFORMED_RESPONSE, outcome("""{"Code":1000,"Total":"0","Contacts":[]}"""))
        val quotedSize = contactJson("quoted-size").replace("\"Size\":1", "\"Size\":\"1\"")
        assertFailure(
            GatewayFailureCategory.MALFORMED_RESPONSE,
            outcome("""{"Code":1000,"Total":1,"Contacts":[$quotedSize]}"""),
        )
    }

    @Test
    fun `P2-05 planner persists a restart-safe baseline and rejects a public short index`() = runTest {
        val store = MemoryCheckpointStore()
        val inventory = ValidatedCompleteInventory.fromPages(
            listOf(
                ContactInventoryPage(
                    listOf(authoritativeMetadata("one")),
                    null,
                    null,
                    1,
                    ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
                ),
            ),
        )

        val firstProcess = PersistentContactInventoryPlanner(store)
        val initial = firstProcess.plan(ACCOUNT, inventory)
        val beforeCommitRestart = PersistentContactInventoryPlanner(store).plan(ACCOUNT, inventory)
        assertTrue(runCatching { firstProcess.commit(ACCOUNT, initial) }.isFailure)
        firstProcess.commit(ACCOUNT, initial.fullyCompleted())
        val afterCommitRestart = PersistentContactInventoryPlanner(store).plan(ACCOUNT, inventory)

        assertEquals(setOf(RemoteContactId("one")), initial.hydrate)
        assertEquals(setOf(RemoteContactId("one")), beforeCommitRestart.hydrate)
        assertTrue(afterCommitRestart.hydrate.isEmpty())
        assertTrue(afterCommitRestart.deleted.isEmpty())
        assertTrue(runCatching {
            firstProcess.commit(AccountScope("foreign"), initial.fullyCompleted())
        }.isFailure)
        assertTrue(runCatching {
            firstProcess.commit(ACCOUNT, beforeCommitRestart.fullyCompleted())
        }.exceptionOrNull() is StaleContactInventoryPlan)
        assertTrue(runCatching {
            firstProcess.commit(ACCOUNT, initial.fullyCompleted())
        }.exceptionOrNull() is StaleContactInventoryPlan)
        assertTrue(runCatching {
            initial.completedAfterDurableReconciliation(
                hydrated = emptySet(),
                labelReconciled = initial.labelOnly,
                deletionsReconciled = initial.deleted,
                canonicalPersistenceCommitted = true,
            )
        }.isFailure)
        assertTrue(runCatching {
            initial.completedAfterDurableReconciliation(
                hydrated = initial.hydrate,
                labelReconciled = initial.labelOnly,
                deletionsReconciled = initial.deleted,
                canonicalPersistenceCommitted = false,
            )
        }.isFailure)

        val shortIndex = authoritativeMetadata("short").let { metadata ->
            ContactInventoryMetadata(
                id = metadata.id,
                displayName = metadata.displayName,
                version = metadata.version,
                sizeBytes = null,
                modifiedAtEpochSeconds = null,
                emailIds = metadata.emailIds,
                groupIds = metadata.groupIds,
                versionProvenance = ContactInventoryVersionProvenance.LOCAL_INDEX_FINGERPRINT,
                coverage = ContactInventoryCoverage.PUBLIC_DIRECTORY_FIELDS_ONLY,
            )
        }
        val nonAuthoritative = ValidatedCompleteInventory.fromPages(
            listOf(ContactInventoryPage(listOf(shortIndex), null, null, 1)),
        )
        assertTrue(runCatching {
            PersistentContactInventoryPlanner(store).plan(ACCOUNT, nonAuthoritative)
        }.isFailure)
    }

    @Test
    fun `P2-05 production planner separates hydration label-only and complete absence deletion`() = runTest {
        val store = MemoryCheckpointStore()
        val planner = PersistentContactInventoryPlanner(store)
        val initialInventory = ValidatedCompleteInventory.fromPages(
            listOf(
                ContactInventoryPage(
                    listOf(authoritativeMetadata("content"), authoritativeMetadata("labels"), authoritativeMetadata("deleted")),
                    null,
                    null,
                    3,
                    ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
                ),
            ),
        )
        planner.commit(ACCOUNT, planner.plan(ACCOUNT, initialInventory).fullyCompleted())
        val labelChanged = ContactInventoryMetadata(
            id = RemoteContactId("labels"),
            displayName = "Fixture",
            version = RemoteVersion("version-labels"),
            sizeBytes = 1,
            modifiedAtEpochSeconds = 1,
            emailIds = listOf(RemoteEmailId("email-labels")),
            groupIds = listOf(RemoteGroupId("new-group")),
            versionProvenance = ContactInventoryVersionProvenance.REMOTE_SERVER,
            coverage = ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION,
            emailGroupMemberships = listOf(
                RemoteEmailGroupMembership(RemoteEmailId("email-labels"), listOf(RemoteGroupId("new-group"))),
            ),
        )
        val contentChanged = ContactInventoryMetadata(
            id = RemoteContactId("content"),
            displayName = "Changed",
            version = RemoteVersion("version-content-2"),
            sizeBytes = 2,
            modifiedAtEpochSeconds = 2,
            emailIds = listOf(RemoteEmailId("email-content")),
            groupIds = emptyList(),
            versionProvenance = ContactInventoryVersionProvenance.REMOTE_SERVER,
            coverage = ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION,
            emailGroupMemberships = emptyList(),
        )
        val changedInventory = ValidatedCompleteInventory.fromPages(
            listOf(
                ContactInventoryPage(
                    listOf(labelChanged, contentChanged),
                    null,
                    null,
                    2,
                    ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
                ),
            ),
        )

        val plan = planner.plan(ACCOUNT, changedInventory)

        assertEquals(setOf(RemoteContactId("content")), plan.hydrate)
        assertEquals(setOf(RemoteContactId("labels")), plan.labelOnly)
        assertEquals(setOf(RemoteContactId("deleted")), plan.deleted)
    }

    private class MemoryCheckpointStore : ContactInventoryCheckpointStore {
        private val values = mutableMapOf<AccountScope, VersionedContactInventoryCheckpoint>()

        override suspend fun load(account: AccountScope): VersionedContactInventoryCheckpoint? = values[account]

        override suspend fun compareAndSet(
            account: AccountScope,
            expectedGeneration: Long?,
            checkpoint: ContactInventoryCheckpoint,
        ): Boolean {
            val current = values[account]
            if (current?.generation != expectedGeneration) return false
            values[account] = VersionedContactInventoryCheckpoint((expectedGeneration ?: -1) + 1, checkpoint)
            return true
        }
    }
}

class EmailGroupAssignmentAdapterTest {
    @Test
    fun `P2-07 freezes exact bulk assign and remove wire shapes without per-contact calls`() = runTest {
        val observed = mutableListOf<Pair<EmailGroupAssignmentWireOperation, String>>()
        val adapter = ProtonEmailGroupAssignmentAdapter(
            ACCOUNT,
            ProtonEmailGroupAssignmentWireTransport { _, operation, body ->
                observed += operation to body
                fixture("assignment-success.json")
            },
        )
        val emails = listOf(RemoteEmailId("fixture-email-1"), RemoteEmailId("fixture-email-2"))

        assertTrue(adapter.apply(ACCOUNT, EmailLabelMutation.Assign(RemoteGroupId("fixture-group"), emails)) is GatewayOutcome.Success)
        assertTrue(adapter.apply(ACCOUNT, EmailLabelMutation.Remove(RemoteGroupId("fixture-group"), emails)) is GatewayOutcome.Success)

        assertEquals(listOf(EmailGroupAssignmentWireOperation.ASSIGN, EmailGroupAssignmentWireOperation.REMOVE), observed.map { it.first })
        observed.forEach { (_, body) ->
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals("fixture-group", json.getValue("LabelID").jsonPrimitive.content)
            assertEquals(listOf("fixture-email-1", "fixture-email-2"), json.getValue("ContactEmailIDs").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(setOf("LabelID", "ContactEmailIDs"), json.keys)
        }
    }

    @Test
    fun `P2-07 wrong account malformed success and oversized batch fail closed`() = runTest {
        val adapter = ProtonEmailGroupAssignmentAdapter(
            ACCOUNT,
            ProtonEmailGroupAssignmentWireTransport { _, _, _ -> "{}" },
        )
        val mutation = EmailLabelMutation.Assign(RemoteGroupId("group"), listOf(RemoteEmailId("email")))
        assertFailure(GatewayFailureCategory.AUTHENTICATION_REQUIRED, adapter.apply(AccountScope("foreign"), mutation))
        assertFailure(GatewayFailureCategory.MALFORMED_RESPONSE, adapter.apply(ACCOUNT, mutation))
        val quotedCode = ProtonEmailGroupAssignmentAdapter(
            ACCOUNT,
            ProtonEmailGroupAssignmentWireTransport { _, _, _ -> """{"Code":"1000"}""" },
        )
        assertFailure(GatewayFailureCategory.MALFORMED_RESPONSE, quotedCode.apply(ACCOUNT, mutation))
        assertTrue(runCatching {
            EmailLabelMutation.Assign(
                RemoteGroupId("group"),
                List(EmailLabelMutation.MAX_EMAIL_IDS_PER_MUTATION + 1) { RemoteEmailId("email-$it") },
            )
        }.isFailure)
    }

    @Test
    fun `P2-07 reconciler reads before write and resolves an ambiguous acknowledgement without replay`() = runTest {
        val assigned = mutableSetOf(RemoteEmailId("already"))
        var reads = 0
        val writes = mutableListOf<EmailLabelMutation>()
        val reader = ProtonEmailGroupMembershipReader { _, _ ->
            reads += 1
            GatewayOutcome.Success(
                AuthoritativeEmailGroupMembership(ACCOUNT, RemoteGroupId("group"), assigned.toList()),
            )
        }
        val ambiguousWriter = object : ProtonContactEmailLabelGateway {
            override suspend fun apply(
                account: AccountScope,
                mutation: EmailLabelMutation,
            ): GatewayOutcome<Unit> {
                writes += mutation
                (mutation as EmailLabelMutation.Assign).emailIds.forEach(assigned::add)
                return GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT)
            }
        }
        val gateway = ReconciledProtonEmailGroupAssignmentGateway(reader, ambiguousWriter)
        val requested = EmailLabelMutation.Assign(
            RemoteGroupId("group"),
            listOf(RemoteEmailId("already"), RemoteEmailId("new")),
        )

        assertTrue(gateway.apply(ACCOUNT, requested) is GatewayOutcome.Success)
        assertEquals(2, reads)
        assertEquals(listOf(RemoteEmailId("new")), (writes.single() as EmailLabelMutation.Assign).emailIds)

        assertTrue(gateway.apply(ACCOUNT, requested) is GatewayOutcome.Success)
        assertEquals(3, reads)
        assertEquals(1, writes.size)
    }

    @Test
    fun `P2-07 unresolved ambiguous mutation remains failed until a fresh authoritative retry`() = runTest {
        var reads = 0
        var writes = 0
        val reader = ProtonEmailGroupMembershipReader { _, _ ->
            reads += 1
            GatewayOutcome.Success(
                AuthoritativeEmailGroupMembership(ACCOUNT, RemoteGroupId("group"), emptyList()),
            )
        }
        val writer = object : ProtonContactEmailLabelGateway {
            override suspend fun apply(
                account: AccountScope,
                mutation: EmailLabelMutation,
            ): GatewayOutcome<Unit> {
                writes += 1
                return GatewayOutcome.Failure(GatewayFailureCategory.TIMEOUT)
            }
        }
        val gateway = ReconciledProtonEmailGroupAssignmentGateway(reader, writer)
        val requested = EmailLabelMutation.Assign(RemoteGroupId("group"), listOf(RemoteEmailId("new")))

        assertFailure(GatewayFailureCategory.TIMEOUT, gateway.apply(ACCOUNT, requested))
        assertEquals(2, reads)
        assertEquals(1, writes)
        assertFailure(GatewayFailureCategory.TIMEOUT, gateway.apply(ACCOUNT, requested))
        assertEquals(4, reads)
        assertEquals(2, writes)
    }
}

class ContactFieldAndMulticardTest {
    @Test
    fun `D-066 imported custom type survives value edit across multiple same-type cards`() {
        val codec = ProtonContactVCardCodec()
        val first = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\nFN:Fixture\r\n" +
            "ITEM1.EMAIL;TYPE=X-SCHOOL:first@example.test\r\nITEM1.X-ABLABEL:School\r\n" +
            "X-FIRST-CANARY:one\r\nEND:VCARD\r\n"
        val second = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\n" +
            "ITEM2.EMAIL;TYPE=X-SECOND:second@example.test\r\nX-SECOND-CANARY:two\r\nEND:VCARD\r\n"
        val decoded = codec.decode(
            "primary",
            emptyRemoteContact(),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, first),
                ProtonPlainContactCard(ContactCardType.Signed, second),
            ),
        )
        val edited = decoded.copy(values = decoded.values.map { value ->
            if (value.kind == ContactValueKind.EMAIL && value.order == 0) value.copy(value = "edited@example.test") else value
        })
        val encoded = codec.encode(edited)

        assertTrue(encoded.signed.contains("TYPE=X-SCHOOL;PREF=1:edited@example.test"))
        assertTrue(encoded.signed.contains("TYPE=X-SECOND;PREF=2:second@example.test"))
        assertTrue(encoded.signed.contains("ITEM1.X-ABLABEL:School"))
        assertTrue(encoded.signed.contains("X-FIRST-CANARY:one"))
        assertTrue(encoded.signed.contains("X-SECOND-CANARY:two"))
    }

    @Test
    fun `D-066 provenance prevents type or grouped label transfer on delete reorder and label edit`() {
        val codec = ProtonContactVCardCodec()
        val first = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\nFN:Fixture\r\n" +
            "item7.EMAIL;Type=X-School;TYPE=X-First,X-Student:first@example.test\r\n" +
            "item7.X-ABLabel;LANG=en:School\r\nEND:VCARD\r\n"
        val second = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\n" +
            "item9.EMAIL;type=X-Second:second@example.test\r\n" +
            "item9.X-ABLABEL:Second label\r\nEND:VCARD\r\n"
        val decoded = codec.decode(
            "primary",
            emptyRemoteContact(),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, first),
                ProtonPlainContactCard(ContactCardType.Signed, second),
            ),
        )
        val emails = decoded.valuesOf(ContactValueKind.EMAIL)

        val deletedFirst = decoded.copy(values = decoded.values.filterNot { it.id == emails[0].id })
        val afterDelete = codec.encode(deletedFirst).signed
        assertTrue(afterDelete.contains(";type=X-Second;PREF=1:second@example.test"))
        assertTrue(afterDelete.contains(".X-ABLABEL:Second label"))
        assertFalse(afterDelete.contains("X-School"))
        assertFalse(afterDelete.contains("X-First"))
        assertFalse(afterDelete.contains("X-ABLabel;LANG=en:School"))

        val reordered = decoded.copy(values = decoded.values.map { value ->
            when (value.id) {
                emails[0].id -> value.copy(order = 1)
                emails[1].id -> value.copy(order = 0, label = "X-LOCAL-EDIT")
                else -> value
            }
        })
        val afterReorder = codec.encode(reordered).signed
        val secondPosition = afterReorder.indexOf(";TYPE=x-local-edit;PREF=1:second@example.test")
        val firstPosition = afterReorder.indexOf(";Type=X-School;TYPE=X-First,X-Student;PREF=2:first@example.test")
        assertTrue(secondPosition in 0 until firstPosition)
        // Explicit custom labels use Proton's TYPE x-name; unchanged grouped labels survive below.
        assertFalse(afterReorder.contains(".X-ABLABEL:Second label"))
        assertTrue(afterReorder.contains(".X-ABLabel;LANG=en:School"))
        assertFalse(afterReorder.contains("X-Second"))
        assertFalse(afterReorder.contains("Second label"))
    }

    @Test
    fun `D-066 duplicate provenance or grouped labels fail closed`() {
        val codec = ProtonContactVCardCodec()
        val card = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\nFN:Fixture\r\n" +
            "ITEM1.EMAIL;TYPE=X-SCHOOL:first@example.test\r\n" +
            "ITEM1.X-ABLABEL:School\r\nEND:VCARD\r\n"
        val decoded = codec.decode(
            "primary",
            emptyRemoteContact(),
            listOf(ProtonPlainContactCard(ContactCardType.Signed, card)),
        )
        val email = decoded.valuesOf(ContactValueKind.EMAIL).single()
        assertTrue(runCatching {
            codec.encode(decoded.copy(values = decoded.values + email.copy(id = "clone", order = 1)))
        }.isFailure)

        val ambiguous = card.replace(
            "ITEM1.X-ABLABEL:School",
            "ITEM1.X-ABLABEL:School\r\nITEM1.X-ABLABEL:Duplicate",
        )
        assertTrue(runCatching {
            codec.decode(
                "primary",
                emptyRemoteContact(),
                listOf(ProtonPlainContactCard(ContactCardType.Signed, ambiguous)),
            )
        }.isFailure)
    }

    @Test
    fun `D-066 grouped label attached to an unknown property survives compatible edit`() {
        val codec = ProtonContactVCardCodec()
        val card = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\nFN:Fixture\r\n" +
            "ITEM42.X-SOCIALPROFILE;TYPE=X-FEDIVERSE:https://social.example.test/@fixture\r\n" +
            "ITEM42.X-ABLABEL:Fediverse\r\nEND:VCARD\r\n"
        val decoded = codec.decode(
            "primary",
            emptyRemoteContact(),
            listOf(ProtonPlainContactCard(ContactCardType.Signed, card)),
        )

        val encoded = codec.encode(decoded.copy(displayName = "Edited Fixture")).signed

        assertTrue(encoded.contains("ITEM42.X-SOCIALPROFILE;TYPE=X-FEDIVERSE:"))
        assertTrue(encoded.contains("ITEM42.X-ABLABEL:Fediverse"))
    }

    @Test
    fun `D-066 and D-067 raw parameters survive compatible edits reorder and scoped deletion`() {
        val codec = ProtonContactVCardCodec()
        val signed = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\nFN:Fixture\r\n" +
            "KEY;VALUE=uri;MEDIATYPE=\"application/pgp-keys\";X-KEY=\"a;b\":https://keys.example.test/one\r\nEND:VCARD\r\n"
        val private = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\nN:Fixture;;;;\r\n" +
            "NOTE;LANGUAGE=fr;X-NOTE=\"bonjour;monde\":initial note\r\n" +
            "BDAY;VALUE=date;X-FOO=\"a;b\":2000-01-02\r\n" +
            "TZ;VALUE=uri;X-TZ=keep:https://zone.example.test/one\r\n" +
            "PHOTO;VALUE=uri;MEDIATYPE=\"image/png\";X-PHOTO=\"p;1\":https://img.example.test/photo-one.png\r\n" +
            "LOGO;VALUE=uri;MEDIATYPE=image/png;X-REPEAT=A;X-REPEAT=B:https://img.example.test/logo-one.png\r\n" +
            "END:VCARD\r\n"
        val decoded = codec.decode(
            "primary",
            emptyRemoteContact(),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, private),
            ),
        )
        val edited = decoded.copy(values = decoded.values.reversed().map { value ->
            when (value.kind) {
                ContactValueKind.PUBLIC_KEY -> value.copy(value = "https://keys.example.test/two")
                ContactValueKind.NOTE -> value.copy(value = "edited note")
                ContactValueKind.BIRTHDAY -> value.copy(value = "2001-02-03")
                ContactValueKind.TIME_ZONE -> value.copy(value = "Europe/Paris")
                ContactValueKind.PHOTO -> value.copy(value = "https://img.example.test/photo-two.png")
                ContactValueKind.LOGO -> value.copy(value = "https://img.example.test/logo-two.png")
                else -> value
            }
        })

        val encoded = codec.encode(edited)
        assertTrue(encoded.signed.contains(
            "KEY;VALUE=uri;MEDIATYPE=\"application/pgp-keys\";X-KEY=\"a;b\";PREF=1:https://keys.example.test/two",
        ))
        assertTrue(encoded.encryptedPrivate.contains(
            "NOTE;LANGUAGE=fr;X-NOTE=\"bonjour;monde\":edited note",
        ))
        assertTrue(encoded.encryptedPrivate.contains("BDAY;VALUE=date;X-FOO=\"a;b\":20010203"))
        assertTrue(encoded.encryptedPrivate.contains("TZ;X-TZ=keep:Europe/Paris"))
        assertFalse(encoded.encryptedPrivate.contains("TZ;VALUE=uri"))
        assertTrue(encoded.encryptedPrivate.contains(
            "PHOTO;VALUE=uri;MEDIATYPE=\"image/png\";X-PHOTO=\"p;1\";PREF=1:https://img.example.test/photo-two.png",
        ))
        assertTrue(encoded.encryptedPrivate.contains(
            "LOGO;VALUE=uri;MEDIATYPE=image/png;X-REPEAT=A;X-REPEAT=B:https://img.example.test/logo-two.png",
        ))
        assertFalse(encoded.encryptedPrivate.contains("ITEM1."))
        assertTrue(encoded.encryptedPrivate.indexOf("LOGO;VALUE=uri") < encoded.encryptedPrivate.indexOf("PHOTO;VALUE=uri"))

        val withoutPhoto = edited.copy(values = edited.values.filterNot { it.kind == ContactValueKind.PHOTO })
        val afterDelete = codec.encode(withoutPhoto).encryptedPrivate
        assertFalse(afterDelete.contains("X-PHOTO"))
        assertTrue(afterDelete.contains("X-REPEAT=A;X-REPEAT=B"))
    }

    @Test
    fun `D-066 preservation provenance rejects parameter transfer and identity usurpation`() {
        val codec = ProtonContactVCardCodec()
        val signed = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\nFN:Fixture\r\n" +
            "KEY;VALUE=uri;X-KEY=stable:https://keys.example.test/one\r\nEND:VCARD\r\n"
        val private = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\nN:Fixture;;;;\r\n" +
            "PHOTO;VALUE=uri;X-PHOTO=stable:https://img.example.test/photo.png\r\nEND:VCARD\r\n"
        val decoded = codec.decode(
            "primary",
            emptyRemoteContact(),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, private),
            ),
        )
        val key = decoded.valuesOf(ContactValueKind.PUBLIC_KEY).single()
        val photo = decoded.valuesOf(ContactValueKind.PHOTO).single()

        assertTrue(runCatching {
            codec.encode(decoded.copy(values = decoded.values.map { value ->
                if (value.id == key.id) value.copy(id = "forged-key") else value
            }))
        }.isFailure)
        assertTrue(runCatching {
            codec.encode(decoded.copy(values = decoded.values.map { value ->
                if (value.id == photo.id) {
                    value.copy(
                        id = "photo@${key.preservationKey}",
                        preservationKey = key.preservationKey,
                    )
                } else value
            }))
        }.isFailure)
    }

    @Test
    fun `D-067 private malformed and oversized KEY or fields fail before protection`() {
        val acceptedPublic = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nfixture-public-only\n-----END PGP PUBLIC KEY BLOCK-----"
        val codec = ProtonContactVCardCodec(
            ProtonContactFieldValidator(PublicKeyMaterialInspector { it == acceptedPublic }),
        )
        val valid = contactWith(ContactValue("key", ContactValueKind.PUBLIC_KEY, acceptedPublic, order = 0))
        assertTrue(codec.encode(valid).signed.contains("KEY;PREF=1:"))

        val privateMaterial = acceptedPublic.replace("PUBLIC", "PRIVATE")
        assertTrue(runCatching { codec.encode(contactWith(ContactValue("key", ContactValueKind.PUBLIC_KEY, privateMaterial, order = 0))) }.isFailure)
        assertTrue(runCatching { codec.encode(contactWith(ContactValue("key", ContactValueKind.PUBLIC_KEY, "not-a-key", order = 0))) }.isFailure)
        assertTrue(runCatching { codec.encode(contactWith(ContactValue("note", ContactValueKind.NOTE, "x".repeat(16 * 1_024 + 1), order = 0))) }.isFailure)
        assertTrue(runCatching { codec.encode(contactWith(ContactValue("url", ContactValueKind.URL, "https://example.test/" + "x".repeat(8 * 1_024), order = 0))) }.isFailure)
        assertTrue(runCatching {
            codec.encode(contactWith(
                ContactValue("gender-1", ContactValueKind.GENDER, "M", order = 0),
                ContactValue("gender-2", ContactValueKind.GENDER, "F", order = 1),
            ))
        }.isFailure)
        assertTrue(runCatching {
            codec.encode(contactWith(
                ContactValue("birthday-1", ContactValueKind.BIRTHDAY, "2000-01-01", order = 0),
                ContactValue("birthday-2", ContactValueKind.BIRTHDAY, "2001-01-01", order = 1),
            ))
        }.isFailure)
        assertTrue(runCatching {
            codec.encode(contactWith(ContactValue("lang", ContactValueKind.LANGUAGE, "not_a_tag", order = 0)))
        }.isFailure)
    }

    @Test
    fun `D-067 decoded key image and aggregate card ceilings are enforced`() {
        val validator = ProtonContactFieldValidator(PublicKeyMaterialInspector { true })
        val oversizedKey = "data:application/pgp-keys;base64," +
            Base64.getEncoder().encodeToString(ByteArray(1 * 1_024 * 1_024 + 1))
        assertTrue(runCatching {
            validator.validate(contactWith(ContactValue("key", ContactValueKind.PUBLIC_KEY, oversizedKey, order = 0)))
        }.isFailure)

        val oversizedImage = "data:image/png;base64," +
            Base64.getEncoder().encodeToString(ByteArray(10 * 1_024 * 1_024 + 1))
        assertTrue(runCatching {
            validator.validate(contactWith(ContactValue("logo", ContactValueKind.LOGO, oversizedImage, order = 0)))
        }.isFailure)
        assertTrue(runCatching {
            validator.validate(contactWith(ContactValue("logo", ContactValueKind.LOGO, "file:///fixture.png", order = 0)))
        }.isFailure)
        val svg = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString("<svg/>".toByteArray())
        assertTrue(runCatching {
            validator.validate(contactWith(ContactValue("logo", ContactValueKind.LOGO, svg, order = 0)))
        }.isFailure)
        val validPng = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val png = "data:image/png;base64," + Base64.getEncoder().encodeToString(validPng)
        validator.validate(contactWith(ContactValue("logo", ContactValueKind.LOGO, png, order = 0)))
        val truncatedPngHeader = validPng.copyOf(8)
        assertTrue(runCatching {
            validator.validate(contactWith(
                ContactValue(
                    "logo",
                    ContactValueKind.LOGO,
                    "data:image/png;base64," + Base64.getEncoder().encodeToString(truncatedPngHeader),
                    order = 0,
                ),
            ))
        }.isFailure)
        val excessiveWidthPng = validPng.copyOf().also { bytes ->
            bytes[16] = 0
            bytes[17] = 0
            bytes[18] = 0x20
            bytes[19] = 0x01
        }
        assertTrue(runCatching {
            validator.validate(contactWith(
                ContactValue(
                    "logo",
                    ContactValueKind.LOGO,
                    "data:image/png;base64," + Base64.getEncoder().encodeToString(excessiveWidthPng),
                    order = 0,
                ),
            ))
        }.isFailure)
        assertTrue(runCatching {
            validator.validate(contactWith(ContactValue("logo", ContactValueKind.LOGO, png.replace(",", ",\n"), order = 0)))
        }.isFailure)
        assertTrue(runCatching {
            validator.validate(contactWith(ContactValue("url", ContactValueKind.URL, "https:///missing-host", order = 0)))
        }.isFailure)
        assertTrue(runCatching {
            validator.validate(contactWith(ContactValue("url", ContactValueKind.URL, "https://user@example.test/path", order = 0)))
        }.isFailure)

        val certificate = syntheticPublicCertificateDer()
        val certificateUri = "data:application/pkix-cert;base64," + Base64.getEncoder().encodeToString(certificate)
        validator.validate(contactWith(ContactValue("cert", ContactValueKind.PUBLIC_KEY, certificateUri, order = 0)))
        val trailingCertificate = certificate + byteArrayOf(0)
        assertTrue(runCatching {
            validator.validate(contactWith(
                ContactValue(
                    "cert",
                    ContactValueKind.PUBLIC_KEY,
                    "data:application/pkix-cert;base64," + Base64.getEncoder().encodeToString(trailingCertificate),
                    order = 0,
                ),
            ))
        }.isFailure)
        val concatenatedCertificates = certificate + certificate
        assertTrue(runCatching {
            validator.validate(contactWith(
                ContactValue(
                    "cert",
                    ContactValueKind.PUBLIC_KEY,
                    "data:application/pkix-cert;base64," + Base64.getEncoder().encodeToString(concatenatedCertificates),
                    order = 0,
                ),
            ))
        }.isFailure)

        assertTrue(runCatching {
            validator.validateSerialized(
                ProtonPreparedVCard(
                    encryptedPrivate = "a".repeat(4 * 1_024 * 1_024),
                    signed = "b".repeat(4 * 1_024 * 1_024),
                    clear = "c".repeat(4 * 1_024 * 1_024),
                ),
            )
        }.isFailure)
    }

    @Test
    fun `D-067 imported out-of-limit value is read-only and provenance cannot bypass validation`() {
        val codec = ProtonContactVCardCodec()
        val oversizedNote = "x".repeat(16 * 1_024 + 1)
        val card = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\nFN:Fixture\r\n" +
            "NOTE:$oversizedNote\r\nITEM1.TEL;TYPE=HOME:+33123456789\r\nEND:VCARD\r\n"
        val decoded = codec.decode(
            "primary",
            emptyRemoteContact(),
            listOf(ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, card)),
        )
        val note = decoded.valuesOf(ContactValueKind.NOTE).single()
        val phone = decoded.valuesOf(ContactValueKind.PHONE).single()

        val compatibleEdit = codec.encode(decoded.copy(displayName = "Edited Fixture"))
        assertTrue(compatibleEdit.encryptedPrivate.contains("NOTE:$oversizedNote"))

        assertTrue(runCatching {
            codec.encode(decoded.copy(values = decoded.values.map { value ->
                if (value.id == note.id) value.copy(value = value.value + "y") else value
            }))
        }.isFailure)

        assertTrue(runCatching {
            codec.encode(decoded.copy(values = decoded.values.map { value ->
                if (value.id == note.id) value.copy(id = "forged-note") else value
            }))
        }.isFailure)

        assertTrue(runCatching {
            codec.encode(decoded.copy(values = decoded.values.mapNotNull { value ->
                when (value.id) {
                    note.id -> value.copy(id = phone.id, preservationKey = phone.preservationKey)
                    phone.id -> null
                    else -> value
                }
            }))
        }.isFailure)
    }

    @Test
    fun `D-066 local custom labels use normalized x-name types without grouped label metadata`() {
        val encoded = ProtonContactVCardCodec().encode(contactWith(
            ContactValue("email", ContactValueKind.EMAIL, "fixture@example.test", " Shared / School! ", 0),
            ContactValue("phone", ContactValueKind.PHONE, "+12025550123", "X-Team  42", 0),
        ))

        assertTrue(encoded.signed.contains("ITEM1.EMAIL;TYPE=x-shared-school;PREF=1:fixture@example.test"))
        assertTrue(encoded.encryptedPrivate.contains("ITEM1.TEL;TYPE=x-team-42;PREF=1:+12025550123"))
        assertFalse(encoded.signed.contains("X-ABLABEL"))
        assertFalse(encoded.encryptedPrivate.contains("X-ABLABEL"))
    }

    @Test
    fun `P2-07 assignment exposes HTTP parse and authoritative proof stages`() = runTest {
        val stages = mutableListOf<ProtonEmailGroupAssignmentStage>()
        val monitor = ProtonEmailGroupAssignmentStageMonitor().also {
            it.observe(ProtonEmailGroupAssignmentStageObserver(stages::add))
        }
        val group = RemoteGroupId("group")
        val email = RemoteEmailId("email")
        val adapter = ProtonEmailGroupAssignmentAdapter(
            ACCOUNT,
            ProtonEmailGroupAssignmentWireTransport { _, _, _ -> fixture("assignment-success.json") },
            monitor,
        )
        var reads = 0
        val reader = ProtonEmailGroupMembershipReader { account, groupId ->
            GatewayOutcome.Success(AuthoritativeEmailGroupMembership(account, groupId, if (reads++ == 0) emptyList() else listOf(email)))
        }

        assertTrue(
            ReconciledProtonEmailGroupAssignmentGateway(reader, adapter, monitor)
                .apply(ACCOUNT, EmailLabelMutation.Assign(group, listOf(email))) is GatewayOutcome.Success,
        )
        assertEquals(
            listOf(
                ProtonEmailGroupAssignmentStage.HTTP,
                ProtonEmailGroupAssignmentStage.PARSE,
                ProtonEmailGroupAssignmentStage.PROOF,
            ),
            stages,
        )
    }

    @Test
    fun `P2-07 malformed acknowledgement is accepted only after authoritative proof`() = runTest {
        val group = RemoteGroupId("group")
        val email = RemoteEmailId("email")
        val adapter = ProtonEmailGroupAssignmentAdapter(
            ACCOUNT,
            ProtonEmailGroupAssignmentWireTransport { _, _, _ -> "{}" },
        )
        var reads = 0
        val reader = ProtonEmailGroupMembershipReader { account, groupId ->
            GatewayOutcome.Success(AuthoritativeEmailGroupMembership(account, groupId, if (reads++ == 0) emptyList() else listOf(email)))
        }

        assertTrue(
            ReconciledProtonEmailGroupAssignmentGateway(reader, adapter)
                .apply(ACCOUNT, EmailLabelMutation.Assign(group, listOf(email))) is GatewayOutcome.Success,
        )
    }

    @Test
    fun `P2-07 transient proof read retries once without replaying the write`() = runTest {
        val group = RemoteGroupId("group")
        val email = RemoteEmailId("email")
        var reads = 0
        var writes = 0
        val reader = ProtonEmailGroupMembershipReader { account, groupId ->
            reads++
            when (reads) {
                1 -> GatewayOutcome.Success(AuthoritativeEmailGroupMembership(account, groupId, emptyList()))
                2 -> GatewayOutcome.Failure(GatewayFailureCategory.NETWORK_UNAVAILABLE)
                else -> GatewayOutcome.Success(AuthoritativeEmailGroupMembership(account, groupId, listOf(email)))
            }
        }
        val writer = object : ProtonContactEmailLabelGateway {
            override suspend fun apply(account: AccountScope, mutation: EmailLabelMutation): GatewayOutcome<Unit> {
                writes++
                return GatewayOutcome.Success(Unit)
            }
        }

        val outcome = ReconciledProtonEmailGroupAssignmentGateway(
            reader,
            writer,
            proofRetryDelayMillis = 0,
        ).apply(ACCOUNT, EmailLabelMutation.Assign(group, listOf(email)))

        assertTrue(outcome is GatewayOutcome.Success)
        assertEquals(3, reads)
        assertEquals(1, writes)
    }

    @Test
    fun `P2-07 proof retry is bounded and excludes local budget stops`() = runTest {
        val group = RemoteGroupId("group")
        val email = RemoteEmailId("email")
        for (failure in listOf(
            GatewayFailureCategory.NETWORK_UNAVAILABLE to 3,
            GatewayFailureCategory.LOCAL_REQUEST_BUDGET_EXHAUSTED to 2,
        )) {
            var reads = 0
            var writes = 0
            val reader = ProtonEmailGroupMembershipReader { account, groupId ->
                reads++
                if (reads == 1) {
                    GatewayOutcome.Success(AuthoritativeEmailGroupMembership(account, groupId, emptyList()))
                } else {
                    GatewayOutcome.Failure(failure.first)
                }
            }
            val writer = object : ProtonContactEmailLabelGateway {
                override suspend fun apply(account: AccountScope, mutation: EmailLabelMutation): GatewayOutcome<Unit> {
                    writes++
                    return GatewayOutcome.Success(Unit)
                }
            }

            assertFailure(
                failure.first,
                ReconciledProtonEmailGroupAssignmentGateway(reader, writer, proofRetryDelayMillis = 0)
                    .apply(ACCOUNT, EmailLabelMutation.Assign(group, listOf(email))),
            )
            assertEquals(failure.second, reads)
            assertEquals(1, writes)
        }
    }

    @Test
    fun `D-066 empty normalized custom label has a safe deterministic type token`() {
        val encoded = ProtonContactVCardCodec().encode(
            contactWith(ContactValue("email", ContactValueKind.EMAIL, "fixture@example.test", "日本語", 0)),
        )

        assertTrue(encoded.signed.contains("ITEM1.EMAIL;TYPE=x-custom;PREF=1:fixture@example.test"))
        assertFalse(encoded.signed.contains("X-ABLABEL"))
    }
}

private fun fixture(name: String): String = requireNotNull(
    RichInventoryAdapterTest::class.java.getResource("/fixtures/gate-d/$name"),
).readText()

private fun contactJson(id: String): String =
    """{"ID":"$id","Name":"Fixture","UID":"uid-$id","Size":1,"ModifyTime":1,"ContactEmails":[]}"""

private fun authoritativeMetadata(id: String): ContactInventoryMetadata = ContactInventoryMetadata(
    id = RemoteContactId(id),
    displayName = "Fixture",
    version = RemoteVersion("version-$id"),
    sizeBytes = 1,
    modifiedAtEpochSeconds = 1,
    emailIds = listOf(RemoteEmailId("email-$id")),
    groupIds = emptyList(),
    versionProvenance = ContactInventoryVersionProvenance.REMOTE_SERVER,
    coverage = ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION,
    emailGroupMemberships = listOf(RemoteEmailGroupMembership(RemoteEmailId("email-$id"), emptyList())),
)

private fun emptyRemoteContact(): Contact = Contact(
    userId = UserId("fixture-user"),
    id = ContactId("fixture-contact"),
    name = "Fixture",
    contactEmails = emptyList(),
)

private fun contactWith(vararg values: ContactValue): CanonicalContact = CanonicalContact(
    accountId = "primary",
    id = "fixture-local",
    displayName = "Fixture",
    values = values.toList(),
)

private fun syntheticPublicCertificateDer(): ByteArray {
    val publicCertificateOnly = """
        MIIBzDCCATWgAwIBAgIIVeQ4N4TsGsMwDQYJKoZIhvcNAQELBQAwFzEVMBMGA1UE
        AxMMZml4dHVyZS50ZXN0MB4XDTI2MDgwMTE0MzYyN1oXDTM2MDcyOTE0MzYyN1ow
        FzEVMBMGA1UEAxMMZml4dHVyZS50ZXN0MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCB
        iQKBgQCS1jjostEEg4Al5P7nmIqbxolDbNhd5hrxrAQfD4z9CD43lYgeHqdw6JyM
        OVXjBwDcT+AT/MM+1scL2eIOfQW+gHCNTImrtbX9llCdiMhehXPHGsV7kLBrgKug
        as8fmXifx/5EtoITEDiFjgECjzTclgK5E3XPkAAOz5RMOyUHTwIDAQABoyEwHzAd
        BgNVHQ4EFgQUlQa8/ynlulg45vEJXMp3PW3Rau8wDQYJKoZIhvcNAQELBQADgYEA
        kZFfNzcgE0CoYjZPCAARWf8I2JobR6MRm0fiA5t7qxXmpzzJAaT2tqxptkmLvuRN
        VKmfA8Yt2gQ4tPI2X/rsOwlCo7igiPgddCk2BOfCkeHj70vrJAhMGi6n7t+73RoD
        GK6/uuCj27Vq1rGD1PEG3LLa/SuBg6UhRZyB+gsgkrQ=
    """.trimIndent().filterNot(Char::isWhitespace)
    return Base64.getDecoder().decode(publicCertificateOnly)
}

private fun ContactInventoryPlan.fullyCompleted(): ContactInventoryPlan =
    completedAfterDurableReconciliation(
        hydrated = hydrate,
        labelReconciled = labelOnly,
        deletionsReconciled = deleted,
        canonicalPersistenceCommitted = true,
    )

private fun <T> GatewayOutcome<T>.success(): T = (this as GatewayOutcome.Success<T>).value

private fun assertFailure(expected: GatewayFailureCategory, outcome: GatewayOutcome<*>) {
    assertEquals(expected, (outcome as GatewayOutcome.Failure).category)
}

private val ACCOUNT = AccountScope("primary")
