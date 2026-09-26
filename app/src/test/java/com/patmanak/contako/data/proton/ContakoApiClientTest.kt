package com.patmanak.contako.data.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContakoApiClientTest {
    @Test
    fun identityIsHonestAndUsesTheRealReleaseVersion() {
        val client = ContakoApiClient.forReleaseVersion("0.8.0")

        assertEquals("Other_0.8.0+contako", client.appVersionHeader)
        assertEquals("Contako/0.8.0 (Android)", client.userAgent)
        assertFalse(client.enableDebugLogging)
        assertFalse(client.appVersionHeader.contains("android-mail@"))
        assertTrue(client.appVersionHeader.endsWith("+contako"))
        assertFalse(client.userAgent.contains("ProtonMailAndroid/"))
        assertFalse(client.appVersionHeader.contains("2026.8.1"))
    }

    @Test
    fun thirdPartyValidatorAcceptsOnlyTheFrozenContakoForm() {
        val validator = ContakoClientVersionValidator.forReleaseVersion("0.8.0")

        assertTrue(validator.validate("Other_0.8.0+contako"))
        listOf(
            null,
            "Other_0.8.0",
            "Other_0.8.0+another-app",
            "android-mail-contako@0.8.0",
            "android-mail@0.8.0",
            "Other_2026.8.1+contako",
            "Other_0.8.0+contako+extra",
            "Other_0.8+contako",
        ).forEach { identity ->
            assertFalse("Identity must be rejected: $identity", validator.validate(identity))
        }
    }

    @Test
    fun decoratedOrMalformedVersionsAreRejected() {
        listOf("0.8.0-debug", "36.6.2.1", "v0.8.0").forEach { version ->
            val result = runCatching { ContakoApiClient.forReleaseVersion(version) }
            assertTrue("Version must be rejected: $version", result.isFailure)
        }
    }

    @Test
    fun forceUpdateDropsTheRemoteMessage() {
        var callbackCount = 0
        val client = ContakoApiClient.forReleaseVersion("0.8.0") { callbackCount++ }

        client.forceUpdate("synthetic remote text that must not survive")

        assertEquals(1, callbackCount)
        assertFalse(client.toString().contains("synthetic remote text"))
    }
}
