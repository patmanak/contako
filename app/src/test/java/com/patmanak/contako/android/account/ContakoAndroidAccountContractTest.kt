package com.patmanak.contako.android.account

import com.patmanak.contako.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ContakoAndroidAccountContractTest {
    @Test
    fun accountTypeTracksTheInstalledApplicationId() {
        assertEquals(BuildConfig.APPLICATION_ID, ContakoAndroidAccountContract.ACCOUNT_TYPE)
    }
}
