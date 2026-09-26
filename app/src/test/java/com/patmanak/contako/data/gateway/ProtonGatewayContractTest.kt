package com.patmanak.contako.data.gateway

import com.patmanak.contako.domain.model.CanonicalContact
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonGatewayContractTest {
    @Test
    fun gatewayFailuresExposeOnlyClosedSanitizedState() {
        val failure = GatewayOutcome.Failure(
            category = GatewayFailureCategory.RATE_LIMITED,
            retryAfterMillis = 4_000,
        )

        assertEquals(GatewayFailureCategory.RATE_LIMITED, failure.category)
        assertEquals(4_000L, failure.retryAfterMillis)
        assertEquals(
            "GatewayOutcome.Failure(category=RATE_LIMITED, retryAfterMillis=4000)",
            failure.toString(),
        )
        assertFalse(failure.javaClass.declaredFields.any { Throwable::class.java.isAssignableFrom(it.type) })
        assertFalse(failure.javaClass.declaredFields.any { it.name.contains("message", ignoreCase = true) })
        assertFalse(failure.javaClass.declaredFields.any { it.name.contains("body", ignoreCase = true) })
        assertTrue(runCatching { GatewayOutcome.Failure(GatewayFailureCategory.RATE_LIMITED, -1) }.isFailure)
    }

    @Test
    fun malformedResponseDetailIsClosedAndRestrictedToMalformedFailures() {
        val detail = GatewayMalformedResponseCategory.RAW_CREATE_CONTACT_ID
        val failure = GatewayOutcome.Failure(
            GatewayFailureCategory.MALFORMED_RESPONSE,
            malformedResponseCategory = detail,
        )

        assertEquals(detail, failure.malformedResponseCategory)
        assertEquals(
            "GatewayOutcome.Failure(category=MALFORMED_RESPONSE, retryAfterMillis=null, " +
                "malformedResponseCategory=RAW_CREATE_CONTACT_ID)",
            failure.toString(),
        )
        assertTrue(
            runCatching {
                GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN, malformedResponseCategory = detail)
            }.isFailure,
        )
    }

    @Test
    fun protonCodeIsNumericBoundedClosedState() {
        val failure = GatewayOutcome.Failure(
            GatewayFailureCategory.MALFORMED_RESPONSE,
            malformedResponseCategory = GatewayMalformedResponseCategory.RAW_CREATE_NESTED_CODE,
            protonResponseCode = 2001,
        )

        assertEquals(2001, failure.protonResponseCode)
        assertTrue(failure.toString().contains("protonResponseCode=2001"))
        assertEquals(
            2001,
            GatewayOutcome.Failure(
                GatewayFailureCategory.VALIDATION_REJECTED,
                protonResponseCode = 2001,
            ).protonResponseCode,
        )
        assertEquals(
            2001,
            GatewayOutcome.Failure(
                GatewayFailureCategory.MALFORMED_RESPONSE,
                malformedResponseCategory = GatewayMalformedResponseCategory.RAW_CREATE_CONTACT_ID,
                protonResponseCode = 2001,
            ).protonResponseCode,
        )
        assertTrue(
            runCatching {
                GatewayOutcome.Failure(
                    GatewayFailureCategory.VALIDATION_REJECTED,
                    protonResponseCode = 10_000,
                )
            }.isFailure,
        )
    }

    @Test
    fun contactHydrationDetailIsClosedAndRestrictedToHydrationFailureClasses() {
        GatewayContactHydrationCategory.entries.forEach { detail ->
            val failure = GatewayOutcome.Failure(
                GatewayFailureCategory.MALFORMED_RESPONSE,
                contactHydrationCategory = detail,
            )
            assertEquals(detail, failure.contactHydrationCategory)
            assertTrue(failure.toString().contains("contactHydrationCategory=${detail.name}"))
        }
        assertTrue(
            runCatching {
                GatewayOutcome.Failure(
                    GatewayFailureCategory.UNKNOWN,
                    contactHydrationCategory = GatewayContactHydrationCategory.VCARD_PARSE,
                )
            }.isFailure,
        )
    }

    @Test
    fun opaqueValuesRejectBlankAndOversizedInput() {
        assertTrue(runCatching { RemoteContactId(" ") }.isFailure)
        assertTrue(runCatching { InventoryCursor("x".repeat(4_097)) }.isFailure)
        assertEquals(RemoteContactId("same"), RemoteContactId("same"))
        assertFalse(RemoteContactId("private-id").toString().contains("private-id"))
    }

    @Test
    fun operationSecretTakesOwnershipAndClearsAfterOneSuccessfulConsumption() {
        val callerBuffer = "fixture-secret".toCharArray()
        val secret = OperationSecret.takeAndClear(callerBuffer)
        lateinit var consumedBuffer: CharArray

        assertTrue(callerBuffer.all { it == '\u0000' })
        val result = secret.consume {
            consumedBuffer = it
            String(it)
        }

        assertEquals("fixture-secret", result)
        assertTrue(secret.isClosed)
        assertTrue(consumedBuffer.all { it == '\u0000' })
        assertTrue(runCatching { secret.consume(::String) }.isFailure)
        assertEquals("OperationSecret(REDACTED)", secret.toString())
    }

    @Test
    fun operationSecretClearsOwnedBufferWhenConsumerThrows() {
        val callerBuffer = "exception-fixture".toCharArray()
        val secret = OperationSecret.takeAndClear(callerBuffer)
        lateinit var consumedBuffer: CharArray

        val result = runCatching {
            secret.consume<Unit> {
                consumedBuffer = it
                error("synthetic consumer failure")
            }
        }

        assertTrue(result.isFailure)
        assertTrue(callerBuffer.all { it == '\u0000' })
        assertTrue(consumedBuffer.all { it == '\u0000' })
        assertTrue(secret.isClosed)
        assertTrue(runCatching { secret.consume(::String) }.isFailure)
    }

    @Test
    fun rejectedSecretInputIsStillCleared() {
        val tooLarge = CharArray(16_385) { 'x' }

        assertTrue(runCatching { OperationSecret.takeAndClear(tooLarge) }.isFailure)
        assertTrue(tooLarge.all { it == '\u0000' })
    }

    @Test
    fun secondFactorFactoryRejectsEmptyAndConvertsSecurityKeyOnly() {
        assertTrue(
            runCatching {
                AuthenticationState.SecondFactorRequired.fromOfferedMethods(emptySet())
            }.isFailure,
        )
        assertSame(
            AuthenticationState.SecurityKeyOnlyUnsupported,
            AuthenticationState.SecondFactorRequired.fromOfferedMethods(setOf(SecondFactorMethod.SECURITY_KEY)),
        )

        val codeState = AuthenticationState.SecondFactorRequired.fromOfferedMethods(
            setOf(SecondFactorMethod.CODE, SecondFactorMethod.SECURITY_KEY),
        )
        assertTrue(codeState is AuthenticationState.SecondFactorRequired)
        assertEquals(
            setOf(SecondFactorMethod.CODE, SecondFactorMethod.SECURITY_KEY),
            (codeState as AuthenticationState.SecondFactorRequired).methods,
        )
    }

    @Test
    fun inventoryMetadataRejectsUnsafeBoundsAndDuplicateReferences() {
        assertTrue(runCatching { metadata("negative-size", sizeBytes = -1) }.isFailure)
        assertTrue(
            runCatching {
                metadata("large-size", sizeBytes = ContactInventoryMetadata.MAX_CONTACT_SIZE_BYTES + 1)
            }.isFailure,
        )
        assertTrue(runCatching { metadata("negative-time", modifiedAtEpochSeconds = -1) }.isFailure)
        assertTrue(
            runCatching {
                metadata(
                    "large-time",
                    modifiedAtEpochSeconds = ContactInventoryMetadata.MAX_EPOCH_SECONDS + 1,
                )
            }.isFailure,
        )
        val duplicateEmail = RemoteEmailId("duplicate-email")
        assertTrue(
            runCatching {
                metadata("duplicate", emailIds = listOf(duplicateEmail, duplicateEmail))
            }.isFailure,
        )
        val duplicateGroup = RemoteGroupId("duplicate-group")
        assertTrue(
            runCatching {
                metadata("duplicate-group", groupIds = listOf(duplicateGroup, duplicateGroup))
            }.isFailure,
        )
    }

    @Test
    fun inventoryPageDerivesTerminalStateAndRejectsLocalInconsistency() {
        val cursor = InventoryCursor("next")
        val nonTerminal = ContactInventoryPage(listOf(metadata("one")), null, cursor, 2)
        val terminal = ContactInventoryPage(listOf(metadata("two")), cursor, null, 2)

        assertFalse(nonTerminal.isComplete)
        assertTrue(terminal.isComplete)
        assertTrue(runCatching { ContactInventoryPage(emptyList(), null, null, -1) }.isFailure)
        assertTrue(runCatching { ContactInventoryPage(listOf(metadata("one")), null, null, 0) }.isFailure)
        assertTrue(
            runCatching {
                ContactInventoryPage(listOf(metadata("same"), metadata("same")), null, null, 2)
            }.isFailure,
        )
    }

    @Test
    fun completeInventoryProvesContinuityTotalAndGlobalUniqueness() {
        val cursor = InventoryCursor("page-two")
        val pages = listOf(
            ContactInventoryPage(listOf(metadata("one")), null, cursor, 2),
            ContactInventoryPage(listOf(metadata("two")), cursor, null, 2),
        )

        val complete = ValidatedCompleteInventory.fromPages(pages)

        assertEquals(2, complete.totalCount)
        assertEquals(listOf(RemoteContactId("one"), RemoteContactId("two")), complete.contacts.map { it.id })
        assertTrue(
            runCatching {
                ValidatedCompleteInventory.fromPages(
                    listOf(ContactInventoryPage(listOf(metadata("one")), null, null, 2)),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                ValidatedCompleteInventory.fromPages(
                    listOf(
                        ContactInventoryPage(listOf(metadata("one")), null, cursor, 2),
                        ContactInventoryPage(
                            listOf(metadata("two")),
                            InventoryCursor("wrong-cursor"),
                            null,
                            2,
                        ),
                    ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                ValidatedCompleteInventory.fromPages(
                    listOf(
                        ContactInventoryPage(listOf(metadata("same")), null, cursor, 2),
                        ContactInventoryPage(listOf(metadata("same")), cursor, null, 2),
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun groupSuccessAndMutationReceiptsAreStructurallyTyped() {
        val available = AvailableContactGroups(
            listOf(RemoteContactGroup(RemoteGroupId("group-one"), "Group", "#6D4AFF")),
        )
        assertFalse(available.javaClass.declaredFields.any { it.name.contains("capability", ignoreCase = true) })
        assertTrue(
            runCatching {
                AvailableContactGroups(
                    listOf(
                        RemoteContactGroup(RemoteGroupId("same"), "One", null),
                        RemoteContactGroup(RemoteGroupId("same"), "Two", null),
                    ),
                )
            }.isFailure,
        )

        val methods = ProtonContactGroupGateway::class.java.declaredMethods.associateBy { it.name }
        assertTrue(methods.getValue("create").genericParameterTypes.last().typeName.contains("RemoteContactGroup"))
        assertTrue(methods.getValue("update").genericParameterTypes.last().typeName.contains("RemoteContactGroup"))
        assertTrue(methods.getValue("delete").genericParameterTypes.last().typeName.contains("kotlin.Unit"))
    }

    @Test
    fun emailLabelMutationsDefensivelyCopyAndBoundTheirBatch() {
        val source = mutableListOf(RemoteEmailId("email-one"), RemoteEmailId("email-two"))
        val assign = EmailLabelMutation.Assign(RemoteGroupId("group"), source)
        val remove = EmailLabelMutation.Remove(RemoteGroupId("group"), source)

        source.clear()

        assertEquals(listOf(RemoteEmailId("email-one"), RemoteEmailId("email-two")), assign.emailIds)
        assertEquals(listOf(RemoteEmailId("email-one"), RemoteEmailId("email-two")), remove.emailIds)
        assertTrue(runCatching { EmailLabelMutation.Assign(RemoteGroupId("group"), emptyList()) }.isFailure)
        assertTrue(
            runCatching {
                EmailLabelMutation.Remove(
                    RemoteGroupId("group"),
                    List(EmailLabelMutation.MAX_EMAIL_IDS_PER_MUTATION + 1) { index ->
                        RemoteEmailId("email-$index")
                    },
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                val duplicate = RemoteEmailId("duplicate")
                EmailLabelMutation.Assign(RemoteGroupId("group"), listOf(duplicate, duplicate))
            }.isFailure,
        )
        assertFalse(assign.toString().contains("email-one"))
        assertFalse(remove.toString().contains("email-two"))
    }

    @Test
    fun identifiersAndNestedPayloadsAreRedactedWhenStringified() {
        val canary = "fixture-private-value"
        val account = AccountScope("account-$canary")
        val contactId = RemoteContactId("contact-$canary")
        val version = RemoteVersion("version-$canary")
        val contact = CanonicalContact(
            accountId = "account-$canary",
            id = "local-$canary",
            displayName = canary,
        )
        val inventory = metadata("inventory-$canary")
        val page = ContactInventoryPage(listOf(inventory), null, null, 1)
        val complete = ValidatedCompleteInventory.fromPages(listOf(page))

        val representativeValues = listOf(
            account,
            contactId,
            version,
            InventoryCursor("cursor-$canary"),
            VerifiedContactCard(contactId, version, contact),
            ContactMutation.Create(contact),
            ContactMutation.Update(contactId, version, contact),
            ContactMutation.Delete(contactId, version),
            ContactMutationReceipt(contactId, version),
            GatewayOutcome.Success(contact),
            GatewayOutcome.Failure(GatewayFailureCategory.UNKNOWN),
            inventory,
            page,
            complete,
            RemoteContactGroup(RemoteGroupId("group-$canary"), canary, canary),
            AvailableContactGroups(listOf(RemoteContactGroup(RemoteGroupId("available-$canary"), canary, null))),
            ContactGroupMutation.Create(canary, canary),
            ContactGroupMutation.Update(RemoteGroupId("group-$canary"), canary, canary),
            ContactGroupMutation.Delete(RemoteGroupId("group-$canary")),
            EmailLabelMutation.Assign(
                RemoteGroupId("group-$canary"),
                listOf(RemoteEmailId("email-$canary")),
            ),
            EmailLabelMutation.Remove(
                RemoteGroupId("group-$canary"),
                listOf(RemoteEmailId("email-$canary")),
            ),
        )

        representativeValues.forEach { value ->
            assertFalse("${value::class.simpleName} leaked its payload", value.toString().contains(canary))
        }
    }

    @Test
    fun everyRequiredRemoteCapabilityHasItsOwnInterface() {
        val boundaries: List<KClass<*>> = listOf(
            ProtonAuthenticationGateway::class,
            ProtonSessionGateway::class,
            ProtonContactInventoryGateway::class,
            ProtonVerifiedContactCardGateway::class,
            ProtonContactMutationGateway::class,
            ProtonContactGroupGateway::class,
            ProtonContactEmailLabelGateway::class,
        )

        assertEquals(7, boundaries.size)
        boundaries.forEach { boundary ->
            assertTrue("${boundary.simpleName} must remain replaceable", boundary.java.isInterface)
            assertTrue("${boundary.simpleName} must remain narrow", boundary.java.declaredMethods.size <= 5)
        }
    }

    @Test
    fun noBoundaryMethodReturnsRawThrowableOrText() {
        val boundaries = listOf(
            ProtonAuthenticationGateway::class.java,
            ProtonSessionGateway::class.java,
            ProtonContactInventoryGateway::class.java,
            ProtonVerifiedContactCardGateway::class.java,
            ProtonContactMutationGateway::class.java,
            ProtonContactGroupGateway::class.java,
            ProtonContactEmailLabelGateway::class.java,
        )

        boundaries.flatMap { it.declaredMethods.asList() }.forEach { method ->
            assertFalse(Throwable::class.java.isAssignableFrom(method.returnType))
            assertFalse(method.returnType == String::class.java)
        }
    }

    private fun metadata(
        id: String,
        sizeBytes: Long = 1,
        modifiedAtEpochSeconds: Long = 1,
        emailIds: List<RemoteEmailId> = emptyList(),
        groupIds: List<RemoteGroupId> = emptyList(),
    ): ContactInventoryMetadata = ContactInventoryMetadata(
        id = RemoteContactId(id),
        displayName = null,
        version = RemoteVersion("version-$id"),
        sizeBytes = sizeBytes,
        modifiedAtEpochSeconds = modifiedAtEpochSeconds,
        emailIds = emailIds,
        groupIds = groupIds,
        versionProvenance = ContactInventoryVersionProvenance.REMOTE_SERVER,
        coverage = ContactInventoryCoverage.AUTHORITATIVE_REMOTE_REVISION,
    )
}
