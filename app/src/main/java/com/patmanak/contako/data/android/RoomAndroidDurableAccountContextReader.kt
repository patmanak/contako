package com.patmanak.contako.data.android

import androidx.room.withTransaction
import com.patmanak.contako.data.gateway.AccountScope
import com.patmanak.contako.data.local.ContakoDatabase

internal class RoomAndroidDurableAccountContextReader(
    private val database: ContakoDatabase,
) : AndroidDurableAccountContextReader {
    override suspend fun load(account: AccountScope): AndroidDurableAccountContext? =
        database.withTransaction {
            val entity = database.androidProjectionLedgerDao().getAccount(account.value)
                ?: return@withTransaction null
            val androidAccountName = entity.androidAccountName
            AndroidDurableAccountContext(
                account = account,
                androidAccountName = androidAccountName,
                accountRevision = entity.revision,
                providerEpoch = entity.providerEpoch,
                hasCrossAccountBinding = androidAccountName != null &&
                    database.androidProjectionLedgerDao().countOtherAccountsBoundToAndroidName(
                        account.value,
                        androidAccountName,
                    ) > 0,
            )
        }
}
