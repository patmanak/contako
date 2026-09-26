package com.patmanak.contako.android.account

import android.accounts.Account
import com.patmanak.contako.data.android.AndroidAccountBindingResult
import com.patmanak.contako.data.android.AndroidProjectionAccountSnapshot
import com.patmanak.contako.data.gateway.AccountScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import kotlinx.coroutines.CancellationException
import org.junit.Test

/**
 * Nothing provisioned the Android account before this coordinator existed, so the sync preflight
 * could never reach `Ready`. These cases pin the decisions that gate that state.
 *
 * `android.accounts.Account` is a non-functional stub on the JVM, so assertions here observe call
 * counts and results rather than account fields; the device suite covers the framework side.
 */
class AndroidAccountProvisioningCoordinatorTest {

    @Test
    fun anUnusableAddressIsRejectedBeforeAnyDurableOrPlatformAccess() = runTest {
        val store = RecordingStore()
        val platform = RecordingPlatform()
        val coordinator = AndroidAccountProvisioningCoordinator(store, platform)

        listOf("", "   ", "a".repeat(513)).forEach { address ->
            assertEquals(
                AccountProvisioningResult.UNAVAILABLE,
                coordinator.provision(SCOPE, address),
            )
        }

        assertEquals(0, store.ensureCalls)
        assertEquals(0, platform.existingQueries)
        assertEquals(0, platform.addCalls)
    }

    @Test
    fun provisioningBindsTheDurableScopeAndEnablesContactsSync() = runTest {
        val store = RecordingStore()
        val platform = RecordingPlatform()
        val coordinator = AndroidAccountProvisioningCoordinator(store, platform)

        val result = coordinator.provision(SCOPE, ADDRESS)

        assertEquals(AccountProvisioningResult.READY, result)
        assertEquals(1, platform.addCalls)
        assertEquals(1, platform.syncEnabledCalls)
        assertEquals(listOf(ADDRESS), store.boundNames)
        // Without this proof the runtime probe reports divergence and the first pass never runs.
        assertEquals(listOf(ADDRESS), platform.epochProofWrites)
    }

    @Test
    fun anExistingPlatformAccountIsReusedRatherThanRecreated() = runTest {
        val store = RecordingStore()
        val platform = RecordingPlatform(existing = mutableListOf(ADDRESS))
        val coordinator = AndroidAccountProvisioningCoordinator(store, platform)

        val result = coordinator.provision(SCOPE, ADDRESS)

        assertEquals(AccountProvisioningResult.READY, result)
        // Re-adding would be refused by the platform and would discard existing sync state.
        assertEquals(0, platform.addCalls)
        assertEquals(1, platform.syncEnabledCalls)
    }

    @Test
    fun anAlreadyBoundScopeStillConvergesToReady() = runTest {
        val store = RecordingStore(
            result = AndroidAccountBindingResult.AlreadyBound(SNAPSHOT),
        )
        val platform = RecordingPlatform(existing = mutableListOf(ADDRESS))
        val coordinator = AndroidAccountProvisioningCoordinator(store, platform)

        // Provisioning runs on every sign-in, so repeating it MUST be idempotent.
        assertEquals(AccountProvisioningResult.READY, coordinator.provision(SCOPE, ADDRESS))
        assertEquals(1, platform.syncEnabledCalls)
    }

    @Test
    fun aScopeBoundToAnotherAndroidNameReportsConflictAndDoesNotEnableSync() = runTest {
        val store = RecordingStore(result = AndroidAccountBindingResult.Conflict)
        val platform = RecordingPlatform()
        val coordinator = AndroidAccountProvisioningCoordinator(store, platform)

        assertEquals(AccountProvisioningResult.CONFLICT, coordinator.provision(SCOPE, ADDRESS))
        assertEquals(0, platform.addCalls)
        assertEquals(0, platform.existingQueries)
        assertEquals(0, platform.syncEnabledCalls)
    }

    @Test
    fun aLostRevisionRaceReportsUnavailableWithoutEnablingSync() = runTest {
        val store = RecordingStore(result = AndroidAccountBindingResult.Stale)
        val platform = RecordingPlatform()
        val coordinator = AndroidAccountProvisioningCoordinator(store, platform)

        assertEquals(AccountProvisioningResult.UNAVAILABLE, coordinator.provision(SCOPE, ADDRESS))
        assertEquals(0, platform.addCalls)
        assertEquals(0, platform.syncEnabledCalls)
    }

    @Test
    fun aPlatformRefusalRetainsTheDurableBindingForRepair() = runTest {
        val store = RecordingStore()
        val platform = RecordingPlatform(acceptAdd = false)
        val coordinator = AndroidAccountProvisioningCoordinator(store, platform)

        assertEquals(AccountProvisioningResult.UNAVAILABLE, coordinator.provision(SCOPE, ADDRESS))
        assertEquals(listOf(ADDRESS), store.boundNames)
        assertEquals(0, platform.syncEnabledCalls)
    }

    @Test
    fun aDurableFailureIsReportedAsUnavailableRatherThanPropagated() = runTest {
        val store = RecordingStore(ensureFailure = IllegalStateException("db"))
        val platform = RecordingPlatform()
        val coordinator = AndroidAccountProvisioningCoordinator(store, platform)

        // D-062 keeps local and Proton work usable, so provisioning MUST fail soft.
        assertEquals(AccountProvisioningResult.UNAVAILABLE, coordinator.provision(SCOPE, ADDRESS))
        assertEquals(0, platform.addCalls)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedIntoAProvisioningFailure() = runTest {
        val store = RecordingStore(ensureFailure = CancellationException())
        AndroidAccountProvisioningCoordinator(store, RecordingPlatform()).provision(SCOPE, ADDRESS)
    }

    private class RecordingStore(
        private val result: AndroidAccountBindingResult = AndroidAccountBindingResult.Bound(SNAPSHOT),
        private val ensureFailure: Throwable? = null,
    ) : AndroidAccountBindingStore {
        var ensureCalls = 0
        val boundNames = mutableListOf<String>()

        override suspend fun ensureAccountRevision(scope: AccountScope): Long {
            ensureCalls++
            ensureFailure?.let { throw it }
            return SNAPSHOT.revision
        }

        override suspend fun bind(
            scope: AccountScope,
            expectedRevision: Long,
            androidAccountName: String,
        ): AndroidAccountBindingResult {
            boundNames += androidAccountName
            return result
        }

        override suspend fun providerEpoch(scope: AccountScope): Long = SNAPSHOT.providerEpoch
    }

    private class RecordingPlatform(
        private val existing: MutableList<String> = mutableListOf(),
        private val acceptAdd: Boolean = true,
    ) : AndroidAccountPlatform {
        var existingQueries = 0
        var addCalls = 0
        var syncEnabledCalls = 0
        val epochProofWrites = mutableListOf<String>()

        override fun existingAccountNames(): List<String> {
            existingQueries++
            return existing.toList()
        }

        override fun addAccount(account: Account): Boolean {
            addCalls++
            return acceptAdd
        }

        override fun enableContactsSync(account: Account) {
            syncEnabledCalls++
        }

        override fun writeProviderEpochProof(androidAccountName: String, providerEpoch: Long): Boolean {
            epochProofWrites += androidAccountName
            return true
        }
    }

    private companion object {
        const val ADDRESS = "person@proton.me"
        val SCOPE = AccountScope("primary")
        val SNAPSHOT = AndroidProjectionAccountSnapshot(revision = 0, providerEpoch = 0)
    }
}
