package com.patmanak.contako.data.proton

import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.proton.core.network.domain.client.ClientId
import me.proton.core.network.domain.client.CookieSessionId
import me.proton.core.network.domain.humanverification.HumanVerificationAvailableMethods
import me.proton.core.network.domain.humanverification.HumanVerificationListener
import me.proton.core.network.domain.humanverification.HumanVerificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GateCInteractiveHumanVerificationTest {
    @Test
    fun `valid hosted result resumes core and keeps secrets out of ui state`() = runTest {
        val coordinator = GateCInteractiveHumanVerification()
        val clientId = cookieClient("synthetic-cookie")
        val result = async {
            coordinator.onHumanVerificationNeeded(
                clientId,
                HumanVerificationAvailableMethods(listOf("captcha"), "synthetic-start-token"),
            )
        }
        runCurrent()

        val ui = coordinator.uiState.value
        assertTrue(ui.isRequired)
        assertTrue(ui.generation > 0)
        assertFalse(ui.toString().contains("synthetic-start-token"))
        assertTrue(coordinator.acceptSolution(ui.generation, "captcha", "synthetic-proof"))
        assertFalse(coordinator.acceptSolution(ui.generation, "captcha", "replayed-proof"))
        assertTrue(result.await() === HumanVerificationListener.HumanVerificationResult.Success)

        val details = coordinator.getHumanVerificationDetails(clientId)
        assertEquals(HumanVerificationState.HumanVerificationSuccess, details?.state)
        assertEquals("captcha", details?.tokenType)
        assertEquals("synthetic-proof", details?.tokenCode)
        assertFalse(coordinator.uiState.value.isRequired)

        coordinator.clear()
        assertNull(coordinator.getHumanVerificationDetails(clientId))
    }

    @Test
    fun `cancel fails the suspended core request and clears all challenge state`() = runTest {
        val coordinator = GateCInteractiveHumanVerification()
        val clientId = cookieClient("cancel-cookie")
        val result = async {
            coordinator.onHumanVerificationNeeded(
                clientId,
                HumanVerificationAvailableMethods(listOf("captcha"), "cancel-token"),
            )
        }
        runCurrent()
        assertTrue(coordinator.uiState.value.isRequired)

        coordinator.cancel()

        assertTrue(result.await() === HumanVerificationListener.HumanVerificationResult.Failure)
        assertFalse(coordinator.uiState.value.isRequired)
        assertNull(coordinator.getHumanVerificationDetails(clientId))
    }

    @Test
    fun `wrong method or stale generation cannot forge a solved challenge`() = runTest {
        val coordinator = GateCInteractiveHumanVerification()
        val result = async {
            coordinator.onHumanVerificationNeeded(
                cookieClient("strict-cookie"),
                HumanVerificationAvailableMethods(listOf("captcha"), "strict-token"),
            )
        }
        runCurrent()
        val generation = coordinator.uiState.value.generation

        assertFalse(coordinator.acceptSolution(generation + 1, "captcha", "proof"))
        assertFalse(coordinator.acceptSolution(generation, "email", "proof"))
        assertFalse(coordinator.acceptSolution(generation, "captcha", ""))

        coordinator.cancel()
        assertTrue(result.await() === HumanVerificationListener.HumanVerificationResult.Failure)
    }

    @Test
    fun `hosted verifier receives opaque server methods and validates its known result`() = runTest {
        val coordinator = GateCInteractiveHumanVerification()
        val result = async {
            coordinator.onHumanVerificationNeeded(
                cookieClient("unknown-cookie"),
                HumanVerificationAvailableMethods(
                    listOf("captcha", "", "future-unknown-method"),
                    "",
                ),
            )
        }
        runCurrent()

        val generation = coordinator.uiState.value.generation
        assertFalse(coordinator.acceptSolution(generation, "future-unknown-method", "proof"))
        assertTrue(coordinator.acceptSolution(generation, "captcha", "proof"))
        assertTrue(result.await() === HumanVerificationListener.HumanVerificationResult.Success)
    }

    @Test fun failedRendererGenerationCannotAffectANewChallenge() = runTest {
        val coordinator = GateCInteractiveHumanVerification()
        val client = cookieClient("renderer-fixture")
        val first = async { coordinator.onHumanVerificationNeeded(client,
            HumanVerificationAvailableMethods(listOf("captcha"), "first")) }
        runCurrent()
        val old = coordinator.uiState.value.generation
        coordinator.failGeneration(old)
        assertFalse(coordinator.uiState.value.isRequired)
        assertTrue(first.await() === HumanVerificationListener.HumanVerificationResult.Failure)
        assertFalse(coordinator.acceptSolution(old, "captcha", "stale"))
        val next = async { coordinator.onHumanVerificationNeeded(client,
            HumanVerificationAvailableMethods(listOf("captcha"), "second")) }
        runCurrent()
        coordinator.failGeneration(old)
        assertTrue(coordinator.uiState.value.isRequired)
        assertTrue(coordinator.acceptSolution(coordinator.uiState.value.generation, "captcha", "current"))
        assertTrue(next.await() === HumanVerificationListener.HumanVerificationResult.Success)
    }

    private fun cookieClient(value: String) = ClientId.CookieSession(CookieSessionId(value))
}
