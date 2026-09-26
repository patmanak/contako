package com.patmanak.contako.qa

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.contako.ContakoApplication
import com.patmanak.contako.data.gateway.AuthenticationState
import com.patmanak.contako.data.gateway.ContactGroupMutation
import com.patmanak.contako.data.gateway.ContactInventoryPage
import com.patmanak.contako.data.gateway.ContactMutation
import com.patmanak.contako.data.gateway.EmailLabelMutation
import com.patmanak.contako.data.gateway.GatewayFailureCategory
import com.patmanak.contako.data.gateway.GatewayOutcome
import com.patmanak.contako.data.gateway.OperationSecret
import com.patmanak.contako.data.gateway.SessionState
import com.patmanak.contako.data.gateway.ValidatedCompleteInventory
import com.patmanak.contako.data.proton.GateCAuthDiagnostic
import com.patmanak.contako.data.proton.GateCAuthDiagnosticEvent
import com.patmanak.contako.data.proton.GateCAuthPhase
import com.patmanak.contako.data.proton.GateCDataMutationClass
import com.patmanak.contako.data.proton.GateCRequestAudit
import com.patmanak.contako.data.proton.GateCRequestClass
import com.patmanak.contako.data.proton.ProtonGateCRuntime
import com.patmanak.contako.data.proton.ProtonPlaintextVCardBoundsFailure
import com.patmanak.contako.data.proton.ProtonPlaintextVCardParserFailure
import com.patmanak.contako.data.proton.ProtonPlaintextVCardSerializationFailure
import com.patmanak.contako.data.proton.ProtonPlaintextVCardStructureFailure
import com.patmanak.contako.data.proton.ProtonPlaintextVCardVersionFailure
import com.patmanak.contako.data.proton.hasContactCardPayloadProperties
import com.patmanak.contako.data.proton.parseSingleCompleteVCard
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactGroupDefaults
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import com.patmanak.contako.domain.model.PreservationEnvelope
import com.patmanak.contako.qa.gatec.GateCCredentialReceiver
import ezvcard.Ezvcard
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Resumable owner-authorized preparation of the retained PN-02 remote directory.
 *
 * Only fixtures carrying [CONTACT_PREFIX] or [GROUP_PREFIX] are created. Existing contacts and
 * groups remain untouched. Public status contains counts and fixed stages only.
 */
@RunWith(AndroidJUnit4::class)
class ProtonNominalRemoteFixturePreparationDeviceTest {
    @Test
    fun prepareRetainedNominalDirectory() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_MODE) == MODE_PREPARE)
        require(com.patmanak.contako.BuildConfig.APPLICATION_ID ==
            ProtonNominalCandidateResidueCleaner.CANDIDATE_APPLICATION_ID) { "PN_FIXTURE_CANDIDATE_ISOLATION" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as ContakoApplication
        val audit = NominalFixtureRequestAudit()
        val authDiagnostic = NominalFixtureAuthDiagnostic()
        val runtime = ProtonGateCRuntime.createForInstrumentedGateC(
            context = context,
            requestAudit = audit,
            authDiagnostic = authDiagnostic,
            humanVerification = application.humanVerification.hooks,
        )
        var stage = "SESSION_RESTORE"
        var result = "FAIL"
        var initialContacts = -1
        var finalContacts = -1
        var createdContacts = 0
        var initialGroups = -1
        var finalGroups = -1
        var createdGroups = 0
        var assignedGroups = 0
        var closedFailure = "NONE"

        try {
            runBlocking {
                val restored = runtime.session.restore(runtime.accountScope).requireSuccess()
                check(restored == SessionState.READY) { "SESSION_NOT_READY" }

                stage = "CONTACT_PREFLIGHT"
                var inventory = completeInventory(runtime)
                initialContacts = inventory.contacts.size
                check(initialContacts <= TARGET_CONTACTS) { "CONTACT_TARGET_EXCEEDED" }
                val existingFixtureNames = inventory.contacts.mapNotNull { it.displayName }
                    .filter { it.startsWith(CONTACT_PREFIX) }
                    .toSet()
                val missingRoles = (1..TARGET_CONTACTS)
                    .map(::contactRole)
                    .filterNot(existingFixtureNames::contains)
                    .take(TARGET_CONTACTS - initialContacts)

                stage = "CONTACT_CREATE"
                missingRoles.forEachIndexed { offset, role ->
                    val index = role.removePrefix(CONTACT_PREFIX).toInt()
                    runtime.gateD.contactMutations.apply(
                        runtime.accountScope,
                        ContactMutation.Create(fixtureContact(runtime.accountScope.value, index)),
                    ).requireSuccess()
                    createdContacts = offset + 1
                }

                stage = "CONTACT_VERIFY"
                inventory = completeInventory(runtime)
                finalContacts = inventory.contacts.size
                check(finalContacts == TARGET_CONTACTS) { "CONTACT_TARGET_NOT_REACHED" }
                check(inventory.contacts.map { it.id }.distinct().size == TARGET_CONTACTS)

                stage = "GROUP_PREFLIGHT"
                var groups = readWithBackoff { runtime.gateD.groups.list(runtime.accountScope) }.groups
                initialGroups = groups.size
                check(initialGroups <= TARGET_GROUPS) { "GROUP_TARGET_EXCEEDED" }
                val existingGroupNames = groups.map { it.name }.toSet()
                val missingGroups = (1..TARGET_GROUPS)
                    .map(::groupRole)
                    .filterNot(existingGroupNames::contains)
                    .take(TARGET_GROUPS - initialGroups)

                stage = "GROUP_CREATE"
                missingGroups.forEachIndexed { offset, name ->
                    runtime.gateD.groups.create(
                        runtime.accountScope,
                        ContactGroupMutation.Create(name, GROUP_COLOR),
                    ).requireSuccess()
                    createdGroups = offset + 1
                }

                stage = "GROUP_VERIFY"
                groups = readWithBackoff { runtime.gateD.groups.list(runtime.accountScope) }.groups
                finalGroups = groups.size
                check(finalGroups == TARGET_GROUPS) { "GROUP_TARGET_NOT_REACHED" }
                check(groups.map { it.id }.distinct().size == TARGET_GROUPS)

                val assignableEmails = inventory.contacts
                    // The rich role receives exactly its two explicit oracle memberships later.
                    .filterNot { it.displayName == contactRole(1) }
                    .sortedBy { it.id.value }
                    .mapNotNull { it.emailIds.firstOrNull() }
                    .distinct()
                check(assignableEmails.size >= MEMBERS_PER_GROUP) { "EMAIL_FIXTURES_UNAVAILABLE" }

                stage = "OWNED_GROUP_ASSIGN"
                groups.filter { it.name.startsWith(GROUP_PREFIX) }
                    .sortedBy { it.name }
                    .forEachIndexed { groupIndex, group ->
                        val start = (groupIndex * MEMBERS_PER_GROUP) % assignableEmails.size
                        val desired = (0 until MEMBERS_PER_GROUP)
                            .map { assignableEmails[(start + it) % assignableEmails.size] }
                        runtime.gateD.emailLabels.apply(
                            runtime.accountScope,
                            EmailLabelMutation.Assign(group.id, desired),
                        ).requireSuccess()
                        val actual = readWithBackoff {
                            runtime.gateD.membershipReader.members(runtime.accountScope, group.id)
                        }.emailIds.toSet()
                        check(desired.all(actual::contains)) { "GROUP_MEMBERSHIP_NOT_REACHED" }
                        assignedGroups = groupIndex + 1
                    }

                stage = "FINAL_VERIFY"
                check(completeInventory(runtime).contacts.size == TARGET_CONTACTS)
                check(readWithBackoff { runtime.gateD.groups.list(runtime.accountScope) }.groups.size == TARGET_GROUPS)
                result = "PASS"
                stage = "COMPLETE"
            }
        } catch (failure: NominalFixtureGatewayFailure) {
            closedFailure = failure.category.name
        } catch (_: Throwable) {
            closedFailure = "INVARIANT"
            // The fixed stage and audit snapshot are the complete public failure surface. A later
            // run reconciles the stable fixture roles before issuing another create.
        } finally {
            val snapshot = audit.snapshot()
            instrumentation.sendStatus(0, Bundle().apply {
                putString("pn_fixture_result", result)
                putString("pn_fixture_stage", stage)
                putString("pn_fixture_failure", closedFailure)
                putInt("pn_fixture_initial_contacts", initialContacts)
                putInt("pn_fixture_final_contacts", finalContacts)
                putInt("pn_fixture_created_contacts", createdContacts)
                putInt("pn_fixture_initial_groups", initialGroups)
                putInt("pn_fixture_final_groups", finalGroups)
                putInt("pn_fixture_created_groups", createdGroups)
                putInt("pn_fixture_assigned_owned_groups", assignedGroups)
                putInt("pn_fixture_requests", snapshot.requests)
                putInt("pn_fixture_mutations", snapshot.mutations)
                putInt("pn_fixture_blocked", snapshot.blocked)
                putString("pn_fixture_auth_phase", authDiagnostic.phase)
                putString("pn_fixture_auth_failure", authDiagnostic.failure)
                putString("pn_fixture_session_retained", "YES")
            })
        }

        assertEquals("PASS", result)
    }

    private suspend fun completeInventory(runtime: ProtonGateCRuntime): ValidatedCompleteInventory {
        val pages = mutableListOf<ContactInventoryPage>()
        var cursor: com.patmanak.contako.data.gateway.InventoryCursor? = null
        do {
            val page = readWithBackoff { runtime.gateD.inventory.page(runtime.accountScope, cursor) }
            pages += page
            cursor = page.nextCursor
            check(pages.size <= MAX_INVENTORY_PAGES)
        } while (cursor != null)
        return ValidatedCompleteInventory.fromPages(pages)
    }

    private fun fixtureContact(accountId: String, index: Int): CanonicalContact {
        val role = contactRole(index)
        val baseValues = mutableListOf(
            ContactValue("$role-email-1", ContactValueKind.EMAIL, "$role@example.test", "work", 0, true),
        )
        if (index % 10 == 0) {
            baseValues += ContactValue("$role-phone-1", ContactValueKind.PHONE, "+1202555${index.toString().padStart(4, '0')}", "work", 0, true)
            baseValues += ContactValue("$role-note-1", ContactValueKind.NOTE, "$role-note", order = 0, isPrimary = true)
        }
        if (index == 1) baseValues += richValues(role)
        return CanonicalContact(
            accountId = accountId,
            id = "$role-local",
            firstName = "Nominal",
            lastName = index.toString().padStart(3, '0'),
            displayName = role,
            values = baseValues,
            preservationEnvelope = if (index == 1) {
                PreservationEnvelope(remoteBaseline = "X-CTK-PN:$role-preserved")
            } else {
                null
            },
        )
    }

    private fun richValues(role: String) = listOf(
        ContactValue("$role-email-2", ContactValueKind.EMAIL, "$role-secondary@example.test", "home", 1),
        ContactValue("$role-phone-1", ContactValueKind.PHONE, "+12025550100", "work", 0, true),
        ContactValue("$role-phone-2", ContactValueKind.PHONE, "+12025550101", "home", 1),
        ContactValue(
            "$role-address",
            ContactValueKind.POSTAL_ADDRESS,
            "1 Synthetic Way, Test City, FR",
            "work",
            0,
            true,
            components = mapOf("street" to "1 Synthetic Way", "locality" to "Test City", "country" to "FR"),
        ),
        ContactValue("$role-org", ContactValueKind.ORGANIZATION, "Contako QA", order = 0, isPrimary = true),
        ContactValue("$role-title", ContactValueKind.TITLE, "Nominal fixture", order = 0, isPrimary = true),
        ContactValue("$role-role", ContactValueKind.ROLE, "Test contact", order = 0, isPrimary = true),
        ContactValue("$role-nickname", ContactValueKind.NICKNAME, "PN Rich", order = 0, isPrimary = true),
        ContactValue("$role-url", ContactValueKind.URL, "https://example.test/contako", "work", 0, true),
        ContactValue("$role-relation", ContactValueKind.RELATIONSHIP, "Synthetic relation", "friend", 0),
        ContactValue("$role-note-1", ContactValueKind.NOTE, "$role-primary-note", order = 0, isPrimary = true),
        ContactValue("$role-note-2", ContactValueKind.NOTE, "$role-preserved-note", order = 1),
        ContactValue("$role-birthday", ContactValueKind.BIRTHDAY, "--02-29", order = 0, isPrimary = true),
        ContactValue("$role-anniversary", ContactValueKind.ANNIVERSARY, "2020-09-30", order = 0, isPrimary = true),
        ContactValue("$role-language", ContactValueKind.LANGUAGE, "fr", order = 0, isPrimary = true),
        ContactValue("$role-timezone", ContactValueKind.TIME_ZONE, "Europe/Paris", order = 0, isPrimary = true),
        ContactValue("$role-gender", ContactValueKind.GENDER, "O;synthetic", order = 0, isPrimary = true),
    )

    private fun <T> GatewayOutcome<T>.requireSuccess(): T = when (this) {
        is GatewayOutcome.Success -> value
        is GatewayOutcome.Failure -> throw NominalFixtureGatewayFailure(category)
    }

    private suspend fun <T> readWithBackoff(block: suspend () -> GatewayOutcome<T>): T {
        repeat(MAX_READ_ATTEMPTS) { attempt ->
            when (val outcome = block()) {
                is GatewayOutcome.Success -> return outcome.value
                is GatewayOutcome.Failure -> {
                    if (outcome.category !in TRANSIENT_READ_FAILURES || attempt == MAX_READ_ATTEMPTS - 1) {
                        throw NominalFixtureGatewayFailure(outcome.category)
                    }
                    delay(READ_BACKOFF_MILLIS shl attempt)
                }
            }
        }
        error("PN_FIXTURE_READ_RETRY_EXHAUSTED")
    }

    private fun contactRole(index: Int) = "$CONTACT_PREFIX${index.toString().padStart(3, '0')}"

    private fun groupRole(index: Int) = "$GROUP_PREFIX${index.toString().padStart(2, '0')}"

    private companion object {
        const val ARG_MODE = "pnFixtureMode"
        const val MODE_PREPARE = "prepare_retained_directory"
        const val CONTACT_PREFIX = "ctk-pn-base-"
        const val GROUP_PREFIX = "ctk-pn-label-"
        const val GROUP_COLOR = ContactGroupDefaults.CREATE_COLOR
        const val TARGET_CONTACTS = 300
        const val TARGET_GROUPS = 20
        const val MEMBERS_PER_GROUP = 10
        const val MAX_INVENTORY_PAGES = 1_000
        const val MAX_READ_ATTEMPTS = 3
        const val READ_BACKOFF_MILLIS = 250L
        val TRANSIENT_READ_FAILURES = setOf(
            GatewayFailureCategory.NETWORK_UNAVAILABLE,
            GatewayFailureCategory.TIMEOUT,
            GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
        )
    }
}

/** Guarded one-shot completion of the retained PN-02 rich-contact oracle. */
@RunWith(AndroidJUnit4::class)
class ProtonNominalRemoteRichFixturePreparationDeviceTest {
    @Test
    fun prepareRetainedRichContactAndRevokeSession() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_MODE) == MODE_PREPARE)
        val reuseCandidateSession = arguments.getString("pnRichSessionMode") == "reuse_candidate_session"
        require(com.patmanak.contako.BuildConfig.APPLICATION_ID ==
            ProtonNominalCandidateResidueCleaner.CANDIDATE_APPLICATION_ID) { "PN_RICH_CANDIDATE_ISOLATION" }
        val runId = requireNotNull(arguments.getString(ARG_RUN_ID)).also {
            require(it.matches(Regex("^[a-f0-9]{32}$"))) { "PN_RICH_RUN_ID_INVALID" }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as ContakoApplication
        val audit = NominalFixtureRequestAudit(allowSingleSessionRevoke = true)
        val authDiagnostic = NominalFixtureAuthDiagnostic()
        val runtime = ProtonGateCRuntime.createForInstrumentedGateC(
            context = context,
            requestAudit = audit,
            authDiagnostic = authDiagnostic,
            humanVerification = application.humanVerification.hooks,
        )
        var username: OperationSecret? = null
        var password: OperationSecret? = null
        var stage = "CREDENTIAL_CHANNEL"
        var result = "FAIL"
        var authentication = "NOT_REACHED"
        var fields = "NOT_REACHED"
        var media = "NOT_REACHED"
        var foldedMedia = "NOT_REACHED"
        var labels = "NOT_REACHED"
        var updateMutations = 0
        var labelMutations = 0
        var cleanup = "NOT_REACHED"
        var closedFailure = "NONE"

        (if (reuseCandidateSession) null else GateCCredentialReceiver(
            socketName = "contako.pn.rich.$runId",
            expectedRunId = runId,
            expectedPeerUid = GateCCredentialReceiver.DEFAULT_ADB_SHELL_UID,
            acceptTimeoutMillis = 30_000,
            readTimeoutMillis = 10_000,
        ).receiveOnce()).use { brokerSession ->
            try {
                if (brokerSession != null) {
                    username = OperationSecret.takeAndClear(
                        brokerSession.lease.withUsernameBytes(::decodeOwnedUtf8),
                    )
                    password = OperationSecret.takeAndClear(
                        brokerSession.lease.withPasswordBytes(::decodeOwnedUtf8),
                    )
                    brokerSession.lease.close()

                    stage = "AUTHENTICATE"
                    val auth = runBlocking {
                        runtime.authentication.signIn(runtime.accountScope, username!!, password!!)
                    }
                    authentication = when (auth) {
                        is GatewayOutcome.Success -> when (auth.value) {
                            AuthenticationState.Ready -> "READY"
                            is AuthenticationState.SecondFactorRequired -> "SECOND_FACTOR_REQUIRED"
                            AuthenticationState.MailboxPasswordRequired -> "MAILBOX_PASSWORD_REQUIRED"
                            AuthenticationState.KeyUnlockRequired -> "KEY_UNLOCK_REQUIRED"
                            AuthenticationState.SecurityKeyOnlyUnsupported -> "SECURITY_KEY_ONLY_UNSUPPORTED"
                        }
                        is GatewayOutcome.Failure -> "FAILURE_${auth.category.name}"
                    }
                } else {
                    stage = "SESSION_RESTORE"
                    authentication = when (val restored = runBlocking {
                        runtime.session.restore(runtime.accountScope)
                    }) {
                        is GatewayOutcome.Success -> if (restored.value == SessionState.READY) "READY" else "SESSION_NOT_READY"
                        is GatewayOutcome.Failure -> "FAILURE_${restored.category.name}"
                    }
                }
                check(authentication == "READY") { "PN_RICH_AUTH_NOT_READY" }

                runBlocking {
                    stage = "INVENTORY"
                    val inventory = completeInventory(runtime)
                    check(inventory.contacts.size == TARGET_CONTACTS) { "PN_RICH_CONTACT_COUNT" }
                    val metadata = inventory.contacts.singleOrNull { it.displayName == RICH_ROLE }
                        ?: error("PN_RICH_CONTACT_NOT_FOUND")
                    check(metadata.emailIds.size >= 2) { "PN_RICH_EMAIL_IDENTITIES" }

                    stage = "HYDRATE"
                    var verified = runtime.gateD.verifiedCards.fetch(
                        runtime.accountScope,
                        metadata.id,
                    ).requireSuccess()

                    val oracleMedia = oracleMedia()
                    stage = "RICH_MEDIA_STANDALONE_ENCODE"
                    val standaloneMedia = runtime.gateD.vCardCodec.encode(
                        CanonicalContact(
                            accountId = runtime.accountScope.value,
                            id = "$RICH_ROLE-media-preflight",
                            displayName = RICH_ROLE,
                            values = listOf(oracleMedia.first()),
                        ),
                    )
                    stage = "RICH_MEDIA_STANDALONE_DIRECT_PARSE"
                    classifyDirectParse(standaloneMedia.encryptedPrivate)
                    stage = "RICH_MEDIA_STANDALONE_FOLDED_PARSE"
                    parseStrictPrivate(foldImageProperties(standaloneMedia.encryptedPrivate), "MEDIA_FOLDED")
                    foldedMedia = "PASS"
                    stage = "RICH_MEDIA_STANDALONE_PRIVATE_PARSE"
                    parseStrictPrivate(standaloneMedia.encryptedPrivate, "MEDIA_STANDALONE")

                    stage = "RICH_BASELINE_ENCODE"
                    val baselinePrepared = runtime.gateD.vCardCodec.encode(verified.contact)
                    stage = "RICH_BASELINE_PRIVATE_PARSE"
                    parseSingleCompleteVCard(baselinePrepared.encryptedPrivate)

                    oracleMedia.indices.forEach { index ->
                        stage = "RICH_MEDIA_${index + 1}_ENCODE"
                        val incremental = runtime.gateD.vCardCodec.encode(
                            completeRichOracle(verified.contact, oracleMedia.take(index + 1)),
                        )
                        stage = "RICH_MEDIA_${index + 1}_PRIVATE_PARSE"
                        parseSingleCompleteVCard(incremental.encryptedPrivate)
                    }
                    val requiresUpdate = runCatching { verifyRichFields(verified.contact) }.isFailure ||
                        !hasOracleMedia(verified.contact, oracleMedia)
                    if (requiresUpdate) {
                        val completed = completeRichOracle(verified.contact, oracleMedia)
                        stage = "RICH_ENCODE"
                        val prepared = runtime.gateD.vCardCodec.encode(completed)
                        stage = "RICH_PRIVATE_PARSE"
                        parseSingleCompleteVCard(prepared.encryptedPrivate)
                        stage = "RICH_SIGNED_PARSE"
                        parseSingleCompleteVCard(prepared.signed)
                        if (hasContactCardPayloadProperties(prepared.clear)) {
                            stage = "RICH_CLEAR_PARSE"
                            parseSingleCompleteVCard(prepared.clear)
                        }
                        stage = "RICH_UPDATE"
                        runtime.gateD.contactMutations.apply(
                            runtime.accountScope,
                            ContactMutation.Update(
                                verified.id,
                                expectedVersion = null,
                                contact = completed,
                            ),
                        ).requireSuccess()
                        updateMutations = 1
                        verified = runtime.gateD.verifiedCards.fetch(
                            runtime.accountScope,
                            metadata.id,
                        ).requireSuccess()
                    }
                    verifyRichFields(verified.contact)
                    fields = "PASS"
                    check(hasOracleMedia(verified.contact, oracleMedia)) { "PN_RICH_MEDIA_NOT_REACHED" }
                    check(verified.contact.valuesOf(ContactValueKind.PHOTO).first().isPrimary)
                    media = "PASS"

                    stage = "LABEL_ASSIGN"
                    // A successful contact-card update invalidates the earlier inventory
                    // observation. Never authorize a label mutation with potentially stale remote
                    // email identities captured before that update.
                    val currentMetadata = completeInventory(runtime).contacts
                        .singleOrNull { it.displayName == RICH_ROLE }
                        ?: error("PN_RICH_CONTACT_NOT_FOUND_AFTER_UPDATE")
                    check(currentMetadata.emailIds.size >= 2) { "PN_RICH_EMAIL_IDENTITIES_AFTER_UPDATE" }
                    val groups = runtime.gateD.groups.list(runtime.accountScope).requireSuccess().groups
                    check(groups.size == TARGET_GROUPS) { "PN_RICH_GROUP_COUNT" }
                    val ownedGroups = groups.filter { it.name.startsWith(GROUP_PREFIX) }
                        .sortedBy { it.name }
                    check(ownedGroups.size >= 2) { "PN_RICH_OWNED_GROUPS" }
                    val preferredEmailId = currentMetadata.emailIds.first()
                    ownedGroups.take(2).forEach { group ->
                        val membership = runtime.gateD.membershipReader.members(
                            runtime.accountScope,
                            group.id,
                        ).requireSuccess()
                        if (preferredEmailId !in membership.emailIds) {
                            runtime.gateD.emailLabels.apply(
                                runtime.accountScope,
                                EmailLabelMutation.Assign(group.id, listOf(preferredEmailId)),
                            ).requireSuccess()
                            labelMutations++
                        }
                        val verifiedMembership = runtime.gateD.membershipReader.members(
                            runtime.accountScope,
                            group.id,
                        ).requireSuccess()
                        check(preferredEmailId in verifiedMembership.emailIds) {
                            "PN_RICH_LABEL_NOT_REACHED"
                        }
                    }
                    labels = "PASS"
                    stage = "VERIFY"
                    check(completeInventory(runtime).contacts.size == TARGET_CONTACTS)
                    check(runtime.gateD.groups.list(runtime.accountScope).requireSuccess().groups.size == TARGET_GROUPS)
                }
                result = "PASS"
                stage = "CLEANUP"
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: NominalRichFieldFailure) {
                closedFailure = failure.category
            } catch (failure: NominalFixtureGatewayFailure) {
                closedFailure = failure.category.name
            } catch (_: Throwable) {
                closedFailure = "INVARIANT"
            } finally {
                username?.close()
                password?.close()
                brokerSession?.lease?.close()
                cleanup = runBlocking {
                    when (runtime.session.revokeAndClear(runtime.accountScope)) {
                        is GatewayOutcome.Success -> "PASS"
                        is GatewayOutcome.Failure -> "FAIL"
                    }
                }
                if (result == "PASS" && cleanup == "PASS") {
                    stage = "COMPLETE"
                }
                val snapshot = audit.snapshot()
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("pn_rich_result", result)
                    putString("pn_rich_stage", stage)
                    putString("pn_rich_failure", closedFailure)
                    putString("pn_rich_authentication", authentication)
                    putString("pn_rich_fields", fields)
                    putString("pn_rich_media", media)
                    putString("pn_rich_folded_media", foldedMedia)
                    putString("pn_rich_labels", labels)
                    putString("pn_rich_cleanup", cleanup)
                    putInt("pn_rich_update_mutations", updateMutations)
                    putInt("pn_rich_label_mutations", labelMutations)
                    putInt("pn_rich_requests", snapshot.requests)
                    putInt("pn_rich_mutations", snapshot.mutations)
                    putInt("pn_rich_session_revokes", snapshot.sessionRevokes)
                    putInt("pn_rich_blocked", snapshot.blocked)
                    putString("pn_rich_auth_phase", authDiagnostic.phase)
                    putString("pn_rich_auth_failure", authDiagnostic.failure)
                })
                if (result == "PASS" && cleanup == "PASS") brokerSession?.completePass()
            }
        }

        assertEquals("PASS", result)
        assertEquals("PASS", cleanup)
    }

    private suspend fun completeInventory(runtime: ProtonGateCRuntime): ValidatedCompleteInventory {
        val pages = mutableListOf<ContactInventoryPage>()
        var cursor: com.patmanak.contako.data.gateway.InventoryCursor? = null
        do {
            val page = readWithBackoff { runtime.gateD.inventory.page(runtime.accountScope, cursor) }
            pages += page
            cursor = page.nextCursor
            check(pages.size <= MAX_INVENTORY_PAGES)
        } while (cursor != null)
        return ValidatedCompleteInventory.fromPages(pages)
    }

    private fun verifyRichFields(contact: CanonicalContact) {
        requireRich(
            contact.firstName.isNotBlank() && contact.lastName.isNotBlank() && contact.displayName.isNotBlank(),
            "FIELD_NAME",
        )
        requireRich(contact.valuesOf(ContactValueKind.EMAIL).size >= 2, "FIELD_EMAIL_COUNT")
        requireRich(contact.valuesOf(ContactValueKind.EMAIL).first().isPrimary, "FIELD_EMAIL_PRIMARY")
        requireRich(contact.valuesOf(ContactValueKind.PHONE).size >= 2, "FIELD_PHONE_COUNT")
        requireRich(contact.valuesOf(ContactValueKind.NOTE).size >= 2, "FIELD_NOTE_COUNT")
        val birthdays = contact.valuesOf(ContactValueKind.BIRTHDAY)
        requireRich(birthdays.size == 1, "FIELD_BIRTHDAY_COUNT")
        requireRich(birthdays.first().value.startsWith("--"), "FIELD_BIRTHDAY_YEARLESS")
        requireRich(contact.valuesOf(ContactValueKind.ANNIVERSARY).size == 1, "FIELD_ANNIVERSARY_COUNT")
        REQUIRED_SINGLE_KINDS.forEach { kind ->
            requireRich(contact.valuesOf(kind).isNotEmpty(), "FIELD_${kind.name}")
        }
        requireRich(contact.preservationEnvelope != null, "FIELD_PRESERVATION")
    }

    private fun completeRichOracle(
        contact: CanonicalContact,
        oracleMedia: List<ContactValue>,
    ): CanonicalContact {
        val values = contact.values.toMutableList()
        fun ensureOne(value: ContactValue) {
            if (values.none { it.kind == value.kind }) values += value
        }
        fun ensureCount(kind: ContactValueKind, required: List<ContactValue>) {
            required.forEach { value ->
                if (
                    values.count { it.kind == kind } < required.size &&
                    values.none { it.kind == kind && it.value == value.value }
                ) {
                    values += value
                }
            }
            requireRich(values.count { it.kind == kind } >= required.size, "FIELD_${kind.name}_COUNT")
        }

        ensureCount(ContactValueKind.EMAIL, listOf(
            ContactValue("$RICH_ROLE-email-1", ContactValueKind.EMAIL, "$RICH_ROLE@example.test", "work", 0, true),
            ContactValue("$RICH_ROLE-email-2", ContactValueKind.EMAIL, "$RICH_ROLE-secondary@example.test", "home", 1),
        ))
        ensureCount(ContactValueKind.PHONE, listOf(
            ContactValue("$RICH_ROLE-phone-1", ContactValueKind.PHONE, "+12025550100", "work", 0, true),
            ContactValue("$RICH_ROLE-phone-2", ContactValueKind.PHONE, "+12025550101", "home", 1),
        ))
        ensureCount(ContactValueKind.NOTE, listOf(
            ContactValue("$RICH_ROLE-note-1", ContactValueKind.NOTE, "$RICH_ROLE-primary-note", order = 0, isPrimary = true),
            ContactValue("$RICH_ROLE-note-2", ContactValueKind.NOTE, "$RICH_ROLE-preserved-note", order = 1),
        ))
        ensureOne(ContactValue(
            "$RICH_ROLE-address",
            ContactValueKind.POSTAL_ADDRESS,
            "1 Synthetic Way, Test City, FR",
            "work",
            0,
            true,
            components = mapOf("street" to "1 Synthetic Way", "locality" to "Test City", "country" to "FR"),
        ))
        ensureOne(ContactValue(
            "$RICH_ROLE-org",
            ContactValueKind.ORGANIZATION,
            "Contako QA",
            order = 0,
            isPrimary = true,
            components = mapOf("company" to "Contako QA"),
        ))
        ensureOne(ContactValue("$RICH_ROLE-title", ContactValueKind.TITLE, "Nominal fixture", order = 0, isPrimary = true))
        ensureOne(ContactValue("$RICH_ROLE-role", ContactValueKind.ROLE, "Test contact", order = 0, isPrimary = true))
        ensureOne(ContactValue("$RICH_ROLE-nickname", ContactValueKind.NICKNAME, "PN Rich", order = 0, isPrimary = true))
        ensureOne(ContactValue("$RICH_ROLE-url", ContactValueKind.URL, "https://example.test/contako", "work", 0, true))
        ensureOne(ContactValue("$RICH_ROLE-relation", ContactValueKind.RELATIONSHIP, "Synthetic relation", "friend", 0))
        ensureOne(ContactValue("$RICH_ROLE-birthday", ContactValueKind.BIRTHDAY, "--02-29", order = 0, isPrimary = true))
        ensureOne(ContactValue("$RICH_ROLE-anniversary", ContactValueKind.ANNIVERSARY, "2020-09-30", order = 0, isPrimary = true))
        ensureOne(ContactValue("$RICH_ROLE-language", ContactValueKind.LANGUAGE, "fr", order = 0, isPrimary = true))
        ensureOne(ContactValue("$RICH_ROLE-timezone", ContactValueKind.TIME_ZONE, "Europe/Paris", order = 0, isPrimary = true))
        ensureOne(ContactValue("$RICH_ROLE-gender", ContactValueKind.GENDER, "O;synthetic", order = 0, isPrimary = true))

        val retained = values.filterNot {
            it.kind == ContactValueKind.PHOTO || it.kind == ContactValueKind.LOGO
        }
        val retainedPhotos = values.filter { it.kind == ContactValueKind.PHOTO }
            .filterNot { current -> oracleMedia.any { it.value == current.value } }
            .mapIndexed { index, value -> value.copy(order = index + 2, isPrimary = false) }
        val retainedLogos = values.filter { it.kind == ContactValueKind.LOGO }
            .filterNot { current -> oracleMedia.any { it.value == current.value } }
            .mapIndexed { index, value -> value.copy(order = index + 1, isPrimary = false) }
        val normalized = (retained + oracleMedia + retainedPhotos + retainedLogos).map { value ->
            if (value.kind == ContactValueKind.EMAIL) {
                val firstId = retained.first { it.kind == ContactValueKind.EMAIL }.id
                value.copy(isPrimary = value.id == firstId)
            } else {
                value
            }
        }
        return contact.copy(
            firstName = contact.firstName.ifBlank { "Nominal" },
            lastName = contact.lastName.ifBlank { "001" },
            displayName = contact.displayName.ifBlank { RICH_ROLE },
            values = normalized,
        )
    }

    private fun requireRich(condition: Boolean, category: String) {
        if (!condition) throw NominalRichFieldFailure(category)
    }

    private fun parseStrictPrivate(card: String, boundary: String) {
        try {
            parseSingleCompleteVCard(card)
        } catch (_: ProtonPlaintextVCardStructureFailure) {
            throw NominalRichFieldFailure("${boundary}_STRUCTURE")
        } catch (_: ProtonPlaintextVCardVersionFailure) {
            throw NominalRichFieldFailure("${boundary}_VERSION")
        } catch (_: ProtonPlaintextVCardParserFailure) {
            throw NominalRichFieldFailure("${boundary}_PARSER")
        } catch (_: ProtonPlaintextVCardSerializationFailure) {
            throw NominalRichFieldFailure("${boundary}_SERIALIZATION")
        } catch (_: ProtonPlaintextVCardBoundsFailure) {
            throw NominalRichFieldFailure("${boundary}_BOUNDS")
        }
    }

    private fun classifyDirectParse(card: String) {
        val parsed = try {
            Ezvcard.parse(card).all()
        } catch (_: IndexOutOfBoundsException) {
            throw NominalRichFieldFailure("MEDIA_DIRECT_INDEX")
        } catch (_: IllegalArgumentException) {
            throw NominalRichFieldFailure("MEDIA_DIRECT_ARGUMENT")
        } catch (_: NullPointerException) {
            throw NominalRichFieldFailure("MEDIA_DIRECT_NULL")
        } catch (_: ClassCastException) {
            throw NominalRichFieldFailure("MEDIA_DIRECT_CAST")
        } catch (_: RuntimeException) {
            throw NominalRichFieldFailure("MEDIA_DIRECT_RUNTIME_OTHER")
        } catch (_: IOException) {
            throw NominalRichFieldFailure("MEDIA_DIRECT_IO")
        } catch (_: StackOverflowError) {
            throw NominalRichFieldFailure("MEDIA_DIRECT_STACK")
        } catch (_: OutOfMemoryError) {
            throw NominalRichFieldFailure("MEDIA_DIRECT_MEMORY")
        } catch (_: ExceptionInInitializerError) {
            throw NominalRichFieldFailure("MEDIA_DIRECT_INITIALIZER")
        } catch (error: NoClassDefFoundError) {
            throw NominalRichFieldFailure(classifyMissingMediaClass(error))
        } catch (_: VerifyError) {
            throw NominalRichFieldFailure("MEDIA_DIRECT_VERIFY")
        } catch (_: LinkageError) {
            throw NominalRichFieldFailure("MEDIA_DIRECT_LINKAGE")
        } catch (_: AssertionError) {
            throw NominalRichFieldFailure("MEDIA_DIRECT_ASSERTION")
        } catch (_: Throwable) {
            throw NominalRichFieldFailure("MEDIA_DIRECT_THROWABLE_OTHER")
        }
        when (parsed.size) {
            1 -> Unit
            0 -> throw NominalRichFieldFailure("MEDIA_DIRECT_EMPTY")
            else -> throw NominalRichFieldFailure("MEDIA_DIRECT_MULTIPLE")
        }
    }

    private fun classifyMissingMediaClass(error: NoClassDefFoundError): String {
        val descriptions = buildList {
            var current: Throwable? = error
            while (current != null) {
                current.message?.let(::add)
                current = current.cause
            }
        }
        val category = when {
            descriptions.any { "BinaryPropertyScribe\$1" in it } -> "BINARY_SWITCH"
            descriptions.any { "VCardPropertyScribe\$1" in it } -> "PROPERTY_SCRIBE_SWITCH"
            descriptions.any { "DateOrTimePropertyScribe\$1" in it } -> "DATE_TIME_SWITCH"
            descriptions.any { "GeoScribe\$1" in it } -> "GEO_SWITCH"
            descriptions.any { "ImppScribe\$HtmlLinkFormat" in it } -> "IMPP_HTML_FORMAT"
            descriptions.any { "KeyScribe\$1" in it } -> "KEY_SWITCH"
            descriptions.any { "TimezoneScribe\$1" in it } -> "TIMEZONE_SWITCH"
            descriptions.any { "VCardDataType\$1" in it } -> "DATA_TYPE_SWITCH"
            descriptions.any { "VCardParameters\$1" in it } -> "PARAMETERS_SWITCH"
            descriptions.any { "VCardPropertyScribe" in it } -> "PROPERTY_SCRIBE"
            descriptions.any { "VCardDataType" in it } -> "DATA_TYPE"
            descriptions.any { "VCardParameters" in it } -> "PARAMETERS"
            descriptions.any { "MediaTypeCaseClasses" in it } -> "MEDIA_TYPE_CASES"
            descriptions.any { "VCardParameterCaseClasses" in it } -> "PARAMETER_CASES"
            descriptions.any { "CaseClasses" in it } -> "CASE_CLASSES"
            descriptions.any { "ImagePropertyScribe" in it } -> "IMAGE_SCRIBE"
            descriptions.any { "BinaryPropertyScribe" in it } -> "BINARY_SCRIBE"
            descriptions.any { "PhotoScribe" in it } -> "PHOTO_SCRIBE"
            descriptions.any { "LogoScribe" in it } -> "LOGO_SCRIBE"
            descriptions.any { "ImageProperty" in it } -> "IMAGE_PROPERTY"
            descriptions.any { "ImageType" in it } -> "IMAGE_TYPE"
            descriptions.any { "BaseNCodec\$Context" in it } -> "BASE_N_CONTEXT"
            descriptions.any { "BaseNCodec" in it } -> "BASE_N_CODEC"
            descriptions.any { "Base64" in it } -> "BASE64"
            descriptions.any { "DataUri" in it } -> "DATA_URI"
            descriptions.any { "VObjectPropertyValues" in it } -> "VINNIE_VALUES"
            descriptions.any { "ScribeIndex" in it } -> "SCRIBE_INDEX"
            descriptions.any { "ezvcard" in it } -> fingerprintMissingEzvcardClass(descriptions)
            descriptions.any { "mangstadt" in it || "vinnie" in it } -> "OTHER_VINNIE"
            else -> "OTHER"
        }
        return "MEDIA_DIRECT_MISSING_$category"
    }

    private fun fingerprintMissingEzvcardClass(descriptions: List<String>): String {
        val descriptorPattern = Regex("""L(ezvcard/[A-Za-z0-9_$/]+);""")
        val dottedPattern = Regex("""(ezvcard(?:[./][A-Za-z0-9_$]+)+)""")
        val normalized = descriptions.firstNotNullOfOrNull { description ->
            descriptorPattern.find(description)?.groupValues?.get(1)
                ?: dottedPattern.find(description)?.groupValues?.get(1)
        }?.replace('/', '.') ?: return "OTHER_EZVCARD_UNPARSED"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { byte -> "%02X".format(byte) }
        return "EZVCARD_HASH_$digest"
    }

    private fun foldImageProperties(card: String): String = card
        .split("\r\n")
        .flatMap { line ->
            if (!line.startsWith("PHOTO") && !line.startsWith("LOGO")) {
                listOf(line)
            } else {
                buildList {
                    add(line.take(75))
                    var offset = 75
                    while (offset < line.length) {
                        add(" " + line.substring(offset, minOf(offset + 74, line.length)))
                        offset += 74
                    }
                }
            }
        }
        .joinToString("\r\n")

    private fun oracleMedia(): List<ContactValue> = listOf(
        ContactValue(
            "$RICH_ROLE-photo-primary",
            ContactValueKind.PHOTO,
            pngDataUri(Color.rgb(109, 74, 255)),
            order = 0,
            isPrimary = true,
            metadata = mapOf("vcardPref" to "1"),
        ),
        ContactValue(
            "$RICH_ROLE-photo-gallery",
            ContactValueKind.PHOTO,
            pngDataUri(Color.rgb(82, 82, 204)),
            order = 1,
            metadata = mapOf("vcardPref" to "2"),
        ),
        ContactValue(
            "$RICH_ROLE-logo",
            ContactValueKind.LOGO,
            pngDataUri(Color.rgb(128, 128, 255)),
            order = 0,
            isPrimary = true,
        ),
    )

    private fun hasOracleMedia(contact: CanonicalContact, oracle: List<ContactValue>): Boolean {
        val current = contact.values
        return oracle.all { expected -> current.any { it.kind == expected.kind && it.value == expected.value } } &&
            contact.valuesOf(ContactValueKind.PHOTO).size >= 2 &&
            contact.valuesOf(ContactValueKind.LOGO).isNotEmpty()
    }

    private fun pngDataUri(color: Int): String {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            val bytes = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            try {
                "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"
            } finally {
                bytes.fill(0)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeOwnedUtf8(source: ByteArray): CharArray =
        NominalFixtureUtf8Decoder(source.size).use { it.decode(source) }

    private fun <T> GatewayOutcome<T>.requireSuccess(): T = when (this) {
        is GatewayOutcome.Success -> value
        is GatewayOutcome.Failure -> throw NominalFixtureGatewayFailure(category)
    }

    private suspend fun <T> readWithBackoff(block: suspend () -> GatewayOutcome<T>): T {
        repeat(MAX_READ_ATTEMPTS) { attempt ->
            when (val outcome = block()) {
                is GatewayOutcome.Success -> return outcome.value
                is GatewayOutcome.Failure -> {
                    if (outcome.category !in TRANSIENT_READ_FAILURES || attempt == MAX_READ_ATTEMPTS - 1) {
                        throw NominalFixtureGatewayFailure(outcome.category)
                    }
                    delay(READ_BACKOFF_MILLIS shl attempt)
                }
            }
        }
        error("PN_RICH_READ_RETRY_EXHAUSTED")
    }

    private companion object {
        const val ARG_MODE = "pnRichFixtureMode"
        const val ARG_RUN_ID = "pnRichFixtureRun"
        const val MODE_PREPARE = "prepare_retained_rich_contact"
        const val RICH_ROLE = "ctk-pn-base-001"
        const val GROUP_PREFIX = "ctk-pn-label-"
        const val TARGET_CONTACTS = 300
        const val TARGET_GROUPS = 20
        const val MAX_INVENTORY_PAGES = 1_000
        const val MAX_READ_ATTEMPTS = 3
        const val READ_BACKOFF_MILLIS = 250L
        val TRANSIENT_READ_FAILURES = setOf(
            GatewayFailureCategory.NETWORK_UNAVAILABLE,
            GatewayFailureCategory.TIMEOUT,
            GatewayFailureCategory.REMOTE_SERVICE_FAILURE,
        )
        val REQUIRED_SINGLE_KINDS = setOf(
            ContactValueKind.POSTAL_ADDRESS,
            ContactValueKind.ORGANIZATION,
            ContactValueKind.TITLE,
            ContactValueKind.ROLE,
            ContactValueKind.NICKNAME,
            ContactValueKind.URL,
            ContactValueKind.RELATIONSHIP,
            ContactValueKind.LANGUAGE,
            ContactValueKind.TIME_ZONE,
            ContactValueKind.GENDER,
        )
    }
}

private class NominalFixtureUtf8Decoder(maximumChars: Int) : Closeable {
    private val scratch = CharArray(maximumChars)

    fun decode(source: ByteArray): CharArray {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val output = CharBuffer.wrap(scratch)
        val decoded = decoder.decode(ByteBuffer.wrap(source), output, true)
        if (decoded.isError) decoded.throwException()
        check(decoded.isUnderflow) { "PN_RICH_CREDENTIAL_ENCODING" }
        val flushed = decoder.flush(output)
        if (flushed.isError) flushed.throwException()
        check(flushed.isUnderflow) { "PN_RICH_CREDENTIAL_ENCODING" }
        val owned = scratch.copyOf(output.position())
        scratch.fill('\u0000')
        return owned
    }

    override fun close() {
        scratch.fill('\u0000')
    }
}

private data class NominalFixtureRequestSnapshot(
    val requests: Int,
    val mutations: Int,
    val sessionRevokes: Int,
    val blocked: Int,
)

private class NominalFixtureGatewayFailure(
    val category: GatewayFailureCategory,
) : IOException("PN_FIXTURE_GATEWAY_FAILURE")

private class NominalRichFieldFailure(
    val category: String,
) : IOException("PN_RICH_FIELD_INVARIANT")

private class NominalFixtureAuthDiagnostic : GateCAuthDiagnostic {
    @Volatile
    var phase: String = "NONE"
        private set

    @Volatile
    var failure: String = "NONE"
        private set

    override fun onPhase(phase: GateCAuthPhase) {
        this.phase = phase.name
    }

    override fun onFailure(event: GateCAuthDiagnosticEvent) {
        failure = event.diagnosticClass.name
    }
}

private class NominalFixtureRequestAudit(
    private val allowSingleSessionRevoke: Boolean = false,
) : GateCRequestAudit {
    private val lock = Any()
    private var requests = 0
    private var mutations = 0
    private var sessionRevokes = 0
    private var blocked = 0
    private var activeReads = 0
    private var activeWrites = 0
    private var stopped = false

    override fun onRequest(requestClass: GateCRequestClass) = synchronized(lock) {
        if (
            stopped || requests >= MAX_REQUESTS ||
            (requestClass == GateCRequestClass.SESSION_REVOKE && (!allowSingleSessionRevoke || sessionRevokes >= 1))
        ) {
            blocked++
            throw IOException("PN_FIXTURE_REQUEST_BLOCKED")
        }
        if (requestClass == GateCRequestClass.SESSION_REVOKE) sessionRevokes++
        requests++
        Unit
    }

    override fun onDataMutationAttempt(mutationClass: GateCDataMutationClass) = synchronized(lock) {
        if (stopped || mutations >= MAX_MUTATIONS || mutationClass == GateCDataMutationClass.UNKNOWN_CONTACT_WRITE) {
            blocked++
            throw IOException("PN_FIXTURE_MUTATION_BLOCKED")
        }
        mutations++
        Unit
    }

    override fun onRequestTarget(host: String, method: String, pathSegments: List<String>) = synchronized(lock) {
        val path = pathSegments.map(String::lowercase)
        val verb = method.uppercase()
        val contactRoot = listOf("contacts", "v4", "contacts")
        val labelRoot = listOf("core", "v4", "labels")
        // Opaque contact/label identities are data, not endpoint names. Substring matching
        // made random encrypted identities containing e.g. "2fa" abort legitimate requests.
        val endpoint = if (path.take(3) == contactRoot || path.take(3) == labelRoot) path.take(3) else path
        val allowedWrite = when {
            verb == "POST" && (path == contactRoot || path == labelRoot) -> true
            verb == "PUT" && path.size == 4 && path.take(3) == contactRoot -> true
            verb == "PUT" && path == contactRoot + listOf("emails", "label") -> true
            verb == "POST" && path in setOf(
                listOf("auth", "v4"), listOf("auth", "v4", "info"), listOf("auth", "v4", "refresh"),
            ) -> true
            verb == "DELETE" && allowSingleSessionRevoke && path == listOf("auth", "v4") -> true
            else -> false
        }
        if (host != "api.protonmail.ch" || endpoint.any { it in FORBIDDEN_PATH_SEGMENTS } ||
            (verb !in READ_METHODS && !allowedWrite)
        ) {
            stopped = true
            blocked++
            // OkHttp delivers IO failures to the caller; a SecurityException on its dispatcher
            // killed the process before aggregate evidence and cleanup could be published.
            throw IOException("PN_FIXTURE_PROHIBITED_TARGET")
        }
        if (method.uppercase() in READ_METHODS) activeReads++ else activeWrites++
        if (activeReads > MAX_ACTIVE_READS || activeWrites > MAX_ACTIVE_WRITES) {
            stopped = true
            blocked++
            throw IOException("PN_FIXTURE_CONCURRENCY_BLOCKED")
        }
    }

    override fun onRequestComplete(method: String, statusCode: Int?) = synchronized(lock) {
        if (method.uppercase() in READ_METHODS) activeReads = (activeReads - 1).coerceAtLeast(0)
        else activeWrites = (activeWrites - 1).coerceAtLeast(0)
        if (statusCode == HTTP_TOO_MANY_REQUESTS) stopped = true
    }

    fun snapshot() = synchronized(lock) {
        NominalFixtureRequestSnapshot(requests, mutations, sessionRevokes, blocked)
    }

    private companion object {
        const val MAX_REQUESTS = 2_048
        const val MAX_MUTATIONS = 512
        const val MAX_ACTIVE_READS = 2
        const val MAX_ACTIVE_WRITES = 1
        const val HTTP_TOO_MANY_REQUESTS = 429
        val READ_METHODS = setOf("GET", "HEAD", "OPTIONS")
        val FORBIDDEN_PATH_SEGMENTS = setOf(
            "password",
            "recovery",
            "securitykey",
            "security-key",
            "2fa",
            "payment",
            "billing",
            "subscription",
            "delete-account",
        )
    }
}
