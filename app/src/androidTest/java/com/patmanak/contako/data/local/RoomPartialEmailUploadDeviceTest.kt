package com.patmanak.contako.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.data.gateway.*
import com.patmanak.contako.data.proton.PROTON_EMAIL_ID_KEY
import com.patmanak.contako.data.proton.PROTON_GROUP_IDS_KEY
import com.patmanak.contako.data.proton.ContactInventoryPlan
import com.patmanak.contako.data.proton.ContactInventoryCheckpoint
import com.patmanak.contako.data.proton.ProtonContactVCardCodec
import com.patmanak.contako.data.proton.ProtonPlainContactCard
import com.patmanak.contako.data.sync.*
import com.patmanak.contako.domain.model.*
import com.patmanak.contako.domain.repository.SaveResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room state only, no Android account, provider or live Proton access. */
@RunWith(AndroidJUnit4::class)
class RoomPartialEmailUploadDeviceTest {
    @Test fun invalidDuplicateEmailKeepsItsOwnAcceptedValueAndGroupsWhileOtherFieldsUpload() = inDatabase { database ->
        val original = seeded()
        insert(database, original)
        val changed = original.copy(values = original.values.map {
            when (it.id) { "email-one" -> it.copy(value = "invalid-address"); "note" -> it.copy(value = "After ë ß 東京"); else -> it }
        })
        val saved = (RoomContactRepository(database).saveContact(changed) as SaveResult.Saved).value
        val intent = database.outboxDao().get(ACCOUNT.value, "CONTACT", ID)!!
        assertNull(intent.blockedReason)
        assertEquals("PENDING", intent.state)
        assertTrue("INVALID_EMAIL" in saved.actionRequiredReasons)
        val overlays = RoomContactUploadOverlays(database)
        assertTrue(overlays.refreshAccepted(saved, original))
        val wire = requireNotNull(overlays.wireProjection(saved))
        val first = wire.values.single { it.id == "email-one" }
        val second = wire.values.single { it.id == "email-two" }
        assertEquals("duplicate@example.test", first.value)
        assertEquals("remote-email-one", first.metadata[PROTON_EMAIL_ID_KEY])
        assertEquals("groups-a,groups-b", first.metadata[PROTON_GROUP_IDS_KEY])
        assertEquals("remote-email-two", second.metadata[PROTON_EMAIL_ID_KEY])
        assertEquals("groups-c", second.metadata[PROTON_GROUP_IDS_KEY])
        assertEquals("After ë ß 東京", wire.values.single { it.id == "note" }.value)
        assertFalse("INVALID_EMAIL" in wire.actionRequiredReasons)
        assertEquals("invalid-address", database.contactDao().get(ACCOUNT.value, ID)!!.toDomain().values.single { it.id == "email-one" }.value)
    }

    @Test fun ackAndLaterHydrationRetainInvalidInputAcrossRestartWithoutAnotherOutboxWrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB)
        var database = ContakoDatabase.create(context, DB)
        try {
            val original = seeded()
            insert(database, original)
            val changed = original.copy(values = original.values.map {
                if (it.id == "email-one") it.copy(value = "unfinished-email") else if (it.id == "note") it.copy(value = "Changed note") else it
            })
            val saved = (RoomContactRepository(database).saveContact(changed) as SaveResult.Saved).value
            RoomContactUploadOverlays(database).refreshAccepted(saved, original)
            val row = database.outboxDao().get(ACCOUNT.value, "CONTACT", ID)!!
            val command = DurableMutationCommand(ACCOUNT.value, "CONTACT", ID, row.revision, RemoteMutationOperation.UPDATE,
                row.attemptCount, row.nextAttemptAtEpochMillis, row.requiresReconciliation,
                expectedRemoteIdentity = REMOTE, expectedRemoteVersion = "remote-v1")
            val execution = RoomMutationExecutionStore(database)
            assertTrue(execution.claim(command, Long.MAX_VALUE))
            assertTrue(execution.acknowledgeAndFinish(command, RemoteMutationAcknowledgement(REMOTE, "remote-v2",
                emailIdsByValueId = mapOf("email-one" to "new-email-one", "email-two" to "new-email-two"))))
            assertNull(database.outboxDao().get(ACCOUNT.value, "CONTACT", ID))
            assertNull(database.contactDao().get(ACCOUNT.value, ID)!!.contact.pendingMutationRevision)
            val remote = original.copy(values = original.values.map {
                when (it.id) {
                    "email-one" -> it.copy(metadata = it.metadata + (PROTON_EMAIL_ID_KEY to "new-email-one"))
                    "email-two" -> it.copy(metadata = it.metadata + (PROTON_EMAIL_ID_KEY to "new-email-two"))
                    "note" -> it.copy(value = "Changed note")
                    else -> it
                }
            })
            RoomRemoteCanonicalReconciliationStore(database).reconcileTargetedVerified(ACCOUNT,
                VerifiedContactCard(RemoteContactId(REMOTE), RemoteVersion("remote-v2"), remote))
            database.close()
            database = ContakoDatabase.create(context, DB)
            val read = database.contactDao().get(ACCOUNT.value, ID)!!.toDomain()
            assertEquals("unfinished-email", read.values.single { it.id == "email-one" }.value)
            assertEquals("new-email-one", read.values.single { it.id == "email-one" }.metadata[PROTON_EMAIL_ID_KEY])
            assertEquals("Changed note", read.values.single { it.id == "note" }.value)
            assertTrue("INVALID_EMAIL" in read.actionRequiredReasons)
            assertNull(database.outboxDao().get(ACCOUNT.value, "CONTACT", ID))
            assertEquals(1, database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, ID).size)
            val fixed = read.copy(values = read.values.map { if (it.id == "email-one") it.copy(value = "fixed@example.test") else it })
            val repaired = (RoomContactRepository(database).saveContact(fixed) as SaveResult.Saved).value
            assertTrue(database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, ID).isEmpty())
            assertTrue(repaired.actionRequiredReasons.isEmpty())
            assertEquals("fixed@example.test", RoomContactUploadOverlays(database).wireProjection(repaired)!!.values.single { it.id == "email-one" }.value)
        } finally { database.close(); context.deleteDatabase(DB) }
    }

    @Test fun newlyAddedInvalidEmailIsAbsentOnlyFromWireAndUnchangedImportedInvalidEmailIsNotNewlyBlocked() = inDatabase { database ->
        val repository = RoomContactRepository(database)
        val local = CanonicalContact(ACCOUNT.value, "new-fixture", displayName = "New fixture", values = listOf(
            ContactValue("invalid-new", ContactValueKind.EMAIL, "not-an-email", order = 0),
            ContactValue("note", ContactValueKind.NOTE, "Must still upload", order = 0)))
        val saved = (repository.saveContact(local) as SaveResult.Saved).value
        val wire = RoomContactUploadOverlays(database).wireProjection(saved)!!
        assertTrue(wire.valuesOf(ContactValueKind.EMAIL).isEmpty())
        assertEquals("Must still upload", wire.valuesOf(ContactValueKind.NOTE).single().value)
        assertEquals("not-an-email", database.contactDao().get(ACCOUNT.value, local.id)!!.toDomain().valuesOf(ContactValueKind.EMAIL).single().value)
        val imported = seeded().copy(id = "legacy-fixture", values = listOf(
            ContactValue("old-invalid", ContactValueKind.EMAIL, "legacy-invalid", order = 0,
                metadata = mapOf(PROTON_EMAIL_ID_KEY to "legacy-remote-email"))))
        insert(database, imported)
        val edited = (repository.saveContact(imported.copy(lastName = "Edited")) as SaveResult.Saved).value
        assertFalse("INVALID_EMAIL" in edited.actionRequiredReasons)
        assertTrue(database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, imported.id).isEmpty())
        assertNull(database.outboxDao().get(ACCOUNT.value, "CONTACT", imported.id)!!.blockedReason)
    }

    @Test fun anUnboundLegacyEmailCannotBeOmittedByGuessingDuplicateText() = inDatabase { database ->
        val original = seeded().copy(values = seeded().values.map { if (it.id == "email-one") it.copy(metadata = emptyMap()) else it })
        insert(database, original)
        val saved = (RoomContactRepository(database).saveContact(original.copy(values = original.values.map {
            if (it.id == "email-one") it.copy(value = "invalid") else it
        })) as SaveResult.Saved).value
        val overlays = RoomContactUploadOverlays(database)
        assertFalse(overlays.refreshAccepted(saved, seeded()))
        assertNull(overlays.wireProjection(saved))
        assertEquals("invalid", database.contactDao().get(ACCOUNT.value, ID)!!.toDomain().values.single { it.id == "email-one" }.value)
    }

    @Test fun twoLocalOccurrencesClaimingOneServiceIdentityCannotDuplicateAnAcceptedFallback() = inDatabase { database ->
        val malformed = seeded().copy(values = seeded().values.map {
            if (it.id == "email-two") it.copy(metadata = it.metadata + (PROTON_EMAIL_ID_KEY to "remote-email-one")) else it
        })
        insert(database, malformed)
        val saved = (RoomContactRepository(database).saveContact(malformed.copy(values = malformed.values.map {
            if (it.id == "email-one") it.copy(value = "invalid") else it
        })) as SaveResult.Saved).value
        val overlays = RoomContactUploadOverlays(database)
        assertFalse(overlays.refreshAccepted(saved, seeded()))
        assertNull(overlays.wireProjection(saved))
        assertEquals("invalid", database.contactDao().get(ACCOUNT.value, ID)!!.toDomain().values.single { it.id == "email-one" }.value)
    }

    @Test fun aStaleOrRemovedLocalOccurrenceCannotBeReinsertedByRemoteOverlayAdoption() = inDatabase { database ->
        insert(database, seeded())
        val saved = (RoomContactRepository(database).saveContact(seeded().copy(values = seeded().values.map {
            if (it.id == "email-one") it.copy(value = "invalid") else it
        })) as SaveResult.Saved).value
        val overlays = RoomContactUploadOverlays(database)
        val stale = runCatching { overlays.overlayRemote(saved.copy(revision = saved.revision + 1), seeded()) }
        assertTrue(stale.exceptionOrNull() is LocalReconciliationInvariantFailure)
        val removed = runCatching { overlays.overlayRemote(saved.copy(values = saved.values.filterNot { it.id == "email-one" }), seeded()) }
        assertTrue(removed.exceptionOrNull() is LocalReconciliationInvariantFailure)
        assertEquals(1, database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, ID).size)
    }

    @Test fun aSupersededAcknowledgementTransfersOnlyOccurrenceIdentityToTheNewerInvalidEdit() = inDatabase { database ->
        val original = seeded()
        insert(database, original)
        val repository = RoomContactRepository(database)
        val submitted = (repository.saveContact(original.copy(lastName = "Submitted")) as SaveResult.Saved).value
        val execution = RoomMutationExecutionStore(database)
        val command = command(submitted)
        assertTrue(execution.claim(command, Long.MAX_VALUE))
        val latest = (repository.saveContact(submitted.copy(lastName = "Newest", values = submitted.values.map {
            if (it.id == "email-one") it.copy(value = "unfinished") else it
        })) as SaveResult.Saved).value
        assertNull(database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, ID).single().acceptedValue)
        val acknowledgement = RemoteMutationAcknowledgement(REMOTE, "remote-v2", emailIdsByValueId = mapOf(
            "email-one" to "new-email-one", "email-two" to "new-email-two", "removed-occurrence" to "must-not-appear"))
        assertTrue(execution.acknowledgeAndFinish(command, acknowledgement))
        val retained = database.contactDao().get(ACCOUNT.value, ID)!!.toDomain()
        assertEquals(latest.revision, retained.pendingMutationRevision)
        assertEquals("Newest", retained.lastName)
        assertEquals("unfinished", retained.values.single { it.id == "email-one" }.value)
        assertEquals("new-email-one", retained.values.single { it.id == "email-one" }.metadata[PROTON_EMAIL_ID_KEY])
        assertFalse(retained.values.any { it.id == "removed-occurrence" })
        assertEquals("new-email-one", database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, ID).single().sourceEmailId)
        assertFalse(execution.acknowledgeAndFinish(command, acknowledgement)) // A replay cannot replace the newer baseline.
        val remote = original.copy(values = original.values.map {
            when (it.id) {
                "email-one" -> it.copy(metadata = it.metadata + (PROTON_EMAIL_ID_KEY to "new-email-one"))
                "email-two" -> it.copy(metadata = it.metadata + (PROTON_EMAIL_ID_KEY to "new-email-two"))
                else -> it
            }
        })
        val overlays = RoomContactUploadOverlays(database)
        assertTrue(overlays.refreshAccepted(retained, remote))
        assertEquals("duplicate@example.test", overlays.wireProjection(retained)!!.values.single { it.id == "email-one" }.value)
        assertFalse(overlays.retainAcknowledgedIdentities(retained.copy(revision = retained.revision - 1), mapOf("email-one" to "stale")))
        assertFalse(overlays.retainAcknowledgedIdentities(retained.copy(values = retained.values.filterNot { it.id == "email-one" }),
            mapOf("email-one" to "absent")))
        assertEquals("new-email-one", database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, ID).single().sourceEmailId)
    }

    @Test fun remoteDeletionAfterPartialAcknowledgementPreservesInputAsDurableDeletionRecovery() = inDatabase { database ->
        val partial = acknowledgedPartial(database)
        val remoteId = RemoteContactId(REMOTE)
        val plan = ContactInventoryPlan.pending(ACCOUNT, null, emptySet(), emptySet(), setOf(remoteId),
            ContactInventoryCheckpoint(emptyList()))
        RoomRemoteCanonicalReconciliationStore(database).commit(ACCOUNT, plan, emptyList(), emptySet(), setOf(remoteId))
        val retained = database.contactDao().get(ACCOUNT.value, ID)!!.toDomain()
        assertFalse(retained.isDeleted)
        assertEquals("unfinished", retained.values.single { it.id == "email-one" }.value)
        assertEquals(partial.revision + 1, retained.revision)
        assertEquals(retained.revision, retained.pendingMutationRevision)
        val intent = database.outboxDao().get(ACCOUNT.value, "CONTACT", ID)!!
        assertEquals("ACTION_REQUIRED", intent.state)
        assertEquals("REMOTE_DELETION_RECOVERY_REQUIRED", intent.blockedReason)
        assertTrue(intent.requiresReconciliation)
        assertEquals(retained.revision, database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, ID).single().canonicalRevision)
        val recovery = RoomDeletionRecoveryStore(database).detail(ACCOUNT.value, ID)!!
        assertEquals("unfinished", recovery.contact.values.single { it.id == "email-one" }.value)
        assertFalse(RoomMutationExecutionStore(database).eligible(ACCOUNT.value, Long.MAX_VALUE, 10).any { it.aggregateId == ID })
    }

    @Test fun remoteGroupChangesSurvivePartialEmailOverlayAndItsNextHydration() = inDatabase { database ->
        acknowledgedPartial(database)
        listOf("groups-a", "groups-b", "groups-c").forEach { remote ->
            val group = ContactGroup(ACCOUNT.value, "local-$remote", remote, remoteLabelId = remote, revision = 1)
            database.contactGroupDao().upsert(group.toEntity())
            if (remote == "groups-a") database.contactGroupDao().insertMemberships(listOf(GroupMembership(ID, "email-one").toEntity(group)))
        }
        val remote = seeded().copy(values = seeded().values.map {
            if (it.id == "email-one") it.copy(metadata = it.metadata + (PROTON_GROUP_IDS_KEY to "groups-b")) else it
        })
        val store = RoomRemoteCanonicalReconciliationStore(database)
        repeat(2) { index ->
            store.reconcileTargetedVerified(ACCOUNT, VerifiedContactCard(RemoteContactId(REMOTE), RemoteVersion("remote-v${index + 3}"), remote))
            val current = database.contactDao().get(ACCOUNT.value, ID)!!.toDomain()
            val invalid = current.values.single { it.id == "email-one" }
            assertEquals("unfinished", invalid.value)
            assertEquals("groups-b", invalid.metadata[PROTON_GROUP_IDS_KEY])
            assertTrue(database.contactGroupDao().get(ACCOUNT.value, "local-groups-a")!!.memberships.none { it.emailValueId == "email-one" })
            assertTrue(database.contactGroupDao().get(ACCOUNT.value, "local-groups-b")!!.memberships.any { it.emailValueId == "email-one" })
            assertTrue("INVALID_EMAIL" in current.actionRequiredReasons)
        }
    }

    @Test fun aVerifiedRemovedEmailDetachesOnlyItsTransportAndRemainsLocalThroughLaterEdits() = inDatabase { database ->
        acknowledgedPartial(database)
        val group = ContactGroup(ACCOUNT.value, "local-groups-a", "Group A", remoteLabelId = "groups-a", revision = 1)
        database.contactGroupDao().upsert(group.toEntity())
        database.contactGroupDao().insertMemberships(listOf(GroupMembership(ID, "email-one").toEntity(group)))
        val remote = seeded().copy(values = seeded().values.filterNot { it.id == "email-one" })
        val reconciliation = RoomRemoteCanonicalReconciliationStore(database)
        repeat(2) { index ->
            reconciliation.reconcileTargetedVerified(ACCOUNT,
                VerifiedContactCard(RemoteContactId(REMOTE), RemoteVersion("remote-v${index + 3}"), remote))
            val local = database.contactDao().get(ACCOUNT.value, ID)!!.toDomain()
            val invalid = local.values.single { it.id == "email-one" }
            assertEquals("unfinished", invalid.value)
            assertEquals("home", invalid.label)
            assertNull(invalid.metadata[PROTON_EMAIL_ID_KEY])
            assertNull(invalid.metadata[PROTON_GROUP_IDS_KEY])
            val row = database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, ID).single()
            assertNull(row.sourceEmailId)
            assertNull(row.acceptedValue)
            assertFalse(row.requiresRemoteIdentity)
            assertTrue(database.contactGroupDao().get(ACCOUNT.value, group.id)!!.memberships.isEmpty())
        }
        val local = database.contactDao().get(ACCOUNT.value, ID)!!.toDomain()
        val changed = (RoomContactRepository(database).saveContact(local.copy(values = local.values.map {
            if (it.id == "note") it.copy(value = "Other fields still upload") else it
        })) as SaveResult.Saved).value
        val overlays = RoomContactUploadOverlays(database)
        assertTrue(overlays.refreshAccepted(changed, remote))
        val wire = overlays.wireProjection(changed)!!
        assertTrue(wire.valuesOf(ContactValueKind.EMAIL).none { it.id == "email-one" })
        assertEquals("Other fields still upload", wire.valuesOf(ContactValueKind.NOTE).single().value)
        assertEquals("unfinished", database.contactDao().get(ACCOUNT.value, ID)!!.toDomain().values.single { it.id == "email-one" }.value)
    }

    @Test fun verifiedAbsenceDoesNotDetachAnUnmappedLegacyEmailByGuessing() = inDatabase { database ->
        val original = seeded().copy(values = seeded().values.map {
            if (it.id == "email-one") it.copy(metadata = emptyMap()) else it
        })
        insert(database, original)
        val saved = (RoomContactRepository(database).saveContact(original.copy(values = original.values.map {
            if (it.id == "email-one") it.copy(value = "unfinished") else it
        })) as SaveResult.Saved).value
        val overlays = RoomContactUploadOverlays(database)
        val remote = original.copy(values = original.values.filterNot { it.id == "email-one" })
        // Exercise adoption directly: the ordinary conflict path still requires an explicit choice.
        RoomRemoteCanonicalReconciliationStore(database).adoptRemote(saved, remote,
            VerifiedContactCard(RemoteContactId(REMOTE), RemoteVersion("remote-v2"), remote), null)
        val retained = database.contactDao().get(ACCOUNT.value, ID)!!.toDomain()
        assertEquals("unfinished", retained.values.single { it.id == "email-one" }.value)
        assertTrue(database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, ID).single().requiresRemoteIdentity)
        assertFalse(overlays.refreshAccepted(retained, remote))
        assertNull(overlays.wireProjection(retained))
    }

    @Test fun codecEmailLineReorderingAndDeletionAreGuardedWithoutMutatingInputOrProvenance() = inDatabase { database ->
        val original = codecContact(listOf("source-a" to "a@example.test", "source-b" to "b@example.test"))
        insert(database, original)
        val withheldId = original.valuesOf(ContactValueKind.EMAIL).single { it.metadata[PROTON_EMAIL_ID_KEY] == "source-a" }.id
        val bId = original.valuesOf(ContactValueKind.EMAIL).single { it.metadata[PROTON_EMAIL_ID_KEY] == "source-b" }.id
        val saved = (RoomContactRepository(database).saveContact(original.copy(values = original.values.map {
            if (it.id == withheldId) it.copy(value = "unfinished-a") else it
        })) as SaveResult.Saved).value
        val execution = RoomMutationExecutionStore(database)
        val command = command(saved)
        assertTrue(execution.claim(command, Long.MAX_VALUE))
        assertTrue(execution.acknowledgeAndFinish(command, RemoteMutationAcknowledgement(REMOTE, "remote-v2",
            emailIdsByValueId = original.valuesOf(ContactValueKind.EMAIL).associate { it.id to it.metadata.getValue(PROTON_EMAIL_ID_KEY) })))
        val reordered = codecContact(listOf("source-b" to "b@example.test", "source-a" to "a@example.test"))
        assertEquals(withheldId, reordered.valuesOf(ContactValueKind.EMAIL).single { it.value == "b@example.test" }.id)
        val reconciliation = RoomRemoteCanonicalReconciliationStore(database)
        val removed = codecContact(listOf("source-b" to "b@example.test"))
        assertEquals(withheldId, removed.valuesOf(ContactValueKind.EMAIL).single().id)
        val before = database.contactDao().get(ACCOUNT.value, ID)!!.toDomain()
        val beforeOverlay = database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, ID).single()
        for (remote in listOf(reordered, removed)) {
            val guarded = runCatching { reconciliation.reconcileTargetedVerified(ACCOUNT,
                VerifiedContactCard(RemoteContactId(REMOTE), RemoteVersion("remote-v3"), remote)) }
            assertTrue(guarded.exceptionOrNull() is LocalReconciliationInvariantFailure)
            val after = database.contactDao().get(ACCOUNT.value, ID)!!.toDomain()
            assertEquals(before.values, after.values)
            assertEquals(before.revision, after.revision)
            assertEquals(before.remoteVersion, after.remoteVersion)
            assertEquals("unfinished-a", after.values.single { it.id == withheldId }.value)
            assertEquals("b@example.test", after.values.single { it.id == bId }.value)
            val afterOverlay = database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, ID).single()
            assertArrayEquals(beforeOverlay.localValue, afterOverlay.localValue)
            assertArrayEquals(beforeOverlay.acceptedValue, afterOverlay.acceptedValue)
            assertEquals(beforeOverlay.sourceEmailId, afterOverlay.sourceEmailId)
        }
        val overlays = RoomContactUploadOverlays(database)
        assertFalse(overlays.refreshAccepted(before, reordered))
        assertArrayEquals(beforeOverlay.acceptedValue, database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, ID).single().acceptedValue)
        // An incompatible legacy fallback also fails before it can reach codec encoding.
        val moved = reordered.valuesOf(ContactValueKind.EMAIL).single { it.metadata[PROTON_EMAIL_ID_KEY] == "source-a" }.copy(id = withheldId)
        database.contactEmailUploadOverlayDao().upsert(listOf(beforeOverlay.copy(acceptedValue =
            ContactConflictSnapshotCodec.encode(CanonicalContact(ACCOUNT.value, ID, values = listOf(moved))))))
        assertNull(overlays.wireProjection(before))
    }

    @Test fun anUnchangedCodecLineAllowsAcceptedFallbackEncodingAndASubsequentIndependentEdit() = inDatabase { database ->
        val original = codecContact(listOf("source-a" to "a@example.test", "source-b" to "b@example.test"))
        insert(database, original)
        val withheldId = original.valuesOf(ContactValueKind.EMAIL).single { it.metadata[PROTON_EMAIL_ID_KEY] == "source-a" }.id
        val repository = RoomContactRepository(database)
        val saved = (repository.saveContact(original.copy(values = original.values.map {
            if (it.id == withheldId) it.copy(value = "unfinished-a") else it
        })) as SaveResult.Saved).value
        val overlays = RoomContactUploadOverlays(database)
        assertTrue(overlays.refreshAccepted(saved, original))
        val codec = ProtonContactVCardCodec()
        val wire = codec.encode(overlays.wireProjection(saved)!!)
        assertTrue(wire.signed.contains("a@example.test"))
        assertTrue(wire.signed.contains("b@example.test"))
        assertFalse(wire.signed.contains("unfinished-a"))
        val edited = (repository.saveContact(saved.copy(lastName = "Independent edit")) as SaveResult.Saved).value
        assertTrue(overlays.refreshAccepted(edited, original))
        val nextWire = codec.encode(overlays.wireProjection(edited)!!)
        assertTrue(nextWire.signed.contains("a@example.test"))
        assertFalse(nextWire.signed.contains("unfinished-a"))
        assertEquals("unfinished-a", database.contactDao().get(ACCOUNT.value, ID)!!.toDomain().values.single { it.id == withheldId }.value)
    }

    @Test fun aNewInvalidInputCannotConsumeAnUnboundRemoteEmailOnParserIdCollision() = inDatabase { database ->
        val remote = codecContact(listOf("source-b" to "b@example.test"))
        val collidingId = remote.valuesOf(ContactValueKind.EMAIL).single().id
        val original = seeded().copy(values = seeded().values.filterNot { it.kind == ContactValueKind.EMAIL })
        insert(database, original)
        val saved = (RoomContactRepository(database).saveContact(original.copy(values = original.values +
            ContactValue(collidingId, ContactValueKind.EMAIL, "unfinished-new", order = 0))) as SaveResult.Saved).value
        val guarded = runCatching { RoomContactUploadOverlays(database).overlayRemote(saved, remote) }
        assertTrue(guarded.exceptionOrNull() is LocalReconciliationInvariantFailure)
        val retained = database.contactDao().get(ACCOUNT.value, ID)!!.toDomain()
        assertEquals("unfinished-new", retained.values.single { it.id == collidingId }.value)
        assertNull(retained.values.single { it.id == collidingId }.metadata[PROTON_EMAIL_ID_KEY])
        assertEquals("b@example.test", remote.valuesOf(ContactValueKind.EMAIL).single().value)
        assertEquals("source-b", remote.valuesOf(ContactValueKind.EMAIL).single().metadata[PROTON_EMAIL_ID_KEY])
        assertEquals(1, database.contactEmailUploadOverlayDao().getForContact(ACCOUNT.value, ID).size)
    }

    private fun codecContact(emails: List<Pair<String, String>>): CanonicalContact {
        val codec = ProtonContactVCardCodec()
        val source = CanonicalContact(ACCOUNT.value, ID, displayName = "Codec fixture", values = emails.mapIndexed { index, (_, email) ->
            ContactValue("wire-$index", ContactValueKind.EMAIL, email, order = index)
        })
        val encoded = codec.encode(source)
        val user = me.proton.core.domain.entity.UserId("synthetic-user")
        val contactId = me.proton.core.contact.domain.entity.ContactId(REMOTE)
        val contact = me.proton.core.contact.domain.entity.Contact(user, contactId, "Codec fixture", emails.mapIndexed { index, (token, email) ->
            me.proton.core.contact.domain.entity.ContactEmail(userId = user,
                id = me.proton.core.contact.domain.entity.ContactEmailId(token), name = "Codec fixture", email = email,
                defaults = 1, order = index, contactId = contactId, canonicalEmail = null,
                labelIds = emptyList(), isProton = false, lastUsedTime = 0)
        })
        return codec.decode(ACCOUNT.value, contact, listOf(
            ProtonPlainContactCard(me.proton.core.contact.domain.entity.ContactCardType.Signed, encoded.signed),
            ProtonPlainContactCard(me.proton.core.contact.domain.entity.ContactCardType.EncryptedAndSigned, encoded.encryptedPrivate)))
            .copy(id = ID, revision = 1, remoteContactId = REMOTE, remoteVersion = "remote-v1")
    }

    private fun command(contact: CanonicalContact) = DurableMutationCommand(ACCOUNT.value, "CONTACT", ID, contact.revision,
        RemoteMutationOperation.UPDATE, 0, 0, false, expectedRemoteIdentity = REMOTE, expectedRemoteVersion = "remote-v1")

    private suspend fun acknowledgedPartial(database: ContakoDatabase): CanonicalContact {
        val original = seeded()
        insert(database, original)
        val saved = (RoomContactRepository(database).saveContact(original.copy(values = original.values.map {
            if (it.id == "email-one") it.copy(value = "unfinished") else it
        })) as SaveResult.Saved).value
        val execution = RoomMutationExecutionStore(database)
        val command = command(saved)
        assertTrue(execution.claim(command, Long.MAX_VALUE))
        assertTrue(execution.acknowledgeAndFinish(command, RemoteMutationAcknowledgement(REMOTE, "remote-v2",
            emailIdsByValueId = mapOf("email-one" to "remote-email-one", "email-two" to "remote-email-two"))))
        assertNull(database.outboxDao().get(ACCOUNT.value, "CONTACT", ID))
        return database.contactDao().get(ACCOUNT.value, ID)!!.toDomain()
    }

    private suspend fun insert(database: ContakoDatabase, contact: CanonicalContact) {
        database.contactDao().upsert(contact.toEntity())
        database.contactDao().upsertValues(contact.values.map { it.toEntity(contact) })
    }
    private fun seeded() = CanonicalContact(ACCOUNT.value, ID, displayName = "Partial fixture", revision = 1,
        remoteContactId = REMOTE, remoteVersion = "remote-v1", values = listOf(
            ContactValue("email-one", ContactValueKind.EMAIL, "duplicate@example.test", "home", 0,
                metadata = mapOf(PROTON_EMAIL_ID_KEY to "remote-email-one", PROTON_GROUP_IDS_KEY to "groups-a,groups-b")),
            ContactValue("email-two", ContactValueKind.EMAIL, "duplicate@example.test", "work", 1,
                metadata = mapOf(PROTON_EMAIL_ID_KEY to "remote-email-two", PROTON_GROUP_IDS_KEY to "groups-c")),
            ContactValue("note", ContactValueKind.NOTE, "Before", order = 0)))
    private fun inDatabase(block: suspend (ContakoDatabase) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB)
        val database = ContakoDatabase.create(context, DB)
        try { block(database) } finally { database.close(); context.deleteDatabase(DB) }
    }
    companion object {
        private const val DB = "synthetic-partial-email-upload.db"
        private val ACCOUNT = AccountScope("synthetic-partial")
        private const val ID = "partial-fixture"
        private const val REMOTE = "remote-fixture"
    }
}
