package com.patmanak.contako.domain.policy

import com.patmanak.contako.domain.model.ContactValue
import com.patmanak.contako.domain.model.ContactValueKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PostalAddressPolicyTest {
    @Test
    fun `structured components are authoritative over a stale formatted value`() {
        val normalized = PostalAddressPolicy.normalize(
            ContactValue(
                id = "address",
                kind = ContactValueKind.POSTAL_ADDRESS,
                value = "stale",
                order = 0,
                components = mapOf(
                    PostalAddressPolicy.STREET to "1 Main Street",
                    PostalAddressPolicy.LOCALITY to "Paris",
                ),
            ),
        )

        assertEquals("1 Main Street, Paris", normalized.value)
        assertEquals("1 Main Street", normalized.components[PostalAddressPolicy.STREET])
    }

    @Test
    fun `legacy scalar becomes a deterministic street component`() {
        val normalized = PostalAddressPolicy.normalize(
            ContactValue(
                id = "address",
                kind = ContactValueKind.POSTAL_ADDRESS,
                value = "1 Legacy Street",
                order = 0,
            ),
        )

        assertEquals("1 Legacy Street", normalized.value)
        assertEquals("1 Legacy Street", normalized.components[PostalAddressPolicy.STREET])
    }
}
