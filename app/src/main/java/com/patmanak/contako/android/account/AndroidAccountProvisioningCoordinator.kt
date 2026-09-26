package com.patmanak.contako.android.account

import android.accounts.Account
import com.patmanak.contako.data.android.AndroidAccountBindingResult
import com.patmanak.contako.data.android.RoomAndroidProjectionLedger
import com.patmanak.contako.data.gateway.AccountScope
import kotlinx.coroutines.CancellationException

/**
 * Outcome of provisioning the Android account for a connected Proton account.
 *
 * Provisioning is deliberately non-fatal: `D-062` keeps local and Proton work usable when Android
 * interoperability is degraded, so a failure here MUST NOT block sign-in.
 */
enum class AccountProvisioningResult {
    /** The Android account and its durable binding are present and syncable. */
    READY,

    /** The durable scope is already bound to a different Android account name. */
    CONFLICT,

    /** The platform refused to create the account, or the binding could not be committed. */
    UNAVAILABLE,
}

/** Framework surface, kept narrow so the coordinator stays unit-testable. */
interface AndroidAccountPlatform {
    fun existingAccountNames(): List<String>
    fun addAccount(account: Account): Boolean
    fun enableContactsSync(account: Account)

    /**
     * Writes the account-scoped provider-epoch proof required by `D-081`.
     *
     * A freshly created account carries no `ContactsContract.SyncState`, which the runtime probe
     * reads as provider-state divergence. Seeding it at provisioning time is what lets the very
     * first pass reach `Ready` instead of scheduling a pointless reconstruction.
     */
    fun writeProviderEpochProof(androidAccountName: String, providerEpoch: Long): Boolean
}

/** Durable side of provisioning, narrowed to what this coordinator needs. */
internal interface AndroidAccountBindingStore {
    suspend fun ensureAccountRevision(scope: AccountScope): Long
    suspend fun bind(scope: AccountScope, expectedRevision: Long, androidAccountName: String): AndroidAccountBindingResult
    suspend fun providerEpoch(scope: AccountScope): Long?
}

internal class RoomAndroidAccountBindingStore(
    private val ledger: RoomAndroidProjectionLedger,
) : AndroidAccountBindingStore {
    override suspend fun ensureAccountRevision(scope: AccountScope): Long =
        ledger.ensureAccount(scope).revision

    override suspend fun bind(
        scope: AccountScope,
        expectedRevision: Long,
        androidAccountName: String,
    ): AndroidAccountBindingResult = ledger.bindAndroidAccountName(scope, expectedRevision, androidAccountName)

    override suspend fun providerEpoch(scope: AccountScope): Long? =
        ledger.loadAccount(scope)?.providerEpoch
}

/**
 * Creates the Android account that Android contacts interoperability depends on.
 *
 * Nothing created this account. `AndroidAccountRemovalCoordinator` removed it, the authenticator and
 * sync adapter were declared for it, and every provider read and write scoped its queries to it, but
 * no code path ever called `addAccountExplicitly`. Consequently `AndroidRuntimeAccountPreflight`
 * always resolved `DURABLE_ACCOUNT_CONTEXT_MISSING` or `ANDROID_ACCOUNT_NAME_UNBOUND`, every pass
 * degraded to action-required, and no contact could reach the provider or Proton.
 *
 * Per `D-081` the Android account name is the connected Proton account address.
 */
internal class AndroidAccountProvisioningCoordinator(
    private val bindingStore: AndroidAccountBindingStore,
    private val platform: AndroidAccountPlatform,
) {
    suspend fun provision(scope: AccountScope, protonAccountAddress: String): AccountProvisioningResult {
        if (protonAccountAddress.isBlank() || protonAccountAddress.length > MAX_ACCOUNT_NAME_LENGTH) {
            return AccountProvisioningResult.UNAVAILABLE
        }
        val androidAccount = Account(protonAccountAddress, ContakoAndroidAccountContract.ACCOUNT_TYPE)

        // The durable row must exist before binding so the CAS below has a revision to check.
        val expectedRevision = try {
            bindingStore.ensureAccountRevision(scope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AccountProvisioningResult.UNAVAILABLE
        }

        // Establish ownership before any platform side effect. In particular, a newly selected
        // display address must not create an orphan account when the scope is already bound.
        // If Android subsequently refuses creation, the durable binding makes a retry repairable.
        val bound = try {
            bindingStore.bind(scope, expectedRevision, protonAccountAddress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AccountProvisioningResult.UNAVAILABLE
        }

        return when (bound) {
            is AndroidAccountBindingResult.Bound,
            is AndroidAccountBindingResult.AlreadyBound,
            -> {
                val alreadyPresent = protonAccountAddress in platform.existingAccountNames()
                if (!alreadyPresent && !platform.addAccount(androidAccount)) {
                    return AccountProvisioningResult.UNAVAILABLE
                }
                platform.enableContactsSync(androidAccount)
                // Seed the provider-epoch proof so the first probe sees Ready rather than
                // divergence. Best-effort: a failure only defers to normal repair.
                val epoch = try {
                    bindingStore.providerEpoch(scope)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (epoch != null) {
                    runCatching { platform.writeProviderEpochProof(protonAccountAddress, epoch) }
                }
                AccountProvisioningResult.READY
            }
            // Another Proton account owns this Android name; never silently rebind it.
            AndroidAccountBindingResult.Conflict -> AccountProvisioningResult.CONFLICT
            // Lost the revision race. The next pass re-runs preflight and repairs.
            AndroidAccountBindingResult.Stale -> AccountProvisioningResult.UNAVAILABLE
        }
    }

    private companion object {
        const val MAX_ACCOUNT_NAME_LENGTH = 512
    }
}

// The framework implementation lives in data/android/provider, which is the only package allowed to
// touch ContentResolver. See FrameworkAndroidAccountPlatform there.
