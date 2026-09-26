package com.patmanak.contako.ui.auth

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationCapturePolicyTest {
    @Test
    fun `only secret bearing authentication destinations block capture`() {
        val protected = AuthenticationDestination.entries
            .filter(AuthenticationCapturePolicy::blocksCapture)
            .toSet()

        assertEquals(
            setOf(
                AuthenticationDestination.SIGN_IN,
                AuthenticationDestination.CODE,
                AuthenticationDestination.PASSWORD,
            ),
            protected,
        )
    }

    @Test
    fun `system back routes every transient challenge and limitation through cleanup`() {
        val cleanupDestinations = AuthenticationDestination.entries
            .filter(AuthenticationDestination::requiresAuthenticationBackCleanup)
            .toSet()

        assertEquals(
            setOf(
                AuthenticationDestination.CODE,
                AuthenticationDestination.PASSWORD,
                AuthenticationDestination.SECURITY_KEY_UNSUPPORTED,
                AuthenticationDestination.HUMAN_VERIFICATION_UNAVAILABLE,
            ),
            cleanupDestinations,
        )
        assertFalse(AuthenticationUiState(AuthenticationDestination.SIGN_IN).requiresAuthenticationBackCleanup())
        assertTrue(
            AuthenticationUiState(
                destination = AuthenticationDestination.SIGN_IN,
                isSubmitting = true,
            ).requiresAuthenticationBackCleanup(),
        )
    }

    @Test
    fun `authentication production UI has no saved state or logging surface`() {
        val sourceDirectory = projectFile("src/main/java/com/patmanak/contako/ui/auth")
        val source = sourceDirectory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        listOf(
            "rememberSaveable",
            "SavedStateHandle",
            "android.util.Log",
            "Timber.",
            "println(",
            "printStackTrace(",
        ).forEach { forbidden ->
            assertFalse("Forbidden auth UI surface: $forbidden", source.contains(forbidden))
        }
        assertTrue(
            "Every secret field must publish Android password semantics",
            source.contains("semantics { password() }"),
        )

        val adapter = projectFile(
            "src/main/java/com/patmanak/contako/data/proton/ProtonAuthenticationFlowAdapter.kt",
        ).readText()
        assertFalse(
            "Authentication boundaries must not downgrade fatal JVM errors",
            Regex("catch\\s*\\([^)]*Throwable").containsMatchIn(source + adapter),
        )
    }

    private fun projectFile(relativePath: String): File {
        val candidates = listOf(File(relativePath), File("app", relativePath), File("..", relativePath))
        return candidates.firstOrNull(File::exists) ?: error("Missing project file: $relativePath")
    }
}
