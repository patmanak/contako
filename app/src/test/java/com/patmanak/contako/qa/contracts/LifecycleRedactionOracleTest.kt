package com.patmanak.contako.qa.contracts

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executable lifecycle, cleanup, screen, and evidence-redaction contracts. */
class LifecycleRedactionOracleTest {
    @Test
    fun concurrentRefreshIsCoalescedAndRevokedStateRequiresAction() = runTest {
        val session = ReferenceSessionLifecycle()

        val outcomes = List(8) { async { session.refresh() } }.awaitAll()

        assertEquals(List(8) { SessionOracleState.READY }, outcomes)
        assertEquals(1, session.remoteRefreshCount.get())
        session.revoke()
        assertEquals(SessionOracleState.ACTION_REQUIRED, session.restore())
    }

    @Test
    fun passwordNeverSurvivesUnlockAndCorruptKeyContextBlocksHeadlessCrypto() {
        val lifecycle = ReferenceKeyLifecycle()
        val password = "fixture-password".toCharArray()

        lifecycle.unlock(password)

        assertTrue(password.all { it == '\u0000' })
        assertEquals(KeyOracleState.UNLOCKED, lifecycle.state)
        assertEquals(setOf("derived-passphrase"), lifecycle.persistedFieldNames)

        lifecycle.processDeath()
        assertEquals(KeyOracleState.UNLOCKED, lifecycle.state)
        lifecycle.invalidateKeyStore()
        assertEquals(KeyOracleState.ACTION_REQUIRED, lifecycle.headlessCryptoState())
        assertEquals(0, lifecycle.canonicalMutations)
        assertEquals(0, lifecycle.remoteMutations)
    }

    @Test
    fun rebootBeforeFirstUnlockBlocksWhileRebootAfterUnlockRestoresDerivedContext() {
        val beforeFirstUnlock = ReferenceKeyLifecycle()
        beforeFirstUnlock.reboot()
        assertEquals(KeyOracleState.ACTION_REQUIRED, beforeFirstUnlock.headlessCryptoState())

        val afterUnlock = ReferenceKeyLifecycle()
        afterUnlock.unlock("fixture".toCharArray())
        afterUnlock.reboot()
        assertEquals(KeyOracleState.UNLOCKED, afterUnlock.headlessCryptoState())

        afterUnlock.signOut()
        assertEquals(KeyOracleState.ABSENT, afterUnlock.state)
        assertTrue(afterUnlock.persistedFieldNames.isEmpty())
    }

    @Test
    fun campaignCleanupIsScopedAndIdempotent() {
        val state = CampaignState(
            remoteRecords = mutableSetOf("owned-contact", "owned-group", "unprefixed-existing"),
            localRecords = mutableSetOf("owned-contact", "owned-group", "unprefixed-existing"),
            sessionPresent = true,
            keyContextPresent = true,
        )
        val cleanup = ReferenceCampaignCleanup(setOf("owned-contact", "owned-group"))

        val first = cleanup.sweep(state)
        val second = cleanup.sweep(state)

        assertEquals(CleanupCounts(remoteDeletes = 2, localDeletes = 2, sessionRevokes = 1, keyClears = 1), first)
        assertEquals(CleanupCounts(), second)
        assertEquals(setOf("unprefixed-existing"), state.remoteRecords)
        assertEquals(setOf("unprefixed-existing"), state.localRecords)
    }

    @Test
    fun secretBearingScreensAreSecureAndOrdinaryContactScreensRemainCapturable() {
        val expected = mapOf(
            ScreenClass.PASSWORD to true,
            ScreenClass.TOTP to true,
            ScreenClass.RECOVERY to true,
            ScreenClass.HUMAN_VERIFICATION to true,
            ScreenClass.CONTACT_LIST to false,
            ScreenClass.CONTACT_DETAIL to false,
        )

        assertEquals(expected, ScreenClass.entries.associateWith(ScreenSecurityOracle::requiresSecureWindow))
    }

    @Test
    fun sanitizedEvidenceContainsOnlyClosedStatesCountsAndHashes() {
        val forbiddenCanary = "fixture-secret-canary"
        val evidence = SanitizedEvidence(
            result = ClosedResult.FAIL,
            failure = ClosedFailure.TIMEOUT,
            requestCount = 0,
            cleanupCount = 0,
            artifactHash = "A".repeat(64),
        )
        val rendered = evidence.toString()
        val fieldNames = SanitizedEvidence::class.java.declaredFields.map { it.name }.toSet()

        assertFalse(rendered.contains(forbiddenCanary))
        assertFalse(rendered.contains("message", ignoreCase = true))
        assertFalse(rendered.contains("path", ignoreCase = true))
        assertFalse(rendered.contains("token", ignoreCase = true))
        assertEquals(
            setOf("result", "failure", "requestCount", "cleanupCount", "artifactHash"),
            fieldNames,
        )
        assertEquals(
            "SanitizedEvidence(result=FAIL, failure=TIMEOUT, requestCount=0, cleanupCount=0, artifactHash=${"A".repeat(64)})",
            rendered,
        )
    }
}

private enum class SessionOracleState { READY, ACTION_REQUIRED }

private class ReferenceSessionLifecycle {
    val remoteRefreshCount = AtomicInteger(0)
    private val refreshLock = Mutex()
    private var revoked = false
    private var lastRefreshSucceeded = false

    suspend fun refresh(): SessionOracleState = refreshLock.withLock {
        if (revoked) return@withLock SessionOracleState.ACTION_REQUIRED
        if (!lastRefreshSucceeded) {
            remoteRefreshCount.incrementAndGet()
            delay(1)
            lastRefreshSucceeded = true
        }
        SessionOracleState.READY
    }

    fun revoke() {
        revoked = true
        lastRefreshSucceeded = false
    }

    fun restore(): SessionOracleState =
        if (revoked) SessionOracleState.ACTION_REQUIRED else SessionOracleState.READY
}

private enum class KeyOracleState { ABSENT, LOCKED, UNLOCKED, ACTION_REQUIRED }

private class ReferenceKeyLifecycle {
    var state: KeyOracleState = KeyOracleState.LOCKED
        private set
    val persistedFieldNames = mutableSetOf<String>()
    var canonicalMutations = 0
        private set
    var remoteMutations = 0
        private set
    private var keyStoreValid = true

    fun unlock(password: CharArray) {
        try {
            require(password.isNotEmpty())
            persistedFieldNames += "derived-passphrase"
            state = KeyOracleState.UNLOCKED
        } finally {
            password.fill('\u0000')
        }
    }

    fun processDeath() = Unit

    fun invalidateKeyStore() {
        keyStoreValid = false
        state = KeyOracleState.ACTION_REQUIRED
    }

    fun reboot() {
        state = if (persistedFieldNames == setOf("derived-passphrase") && keyStoreValid) {
            KeyOracleState.UNLOCKED
        } else {
            KeyOracleState.ACTION_REQUIRED
        }
    }

    fun headlessCryptoState(): KeyOracleState =
        if (keyStoreValid && state == KeyOracleState.UNLOCKED) KeyOracleState.UNLOCKED else KeyOracleState.ACTION_REQUIRED

    fun signOut() {
        persistedFieldNames.clear()
        state = KeyOracleState.ABSENT
    }
}

private data class CampaignState(
    val remoteRecords: MutableSet<String>,
    val localRecords: MutableSet<String>,
    var sessionPresent: Boolean,
    var keyContextPresent: Boolean,
)

private data class CleanupCounts(
    val remoteDeletes: Int = 0,
    val localDeletes: Int = 0,
    val sessionRevokes: Int = 0,
    val keyClears: Int = 0,
)

private class ReferenceCampaignCleanup(
    private val ownedRecordIds: Set<String>,
) {
    fun sweep(state: CampaignState): CleanupCounts {
        val remoteDeletes = ownedRecordIds.count(state.remoteRecords::remove)
        val localDeletes = ownedRecordIds.count(state.localRecords::remove)
        val sessionRevokes = if (state.sessionPresent) 1 else 0
        val keyClears = if (state.keyContextPresent) 1 else 0
        state.sessionPresent = false
        state.keyContextPresent = false
        return CleanupCounts(remoteDeletes, localDeletes, sessionRevokes, keyClears)
    }
}

private enum class ScreenClass {
    PASSWORD,
    TOTP,
    RECOVERY,
    HUMAN_VERIFICATION,
    CONTACT_LIST,
    CONTACT_DETAIL,
}

private object ScreenSecurityOracle {
    fun requiresSecureWindow(screen: ScreenClass): Boolean = screen in setOf(
        ScreenClass.PASSWORD,
        ScreenClass.TOTP,
        ScreenClass.RECOVERY,
        ScreenClass.HUMAN_VERIFICATION,
    )
}

private enum class ClosedResult { PASS, FAIL }
private enum class ClosedFailure { NONE, TIMEOUT, CANCELLED, VALIDATION }

private data class SanitizedEvidence(
    val result: ClosedResult,
    val failure: ClosedFailure,
    val requestCount: Int,
    val cleanupCount: Int,
    val artifactHash: String,
) {
    init {
        require(requestCount >= 0)
        require(cleanupCount >= 0)
        require(artifactHash.matches(Regex("[A-F0-9]{64}")))
    }
}
