package com.patmanak.contako.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidNetworkStateProviderDeviceTest {
    @Test
    fun readsFrameworkNetworkCapabilitiesWithoutAdditionalPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(AndroidNetworkStateProvider(context).current())
    }
}
