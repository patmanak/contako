package com.patmanak.contako.data.sync

import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.proton.*
import com.patmanak.contako.domain.model.CanonicalContact
import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Controlled remote/local peers: these cases qualify orchestration, not Proton or an OEM. */
class EventDrivenRemoteSyncTest {
    @Test fun unchangedPassDoesNotEnumerateOrReadCardsAndSharesOneEventInterval() = runTest {
        val f = Fixture()
        val original = f.local.toMap()
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(listOf("cursor-old"), f.eventReads)
        assertEquals(0, f.inventoryReads)
        assertEquals(0, f.groupListReads)
        assertTrue(f.metadataReads.isEmpty())
        assertTrue(f.cardReads.isEmpty())
        assertTrue(f.ownerReads.isEmpty())
        assertTrue(f.targetedGroupReads.isEmpty())
        assertEquals(original, f.local)
        assertEquals("cursor-next", f.header().eventCursor)
        assertEquals(START, f.header().lastFullScanAtEpochMillis)
    }

    @Test fun privateOnlyEditHydratesOneContactEvenWhenPublicFingerprintIsUnchanged() = runTest {
        val f = Fixture()
        val untouched = f.local.getValue(B)
        f.cards[A] = contact(A, "Private note: Straße, Maël, 東京.")
        f.delta = delta(changed = setOf(A))
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(listOf(A), f.metadataReads)
        assertEquals(listOf(A), f.cardReads)
        assertEquals(f.cards.getValue(A), f.local[A])
        assertEquals(untouched, f.local[B])
        assertEquals(0, f.inventoryReads)
    }

    @Test fun recreatedIdenticalEmailOccurrencesRetainDistinctRemoteGroupEdges() = runTest {
        val old = metadata(A, listOf("old-one" to listOf(G), "old-two" to emptyList()))
        val f = Fixture(listOf(old, metadata(B)))
        f.metadata[A] = metadata(A, listOf("new-one" to listOf(G), "new-two" to listOf(H)))
        f.delta = delta(changed = setOf(A), changedEmails = setOf(email("new-one"), email("new-two")),
            deletedEmails = setOf(email("old-one"), email("old-two")))
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(listOf(A), f.metadataReads)
        assertTrue(f.ownerReads.isEmpty()) // New identities were resolved from the targeted contact.
        assertEquals(listOf(A), f.cardReads)
        val edges = f.header().entries.single { it.id == A }.emailGroupMemberships
        assertEquals(setOf(email("new-one"), email("new-two")), edges.map { it.emailId }.toSet())
        assertEquals(listOf(G), edges.single { it.emailId == email("new-one") }.groupIds)
        assertEquals(listOf(H), edges.single { it.emailId == email("new-two") }.groupIds)
        assertEquals(0, f.inventoryReads)
    }

    @Test fun unknownChangedEmailResolvesItsOpaqueOwnerWithoutFetchingTheDirectory() = runTest {
        val f = Fixture()
        f.metadata[A] = metadata(A, listOf("new-email" to listOf(G)))
        f.owners[email("new-email")] = A
        f.delta = delta(changedEmails = setOf(email("new-email")))
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(listOf(email("new-email")), f.ownerReads)
        assertEquals(listOf(A), f.metadataReads)
        assertEquals(listOf(A), f.cardReads)
        assertEquals(0, f.inventoryReads)
    }

    @Test fun contactFallbackAfterGroupDeltaDoesNotPostponeTheFullCatalogSafetyScan() = runTest {
        val f = Fixture()
        f.now = START + 5_000
        f.cards[A] = contact(A, "Private change behind an unresolved email identity")
        f.delta = delta(changedEmails = setOf(email("unresolvable-email")))
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(1, f.inventoryReads)
        assertEquals(0, f.groupListReads)
        assertEquals(setOf(A, B), f.cardReads.toSet())
        assertEquals(f.cards.getValue(A), f.local[A])
        assertEquals(START, f.header().lastFullScanAtEpochMillis)
    }

    @Test fun createdThenEditedContactWithAnUnknownDeletedEmailReadsOnlyItsOwnCard() = runTest {
        val f = Fixture((0 until 300).map { metadata(RemoteContactId("existing-$it")) })
        val untouched = f.local.toMap()
        f.metadata[A] = metadata(A, listOf("replacement-email" to emptyList()))
        f.cards[A] = contact(A, "Private note on a newly created then edited card")
        f.delta = delta(changed = setOf(A), changedEmails = setOf(email("replacement-email")),
            deletedEmails = setOf(email("intermediate-email")))
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(1, f.inventoryReads)
        assertEquals(listOf(A), f.cardReads)
        assertEquals(f.cards.getValue(A), f.local[A])
        untouched.forEach { (id, value) -> assertEquals(value, f.local[id]) }
        assertEquals(301, f.header().entries.size)
        assertEquals("cursor-next", f.header().eventCursor)
    }

    @Test fun createdThenDeletedContactDoesNotHydrateTheExistingDirectory() = runTest {
        val f = Fixture((0 until 300).map { metadata(RemoteContactId("existing-$it")) })
        val untouched = f.local.toMap()
        f.delta = delta(deleted = setOf(A), deletedEmails = setOf(email("intermediate-email")))
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(1, f.inventoryReads)
        assertTrue(f.cardReads.isEmpty())
        assertEquals(listOf(A), f.existenceReads)
        assertEquals(setOf(A), f.deletions) // Explicit tombstones remain subject to absence proof.
        assertEquals(untouched, f.local)
        assertEquals(300, f.header().entries.size)
        assertEquals("cursor-next", f.header().eventCursor)
    }

    @Test fun lightFallbackRetainsExplicitDeletionOfALocalCreationAcknowledgedAfterCheckpoint() = runTest {
        val f = Fixture(listOf(metadata(B)))
        val untouched = f.local.getValue(B)
        // Simulate an upload acknowledgement after the previous pass checkpoint: bound locally,
        // never in its inventory. The remote contact has since been deleted in the event interval.
        f.local[A] = contact(A, "Locally acknowledged creation outside inventory checkpoint")
        f.delta = delta(deleted = setOf(A), deletedEmails = setOf(email("uncheckpointed-email")))
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(1, f.inventoryReads)
        assertEquals(listOf(A), f.existenceReads)
        assertEquals(setOf(A), f.deletions)
        assertFalse(f.local.containsKey(A))
        assertEquals(untouched, f.local[B])
        assertTrue(f.cardReads.isEmpty())
        assertEquals("cursor-next", f.header().eventCursor)
    }

    @Test fun lightFallbackCannotAcknowledgeADeletionContradictedByTheCurrentInventory() = runTest {
        val f = Fixture()
        val untouched = f.local.toMap()
        f.delta = delta(deleted = setOf(A), deletedEmails = setOf(email("intermediate-email")))
        assertEquals(RemoteContactStageResult.StalePlan, f.pass())
        assertEquals(1, f.inventoryReads)
        assertEquals(untouched, f.local)
        assertTrue(f.deletions.isEmpty())
        assertEquals("cursor-old", f.header().eventCursor)
    }

    @Test fun deletedUnknownEmailFallbackStillHydratesPrivateChangesAndConfirmsKnownDeletions() = runTest {
        val f = Fixture()
        f.cards[A] = contact(A, "Unchanged public fingerprint; changed private note")
        f.metadata.remove(B)
        f.cards.remove(B)
        f.delta = delta(changed = setOf(A), deleted = setOf(B),
            deletedEmails = setOf(email("intermediate-email")))
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(listOf(A), f.cardReads)
        assertEquals(listOf(B), f.existenceReads)
        assertEquals(setOf(B), f.deletions)
        assertEquals(f.cards.getValue(A), f.local[A])
        assertEquals("cursor-next", f.header().eventCursor)
    }

    @Test fun deletedUnknownEmailCannotAcknowledgeAFailedLightInventory() = runTest {
        val f = Fixture()
        val untouched = f.local.toMap()
        f.delta = delta(deletedEmails = setOf(email("intermediate-email")))
        f.inventoryFailure = GatewayFailureCategory.TIMEOUT
        assertTrue(f.pass() is RemoteContactStageResult.RetryWaiting)
        assertTrue(f.cardReads.isEmpty())
        assertEquals(untouched, f.local)
        assertEquals("cursor-old", f.header().eventCursor)
    }

    @Test fun incompleteTargetedGroupReadRequiresACompleteCatalogBeforeCursorAcknowledgement() = runTest {
        val f = Fixture()
        f.delta = delta(changedGroups = setOf(G))
        f.targetedGroupFailure = GatewayFailureCategory.MALFORMED_RESPONSE
        f.remoteGroups[G] = RemoteContactGroup(G, "Verified through complete catalog", null)
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(1, f.groupListReads)
        assertEquals(1, f.inventoryReads)
        assertEquals("Verified through complete catalog", f.localGroups.getValue(G).name)
        assertEquals("cursor-next", f.header().eventCursor)
    }

    @Test fun groupDeletionWithoutContactEventsReadsOnlyPreviouslyKnownMembers() = runTest {
        val f = Fixture(listOf(metadata(A, listOf("a-email" to listOf(G))), metadata(B)))
        val untouched = f.local.getValue(B)
        f.delta = delta(deletedGroups = setOf(G))
        f.remoteGroups.remove(G)
        f.metadata[A] = metadata(A, listOf("a-email" to emptyList()))
        f.cards[A] = contact(A, "Preserved private note")
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(setOf(G), f.deletedGroups)
        assertEquals(listOf(A), f.metadataReads)
        assertEquals(listOf(A), f.cardReads)
        assertTrue(f.header().entries.single { it.id == A }.groupIds.isEmpty())
        assertEquals(untouched, f.local[B])
        assertEquals(0, f.inventoryReads)
    }

    @Test fun knownGroupMetadataEditUsesTargetedReadAndPreservesOtherGroups() = runTest {
        val f = Fixture(listOf(metadata(A, listOf("member" to listOf(G))), metadata(B)))
        f.remoteGroups[G] = RemoteContactGroup(G, "Renamed", "#415df0")
        f.delta = delta(changedGroups = setOf(G))
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(listOf(setOf(G)), f.targetedGroupReads)
        assertEquals("Renamed", f.localGroups.getValue(G).name)
        assertEquals("Second", f.localGroups.getValue(H).name)
        assertEquals(0, f.groupListReads)
        assertEquals(0, f.inventoryReads)
        assertTrue(f.metadataReads.isEmpty())
        assertTrue(f.cardReads.isEmpty())
    }

    @Test fun eventDeltaCatalogSuccessDoesNotGrantOrEraseListPermissionEvidence() = runTest {
        val f = Fixture()
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(ContactGroupCapabilityAvailability.UNKNOWN,
            f.groupCapabilities.capabilities().availability(ContactGroupCapability.LIST))
        f.groupFailure = GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED
        assertTrue(f.groupCapabilities.list(ACCOUNT) is GatewayOutcome.Failure)
        f.clearReads()
        val retained = f.localGroups.toMap()
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(0, f.groupListReads)
        assertEquals(retained, f.localGroups)
        assertEquals(ContactGroupCapabilityAvailability.DENIED,
            f.groupCapabilities.capabilities().availability(ContactGroupCapability.LIST))
    }

    @Test fun unknownGroupCreationReconcilesContactsOnceRatherThanAssumingItHasNoMembers() = runTest {
        val f = Fixture(listOf(metadata(A, listOf("member" to emptyList())), metadata(B)))
        val added = RemoteGroupId("created-group")
        val initialContact = contact(A).copy(values = contact(A).values + ContactValue("email-value",
            ContactValueKind.EMAIL, "member@example.test", order = 0,
            metadata = mapOf(PROTON_EMAIL_ID_KEY to "member")))
        f.local[A] = initialContact
        f.cards[A] = initialContact.copy(values = initialContact.values.map { value ->
            if (value.kind == ContactValueKind.EMAIL) value.copy(metadata = value.metadata +
                (PROTON_GROUP_IDS_KEY to added.value)) else value
        })
        val untouched = f.local.getValue(B)
        f.remoteGroups[added] = RemoteContactGroup(added, "New team", null)
        f.metadata[A] = metadata(A, listOf("member" to listOf(added)))
        f.delta = delta(changedGroups = setOf(added))
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(listOf(setOf(added)), f.targetedGroupReads)
        assertEquals(1, f.inventoryReads)
        assertEquals(listOf(added), f.header().entries.single { it.id == A }.groupIds)
        assertEquals(listOf(A), f.cardReads)
        assertEquals(added.value, f.local.getValue(A).valuesOf(ContactValueKind.EMAIL).single().metadata[PROTON_GROUP_IDS_KEY])
        assertEquals(untouched, f.local[B])
        assertEquals("cursor-next", f.header().eventCursor)
    }

    @Test fun deniedGroupReadAcknowledgesDurableContactsWithAnExplicitCatalogRescanObligation() = runTest {
        val f = Fixture()
        f.delta = delta(changed = setOf(A), changedGroups = setOf(G))
        f.groupFailure = GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED
        assertTrue(f.pass(allowUnavailableGroups = true) is RemoteContactStageResult.Success)
        assertEquals("cursor-next", f.header().eventCursor)
        assertEquals(EventDrivenRemoteReadSession.GROUP_RESCAN_REQUIRED, f.header().eventStrategyVersion)
        f.groupFailure = null
        f.clearReads()
        f.delta = delta()
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        // The unverified catalog requires a complete LIGHT acquisition at the acknowledged
        // position. It must not reset the cursor and wake every ignored complete card.
        assertEquals(listOf("cursor-next"), f.eventReads)
        assertEquals(1, f.groupListReads)
        assertEquals(1, f.inventoryReads)
        assertTrue(f.cardReads.isEmpty())
        assertEquals(1, f.header().eventStrategyVersion)
    }

    @Test fun obligationOutsideCheckpointMustBeLookedUpRatherThanInferredDeleted() = runTest {
        val f = Fixture(listOf(metadata(B)))
        f.pending += A
        f.metadata[A] = metadata(A)
        f.cards[A] = contact(A, "Retained deferred card")
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(listOf(A), f.metadataReads)
        assertEquals(listOf(A), f.cardReads)
        assertTrue(f.existenceReads.isEmpty())
        assertTrue(f.deletions.isEmpty())
        assertEquals(f.cards.getValue(A), f.local[A])
        assertTrue(f.pending.isEmpty())
    }

    @Test fun unchangedIgnoredObligationRetainsItsDispositionWithoutAnyCardOrMetadataRead() = runTest {
        val f = Fixture()
        f.pending += A
        f.dispositions[A] = RemoteHydrationReadDisposition.IGNORED
        assertEquals(RemoteContactStageResult.Partial(1, ignoredCount = 1), f.pass())
        assertTrue(f.metadataReads.isEmpty())
        assertTrue(f.cardReads.isEmpty())
        assertEquals(setOf(A), f.pending)
        assertEquals(false, f.sourceChanges[A])
        assertEquals("cursor-next", f.header().eventCursor)
    }

    @Test fun freshEventReopensOnlyItsOwnIgnoredHydrationAndKeepsOtherBackoff() = runTest {
        val f = Fixture()
        f.pending += setOf(A, B)
        f.dispositions[A] = RemoteHydrationReadDisposition.IGNORED
        f.dispositions[B] = RemoteHydrationReadDisposition.RETRY_WAITING
        f.delta = delta(changed = setOf(A))
        assertEquals(RemoteContactStageResult.Partial(1, retryWaitingCount = 1), f.pass())
        assertEquals(listOf(A), f.metadataReads)
        assertEquals(listOf(A), f.cardReads)
        assertEquals(setOf(B), f.pending)
        assertEquals(true, f.sourceChanges[A])
        assertEquals(false, f.sourceChanges[B])
    }

    @Test fun missingDeferredContactNeedsStructuredAbsenceBeforeRemoval() = runTest {
        val f = Fixture(listOf(metadata(B)))
        f.pending += A
        f.presenceFailure = GatewayFailureCategory.NOT_FOUND
        assertEquals(RemoteContactStageResult.ActionRequired, f.pass())
        assertEquals(listOf(A), f.metadataReads)
        assertEquals(listOf(A), f.existenceReads)
        assertTrue(f.deletions.isEmpty())
        assertEquals("cursor-old", f.header().eventCursor)
        f.presenceFailure = null
        f.clearReads()
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(setOf(A), f.deletions)
        assertTrue(f.pending.isEmpty())
    }

    @Test fun contactTombstoneDoesNotAuthorizeDeletionWhileTheContactStillExists() = runTest {
        val f = Fixture()
        f.delta = delta(deleted = setOf(A))
        assertEquals(RemoteContactStageResult.StalePlan, f.pass())
        assertEquals(listOf(A), f.existenceReads)
        assertTrue(f.deletions.isEmpty())
        assertEquals("cursor-old", f.header().eventCursor)
    }

    @Test fun confirmedContactDeletionPreservesTheOtherContactAndAdvancesOnlyAfterCommit() = runTest {
        val f = Fixture()
        val untouched = f.local.getValue(B)
        f.delta = delta(deleted = setOf(A), deletedEmails = setOf(email("a-email")))
        f.metadata.remove(A)
        f.cards.remove(A)
        // A known opaque email can still be located in the previous checkpoint.
        f.installCheckpoint(listOf(metadata(A, listOf("a-email" to emptyList())), metadata(B)))
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(setOf(A), f.deletions)
        assertFalse(f.local.containsKey(A))
        assertEquals(untouched, f.local[B])
        assertEquals("cursor-next", f.header().eventCursor)
        assertEquals(listOf(A), f.existenceReads)
    }

    @Test fun canonicalFailureKeepsCursorAndTheSameEventCanBeReplayed() = runTest {
        val f = Fixture()
        f.delta = delta(changed = setOf(A))
        f.failCanonical = true
        assertEquals(RemoteContactStageResult.LocalPersistenceFailure, f.pass())
        assertEquals("cursor-old", f.header().eventCursor)
        f.failCanonical = false
        f.clearReads()
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(listOf("cursor-old"), f.eventReads)
        assertEquals(listOf(A), f.cardReads)
    }

    @Test fun checkpointFailureAfterCanonicalCommitKeepsOldCursorUntilReplaySucceeds() = runTest {
        val f = Fixture()
        f.cards[A] = contact(A, "Already durable before failed checkpoint")
        f.delta = delta(changed = setOf(A))
        f.failCheckpoint = true
        assertEquals(RemoteContactStageResult.LocalPersistenceFailure, f.pass())
        assertEquals(f.cards.getValue(A), f.local[A])
        assertEquals("cursor-old", f.header().eventCursor)
        f.failCheckpoint = false
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals("cursor-next", f.header().eventCursor)
    }

    @Test fun cancelledHydrationDoesNotAcknowledgeTheInterval() = runTest {
        val f = Fixture()
        f.delta = delta(changed = setOf(A))
        f.cancelAfterCard = true
        assertEquals(RemoteContactStageResult.Cancelled, f.pass())
        assertEquals("cursor-old", f.header().eventCursor)
        assertEquals(0, f.canonicalCommits)
    }

    @Test fun cancellationBeforeGroupAcquisitionDoesNotReadEvents() = runTest {
        val f = Fixture()
        assertEquals(RemoteGroupStageResult.Cancelled, f.groupStage.run(ACCOUNT) { true })
        assertTrue(f.eventReads.isEmpty())
        assertEquals("cursor-old", f.header().eventCursor)
    }

    @Test fun checkpointRaceUsesTheCapturedGenerationAndCannotOverwriteTheWinner() = runTest {
        val f = Fixture()
        f.delta = delta(changed = setOf(A))
        f.raceOnCanonical = true
        assertEquals(RemoteContactStageResult.StalePlan, f.pass())
        assertEquals("winner-cursor", f.header().eventCursor)
        assertEquals(8L, f.checkpoint!!.generation)
    }

    @Test fun oldStrategyAndExplicitRepairCaptureLatestBeforeFullHydration() = runTest {
        for (mode in listOf("old", "missing-cursor", "force")) {
            val f = Fixture()
            when (mode) {
                "old" -> f.installCheckpoint(f.metadata.values.toList(), strategy = 0)
                "missing-cursor" -> f.checkpoint = VersionedContactInventoryCheckpoint(7,
                    ContactInventoryCheckpoint(f.header().entries, null, 1, START))
            }
            assertTrue(mode, f.pass(forceFull = mode == "force") is RemoteContactStageResult.Success)
            assertEquals(mode, listOf<String?>(null), f.eventReads)
            assertEquals(mode, 1, f.inventoryReads)
            assertEquals(mode, 1, f.groupListReads)
            assertTrue(mode, f.trace.indexOf("events:latest") < f.trace.indexOf("group-list"))
            assertTrue(mode, f.trace.indexOf("events:latest") < f.trace.indexOf("inventory"))
            assertEquals(mode, setOf(A, B), f.cardReads.toSet())
            assertEquals(mode, f.now, f.header().lastFullScanAtEpochMillis)
        }
    }

    @Test fun dueScanClockRollbackAndUnknownScanTimeKeepCursorAndAvoidUnchangedCompleteCards() = runTest {
        for (mode in listOf("due", "rollback", "unknown-time")) {
            val f = Fixture()
            when (mode) {
                "due" -> f.now = START + EventDrivenRemoteReadSession.FULL_SCAN_INTERVAL_MILLIS
                "rollback" -> f.now = START - 1
                "unknown-time" -> f.installCheckpoint(f.metadata.values.toList(), lastFull = null)
            }
            assertTrue(mode, f.pass() is RemoteContactStageResult.Success)
            assertEquals(mode, listOf("cursor-old"), f.eventReads)
            assertEquals(mode, 1, f.inventoryReads)
            assertEquals(mode, 1, f.groupListReads)
            assertTrue(mode, f.cardReads.isEmpty())
            assertTrue(mode, f.metadataReads.isEmpty())
            assertEquals(mode, f.now, f.header().lastFullScanAtEpochMillis)
        }
    }

    @Test fun dueLightReconciliationDoesNotReopenAnUnchangedIgnoredCard() = runTest {
        val f = Fixture()
        f.pending += A
        f.dispositions[A] = RemoteHydrationReadDisposition.IGNORED
        f.now = START + EventDrivenRemoteReadSession.FULL_SCAN_INTERVAL_MILLIS
        assertEquals(RemoteContactStageResult.Partial(1, ignoredCount = 1), f.pass())
        assertEquals(listOf("cursor-old"), f.eventReads)
        assertEquals(1, f.inventoryReads)
        assertEquals(1, f.groupListReads)
        assertTrue(f.cardReads.isEmpty())
        assertTrue(f.metadataReads.isEmpty())
        assertEquals(false, f.sourceChanges[A])
        assertEquals(setOf(A), f.pending)
        assertEquals(f.now, f.header().lastFullScanAtEpochMillis)
    }

    @Test fun repeatedCatalogDenialKeepsIgnoredCardsAsleepUntilCatalogRecoveryRestoresDeltaMode() = runTest {
        val f = Fixture()
        f.pending += A
        f.dispositions[A] = RemoteHydrationReadDisposition.IGNORED
        f.delta = delta(changedGroups = setOf(G))
        f.groupFailure = GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED
        assertEquals(RemoteContactStageResult.Partial(1, ignoredCount = 1), f.pass(allowUnavailableGroups = true))
        assertEquals("cursor-next", f.header().eventCursor)
        assertEquals(EventDrivenRemoteReadSession.GROUP_RESCAN_REQUIRED, f.header().eventStrategyVersion)
        assertTrue(f.cardReads.isEmpty())
        f.clearReads()
        f.delta = delta()
        assertEquals(RemoteContactStageResult.Partial(1, ignoredCount = 1), f.pass(allowUnavailableGroups = true))
        assertEquals(listOf("cursor-next"), f.eventReads)
        assertEquals(1, f.groupListReads)
        assertEquals(1, f.inventoryReads)
        assertTrue(f.cardReads.isEmpty())
        assertEquals(false, f.sourceChanges[A])
        assertEquals(EventDrivenRemoteReadSession.GROUP_RESCAN_REQUIRED, f.header().eventStrategyVersion)
        f.groupFailure = null
        f.clearReads()
        assertEquals(RemoteContactStageResult.Partial(1, ignoredCount = 1), f.pass())
        assertEquals(1, f.groupListReads)
        assertEquals(1, f.inventoryReads)
        assertTrue(f.cardReads.isEmpty())
        assertEquals(EventDrivenRemoteReadSession.STRATEGY_VERSION, f.header().eventStrategyVersion)
        f.clearReads()
        assertEquals(RemoteContactStageResult.Partial(1, ignoredCount = 1), f.pass())
        assertEquals(0, f.groupListReads)
        assertEquals(0, f.inventoryReads)
        assertTrue(f.cardReads.isEmpty())
        assertEquals(setOf(A), f.pending)
    }

    @Test fun refreshAndInvalidCursorRecoverWithoutConsumingAFailedInterval() = runTest {
        val refresh = Fixture()
        refresh.delta = ContactEventsDelta("cursor-next", emptySet(), true)
        assertTrue(refresh.pass() is RemoteContactStageResult.Success)
        assertEquals(listOf("cursor-old"), refresh.eventReads)
        assertEquals(1, refresh.inventoryReads)
        assertEquals(1, refresh.groupListReads)
        assertEquals(setOf(A, B), refresh.cardReads.toSet())
        assertEquals("cursor-next", refresh.header().eventCursor)
        val expired = Fixture()
        expired.expiredCursor = true
        assertTrue(expired.pass() is RemoteContactStageResult.Success)
        assertEquals(listOf("cursor-old", null), expired.eventReads)
        assertEquals(1, expired.inventoryReads)
        assertEquals(1, expired.groupListReads)
        assertEquals("cursor-next", expired.header().eventCursor)
    }

    @Test fun explicitRepairBaselineIsCapturedBeforeGroupsAndDoesNotLeakIntoTheNextPass() = runTest {
        val f = Fixture()
        f.session.reset()
        assertTrue(f.groupStage.run(ACCOUNT, forceFullBaseline = true) is RemoteGroupStageResult.Success)
        assertTrue(f.contacts.run(ACCOUNT) is RemoteContactStageResult.Success)
        assertEquals(listOf<String?>(null), f.eventReads)
        assertEquals(1, f.groupListReads)
        assertEquals(1, f.inventoryReads)
        assertTrue(f.trace.indexOf("events:latest") < f.trace.indexOf("group-list"))
        f.clearReads()
        assertTrue(f.pass() is RemoteContactStageResult.Success)
        assertEquals(listOf("cursor-next"), f.eventReads)
        assertEquals(0, f.groupListReads)
        assertEquals(0, f.inventoryReads)
        assertTrue(f.cardReads.isEmpty())
    }

    @Test fun transientEventFailureDoesNotFallbackOrAcknowledge() = runTest {
        val f = Fixture()
        f.eventFailure = GatewayFailureCategory.TIMEOUT
        assertTrue(f.groupStage.run(ACCOUNT) is RemoteGroupStageResult.RetryWaiting)
        assertEquals(listOf("cursor-old"), f.eventReads)
        assertEquals(0, f.inventoryReads)
        assertEquals(0, f.groupListReads)
        assertEquals("cursor-old", f.header().eventCursor)
    }

    @Test fun unexpectedCursorResponsesUseOneLatestReadAtTheSafetyDeadlineAndThenReturnToDelta() = runTest {
        for (failure in listOf(GatewayFailureCategory.MALFORMED_RESPONSE, GatewayFailureCategory.UNKNOWN)) {
            val f = Fixture()
            f.now += EventDrivenRemoteReadSession.FULL_SCAN_INTERVAL_MILLIS
            f.eventFailure = failure
            assertTrue(f.pass() is RemoteContactStageResult.Success)
            assertEquals(listOf("cursor-old", null), f.eventReads)
            assertEquals(setOf(A, B), f.cardReads.toSet())
            assertEquals(f.now, f.header().lastFullScanAtEpochMillis)
            f.eventFailure = null
            f.clearReads()
            assertTrue(f.pass() is RemoteContactStageResult.Success)
            assertEquals(listOf("cursor-next"), f.eventReads)
            assertEquals(0, f.inventoryReads)
            assertTrue(f.cardReads.isEmpty())
        }
    }

    @Test fun unexpectedCursorResponseBeforeSafetyDeadlineDoesNotResetOrAcknowledge() = runTest {
        val f = Fixture()
        f.eventFailure = GatewayFailureCategory.MALFORMED_RESPONSE
        assertEquals(RemoteGroupStageResult.ActionRequired, f.groupStage.run(ACCOUNT))
        assertEquals(listOf("cursor-old"), f.eventReads)
        assertEquals("cursor-old", f.header().eventCursor)
        assertEquals(0, f.inventoryReads)
    }

    @Test fun safetyDeadlineNeverTreatsAuthRateLimitOrServiceFailureAsCursorExpiry() = runTest {
        for (failure in listOf(GatewayFailureCategory.AUTHENTICATION_REQUIRED,
            GatewayFailureCategory.HUMAN_VERIFICATION_REQUIRED, GatewayFailureCategory.CLIENT_IDENTITY_REJECTED,
            GatewayFailureCategory.PERMISSION_OR_PLAN_DENIED, GatewayFailureCategory.RATE_LIMITED,
            GatewayFailureCategory.NETWORK_UNAVAILABLE, GatewayFailureCategory.TIMEOUT,
            GatewayFailureCategory.REMOTE_SERVICE_FAILURE, GatewayFailureCategory.LOCAL_REQUEST_BUDGET_EXHAUSTED)) {
            val f = Fixture()
            f.now += EventDrivenRemoteReadSession.FULL_SCAN_INTERVAL_MILLIS
            f.eventFailure = failure
            assertFalse(f.groupStage.run(ACCOUNT) is RemoteGroupStageResult.Success)
            assertEquals(failure.name, listOf("cursor-old"), f.eventReads)
            assertEquals(0, f.inventoryReads)
            assertTrue(f.cardReads.isEmpty())
            assertEquals("cursor-old", f.header().eventCursor)
        }
    }

    @Test fun failedLatestRecoveryDoesNotDiscardTheOldCheckpoint() = runTest {
        val f = Fixture()
        f.now += EventDrivenRemoteReadSession.FULL_SCAN_INTERVAL_MILLIS
        f.eventFailure = GatewayFailureCategory.UNKNOWN
        f.latestFailure = GatewayFailureCategory.REMOTE_SERVICE_FAILURE
        assertTrue(f.groupStage.run(ACCOUNT) is RemoteGroupStageResult.RetryWaiting)
        assertEquals(listOf("cursor-old", null), f.eventReads)
        assertEquals(0, f.inventoryReads)
        assertEquals("cursor-old", f.header().eventCursor)
    }

    private class Fixture(initial: List<ContactInventoryMetadata> = listOf(metadata(A), metadata(B))) {
        val metadata = initial.associateBy { it.id }.toMutableMap()
        val cards = initial.associate { it.id to contact(it.id) }.toMutableMap()
        val local = cards.toMutableMap()
        val remoteGroups = mutableMapOf(G to RemoteContactGroup(G, "First", "#f00"), H to RemoteContactGroup(H, "Second", "#0f0"))
        val localGroups = remoteGroups.toMutableMap()
        val owners = mutableMapOf<RemoteEmailId, RemoteContactId>()
        var checkpoint: VersionedContactInventoryCheckpoint? = null
        var delta = delta()
        var now = START
        var groupFailure: GatewayFailureCategory? = null
        var targetedGroupFailure: GatewayFailureCategory? = null
        var eventFailure: GatewayFailureCategory? = null
        var latestFailure: GatewayFailureCategory? = null
        var inventoryFailure: GatewayFailureCategory? = null
        var presenceFailure: GatewayFailureCategory? = null
        var expiredCursor = false
        var failCanonical = false
        var failCheckpoint = false
        var cancelAfterCard = false
        var cancelled = false
        var raceOnCanonical = false
        var canonicalCommits = 0
        var inventoryReads = 0
        var groupListReads = 0
        val metadataReads = mutableListOf<RemoteContactId>()
        val cardReads = mutableListOf<RemoteContactId>()
        val ownerReads = mutableListOf<RemoteEmailId>()
        val existenceReads = mutableListOf<RemoteContactId>()
        val eventReads = mutableListOf<String?>()
        val targetedGroupReads = mutableListOf<Set<RemoteGroupId>>()
        val deletions = mutableSetOf<RemoteContactId>()
        val deletedGroups = mutableSetOf<RemoteGroupId>()
        val pending = mutableSetOf<RemoteContactId>()
        val dispositions = mutableMapOf<RemoteContactId, RemoteHydrationReadDisposition>()
        val sourceChanges = mutableMapOf<RemoteContactId, Boolean>()
        val trace = mutableListOf<String>()
        init { installCheckpoint(initial) }
        fun header() = requireNotNull(checkpoint).checkpoint
        fun installCheckpoint(entries: List<ContactInventoryMetadata>, strategy: Int = 1, lastFull: Long? = START) {
            checkpoint = VersionedContactInventoryCheckpoint(7, ContactInventoryCheckpoint(entries.map(::baseline),
                "cursor-old", strategy, lastFull))
        }
        val planner = PersistentContactInventoryPlanner(object : ContactInventoryCheckpointStore {
            override suspend fun load(account: AccountScope) = checkpoint
            override suspend fun compareAndSet(account: AccountScope, expectedGeneration: Long?, checkpoint: ContactInventoryCheckpoint): Boolean {
                if (failCheckpoint) throw IllegalStateException("Synthetic checkpoint failure")
                if (this@Fixture.checkpoint?.generation != expectedGeneration) return false
                this@Fixture.checkpoint = VersionedContactInventoryCheckpoint((expectedGeneration ?: -1) + 1, checkpoint)
                return true
            }
        })
        val resources = object : ProtonContactEventResourceGateway {
            override suspend fun contact(account: AccountScope, id: RemoteContactId): GatewayOutcome<ContactInventoryMetadata> {
                metadataReads += id
                return metadata[id]?.let { success(it) } ?: GatewayOutcome.Failure(GatewayFailureCategory.NOT_FOUND)
            }
            override suspend fun emailOwner(account: AccountScope, id: RemoteEmailId): GatewayOutcome<RemoteContactId> {
                ownerReads += id
                return owners[id]?.let { success(it) } ?: GatewayOutcome.Failure(GatewayFailureCategory.NOT_FOUND)
            }
            override suspend fun groups(account: AccountScope, ids: Set<RemoteGroupId>): GatewayOutcome<AvailableContactGroups> {
                if (ids.isEmpty()) return success(AvailableContactGroups(emptyList()))
                targetedGroupReads += ids
                targetedGroupFailure?.let { return GatewayOutcome.Failure(it) }
                groupFailure?.let { return GatewayOutcome.Failure(it) }
                return success(AvailableContactGroups(ids.map { remoteGroups.getValue(it) }))
            }
        }
        val session = EventDrivenRemoteReadSession(planner, ProtonContactEventsGateway { _, cursor ->
            eventReads += cursor; trace += if (cursor == null) "events:latest" else "events:delta"
            (if (cursor == null) latestFailure else eventFailure)?.let {
                return@ProtonContactEventsGateway GatewayOutcome.Failure(it)
            }
            if (expiredCursor && cursor != null) GatewayOutcome.Failure(GatewayFailureCategory.NOT_FOUND)
            else success(if (cursor == null) ContactEventsDelta("cursor-next", emptySet(), true) else delta)
        }, resources, clock = { now })
        val groupCapabilities = AccountScopedContactGroupCapabilityCoordinator(ACCOUNT, object : ProtonContactGroupGateway {
            override fun capabilities() = ContactGroupCapabilities.PROTON_CORE_36_6_2_SURFACE
            override suspend fun list(account: AccountScope): GatewayOutcome<AvailableContactGroups> {
                groupListReads++; trace += "group-list"
                groupFailure?.let { return GatewayOutcome.Failure(it) }
                return success(AvailableContactGroups(remoteGroups.values.toList()))
            }
            override suspend fun create(account: AccountScope, mutation: ContactGroupMutation.Create): GatewayOutcome<RemoteContactGroup> = error("Unexpected write")
            override suspend fun update(account: AccountScope, mutation: ContactGroupMutation.Update): GatewayOutcome<RemoteContactGroup> = error("Unexpected write")
            override suspend fun delete(account: AccountScope, mutation: ContactGroupMutation.Delete): GatewayOutcome<Unit> = error("Unexpected write")
        })
        val groupStage = IncrementalRemoteGroupStage(groupCapabilities, object : RemoteGroupReconciliationStore {
            override suspend fun knownRemoteIds(account: AccountScope) = localGroups.keys.toSet()
            override suspend fun commit(account: AccountScope, groups: AvailableContactGroups) {
                localGroups.clear(); groups.groups.forEach { localGroups[it.id] = it }
            }
            override suspend fun commitDelta(account: AccountScope, upserts: AvailableContactGroups, deletedIds: Set<RemoteGroupId>) {
                upserts.groups.forEach { localGroups[it.id] = it }
                deletedIds.forEach(localGroups::remove); deletedGroups += deletedIds
            }
        }, eventSession = session)
        private val obligations = object : RemoteHydrationObligationStore {
            override suspend fun pending(account: AccountScope) = pending.toSet()
            override suspend fun defer(account: AccountScope, baseline: ContactInventoryBaseline, failure: GatewayOutcome.Failure) { pending += baseline.id }
            override suspend fun readDisposition(account: AccountScope, baseline: ContactInventoryBaseline,
                sourceChanged: Boolean): RemoteHydrationReadDisposition {
                sourceChanges[baseline.id] = sourceChanged
                return if (sourceChanged) RemoteHydrationReadDisposition.READ
                    else dispositions[baseline.id] ?: RemoteHydrationReadDisposition.READ
            }
            override suspend fun resolved(account: AccountScope, ids: Set<RemoteContactId>) { pending -= ids }
        }
        val contacts = IncrementalRemoteContactStage(ProtonContactInventoryGateway { _, cursor ->
            check(cursor == null); inventoryReads++; trace += "inventory"
            inventoryFailure?.let { return@ProtonContactInventoryGateway GatewayOutcome.Failure(it) }
            success(ContactInventoryPage(metadata.values.toList(), null, null, metadata.size,
                ContactInventorySnapshotAuthority.COMPLETE_PUBLIC_DIRECTORY))
        }, ProtonVerifiedContactCardGateway { _, id ->
            cardReads += id
            val value = cards[id]?.let { success(VerifiedContactCard(id, RemoteVersion("complete-${id.value}"), it)) }
                ?: GatewayOutcome.Failure(GatewayFailureCategory.NOT_FOUND)
            if (cancelAfterCard) cancelled = true
            value
        }, planner, RemoteCanonicalReconciliationStore { _, _, hydrated, labels, deleted ->
            if (failCanonical) throw IllegalStateException("Synthetic transaction failure")
            hydrated.forEach { local[it.id] = it.contact }
            deleted.forEach(local::remove); deletions += deleted; canonicalCommits++
            if (raceOnCanonical) {
                raceOnCanonical = false
                checkpoint = VersionedContactInventoryCheckpoint(8, ContactInventoryCheckpoint(header().entries,
                    "winner-cursor", 1, START))
            }
            CanonicalReconciliationReceipt(hydrated.map { it.id }.toSet(), labels, deleted)
        }, existenceGateway = ProtonContactExistenceGateway { _, id ->
            existenceReads += id
            presenceFailure?.let { GatewayOutcome.Failure(it) } ?: success(
                if (id in metadata) RemoteContactPresence.PRESENT else RemoteContactPresence.CONFIRMED_ABSENT)
        }, obligations = obligations, eventSession = session)
        suspend fun pass(forceFull: Boolean = false, allowUnavailableGroups: Boolean = false): RemoteContactStageResult {
            session.reset(forceFull)
            val groups = groupStage.run(ACCOUNT, { cancelled })
            check(groups is RemoteGroupStageResult.Success || (allowUnavailableGroups && groups == RemoteGroupStageResult.Unavailable))
            return contacts.run(ACCOUNT, isCancellationRequested = { cancelled })
        }
        fun clearReads() {
            metadataReads.clear(); cardReads.clear(); ownerReads.clear(); existenceReads.clear()
            eventReads.clear(); targetedGroupReads.clear(); trace.clear(); inventoryReads = 0; groupListReads = 0
        }
    }

    private companion object {
        const val START = 1_000_000L
        val ACCOUNT = AccountScope("event-test-account")
        val A = RemoteContactId("contact-a")
        val B = RemoteContactId("contact-b")
        val G = RemoteGroupId("group-a")
        val H = RemoteGroupId("group-b")
        fun email(id: String) = RemoteEmailId(id)
        fun <T> success(value: T) = GatewayOutcome.Success(value)
        fun delta(changed: Set<RemoteContactId> = emptySet(), deleted: Set<RemoteContactId> = emptySet(),
            changedEmails: Set<RemoteEmailId> = emptySet(), deletedEmails: Set<RemoteEmailId> = emptySet(),
            changedGroups: Set<RemoteGroupId> = emptySet(), deletedGroups: Set<RemoteGroupId> = emptySet()) =
            ContactEventsDelta("cursor-next", changed, false, changedEmails, deleted, deletedEmails, changedGroups, deletedGroups)
        fun metadata(id: RemoteContactId, emails: List<Pair<String, List<RemoteGroupId>>> = emptyList()) =
            ContactInventoryMetadata(id, "Synthetic ${id.value}", RemoteVersion("public-${id.value}"), null, null,
                emails.map { email(it.first) }, emails.flatMap { it.second }.distinct(),
                ContactInventoryVersionProvenance.LOCAL_INDEX_FINGERPRINT, ContactInventoryCoverage.PUBLIC_DIRECTORY_FIELDS_ONLY,
                emails.map { RemoteEmailGroupMembership(email(it.first), it.second) })
        fun baseline(item: ContactInventoryMetadata) = ContactInventoryBaseline(item.id, item.displayName,
            item.version, item.sizeBytes, item.modifiedAtEpochSeconds, item.groupIds, item.emailGroupMemberships)
        fun contact(id: RemoteContactId, note: String = "Initial private note") = CanonicalContact(
            ACCOUNT.value, id.value, displayName = "Synthetic ${id.value}", values = listOf(
                ContactValue("note", ContactValueKind.NOTE, note, order = 0),
                ContactValue("phone", ContactValueKind.PHONE, "+1 202 555 0100", order = 0),
            ))
    }
}
