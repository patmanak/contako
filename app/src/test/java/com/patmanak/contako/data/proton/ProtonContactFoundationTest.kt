package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.gateway.ContactGroupCapability
import com.patmanak.contako.data.gateway.ContactGroupCapabilityAvailability
import com.patmanak.contako.data.gateway.ContactGroupMutation
import com.patmanak.contako.data.gateway.ContactGroupPublicCoreSurface
import com.patmanak.contako.data.gateway.ContactInventoryCoverage
import com.patmanak.contako.data.gateway.ContactInventorySnapshotAuthority
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.ContactInventoryVersionProvenance
import com.patmanak.contako.data.gateway.ContactMutation
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayContactHydrationCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.InventoryCursor
import com.patmanak.contako.data.gateway.RemoteContactId
import com.patmanak.contako.data.gateway.RemoteGroupId
import com.patmanak.contako.data.gateway.RemoteVersion
import com.patmanak.contako.data.gateway.requiresMutationReconciliationBeforeReplay
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroup
import com.patmanak.contako.domain.model.ContactGroupDefaults
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import ezvcard.Ezvcard
import me.proton.core.contact.data.api.resource.toContactCard
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.proton.core.contact.domain.entity.Contact
import me.proton.core.contact.domain.entity.ContactCard
import me.proton.core.contact.domain.entity.ContactCardType
import me.proton.core.contact.domain.entity.ContactEmail
import me.proton.core.contact.domain.entity.ContactEmailId
import me.proton.core.contact.domain.entity.ContactId
import me.proton.core.contact.domain.entity.ContactWithCards
import me.proton.core.domain.entity.UserId
import me.proton.core.label.domain.entity.Label
import me.proton.core.label.domain.entity.LabelId
import me.proton.core.label.domain.entity.LabelType
import me.proton.core.label.domain.entity.NewLabel
import me.proton.core.label.domain.entity.UpdateLabel
import me.proton.core.label.domain.repository.LabelRemoteDataSource
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import me.proton.core.crypto.common.pgp.exception.CryptoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureFoundationTest {
    @Test
    fun `02-ARCH uses public Core ports and no private transport contract`() {
        val foundation = source("ProtonPublicContactFoundation.kt")
        val crypto = source("ProtonContactVCardCodec.kt")

        assertTrue(foundation.contains("ContactRemoteDataSource"))
        assertTrue(foundation.contains("LabelRemoteDataSource"))
        assertFalse(foundation.contains("ApiProvider("))
        assertFalse(foundation.contains("retrofit2."))
        assertFalse(foundation.contains("mail.proton.me"))
        assertTrue(crypto.contains("decryptText"))
        assertTrue(crypto.contains("verifyText"))
        assertTrue(crypto.contains("encryptAndSignContactCard"))
        assertTrue(crypto.contains("signContactCard"))
        assertFalse(crypto.contains("GOpenPGP"))
    }

    private fun source(name: String): String {
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::exists) ?: error("source root")
        return root.walkTopDown().first { it.name == name }.readText()
    }
}

class ContactIndexFoundationTest {
    @Test
    fun `02-INDEX pages one bounded snapshot and preserves email to group relation`() = runTest {
        val remote = FakeContactRemote(List(301) { index -> contact(index) })
        val gateway = gateway(remote)

        val first = gateway.page(ACCOUNT, null).success()
        val second = gateway.page(ACCOUNT, first.nextCursor).success()

        assertEquals(300, first.contacts.size)
        assertEquals(1, second.contacts.size)
        assertEquals(1, remote.inventoryCalls)
        val metadata = first.contacts.first()
        assertEquals(ContactInventoryCoverage.PUBLIC_DIRECTORY_FIELDS_ONLY, metadata.coverage)
        assertEquals(ContactInventoryVersionProvenance.LOCAL_INDEX_FINGERPRINT, metadata.versionProvenance)
        assertEquals(listOf(RemoteGroupId("group-0")), metadata.emailGroupMemberships.single().groupIds)
        assertEquals(null, metadata.sizeBytes)
        assertEquals(null, metadata.modifiedAtEpochSeconds)
        // D-096: collection membership is attested even though entries carry only a local
        // fingerprint, because the maintained public route returns the complete list in one call
        // and every page is served from that single snapshot. D-032 requires this to plan at all.
        assertEquals(
            ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
            first.snapshotAuthority,
        )
        assertEquals(
            ContactInventorySnapshotAuthority.AUTHORITATIVE_REMOTE_REVISION,
            second.snapshotAuthority,
        )
    }

    @Test
    fun `02-INDEX rejects a cursor outside the active local snapshot`() = runTest {
        val outcome = gateway(FakeContactRemote(listOf(contact(0))))
            .page(ACCOUNT, InventoryCursor("foreign"))

        assertEquals(
            GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED),
            outcome,
        )
    }

    @Test
    fun `02-INDEX classifies duplicate remote identities as malformed response`() = runTest {
        val duplicate = contact(0)
        val outcome = gateway(FakeContactRemote(listOf(duplicate, duplicate))).page(ACCOUNT, null)

        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE), outcome)
    }

    private fun gateway(remote: FakeContactRemote) = ProtonPublicContactGateway(
        expectedAccount = ACCOUNT,
        userProvider = ProtonReadyUserProvider { USER_ID },
        remote = remote,
        cardCrypto = FakeCardCrypto(),
    )
}

class ProtonVCardParseDiagnosticTest {
    @Test
    fun `closed parser details retain malformed classification without exposing rejected data`() = runTest {
        val fixtures = listOf(
            "NOTE:private-canary\u202E" to GatewayContactHydrationCategory.VCARD_PARSE_CHARACTERS,
            "item1.X-ABLABEL:private-canary\r\nitem1.X-ABLABEL:other" to GatewayContactHydrationCategory.VCARD_PARSE_DUPLICATE_LABEL,
            "EMAIL;PREF=1;PREF=2:private-canary@example.test" to GatewayContactHydrationCategory.VCARD_PARSE_DUPLICATE_PARAMETER,
            "EMAIL;TYPE=\"private-canary:unclosed" to GatewayContactHydrationCategory.VCARD_PARSE_PARAMETER_SYNTAX,
            "KEY:-----BEGIN PGP PRIVATE KEY BLOCK-----private-canary" to GatewayContactHydrationCategory.VCARD_PARSE_PUBLIC_KEY,
        )
        fixtures.forEach { (property, detail) ->
            val remote = FakeContactRemote(listOf(contact(0))).apply {
                hydrated = ContactWithCards(contact(0), listOf(ContactCard.Signed("opaque", "signature")))
            }
            val crypto = FakeCardCrypto(plain = listOf(ProtonPlainContactCard(ContactCardType.Signed,
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:uid-0\r\nFN:Fixture\r\n$property\r\nEND:VCARD")))
            val gateway = ProtonPublicContactGateway(ACCOUNT, ProtonReadyUserProvider { USER_ID }, remote, crypto)
            val outcome = gateway.fetch(ACCOUNT, RemoteContactId(contact(0).id.id))
            assertTrue(outcome is GatewayOutcome.Failure)
            val failure = outcome as GatewayOutcome.Failure
            assertEquals(GatewayFailureCategory.MALFORMED_RESPONSE, failure.category)
            assertEquals(detail, failure.contactHydrationCategory)
            assertFalse(failure.toString().contains("private-canary"))
            assertEquals(0, remote.updateCalls)
        }
    }

    @Test
    fun `codec structural details have no payload message or cause`() {
        val codec = ProtonContactVCardCodec()
        val valid = ProtonPlainContactCard(ContactCardType.Signed,
            "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:uid-0\r\nFN:Fixture\r\nEND:VCARD")
        listOf(
            emptyList<ProtonPlainContactCard>() to GatewayContactHydrationCategory.VCARD_PARSE_BOUNDS,
            listOf(valid, valid) to GatewayContactHydrationCategory.VCARD_PARSE_DUPLICATE_CARD,
            listOf(valid.copy(vCard = valid.vCard.replace("VERSION:4.0", "VERSION:3.0"))) to
                GatewayContactHydrationCategory.VCARD_PARSE_ENVELOPE,
        ).forEach { (cards, detail) ->
            val failure = runCatching { codec.decode(ACCOUNT.value, contact(0), cards) }.exceptionOrNull()
            assertTrue(failure is ProtonVCardParseFailure)
            assertEquals(detail, (failure as ProtonVCardParseFailure).category)
            assertEquals(null, failure.message)
            assertEquals(null, failure.cause)
        }
    }
}

class ContactCryptoFoundationTest {
    @Test
    fun `D131 Core distinguishes unsigned encryption from a missing signed-card signature`() {
        val unsigned = me.proton.core.contact.data.api.resource.ContactCardResource(
            type = 1, data = "opaque-encrypted-card", signature = null,
        )
        assertEquals(ContactCard.Encrypted(unsigned.data, null), unsigned.toContactCard())
        val failure = runCatching { unsigned.copy(type = 3).toContactCard() }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(ContactCard.Encrypted(unsigned.data, "detached-signature"),
            unsigned.copy(type = 3, signature = "detached-signature").toContactCard())
    }

    @Test
    fun `02-CRYPTO plaintext policy accepts exactly one complete bounded vcard 4`() {
        val valid = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\nFN:Fixture\r\nEND:VCARD\r\n"
        val exactBytes = valid.toByteArray(Charsets.UTF_8).size

        assertTrue(parseSingleCompleteVCard(valid, exactBytes).write().contains("FN:Fixture"))
        val invalid = listOf(
            "X-PREAMBLE:forbidden\r\n$valid",
            "${valid}X-TRAILER:forbidden\r\n",
            valid + valid,
            valid.replace("VERSION:4.0\r\n", ""),
            valid.replace("VERSION:4.0", "VERSION:3.0"),
            valid.replace("VERSION:4.0", "VERSION:4.0\r\nVERSION:4.0"),
        )
        invalid.forEach { candidate ->
            assertTrue(
                runCatching { parseSingleCompleteVCard(candidate) }.exceptionOrNull()
                    is ProtonMalformedContactResponse,
            )
        }
        assertTrue(
            runCatching { parseSingleCompleteVCard(valid, exactBytes - 1) }.exceptionOrNull()
                is ProtonMalformedContactResponse,
        )
        assertTrue(
            runCatching { parseSingleCompleteVCard(valid.replace("VERSION:4.0", "VERSION:3.0")) }
                .exceptionOrNull() is ProtonPlaintextVCardVersionFailure,
        )
        assertTrue(
            runCatching { parseSingleCompleteVCard(valid.substringAfter("BEGIN:VCARD\r\n")) }
                .exceptionOrNull() is ProtonPlaintextVCardStructureFailure,
        )
        assertTrue(
            runCatching { parseSingleCompleteVCard(valid, exactBytes - 1) }
                .exceptionOrNull() is ProtonPlaintextVCardBoundsFailure,
        )
        assertEquals(valid, validateSingleCompleteHydratedVCard(valid))
        val protonCompatibleExtension =
            "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture-extension\r\nFN:Fixture\r\n" +
                "ITEM7.EMAIL;TYPE=X-SHARED:fixture@example.test\r\n" +
                "ITEM7.X-ABLABEL;LANG=fr:Partage\r\n" +
                "X-PROTON-OPAQUE;X-REPEAT=A;X-REPEAT=B:preserve-exactly\r\n" +
                "END:VCARD\r\n"
        assertEquals(
            protonCompatibleExtension,
            validateSingleCompleteHydratedVCard(protonCompatibleExtension),
        )
        assertTrue(
            runCatching {
                validateSingleCompleteHydratedVCard(valid.replace("VERSION:4.0", "VERSION:3.0"))
            }.exceptionOrNull() is ProtonPlaintextVCardVersionFailure,
        )
    }

    @Test
    fun `02-CRYPTO omits a structural-only clear card and retains clear categories`() {
        val structuralOnly = "BEGIN:VCARD\r\nVERSION:4.0\r\nEND:VCARD"
        val categories = structuralOnly.replace("END:VCARD", "CATEGORIES:Friends\r\nEND:VCARD")

        assertFalse(hasContactCardPayloadProperties(structuralOnly))
        assertTrue(hasContactCardPayloadProperties(categories))
    }

    @Test
    fun `02-CRYPTO verified hydration uses injected maintained-crypto boundary once`() = runTest {
        val remote = FakeContactRemote(listOf(contact(0))).apply {
            hydrated = ContactWithCards(
                contact(0),
                listOf(ContactCard.Signed("opaque", "signature")),
            )
        }
        val crypto = FakeCardCrypto(
            plain = listOf(
                ProtonPlainContactCard(
                    ContactCardType.Signed,
                    "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:uid-0\r\nFN:Alice\r\nEND:VCARD\r\n",
                ),
            ),
        )
        val gateway = ProtonPublicContactGateway(
            ACCOUNT,
            ProtonReadyUserProvider { USER_ID },
            remote,
            crypto,
        )

        val verified = gateway.fetch(ACCOUNT, RemoteContactId("contact-0")).success()

        assertEquals(1, crypto.decryptCalls)
        assertEquals("Alice", verified.contact.displayName)
        assertEquals("uid-0", verified.contact.remoteVCardUid)
    }

    @Test
    fun `02-CRYPTO mutation adapter invokes prepared-card crypto and never accepts CAS silently`() = runTest {
        val remote = FakeContactRemote(listOf(contact(0)))
        val crypto = FakeCardCrypto()
        val gateway = ProtonPublicContactGateway(
            ACCOUNT,
            ProtonReadyUserProvider { USER_ID },
            remote,
            crypto,
        )
        val unnamedExisting = CanonicalContact(
            accountId = "primary",
            id = "contact-0",
            remoteContactId = "contact-0",
        )

        val update = gateway.apply(
            ACCOUNT,
            ContactMutation.Update(RemoteContactId("contact-0"), null, unnamedExisting),
        )
        val rejectedCas = gateway.apply(
            ACCOUNT,
            ContactMutation.Delete(RemoteContactId("contact-0"), RemoteVersion("remote-cas")),
        )

        assertTrue(update is GatewayOutcome.Success)
        assertEquals(1, crypto.protectCalls)
        assertFalse(crypto.lastPrepared!!.signed.contains("Unnamed contact"))
        assertTrue(crypto.lastPrepared!!.signed.contains("FN;PREF=1:\r\n"))
        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.CONFLICT), rejectedCas)
        assertEquals(0, remote.deleteCalls)

        val wrongIdentity = gateway.apply(
            ACCOUNT,
            ContactMutation.Update(
                RemoteContactId("contact-0"),
                null,
                unnamedExisting.copy(remoteContactId = "contact-1"),
            ),
        )
        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED), wrongIdentity)
        assertEquals(1, crypto.protectCalls)
    }

    @Test
    fun `02-CREATE Gate D fixture 1 name-only passes protect and public create response`() = runTest {
        assertGateDFixtureCreateAccepted(gateDFixture(1))
    }

    @Test
    fun `02-CREATE Gate D fixture 2 common fields pass custom type public parsing`() = runTest {
        val prepared = assertGateDFixtureCreateAccepted(gateDFixture(2))

        assertTrue(prepared.signed.contains("ITEM1.EMAIL;TYPE=x-fixture-label"))
        assertTrue(prepared.encryptedPrivate.contains("ITEM1.TEL;TYPE=x-fixture-label"))
        assertFalse(prepared.signed.contains("X-ABLABEL"))
        assertFalse(prepared.encryptedPrivate.contains("X-ABLABEL"))
    }

    @Test
    fun `02-CREATE Proton Web partition groups every email in the signed card`() = runTest {
        val fixture = CanonicalContact(
            accountId = "primary",
            id = "fixture-email-placement",
            displayName = "fixture-email-placement",
            values = listOf(
                ContactValue("email", ContactValueKind.EMAIL, "fixture@example.test", null, 0, true),
                ContactValue("phone", ContactValueKind.PHONE, "+12025550123", null, 0, true),
                ContactValue("note", ContactValueKind.NOTE, "fixture-note", null, 0),
            ),
        )

        val prepared = ProtonContactVCardCodec().encode(fixture)

        assertEquals(
            "BEGIN:VCARD\r\n" +
                "VERSION:4.0\r\n" +
                "FN;PREF=1:fixture-email-placement\r\n" +
                "ITEM1.EMAIL;PREF=1:fixture@example.test\r\n" +
                "UID:fixture-email-placement\r\n" +
                "END:VCARD",
            prepared.signed,
        )
        assertEquals(
            "BEGIN:VCARD\r\n" +
                "VERSION:4.0\r\n" +
                "N:;;;;\r\n" +
                "TEL;PREF=1:+12025550123\r\n" +
                "NOTE:fixture-note\r\n" +
                "END:VCARD",
            prepared.encryptedPrivate,
        )
        assertFalse(prepared.signed.contains("TEL"))
        assertFalse(prepared.signed.contains("NOTE"))
        assertTrue(prepared.signed.contains("UID:fixture-email-placement"))
        assertFalse(prepared.encryptedPrivate.contains("EMAIL"))
        assertFalse(prepared.encryptedPrivate.contains("UID:"))
        assertTrue(prepared.encryptedPrivate.contains("TEL;PREF=1:+12025550123"))
        assertTrue(prepared.encryptedPrivate.contains("NOTE:fixture-note"))
        assertFalse(prepared.signed.contains("X-ABLABEL"))
    }

    @Test
    fun `02-CREATE Gate D fixture 3 preserved unknown field passes protect and public create response`() = runTest {
        val prepared = assertGateDFixtureCreateAccepted(gateDFixture(3))

        assertTrue(prepared.encryptedPrivate.contains("X-CTK-GD:fixture-vcard"))
    }

    @Test
    fun `02-CREATE raw ID is hydrated without requiring server cards to equal upload objects`() = runTest {
        val observed = mutableListOf<ProtonContactCreateStage>()
        val monitor = ProtonContactCreateStageMonitor().apply {
            observe(ProtonContactCreateStageObserver(observed::add))
        }
        val coreRemote = object : me.proton.core.contact.domain.repository.ContactRemoteDataSource {
            override suspend fun getContactWithCards(userId: UserId, contactId: ContactId): ContactWithCards =
                error("NOT_USED")
            override suspend fun getAllContacts(userId: UserId): List<Contact> = error("NOT_USED")
            override suspend fun createContacts(
                userId: UserId,
                contactCards: List<List<ContactCard>>,
            ): List<Contact> = error("RAW_CREATE_REQUIRED")
            override suspend fun deleteContacts(userId: UserId, contactIds: List<ContactId>) = error("NOT_USED")
            override suspend fun updateContact(
                userId: UserId,
                contactId: ContactId,
                contactCards: List<ContactCard>,
            ): Contact = error("NOT_USED")
        }
        val cards = listOf(ContactCard.Signed("opaque", "signature"))
        val hydratedContact = ContactWithCards(
            contact(0),
            listOf(ContactCard.Signed("server-normalized", "server-signature")),
        )
        val hydratingRemote = object : me.proton.core.contact.domain.repository.ContactRemoteDataSource by coreRemote {
            override suspend fun getContactWithCards(userId: UserId, contactId: ContactId): ContactWithCards =
                hydratedContact
        }
        val gateway = ProtonPublicContactGateway(
            ACCOUNT,
            ProtonReadyUserProvider { USER_ID },
            ProtonCoreContactRemotePort(
                hydratingRemote,
                ProtonRawContactCreateTransport { _, protected ->
                    assertEquals(cards, protected)
                    """{"Code":1000,"Responses":[{"Index":0,"Response":{"Code":1000,"Contact":{"ID":"contact-0"}}}]}"""
                },
                monitor,
            ),
            object : ProtonContactCardCrypto {
                override suspend fun decryptAndVerify(
                    userId: UserId,
                    cards: List<ContactCard>,
                ): List<ProtonPlainContactCard> = listOf(ProtonPlainContactCard(
                    ContactCardType.Signed, "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Fixture\r\nEND:VCARD\r\n",
                ))

                override suspend fun protect(
                    userId: UserId,
                    vCard: ProtonPreparedVCard,
                ): List<ContactCard> = cards
            },
            createStageObserver = monitor,
        )

        val outcome = gateway.apply(ACCOUNT, ContactMutation.Create(gateDFixture(2)))

        assertTrue(outcome is GatewayOutcome.Success)
        assertEquals(
            listOf(
                ProtonContactCreateStage.PROTECT,
                ProtonContactCreateStage.RAW_HTTP,
                ProtonContactCreateStage.RAW_PARSE,
                ProtonContactCreateStage.HYDRATE_VERIFY,
            ),
            observed,
        )
    }

    @Test
    fun `02-HYDRATE vCard UID is independent from server contact ID`() = runTest {
        val remote = FakeContactRemote(listOf(contact(0))).apply {
            hydrated = ContactWithCards(contact(0), listOf(ContactCard.Signed("opaque", "signature")))
        }
        val gateway = ProtonPublicContactGateway(
            ACCOUNT,
            ProtonReadyUserProvider { USER_ID },
            remote,
            FakeCardCrypto(
                listOf(
                    ProtonPlainContactCard(
                        ContactCardType.Signed,
                        "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:independent-vcard-uid\r\nFN:Fixture\r\nEND:VCARD\r\n",
                    ),
                ),
            ),
        )

        val outcome = gateway.fetch(ACCOUNT, RemoteContactId("contact-0")).success()

        assertEquals("contact-0", outcome.id.value)
        assertEquals("contact-0", outcome.contact.remoteContactId)
        assertEquals("independent-vcard-uid", outcome.contact.remoteVCardUid)
    }

    @Test
    fun `02-HYDRATE malformed stages expose closed subcategories`() = runTest {
        suspend fun outcomeFor(
            hydrated: ContactWithCards,
            crypto: ProtonContactCardCrypto = FakeCardCrypto(),
        ): GatewayOutcome.Failure {
            val remote = FakeContactRemote(listOf(contact(0))).apply { this.hydrated = hydrated }
            return ProtonPublicContactGateway(
                ACCOUNT,
                ProtonReadyUserProvider { USER_ID },
                remote,
                crypto,
            ).fetch(ACCOUNT, RemoteContactId("contact-0")) as GatewayOutcome.Failure
        }
        val wireCard = ContactCard.Signed("opaque", "signature")

        assertEquals(
            com.patmanak.contako.data.gateway.GatewayContactHydrationCategory.CARD_BOUNDS,
            outcomeFor(ContactWithCards(contact(0), emptyList())).contactHydrationCategory,
        )
        assertEquals(
            com.patmanak.contako.data.gateway.GatewayContactHydrationCategory.UID_ID_CONSISTENCY,
            outcomeFor(ContactWithCards(contact(1), listOf(wireCard))).contactHydrationCategory,
        )
        assertEquals(
            com.patmanak.contako.data.gateway.GatewayContactHydrationCategory.VCARD_PARSE_ENVELOPE,
            outcomeFor(
                ContactWithCards(contact(0), listOf(wireCard)),
                FakeCardCrypto(
                    listOf(ProtonPlainContactCard(ContactCardType.Signed, "not-a-vcard")),
                ),
            ).contactHydrationCategory,
        )
        val verificationFailure = outcomeFor(
            ContactWithCards(contact(0), listOf(wireCard)),
            object : ProtonContactCardCrypto {
                override suspend fun decryptAndVerify(userId: UserId, cards: List<ContactCard>) =
                    throw ProtonContactVerificationFailure()
                override suspend fun protect(userId: UserId, vCard: ProtonPreparedVCard) = emptyList<ContactCard>()
            },
        )
        assertEquals(GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED, verificationFailure.category)
        assertEquals(
            com.patmanak.contako.data.gateway.GatewayContactHydrationCategory.DECRYPT_VERIFY,
            verificationFailure.contactHydrationCategory,
        )
        val normalizationFailure = outcomeFor(
            ContactWithCards(contact(0), listOf(wireCard)),
            object : ProtonContactCardCrypto {
                override suspend fun decryptAndVerify(userId: UserId, cards: List<ContactCard>) =
                    throw ProtonHydrationMalformedResponse(
                        com.patmanak.contako.data.gateway.GatewayContactHydrationCategory.PLAINTEXT_VCARD_NORMALIZATION,
                    )
                override suspend fun protect(userId: UserId, vCard: ProtonPreparedVCard) = emptyList<ContactCard>()
            },
        )
        assertEquals(GatewayFailureCategory.MALFORMED_RESPONSE, normalizationFailure.category)
        assertEquals(
            com.patmanak.contako.data.gateway.GatewayContactHydrationCategory.PLAINTEXT_VCARD_NORMALIZATION,
            normalizationFailure.contactHydrationCategory,
        )
        val signatureFailure = outcomeFor(
            ContactWithCards(contact(0), listOf(wireCard)),
            object : ProtonContactCardCrypto {
                override suspend fun decryptAndVerify(userId: UserId, cards: List<ContactCard>) =
                    throw ProtonHydrationVerificationFailure(
                        com.patmanak.contako.data.gateway.GatewayContactHydrationCategory.SIGNATURE_VERIFICATION,
                    )
                override suspend fun protect(userId: UserId, vCard: ProtonPreparedVCard) = emptyList<ContactCard>()
            },
        )
        assertEquals(GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED, signatureFailure.category)
        assertEquals(
            com.patmanak.contako.data.gateway.GatewayContactHydrationCategory.SIGNATURE_VERIFICATION,
            signatureFailure.contactHydrationCategory,
        )
    }

    @Test
    fun `02-CREATE raw HTTP and parse failures retain distinct stages`() = runTest {
        suspend fun observedFor(response: suspend () -> String): List<ProtonContactCreateStage> {
            val observed = mutableListOf<ProtonContactCreateStage>()
            val remote = object : me.proton.core.contact.domain.repository.ContactRemoteDataSource {
                override suspend fun getContactWithCards(userId: UserId, contactId: ContactId): ContactWithCards =
                    error("HYDRATE_NOT_EXPECTED")
                override suspend fun getAllContacts(userId: UserId): List<Contact> = error("NOT_USED")
                override suspend fun createContacts(
                    userId: UserId,
                    contactCards: List<List<ContactCard>>,
                ): List<Contact> = error("NOT_USED")
                override suspend fun deleteContacts(userId: UserId, contactIds: List<ContactId>) = error("NOT_USED")
                override suspend fun updateContact(
                    userId: UserId,
                    contactId: ContactId,
                    contactCards: List<ContactCard>,
                ): Contact = error("NOT_USED")
            }
            val port = ProtonCoreContactRemotePort(
                remote,
                ProtonRawContactCreateTransport { _, _ -> response() },
                ProtonContactCreateStageObserver(observed::add),
            )

            assertTrue(runCatching { port.create(USER_ID, listOf(ContactCard.Signed("opaque", "signature"))) }.isFailure)
            return observed
        }

        assertEquals(
            listOf(ProtonContactCreateStage.RAW_HTTP),
            observedFor { error("HTTP_FAILURE") },
        )
        assertEquals(
            listOf(ProtonContactCreateStage.RAW_HTTP, ProtonContactCreateStage.RAW_PARSE),
            observedFor { "{}" },
        )
    }

    private suspend fun assertGateDFixtureCreateAccepted(fixture: CanonicalContact): ProtonPreparedVCard {
        val crypto = PublicCreateParserFakeCrypto()
        val remote = FakeContactRemote(emptyList()).apply {
            createResult = { cards -> publicCreateResponse(fixture, cards) }
        }
        val gateway = ProtonPublicContactGateway(
            ACCOUNT,
            ProtonReadyUserProvider { USER_ID },
            remote,
            crypto,
        )

        val outcome = gateway.apply(ACCOUNT, ContactMutation.Create(fixture))

        assertTrue(outcome is GatewayOutcome.Success)
        assertEquals(1, crypto.protectCalls)
        assertEquals(1, remote.createCalls)
        return requireNotNull(crypto.lastPrepared)
    }

    private fun publicCreateResponse(fixture: CanonicalContact, cards: List<ContactCard>): Contact {
        val plain = cards.map { card ->
            when (card) {
                is ContactCard.ClearText -> card.data
                is ContactCard.Signed -> card.data
                is ContactCard.Encrypted -> card.data
            }
        }
        plain.forEach(::parseSingleCompleteVCard)
        val signed = plain.single { it.contains("FN;PREF=1:") }
        val parsed = Ezvcard.parse(signed).first()
        val emails = parsed.emails.mapIndexed { index, email ->
            requireNotNull(email.group)
            require(email.types.isNotEmpty())
            require(parsed.extendedProperties.none { property ->
                property.propertyName.equals("X-ABLABEL", ignoreCase = true)
            })
            ContactEmail(
                userId = USER_ID,
                id = ContactEmailId("created-email-$index"),
                name = fixture.displayName,
                email = email.value,
                defaults = 1,
                order = index,
                contactId = ContactId("created-contact"),
                canonicalEmail = null,
                labelIds = emptyList(),
                isProton = false,
                lastUsedTime = 0,
            )
        }
        return Contact(USER_ID, ContactId("created-contact"), fixture.displayName, emails)
    }

    private fun gateDFixture(index: Int): CanonicalContact = when (index) {
        1 -> CanonicalContact("primary", "fixture-local-1", displayName = "fixture-name-only")
        2 -> CanonicalContact(
            accountId = "primary",
            id = "fixture-local-2",
            displayName = "fixture-common",
            values = listOf(
                ContactValue("fixture-email", ContactValueKind.EMAIL, "fixture@example.test", "fixture-label", 0, true),
                ContactValue("fixture-phone", ContactValueKind.PHONE, "+12025550123", "fixture-label", 0, true),
                ContactValue("fixture-note", ContactValueKind.NOTE, "fixture-note", null, 0),
            ),
        )
        3 -> CanonicalContact(
            accountId = "primary",
            id = "fixture-local-3",
            displayName = "fixture-unknown",
            preservationEnvelope = PreservationEnvelope(remoteBaseline = "X-CTK-GD:fixture-vcard"),
        )
        else -> error("fixture")
    }

    @Test
    fun `02-ACCOUNT every public contact operation rejects wrong scope before remote or crypto`() = runTest {
        val remote = FakeContactRemote(listOf(contact(0)))
        val crypto = FakeCardCrypto()
        val gateway = ProtonPublicContactGateway(
            ACCOUNT,
            ProtonReadyUserProvider { USER_ID },
            remote,
            crypto,
        )
        val other = AccountScope("other")
        val existing = CanonicalContact(
            accountId = "primary",
            id = "contact-0",
            remoteContactId = "contact-0",
            displayName = "Fixture",
        )
        val outcomes = listOf(
            gateway.page(other, null),
            gateway.fetch(other, RemoteContactId("contact-0")),
            gateway.apply(other, ContactMutation.Create(existing.copy(id = "local", remoteContactId = null))),
            gateway.apply(other, ContactMutation.Update(RemoteContactId("contact-0"), null, existing)),
            gateway.apply(other, ContactMutation.Delete(RemoteContactId("contact-0"), null)),
        )

        outcomes.forEach { outcome ->
            assertEquals(
                GatewayFailureCategory.AUTHENTICATION_REQUIRED,
                (outcome as GatewayOutcome.Failure).category,
            )
        }
        assertEquals(0, remote.inventoryCalls)
        assertEquals(0, remote.hydrateCalls)
        assertEquals(0, remote.createCalls)
        assertEquals(0, remote.updateCalls)
        assertEquals(0, remote.deleteCalls)
        assertEquals(0, crypto.decryptCalls)
        assertEquals(0, crypto.protectCalls)
    }

    @Test
    fun `02-GATEWAY-FAIL rejects mismatched remote identities and malformed remote cards`() = runTest {
        val remote = FakeContactRemote(listOf(contact(0))).apply {
            hydrated = ContactWithCards(contact(1), listOf(ContactCard.Signed("opaque", "signature")))
        }
        val crypto = FakeCardCrypto()
        val gateway = ProtonPublicContactGateway(ACCOUNT, ProtonReadyUserProvider { USER_ID }, remote, crypto)

        assertEquals(
            GatewayOutcome.Failure(
                GatewayFailureCategory.MALFORMED_RESPONSE,
                contactHydrationCategory = com.patmanak.contako.data.gateway.GatewayContactHydrationCategory.UID_ID_CONSISTENCY,
            ),
            gateway.fetch(ACCOUNT, RemoteContactId("contact-0")),
        )
        assertEquals(0, crypto.decryptCalls)

        remote.hydrated = ContactWithCards(contact(0), listOf(ContactCard.Signed("opaque", "signature")))
        val mixedUidCrypto = FakeCardCrypto(
            listOf(
                ProtonPlainContactCard(
                    ContactCardType.Signed,
                    "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:one\r\nFN:Fixture\r\nEND:VCARD\r\n",
                ),
                ProtonPlainContactCard(
                    ContactCardType.EncryptedAndSigned,
                    "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:two\r\nN:Fixture;;;;\r\nEND:VCARD\r\n",
                ),
            ),
        )
        val malformedGateway = ProtonPublicContactGateway(
            ACCOUNT,
            ProtonReadyUserProvider { USER_ID },
            remote,
            mixedUidCrypto,
        )
        assertEquals(
            GatewayOutcome.Failure(
                GatewayFailureCategory.MALFORMED_RESPONSE,
                contactHydrationCategory = com.patmanak.contako.data.gateway.GatewayContactHydrationCategory.UID_ID_CONSISTENCY,
            ),
            malformedGateway.fetch(ACCOUNT, RemoteContactId("contact-0")),
        )

        remote.updateResult = contact(2)
        val existing = CanonicalContact(
            accountId = "primary",
            id = "contact-0",
            displayName = "Fixture",
            remoteContactId = "contact-0",
        )
        assertEquals(
            GatewayOutcome.Failure(GatewayFailureCategory.MALFORMED_RESPONSE),
            gateway.apply(
                ACCOUNT,
                ContactMutation.Update(RemoteContactId("contact-0"), null, existing),
            ),
        )
    }
}

class ProtonDeleteBaselineTest {
    @Test fun `uncertain private readback retains retryable intent without replaying a write`() = runTest {
        val remote = FakeContactRemote(listOf(contact(0)))
        val delegate = FakeCardCrypto()
        var reads = 0
        val crypto = object : ProtonContactCardCrypto by delegate {
            override suspend fun decryptAndVerify(userId: UserId, cards: List<ContactCard>): List<ProtonPlainContactCard> =
                listOf(ProtonPlainContactCard(ContactCardType.EncryptedAndSigned,
                    if (reads++ == 0) "NOTE:intended" else "NOTE:concurrent"))
        }
        val gateway = ProtonPublicContactGateway(ACCOUNT, ProtonReadyUserProvider { USER_ID }, remote, crypto)
        val result = gateway.apply(ACCOUNT, ContactMutation.Update(RemoteContactId("contact-0"), null,
            CanonicalContact(accountId = ACCOUNT.value, id = "local", remoteContactId = "contact-0", displayName = "Fixture")))
        assertEquals(GatewayFailureCategory.NETWORK_UNAVAILABLE, (result as GatewayOutcome.Failure).category)
        assertEquals(1, remote.updateCalls)
        assertEquals(1, remote.hydrateCalls)
    }

    @Test fun `clear per email categories are independent but unknown clear fields remain protected`() {
        fun clear(body: String) = ProtonPlainContactCard(ContactCardType.ClearText, "BEGIN:VCARD\r\nVERSION:4.0\r\n$body\r\nEND:VCARD\r\n")
        val signed = ProtonPlainContactCard(ContactCardType.Signed, "FN:Fixture")
        val initial = listOf(signed, clear("ITEM1.CATEGORIES:group-one\r\nITEM2.CATEGORIES:group-two"))
        val removed = listOf(signed, clear("ITEM1.CATEGORIES:group-two"))
        assertEquals(verifiedContentFingerprint(contact(0), initial), verifiedContentFingerprint(contact(0), removed))
        assertEquals(verifiedContentFingerprint(contact(0), initial), verifiedContentFingerprint(contact(0), listOf(signed)))
        assertFalse(verifiedContentFingerprint(contact(0), initial) == verifiedContentFingerprint(contact(0), listOf(signed, clear("X-UNKNOWN:retained"))))
    }

    @Test fun `verified content baseline preserves private unknown fields and declared trust types`() {
        val index = contact(0)
        val plain = listOf(ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, "NOTE:original\r\nX-UNKNOWN:retained\r\n"))
        val baseline = verifiedContentFingerprint(index, plain)
        assertEquals(baseline, verifiedContentFingerprint(index.copy(contactEmails = index.contactEmails.map { it.copy(labelIds = emptyList()) }), plain))
        assertFalse(baseline == verifiedContentFingerprint(index, listOf(plain.single().copy(vCard = "NOTE:changed\r\nX-UNKNOWN:retained\r\n"))))
        assertFalse(baseline == verifiedContentFingerprint(index, listOf(plain.single().copy(vCard = "NOTE:original\r\nX-UNKNOWN:changed\r\n"))))
        assertFalse(baseline == verifiedContentFingerprint(index, listOf(plain.single().copy(type = ContactCardType.Encrypted))))
    }

    @Test fun `acknowledged create and update match verified reads after independent label changes`() = runTest {
        val remote = FakeContactRemote(listOf(contact(0)))
        val crypto = FakeCardCrypto()
        val gateway = ProtonPublicContactGateway(ACCOUNT, ProtonReadyUserProvider { USER_ID }, remote, crypto)
        val local = CanonicalContact(accountId = ACCOUNT.value, id = "local", displayName = "Fixture")
        for (mutation in listOf(
            ContactMutation.Create(local),
            ContactMutation.Update(RemoteContactId("contact-0"), null, local.copy(remoteContactId = "contact-0")),
        )) {
            val receipt = gateway.apply(ACCOUNT, mutation).success()
            val accepted = if (mutation is ContactMutation.Create) contact(999) else contact(0)
            val changedLabels = accepted.copy(contactEmails = accepted.contactEmails.map { it.copy(labelIds = listOf("other-group")) })
            val cards = listOf(ContactCard.ClearText(checkNotNull(crypto.lastPrepared).clear))
            remote.hydrated = ContactWithCards(changedLabels, cards)
            val read = gateway.fetch(ACCOUNT, receipt.id).success()
            assertEquals(receipt.version, read.version)
            assertTrue(read.matchesBaseline(receipt.version?.value))
            assertFalse(read.matchesBaseline(null))
            assertFalse(read.matchesBaseline("unrelated-index-version"))
        }
        assertEquals(4, remote.hydrateCalls) // one write confirmation and one explicit test read per mutation
    }

    @Test fun `complete content fingerprint changes for edits and signatures but ignores card ordering`() {
        val index = contact(0)
        val cards = listOf(ContactCard.Signed("public-card", "signature"), ContactCard.Encrypted("private-card", "private-signature"))
        val baseline = contentFingerprint(ContactWithCards(index, cards))
        assertEquals(baseline, contentFingerprint(ContactWithCards(index, cards.reversed())))
        for (changed in listOf(
            ContactWithCards(index.copy(name = "Changed"), cards),
            ContactWithCards(index.copy(contactEmails = index.contactEmails.map { it.copy(email = "changed@example.test") }), cards),
            ContactWithCards(index, listOf(cards.first(), ContactCard.Encrypted("changed-private-card", "private-signature"))),
            ContactWithCards(index, listOf(cards.first(), ContactCard.Encrypted("private-card", "changed-signature"))),
            ContactWithCards(index, cards + cards.first()),
        )) assertFalse(baseline == contentFingerprint(changed))
    }
}

class VCardFoundationTest {
    @Test fun `RF04 explicit mobile selection replaces imported custom type and grouped label`() {
        listOf(
            "TEL;TYPE=x-mobile;X-UNRELATED=kept:+12025550123",
            "ITEM3.TEL;TYPE=WORK;X-UNRELATED=kept:+12025550123\r\nITEM3.X-ABLABEL:Custom phone",
        ).forEach { phoneLine ->
            val signed = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\nFN:Fixture\r\nEND:VCARD\r\n"
            val private = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\n$phoneLine\r\n" +
                "X-UNRELATED-PROPERTY:preserved\r\nEND:VCARD\r\n"
            val decoded = codec.decode("primary", contact(1), listOf(
                ProtonPlainContactCard(ContactCardType.Signed, signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, private),
            ))
            val unchanged = codec.encode(decoded)
            val unchangedReparsed = codec.decode("primary", contact(1), listOf(
                ProtonPlainContactCard(ContactCardType.Signed, unchanged.signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, unchanged.encryptedPrivate),
            ))
            assertEquals(
                decoded.valuesOf(ContactValueKind.PHONE).single().label,
                unchangedReparsed.valuesOf(ContactValueKind.PHONE).single().label,
            )
            listOf("cell", null).forEach { label ->
                val edited = decoded.copy(values = decoded.values.map {
                    if (it.kind == ContactValueKind.PHONE) it.copy(label = label) else it
                })
                val encoded = codec.encode(edited)
                val reparsed = codec.decode("primary", contact(1), listOf(
                    ProtonPlainContactCard(ContactCardType.Signed, encoded.signed),
                    ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, encoded.encryptedPrivate),
                ))
                assertEquals(label, reparsed.valuesOf(ContactValueKind.PHONE).single().label)
                assertTrue(encoded.encryptedPrivate.contains("X-UNRELATED=kept"))
                assertTrue(encoded.encryptedPrivate.contains("X-UNRELATED-PROPERTY:preserved"))
                assertFalse(encoded.encryptedPrivate.contains("X-ABLABEL"))
                assertFalse(encoded.encryptedPrivate.contains("TYPE=x-mobile"))
            }
        }
    }
    private val codec = ProtonContactVCardCodec()

    @Test
    fun `RF04 clear categories follow their signed email through reordering`() {
        fun card(lines: String) = "BEGIN:VCARD\r\nVERSION:4.0\r\n$lines\r\nEND:VCARD\r\n"
        val decoded = codec.decode("primary", Contact(USER_ID, ContactId("contact"), "Fixture", emptyList()), listOf(
            ProtonPlainContactCard(ContactCardType.ClearText, card("ITEM1.CATEGORIES:Group A\r\nITEM2.CATEGORIES:Group B")),
            ProtonPlainContactCard(ContactCardType.Signed, card("FN:Fixture\r\nUID:fixture\r\nITEM1.EMAIL:a@example.test\r\nITEM2.EMAIL:b@example.test")),
        ))
        val unchanged = codec.encode(decoded)
        assertTrue(unchanged.clear.contains("ITEM1.CATEGORIES:Group A"))
        assertTrue(unchanged.clear.contains("ITEM2.CATEGORIES:Group B"))
        val reordered = decoded.copy(values = decoded.values.map {
            if (it.kind == ContactValueKind.EMAIL) it.copy(order = 1 - it.order) else it
        })
        val encoded = codec.encode(reordered)
        assertTrue(encoded.signed.contains("ITEM1.EMAIL;PREF=1:b@example.test"))
        assertTrue(encoded.clear.contains("ITEM2.CATEGORIES:Group A"))
        assertTrue(encoded.clear.contains("ITEM1.CATEGORIES:Group B"))
    }

    @Test
    fun `02-VCARD preserves card-specific unknown lines repetition order and yearless date`() {
        val signed = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:uid-1\r\nFN:Alice\r\n" +
            "ITEM1.EMAIL;TYPE=HOME:a@example.test\r\nITEM2.EMAIL;TYPE=HOME:a@example.test\r\n" +
            "X-SIGNED-UNKNOWN:one\r\nEND:VCARD\r\n"
        val private = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:uid-1\r\nN:Example;Alice;;;\r\n" +
            "BDAY:--03-14\r\nX-PRIVATE-UNKNOWN:two\r\nEND:VCARD\r\n"
        val decoded = codec.decode(
            "primary",
            contact(1),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, private),
            ),
        )

        assertEquals(3, decoded.valuesOf(ContactValueKind.EMAIL).size)
        assertEquals(
            listOf("a@example.test", "a@example.test"),
            decoded.valuesOf(ContactValueKind.EMAIL).take(2).map(ContactValue::value),
        )
        assertEquals("--03-14", decoded.valuesOf(ContactValueKind.BIRTHDAY).single().value)
        val encoded = codec.encode(decoded.copy(displayName = "Alice Edited"))
        assertTrue(encoded.signed.contains("X-SIGNED-UNKNOWN:one"))
        assertTrue(encoded.encryptedPrivate.contains("X-PRIVATE-UNKNOWN:two"))
        assertFalse(encoded.clear.contains("EMAIL"))
        assertTrue(encoded.signed.contains("EMAIL"))
    }

    @Test
    fun `02-VCARD omits custom dates and emits only supported custom type labels`() {
        val customDate = CanonicalContact(
            accountId = "primary",
            id = "local",
            displayName = "Alice",
            values = listOf(ContactValue("date", ContactValueKind.CUSTOM_DATE, "2026-01-01", order = 0)),
        )
        val localOnly = codec.encode(customDate)
        assertFalse(localOnly.encryptedPrivate.contains("2026-01-01"))
        assertFalse(localOnly.encryptedPrivate.contains("X-ABLABEL"))

        val customLabel = customDate.copy(
            values = listOf(
                ContactValue("email", ContactValueKind.EMAIL, "a@example.test", "School", 0),
                ContactValue("url", ContactValueKind.URL, "https://example.test", "Home", 0),
                ContactValue("related", ContactValueKind.RELATIONSHIP, "Alice", "Other", 0),
            ),
        )
        val encoded = codec.encode(customLabel)
        assertTrue(encoded.signed.contains("ITEM1.EMAIL;TYPE=x-school;PREF=1:a@example.test"))
        assertFalse(encoded.signed.contains("X-ABLABEL"))
        assertFalse(encoded.encryptedPrivate.contains("TYPE="))
        assertFalse(encoded.encryptedPrivate.contains("X-ABLABEL"))
        assertTrue(encoded.encryptedPrivate.contains("URL:https://example.test"))
        assertTrue(encoded.encryptedPrivate.contains("RELATED:Alice"))
    }

    @Test
    fun `02-VCARD grouped signed email fixture round trips without moving card partitions`() {
        val signed = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\nFN;PREF=1:Fixture\r\n" +
            "ITEM1.EMAIL;TYPE=WORK;PREF=1:fixture@example.test\r\nEND:VCARD\r\n"
        val private = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\nN:Fixture;;;;\r\n" +
            "TEL;TYPE=CELL;PREF=1:+12025550123\r\nNOTE:fixture-note\r\nEND:VCARD\r\n"
        val decoded = codec.decode(
            "primary",
            contact(1),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, private),
            ),
        )

        val encoded = codec.encode(decoded)
        val reparsed = codec.decode(
            "primary",
            contact(1),
            listOf(
                ProtonPlainContactCard(ContactCardType.Signed, encoded.signed),
                ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, encoded.encryptedPrivate),
            ),
        )

        assertTrue(encoded.signed.contains("ITEM1.EMAIL;TYPE=WORK;PREF=1:fixture@example.test"))
        assertFalse(encoded.encryptedPrivate.contains("EMAIL"))
        assertTrue(encoded.encryptedPrivate.contains("TEL;TYPE=CELL;PREF=1:+12025550123"))
        assertEquals(
            decoded.valuesOf(ContactValueKind.EMAIL).map(ContactValue::value),
            reparsed.valuesOf(ContactValueKind.EMAIL).map(ContactValue::value),
        )
        assertEquals(
            decoded.valuesOf(ContactValueKind.PHONE).map(ContactValue::value),
            reparsed.valuesOf(ContactValueKind.PHONE).map(ContactValue::value),
        )
    }
}

class GatewayFailureFoundationTest {
    @Test
    fun `02-GATEWAY-FAIL marks only ambiguous mutation delivery for reconciliation`() {
        val ambiguous = setOf(
            GatewayFailureCategory.NETWORK_UNAVAILABLE,
            GatewayFailureCategory.TIMEOUT,
            GatewayFailureCategory.CANCELLED,
            GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
            GatewayFailureCategory.UNKNOWN,
        )

        GatewayFailureCategory.entries.forEach { category ->
            assertEquals(
                category in ambiguous,
                category.requiresMutationReconciliationBeforeReplay(),
            )
        }
    }

    @Test
    fun `02-GATEWAY-FAIL contact writes attempt once and preserve uncertain delivery`() = runTest {
        val remote = FakeContactRemote(listOf(contact(0)))
        val gateway = ProtonPublicContactGateway(
            ACCOUNT,
            ProtonReadyUserProvider { USER_ID },
            remote,
            FakeCardCrypto(),
        )
        val created = CanonicalContact(accountId = "primary", id = "local", displayName = "Fixture")
        val updated = CanonicalContact(
            accountId = "primary",
            id = "contact-0",
            remoteContactId = "contact-0",
            displayName = "Fixture",
        )

        remote.mutationFailure = ApiException(ApiResult.Error.Timeout(isConnectedToNetwork = true))
        val create = gateway.apply(ACCOUNT, ContactMutation.Create(created))
        assertEquals(GatewayFailureCategory.TIMEOUT, (create as GatewayOutcome.Failure).category)
        assertTrue(create.category.requiresMutationReconciliationBeforeReplay())
        assertEquals(1, remote.createCalls)

        remote.mutationFailure = ApiException(
            ApiResult.Error.Http(httpCode = 503, message = "synthetic", proton = null),
        )
        val update = gateway.apply(
            ACCOUNT,
            ContactMutation.Update(RemoteContactId("contact-0"), null, updated),
        )
        assertEquals(GatewayFailureCategory.REMOTE_SERVICE_FAILURE, (update as GatewayOutcome.Failure).category)
        assertTrue(update.category.requiresMutationReconciliationBeforeReplay())
        assertEquals(1, remote.updateCalls)

        remote.mutationFailure = CancellationException("synthetic cancellation")
        assertTrue(
            runCatching {
                gateway.apply(ACCOUNT, ContactMutation.Delete(RemoteContactId("contact-0"), null))
            }.exceptionOrNull() is CancellationException,
        )
        assertEquals(1, remote.deleteCalls)
    }

    @Test
    fun `02-GATEWAY-FAIL group writes attempt once and preserve retry guidance`() = runTest {
        val remote = FakeLabelRemote()
        val gateway = ProtonPublicContactGroupGateway(
            ACCOUNT,
            ProtonReadyUserProvider { USER_ID },
            remote,
        )
        remote.mutationFailure = ApiException(
            ApiResult.Error.Http(
                httpCode = 429,
                message = "synthetic",
                proton = null,
                retryAfter = 7.seconds,
            ),
        )

        val create = gateway.create(ACCOUNT, ContactGroupMutation.Create("Friends", "#6D4AFF"))

        create as GatewayOutcome.Failure
        assertEquals(GatewayFailureCategory.RATE_LIMITED, create.category)
        assertEquals(7_000L, create.retryAfterMillis)
        assertFalse(create.category.requiresMutationReconciliationBeforeReplay())
        assertEquals(1, remote.createCalls)

        remote.mutationFailure = ApiException(
            ApiResult.Error.Http(httpCode = 503, message = "synthetic", proton = null),
        )
        val update = gateway.update(
            ACCOUNT,
            ContactGroupMutation.Update(RemoteGroupId("group"), "Friends", "#6D4AFF"),
        )
        assertEquals(GatewayFailureCategory.REMOTE_SERVICE_FAILURE, (update as GatewayOutcome.Failure).category)
        assertTrue(update.category.requiresMutationReconciliationBeforeReplay())
        assertEquals(1, remote.updateCalls)

        remote.mutationFailure = CancellationException("synthetic cancellation")
        assertTrue(
            runCatching {
                gateway.delete(ACCOUNT, ContactGroupMutation.Delete(RemoteGroupId("group")))
            }.exceptionOrNull() is CancellationException,
        )
        assertEquals(1, remote.deleteCalls)
    }

    @Test
    fun `02-GATEWAY-FAIL maps the complete HTTP and transport matrix to closed outcomes`() {
        val httpMatrix = mapOf(
            400 to GatewayFailureCategory.VALIDATION_REJECTED,
            401 to GatewayFailureCategory.AUTHENTICATION_REQUIRED,
            403 to GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED,
            404 to GatewayFailureCategory.NOT_FOUND,
            409 to GatewayFailureCategory.CONFLICT,
            412 to GatewayFailureCategory.CONFLICT,
            422 to GatewayFailureCategory.VALIDATION_REJECTED,
            429 to GatewayFailureCategory.RATE_LIMITED,
            500 to GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
            503 to GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
            418 to GatewayFailureCategory.UNKNOWN,
        )

        httpMatrix.forEach { (status, expected) ->
            val raw = ApiException(
                ApiResult.Error.Http(
                    httpCode = status,
                    message = "private remote text $status",
                    proton = ApiResult.Error.ProtonData(status, "private proton text $status"),
                    retryAfter = if (status == 429) 7.seconds else null,
                ),
            )
            val outcome = raw.toContactGatewayFailureOutcome()

            assertEquals(expected, outcome.category)
            assertEquals(if (status == 429) 7_000L else null, outcome.retryAfterMillis)
            assertFalse(outcome.toString().contains("private"))
        }

        assertEquals(
            GatewayFailureCategory.NETWORK_UNAVAILABLE,
            ApiException(ApiResult.Error.NoInternet()).toContactGatewayFailureOutcome().category,
        )
        assertEquals(
            GatewayFailureCategory.TIMEOUT,
            ApiException(ApiResult.Error.Timeout(isConnectedToNetwork = true))
                .toContactGatewayFailureOutcome().category,
        )
        assertEquals(
            GatewayFailureCategory.MALFORMED_RESPONSE,
            ApiException(ApiResult.Error.Parse(IOException("private parse text")))
                .toContactGatewayFailureOutcome().category,
        )
        assertEquals(
            GatewayFailureCategory.NETWORK_UNAVAILABLE,
            IOException("private network text").toContactGatewayFailureOutcome().category,
        )
    }

    @Test
    fun `02-GATEWAY-FAIL maps closed categories without retaining remote text`() {
        val raw = ApiException(
            ApiResult.Error.Http(
                httpCode = 403,
                message = "private remote text",
                proton = ApiResult.Error.ProtonData(2011, "private proton text"),
            ),
        )

        assertEquals(GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED, raw.toContactGatewayFailure())
        assertEquals(GatewayFailureCategory.NETWORK_UNAVAILABLE, IOException().toContactGatewayFailure())
        assertEquals(
            GatewayFailureCategory.LOCAL_REQUEST_BUDGET_EXHAUSTED,
            GateCLocalRequestBudgetExceeded().toContactGatewayFailure(),
        )
        assertEquals(
            GatewayFailureCategory.CRYPTOGRAPHIC_VERIFICATION_FAILED,
            CryptoException("private crypto text").toContactGatewayFailure(),
        )
        assertFalse(
            CryptoException("private crypto text").toContactGatewayFailureOutcome().toString()
                .contains("private crypto text"),
        )
        assertEquals(2011, raw.toContactGatewayFailureOutcome().protonResponseCode)
        assertEquals(
            "GatewayOutcome.Failure(category=PERMISSION_OR_PLAN_DENIED, retryAfterMillis=null)",
            GatewayOutcome.Failure(raw.toContactGatewayFailure()).toString(),
        )
    }

    @Test
    fun `02-GATEWAY-FAIL propagates fatal runtime errors instead of downgrading them`() = runTest {
        val fatal = AssertionError("synthetic fatal")
        val remote = FakeContactRemote(emptyList()).apply { inventoryFailure = fatal }
        val gateway = ProtonPublicContactGateway(
            ACCOUNT,
            ProtonReadyUserProvider { USER_ID },
            remote,
            FakeCardCrypto(),
        )
        var observed: AssertionError? = null

        try {
            gateway.page(ACCOUNT, null)
        } catch (error: AssertionError) {
            observed = error
        }

        assertSame(fatal, observed)
    }
}

class GroupCapabilityFoundationTest {
    @Test
    fun `02-GROUP-CAP new groups use the Proton accepted create palette color`() = runTest {
        val labels = FakeLabelRemote()
        val gateway = ProtonPublicContactGroupGateway(
            ACCOUNT,
            ProtonReadyUserProvider { USER_ID },
            labels,
        )

        assertEquals(
            ContactGroupDefaults.CREATE_COLOR,
            ContactGroup(ACCOUNT.value, "local-group", "Fixture").color,
        )
        gateway.create(ACCOUNT, ContactGroupMutation.Create("Fixture", null)).success()
        assertEquals(ContactGroupDefaults.CREATE_COLOR, labels.lastCreated?.color)
    }

    @Test
    fun `02-GROUP-CAP separates public API surface from unknown entitlement and missing assignment API`() = runTest {
        val labels = FakeLabelRemote()
        val gateway = ProtonPublicContactGroupGateway(
            ACCOUNT,
            ProtonReadyUserProvider { USER_ID },
            labels,
        )

        val capabilities = gateway.capabilities()
        assertEquals(ContactGroupCapabilityAvailability.UNKNOWN, capabilities.availability(ContactGroupCapability.CREATE))
        assertEquals(
            ContactGroupCapabilityAvailability.UNKNOWN,
            capabilities.availability(ContactGroupCapability.ASSIGN_EMAILS),
        )
        assertEquals(
            ContactGroupPublicCoreSurface.NOT_EXPOSED,
            capabilities.publicCoreSurface(ContactGroupCapability.ASSIGN_EMAILS),
        )
        val created = gateway.create(ACCOUNT, ContactGroupMutation.Create("Friends", "#8080FF")).success()
        assertEquals("Friends", created.name)
        assertEquals(LabelType.ContactGroup, labels.lastCreated?.type)
        assertEquals("#8080FF", labels.lastCreated?.color)
        assertEquals(false, labels.lastCreated?.isNotified)
        assertEquals(false, labels.lastCreated?.isExpanded)
        assertEquals(false, labels.lastCreated?.isSticky)
    }

    @Test
    fun `02-ACCOUNT every public group operation rejects wrong scope before remote access`() = runTest {
        val labels = FakeLabelRemote()
        val gateway = ProtonPublicContactGroupGateway(
            ACCOUNT,
            ProtonReadyUserProvider { USER_ID },
            labels,
        )
        val other = AccountScope("other")
        val outcomes = listOf(
            gateway.list(other),
            gateway.create(other, ContactGroupMutation.Create("Friends", "#6D4AFF")),
            gateway.update(
                other,
                ContactGroupMutation.Update(RemoteGroupId("group"), "Friends", "#6D4AFF"),
            ),
            gateway.delete(other, ContactGroupMutation.Delete(RemoteGroupId("group"))),
        )

        outcomes.forEach { outcome ->
            assertEquals(
                GatewayFailureCategory.AUTHENTICATION_REQUIRED,
                (outcome as GatewayOutcome.Failure).category,
            )
        }
        assertEquals(0, labels.listCalls)
        assertEquals(0, labels.createCalls)
        assertEquals(0, labels.updateCalls)
        assertEquals(0, labels.deleteCalls)
    }
}

class ProtonContactUpdateDiagnosticTest {
    @Test fun `update diagnostic distinguishes local field validation and remote refusal without changing outcomes`() = runTest {
        val remote = FakeContactRemote(listOf(contact(0)))
        val crypto = FakeCardCrypto()
        val observed = mutableListOf<List<Any?>>()
        val gateway = ProtonPublicContactGateway(ACCOUNT, ProtonReadyUserProvider { USER_ID }, remote, crypto,
            updateFailureObserver = { stage, category, encoding, field ->
                observed += listOf(stage, category, encoding, field)
                throw IllegalStateException("observer must be isolated")
            })
        val contact = CanonicalContact(accountId = ACCOUNT.value, id = "contact-0", remoteContactId = "contact-0",
            displayName = "Note fixture", values = listOf(ContactValue("note", ContactValueKind.NOTE,
                "invalid\uFFFD", order = 0)))
        val localFailure = gateway.apply(ACCOUNT, ContactMutation.Update(RemoteContactId("contact-0"), null, contact))
        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED), localFailure)
        assertEquals(listOf(ProtonContactUpdateStage.ENCODE, GatewayFailureCategory.VALIDATION_REJECTED,
            ProtonContactEncodingStage.FIELDS, ContactValueKind.NOTE), observed.single())
        assertEquals(0, remote.updateCalls)
        assertEquals(0, crypto.protectCalls)

        observed.clear()
        remote.mutationFailure = IllegalArgumentException("untrusted remote text")
        val valid = contact.copy(values = listOf(contact.values.single().copy(value = "Edited note")))
        val remoteFailure = gateway.apply(ACCOUNT, ContactMutation.Update(RemoteContactId("contact-0"), null, valid))
        assertEquals(GatewayOutcome.Failure(GatewayFailureCategory.VALIDATION_REJECTED), remoteFailure)
        assertEquals(listOf(ProtonContactUpdateStage.REMOTE_UPDATE, GatewayFailureCategory.VALIDATION_REJECTED,
            null, null), observed.single())
        assertEquals(1, remote.updateCalls)

        observed.clear()
        val cancellation = kotlinx.coroutines.CancellationException("cancelled")
        remote.mutationFailure = cancellation
        var caught: Throwable? = null
        try { gateway.apply(ACCOUNT, ContactMutation.Update(RemoteContactId("contact-0"), null, valid)) }
        catch (failure: kotlinx.coroutines.CancellationException) { caught = failure }
        assertTrue(caught === cancellation)
        assertTrue(observed.isEmpty())
    }
}

private class FakeCardCrypto(
    private val plain: List<ProtonPlainContactCard> = listOf(
        ProtonPlainContactCard(
            ContactCardType.Signed,
            "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:fixture\r\nFN:Fixture\r\nEND:VCARD\r\n",
        ),
    ),
) : ProtonContactCardCrypto {
    var decryptCalls = 0
    var protectCalls = 0
    var lastPrepared: ProtonPreparedVCard? = null

    override suspend fun decryptAndVerify(userId: UserId, cards: List<ContactCard>): List<ProtonPlainContactCard> {
        decryptCalls++
        return plain
    }

    override suspend fun protect(userId: UserId, vCard: ProtonPreparedVCard): List<ContactCard> {
        protectCalls++
        lastPrepared = vCard
        return listOf(ContactCard.ClearText(vCard.clear))
    }
}

private class PublicCreateParserFakeCrypto : ProtonContactCardCrypto {
    var protectCalls = 0
    var lastPrepared: ProtonPreparedVCard? = null

    override suspend fun decryptAndVerify(
        userId: UserId,
        cards: List<ContactCard>,
    ): List<ProtonPlainContactCard> = cards.map { card -> when (card) {
        is ContactCard.ClearText -> ProtonPlainContactCard(ContactCardType.ClearText, card.data)
        is ContactCard.Signed -> ProtonPlainContactCard(ContactCardType.Signed, card.data)
        is ContactCard.Encrypted -> ProtonPlainContactCard(ContactCardType.EncryptedAndSigned, card.data)
    } }

    override suspend fun protect(userId: UserId, vCard: ProtonPreparedVCard): List<ContactCard> {
        protectCalls++
        lastPrepared = vCard
        return buildList {
            add(ContactCard.Encrypted(vCard.encryptedPrivate, "fixture-signature"))
            add(ContactCard.Signed(vCard.signed, "fixture-signature"))
            if (hasContactCardPayloadProperties(vCard.clear)) add(ContactCard.ClearText(vCard.clear))
        }
    }
}

private class FakeContactRemote(
    var contacts: List<Contact>,
) : ProtonContactRemotePort {
    var inventoryCalls = 0
    var hydrateCalls = 0
    var createCalls = 0
    var updateCalls = 0
    var deleteCalls = 0
    var hydrated: ContactWithCards? = null
    var inventoryFailure: Throwable? = null
    var updateResult: Contact? = null
    var mutationFailure: Throwable? = null
    var createResult: ((List<ContactCard>) -> Contact)? = null

    override suspend fun inventory(userId: UserId): List<Contact> {
        inventoryCalls++
        inventoryFailure?.let { throw it }
        return contacts
    }

    override suspend fun hydrate(userId: UserId, contactId: ContactId): ContactWithCards {
        hydrateCalls++
        return hydrated ?: ContactWithCards(contacts.first { it.id == contactId }, emptyList())
    }

    override suspend fun create(userId: UserId, cards: List<ContactCard>): Contact {
        createCalls++
        mutationFailure?.let { throw it }
        return (createResult?.invoke(cards) ?: contact(999)).also {
            hydrated = ContactWithCards(it, cards)
        }
    }

    override suspend fun update(userId: UserId, contactId: ContactId, cards: List<ContactCard>): Contact {
        updateCalls++
        mutationFailure?.let { throw it }
        return (updateResult ?: contacts.firstOrNull { it.id == contactId } ?: contact(0)).also {
            hydrated = ContactWithCards(it, cards)
        }
    }

    override suspend fun delete(userId: UserId, contactId: ContactId) {
        deleteCalls++
        mutationFailure?.let { throw it }
    }
}

private class FakeLabelRemote : LabelRemoteDataSource {
    var lastCreated: NewLabel? = null
    var listCalls = 0
    var createCalls = 0
    var updateCalls = 0
    var deleteCalls = 0
    var mutationFailure: Throwable? = null

    override suspend fun getLabels(userId: UserId, type: LabelType): List<Label> {
        listCalls++
        return emptyList()
    }

    override suspend fun createLabel(userId: UserId, label: NewLabel): Label {
        createCalls++
        mutationFailure?.let { throw it }
        lastCreated = label
        return groupLabel("created", label.name, label.color)
    }

    override suspend fun updateLabel(userId: UserId, label: UpdateLabel): Label {
        updateCalls++
        mutationFailure?.let { throw it }
        return groupLabel(label.labelId.id, label.name, label.color)
    }

    override suspend fun deleteLabel(userId: UserId, labelId: LabelId) {
        deleteCalls++
        mutationFailure?.let { throw it }
    }
}

private fun contact(index: Int): Contact {
    val contactId = ContactId("contact-$index")
    return Contact(
        userId = USER_ID,
        id = contactId,
        name = "Contact $index",
        contactEmails = listOf(
            ContactEmail(
                userId = USER_ID,
                id = ContactEmailId("email-$index"),
                name = "Contact $index",
                email = "contact-$index@example.test",
                defaults = 1,
                order = 0,
                contactId = contactId,
                canonicalEmail = null,
                labelIds = listOf("group-$index"),
                isProton = false,
                lastUsedTime = 0,
            ),
        ),
    )
}

private fun groupLabel(id: String, name: String, color: String) = Label(
    userId = USER_ID,
    labelId = LabelId(id),
    parentId = null,
    name = name,
    type = LabelType.ContactGroup,
    path = name,
    color = color,
    order = 0,
    isNotified = false,
    isExpanded = false,
    isSticky = false,
)

private fun <T> GatewayOutcome<T>.success(): T = (this as GatewayOutcome.Success<T>).value

private val ACCOUNT = AccountScope("primary")
private val USER_ID = UserId("fixture-user")
