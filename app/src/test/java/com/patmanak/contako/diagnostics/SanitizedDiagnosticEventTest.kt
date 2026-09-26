package com.patmanak.contako.diagnostics

import com.patmanak.contako.data.proton.GateCAuthDiagnosticClass
import com.patmanak.contako.data.proton.GateCAuthDiagnosticEvent
import com.patmanak.contako.data.proton.GateCAuthPhase
import com.patmanak.contako.data.sync.RemoteContactActionRequiredBoundary
import com.patmanak.contako.data.sync.SyncHealthState
import com.patmanak.contako.data.sync.SyncPassStage
import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

class SanitizedDiagnosticEventTest {
    @Test fun compiledEventSchemaCannotCarryFreeTextOrPayloadObjects() {
        val events = SanitizedDiagnosticEvent::class.java.declaredClasses
            .filter { SanitizedDiagnosticEvent::class.java.isAssignableFrom(it) }
        assertEquals(25, events.size)
        fun checkFields(type: Class<*>) {
            type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
                val leaf = field.type
                if (leaf == GateCAuthDiagnosticEvent::class.java ||
                    leaf == com.patmanak.contako.data.android.provider.AndroidBindingRecoveryDiagnostic::class.java
                ) checkFields(leaf)
                else assertTrue("Unreviewed diagnostic field ${type.simpleName}.${field.name}: $leaf",
                    leaf.isEnum || leaf in setOf(
                        Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                        Int::class.javaObjectType,
                    ))
            }
        }
        events.forEach(::checkFields)
        val write = SanitizedDiagnosticLog::class.java.declaredMethods.single { it.name == "write" }
        assertArrayEquals(arrayOf(SanitizedDiagnosticEvent::class.java), write.parameterTypes)
    }

    @Test fun approvedOperatorMessagesRetainTheirFormat() {
        val recovery = SanitizedDiagnosticEvent.ProjectionBindingRecoveryFailure(
            com.patmanak.contako.data.sync.AndroidProjectionDecodeStage.POST_WRITE,
            com.patmanak.contako.data.android.provider.AndroidBindingRecoveryDiagnostic(
                com.patmanak.contako.data.android.provider.AndroidBindingRecoveryReason.PROJECTION_MISMATCH,
                kind = com.patmanak.contako.data.android.mapping.AndroidRowKind.POSTAL_ADDRESS,
                mismatch = com.patmanak.contako.data.sync.AndroidProjectionRepairCategory.POST_WRITE_CONTACT_COMPONENTS,
                component = com.patmanak.contako.data.android.mapping.AndroidComponent.LOCALITY,
                difference = com.patmanak.contako.data.sync.AndroidComponentDifference.DIFFERENT,
            ),
        )
        assertEquals("PROJECTION_BINDING_RECOVERY=PROJECTION_MISMATCH STAGE=POST_WRITE KIND=POSTAL_ADDRESS CODEC=null " +
            "MISMATCH=POST_WRITE_CONTACT_COMPONENTS COMPONENT=LOCALITY DIFFERENCE=DIFFERENT", renderDiagnostic(recovery))
        assertTrue(isSyncDiagnostic(recovery))
        val rowFailure = SanitizedDiagnosticEvent.ProjectionRowFailure(
            com.patmanak.contako.data.sync.AndroidProjectionDecodeStage.POST_WRITE,
            com.patmanak.contako.data.android.provider.AndroidProviderRowCodecFailure.IDENTITY_BINDING_DIVERGENCE,
            com.patmanak.contako.data.android.provider.AndroidProviderIdentityFailure.CLAIM_ATTACHED_ELSEWHERE,
            com.patmanak.contako.data.android.mapping.AndroidRowKind.PHOTO,
        )
        assertEquals("PROJECTION_ROW_FAILURE=IDENTITY_BINDING_DIVERGENCE STAGE=POST_WRITE " +
            "IDENTITY=CLAIM_ATTACHED_ELSEWHERE KIND=PHOTO", renderDiagnostic(rowFailure))
        assertEquals("PROJECTION_ROW_FAILURE=IDENTITY_BINDING_DIVERGENCE STAGE=POST_WRITE " +
            "IDENTITY=null KIND=null", renderDiagnostic(rowFailure.copy(identity = null, kind = null)))
        assertTrue(isSyncDiagnostic(rowFailure))
        val update = SanitizedDiagnosticEvent.ContactUpdateFailure(
            com.patmanak.contako.data.proton.ProtonContactUpdateStage.ENCODE,
            com.patmanak.contako.data.gateway.GatewayFailureCategory.VALIDATION_REJECTED,
            com.patmanak.contako.data.proton.ProtonContactEncodingStage.FIELDS,
            com.patmanak.contako.domain.model.ContactValueKind.NOTE,
        )
        assertEquals("CONTACT_UPDATE_FAILURE=ENCODE CATEGORY=VALIDATION_REJECTED ENCODING=FIELDS FIELD=NOTE",
            renderDiagnostic(update))
        assertTrue(isSyncDiagnostic(update))
        assertEquals("AUTH_STAGE=BEGIN_LOGIN", renderDiagnostic(SanitizedDiagnosticEvent.AuthPhase(GateCAuthPhase.BEGIN_LOGIN)))
        assertEquals("AUTH_ERROR=API_HTTP HTTP=401 CODE=8002", renderDiagnostic(SanitizedDiagnosticEvent.AuthFailure(
            GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.API_HTTP, 401, 8002))))
        assertEquals("SYNC_STAGE=COMPLETE", renderDiagnostic(SanitizedDiagnosticEvent.SyncStage(SyncPassStage.COMPLETE)))
        assertEquals("CONTACT_ERROR=HYDRATION CATEGORY=null HYDRATION=null", renderDiagnostic(
            SanitizedDiagnosticEvent.ContactFailure(RemoteContactActionRequiredBoundary.HYDRATION, null, null)))
        assertEquals("SYNC_STATUS=IDLE REASON=null PENDING=0 ACTIONS=0 CONTACTS=300", renderDiagnostic(
            SanitizedDiagnosticEvent.SyncStatus(SyncHealthState.IDLE, null, 0, 0, 300)))
        assertEquals("CARD_TYPE=3 SIGNATURE_EMPTY=false VERIFY_KEYS=2 ACTIVE_VERIFIERS=1 TIME_INDEPENDENT_PROOF=false EXACT_BYTE_PROOF=true",
            renderDiagnostic(SanitizedDiagnosticEvent.SignatureCheck(DiagnosticCardType.ENCRYPTED_AND_SIGNED, false, 2, 1, false, true)))
    }

    @Test fun malformedCountsAreClampedAndAuthCodeBoundsRemainEnforced() {
        assertEquals("SYNC_STATUS=IDLE REASON=null PENDING=0 ACTIONS=0 CONTACTS=0", renderDiagnostic(
            SanitizedDiagnosticEvent.SyncStatus(SyncHealthState.IDLE, null, -1, Int.MIN_VALUE, Long.MIN_VALUE)))
        assertThrows(IllegalArgumentException::class.java) { GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.API_HTTP, 600, null) }
        assertThrows(IllegalArgumentException::class.java) { GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.API_HTTP, 200, 1_000_000) }
    }

    @Test fun ordinaryUnitTestBuildCannotReachAndroidLogSink() {
        assertFalse(com.patmanak.contako.BuildConfig.SANITIZED_DIAGNOSTICS)
        assertFalse(com.patmanak.contako.BuildConfig.SYNC_DIAGNOSTICS)
        // JVM Android Log stubs would throw if the sole sink were called.
        SanitizedDiagnosticLog.write(SanitizedDiagnosticEvent.AuthPhase(GateCAuthPhase.BEGIN_LOGIN))
        SanitizedDiagnosticLog.write(SanitizedDiagnosticEvent.SyncStage(SyncPassStage.REQUEST_GATE))
    }

    @Test fun syncOnlyDiagnosticsExcludeAuthenticationAndSignatureEvents() {
        assertFalse(isSyncDiagnostic(SanitizedDiagnosticEvent.AuthPhase(GateCAuthPhase.BEGIN_LOGIN)))
        assertFalse(isSyncDiagnostic(SanitizedDiagnosticEvent.AuthFailure(
            GateCAuthDiagnosticEvent(GateCAuthDiagnosticClass.API_HTTP, 401, 8002))))
        assertFalse(isSyncDiagnostic(SanitizedDiagnosticEvent.SignatureCheck(
            DiagnosticCardType.SIGNED, false, 1, 1, false, true)))
        assertTrue(isSyncDiagnostic(SanitizedDiagnosticEvent.SyncStage(SyncPassStage.REMOTE_CONTACTS)))
    }

    @Test fun projectionResultsAndExceptionCategoriesRemainReadableWithoutPayloads() {
        com.patmanak.contako.data.local.RoomAndroidUnifiedCommitReplanReason.entries.forEach { reason ->
            val event = SanitizedDiagnosticEvent.IngestCommitReplan(reason)
            assertEquals("INGEST_COMMIT_REPLAN=${reason.name}", renderDiagnostic(event))
            assertTrue(isSyncDiagnostic(event))
        }
        com.patmanak.contako.data.local.RoomAndroidMembershipLedgerStaleReason.entries.forEach { reason ->
            val event = SanitizedDiagnosticEvent.IngestMembershipStale(reason)
            assertEquals("INGEST_MEMBERSHIP_STALE=${reason.name}", renderDiagnostic(event))
            assertTrue(isSyncDiagnostic(event))
        }
        com.patmanak.contako.data.android.provider.AndroidPhotoProviderRepairCategory.entries.forEach { category ->
            val event = SanitizedDiagnosticEvent.PhotoWriteFailure(category)
            assertEquals("PHOTO_WRITE_FAILURE=${category.name}", renderDiagnostic(event))
            assertTrue(isSyncDiagnostic(event))
        }
        com.patmanak.contako.data.sync.AndroidExistingContactPlanRepairReason.entries.forEach { reason ->
            val event = SanitizedDiagnosticEvent.ExistingContactPlanFailure(reason)
            assertEquals("INGEST_PLAN_FAILURE=${reason.name}", renderDiagnostic(event))
            assertTrue(isSyncDiagnostic(event))
        }
        com.patmanak.contako.data.android.provider.AndroidProviderRowCodecFailure.entries.forEach { category ->
            val event = SanitizedDiagnosticEvent.IngestRowCodecFailure(category)
            assertEquals("INGEST_ROW_FAILURE=${category.name}", renderDiagnostic(event))
            assertTrue(isSyncDiagnostic(event))
        }
        val baselineEvent = SanitizedDiagnosticEvent.IngestBaselineMismatch(
            com.patmanak.contako.data.sync.AndroidProjectionRepairCategory.POST_WRITE_CONTACT_COMPONENTS,
            com.patmanak.contako.data.android.mapping.AndroidRowKind.STRUCTURED_NAME,
            com.patmanak.contako.data.android.mapping.AndroidComponent.GIVEN_NAME,
            com.patmanak.contako.data.sync.AndroidComponentDifference.EXPECTED_EMPTY,
        )
        assertTrue(isSyncDiagnostic(baselineEvent))
        assertEquals("INGEST_BASELINE_MISMATCH=POST_WRITE_CONTACT_COMPONENTS KIND=STRUCTURED_NAME " +
            "COMPONENT=GIVEN_NAME DIFFERENCE=EXPECTED_EMPTY", renderDiagnostic(baselineEvent))
        assertEquals("PROJECTION_COMPONENT=GIVEN_NAME KIND=STRUCTURED_NAME DIFFERENCE=EXPECTED_EMPTY", renderDiagnostic(
            SanitizedDiagnosticEvent.ProjectionComponent(
                com.patmanak.contako.data.android.mapping.AndroidRowKind.STRUCTURED_NAME,
                com.patmanak.contako.data.android.mapping.AndroidComponent.GIVEN_NAME,
                com.patmanak.contako.data.sync.AndroidComponentDifference.EXPECTED_EMPTY)))
        assertEquals("PROJECTION_REPAIR=POST_WRITE_CONTACT_FLAGS_EMAIL_PRIMARY", renderDiagnostic(
            SanitizedDiagnosticEvent.ProjectionRepair(
                com.patmanak.contako.data.sync.AndroidProjectionRepairCategory.POST_WRITE_CONTACT_FLAGS_EMAIL_PRIMARY)))
        assertEquals("ANDROID_STAGE=ANDROID_FINAL_PROJECTION RESULT=LOCAL_PERSISTENCE_FAILURE", renderDiagnostic(
            SanitizedDiagnosticEvent.AndroidResult(SyncPassStage.ANDROID_FINAL_PROJECTION,
                DiagnosticAndroidResult.from(com.patmanak.contako.data.sync.AndroidInteroperabilityStageResult.LocalPersistenceFailure))))
        val privateError = IllegalStateException("PRIVATE_REMOTE_TEXT", RuntimeException("PRIVATE_CAUSE"))
        assertEquals("SYNC_EXCEPTION=INVALID_STATE", renderDiagnostic(SanitizedDiagnosticEvent.SyncException(
            com.patmanak.contako.data.sync.syncPassExceptionCategory(privateError))))
    }
}
