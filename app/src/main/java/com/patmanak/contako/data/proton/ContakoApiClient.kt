package com.patmanak.contako.data.proton

import me.proton.core.network.domain.ApiClient
import me.proton.core.network.domain.client.ClientVersionValidator

/**
 * Honest, non-official identity for the Proton Core compile surface.
 *
 * D-078 selects the anonymously accepted observed generic-client header and forbids official-product identities.
 */
class ContakoApiClient private constructor(
    releaseVersion: String,
    private val onForceUpdate: () -> Unit,
) : ApiClient {
    override val appVersionHeader: String = identityForReleaseVersion(releaseVersion)
    override val userAgent: String = "Contako/$releaseVersion (Android)"
    override val enableDebugLogging: Boolean = false

    override suspend fun shouldUseDoh(): Boolean = false

    override fun forceUpdate(errorMessage: String) {
        // The remote message is deliberately neither retained nor forwarded.
        onForceUpdate()
    }

    companion object {
        const val THIRD_PARTY_PRODUCT_PREFIX: String = "Other_"
        const val CONTAKO_METADATA: String = "+contako"
        private val RELEASE_VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

        internal fun identityForReleaseVersion(releaseVersion: String): String {
            require(RELEASE_VERSION.matches(releaseVersion))
            return "$THIRD_PARTY_PRODUCT_PREFIX$releaseVersion$CONTAKO_METADATA"
        }

        fun forReleaseVersion(
            releaseVersion: String,
            onForceUpdate: () -> Unit = {},
        ): ContakoApiClient {
            identityForReleaseVersion(releaseVersion)
            return ContakoApiClient(releaseVersion, onForceUpdate)
        }
    }
}

/**
 * Proton Core's stock validator accepts only Proton's `product@version` grammar. D-078 instead
 * selects Proton's accepted generic third-party form and keeps this exception deliberately exact.
 */
class ContakoClientVersionValidator private constructor(
    private val expectedIdentity: String,
) : ClientVersionValidator {
    override fun validate(versionName: String?): Boolean = versionName == expectedIdentity

    companion object {
        fun forReleaseVersion(releaseVersion: String): ContakoClientVersionValidator =
            ContakoClientVersionValidator(
                ContakoApiClient.identityForReleaseVersion(releaseVersion),
            )
    }
}
