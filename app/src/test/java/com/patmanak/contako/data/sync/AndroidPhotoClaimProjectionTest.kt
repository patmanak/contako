package com.patmanak.contako.data.sync

import com.patmanak.contako.data.android.AndroidProjectionFingerprint
import com.patmanak.contako.data.android.mapping.AndroidContactRow
import com.patmanak.contako.data.android.mapping.AndroidContactSnapshot
import com.patmanak.contako.data.android.mapping.AndroidProjectionPlan
import com.patmanak.contako.data.android.mapping.AndroidRowKind
import com.patmanak.contako.data.android.mapping.AndroidRowOperation
import com.patmanak.contako.data.android.mapping.AndroidValueIdentity
import com.patmanak.contako.data.android.provider.AndroidOwnedDataRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AndroidPhotoClaimProjectionTest {
    private val currentPhoto = AndroidContactRow(
        AndroidValueIdentity("photo-value", 12L), AndroidRowKind.PHOTO,
        order = 3, isPrimary = true, isSuperPrimary = true, binaryReference = "fixture-photo",
    )
    private val desiredPhoto = currentPhoto.withoutProviderLocator().copy(isSuperPrimary = false)
    private val decoded = AndroidContactSnapshot("contact", listOf(currentPhoto))
    private val plan = AndroidProjectionPlan(
        AndroidContactSnapshot("contact", listOf(desiredPhoto)),
        AndroidProjectionFingerprint("a".repeat(64)), emptyList(),
    )
    private val physical = AndroidOwnedDataRow(
        dataRowId = 12L, rawContactId = 7L, mimeType = "vnd.android.cursor.item/photo",
        canonicalValueId = null, canonicalOrder = null, linkedValueIdsEncoding = null,
        isPrimary = true, isSuperPrimary = true, stringSlots = List(14) { null },
        binarySlot = byteArrayOf(1, 2, 3),
    )

    @Test
    fun `missing physical claims require exact row update despite semantic equality`() {
        val result = apply(physical)
        val update = result.operations.single() as AndroidRowOperation.Update
        assertEquals(currentPhoto.identity, update.currentIdentity)
        assertEquals(desiredPhoto.copy(isSuperPrimary = true), update.desired)
        assertSame(plan.desired, result.desired)
        assertSame(plan.fingerprint, result.fingerprint)
        assertEquals(3, update.desired.order)
        assertEquals(true, update.desired.isPrimary)
        assertEquals("fixture-photo", update.desired.binaryReference)
    }

    @Test
    fun `either missing marker is materialized`() {
        assertEquals(1, apply(physical.copy(canonicalOrder = 3)).operations.size)
        assertEquals(1, apply(physical.copy(canonicalValueId = "photo-value")).operations.size)
    }

    @Test
    fun `complete claims keep the no-op plan`() {
        assertSame(plan, apply(physical.copy(canonicalValueId = "photo-value", canonicalOrder = 3)))
    }

    @Test
    fun `foreign nonempty claim and malformed order cannot be rebound`() {
        assertSame(plan, apply(physical.copy(canonicalValueId = "foreign-photo")))
        assertSame(plan, apply(physical.copy(canonicalValueId = "")))
        assertSame(plan, apply(physical.copy(canonicalOrderMalformed = true)))
    }

    @Test
    fun `durable decoded identity and exact scoped locator must agree`() {
        assertSame(plan, apply(physical.copy(dataRowId = 13L)))
        assertSame(plan, apply(physical.copy(rawContactId = 8L)))
        assertSame(plan, materializeMissingPhotoClaims(
            plan, decoded.copy(rows = listOf(currentPhoto.copy(identity = AndroidValueIdentity("other", 12L)))),
            7L, listOf(physical),
        ))
        assertSame(plan, materializeMissingPhotoClaims(
            plan, decoded.copy(canonicalContactId = "other-contact"), 7L, listOf(physical),
        ))
    }

    @Test
    fun `missing or ambiguous photo rows do not manufacture an update`() {
        assertSame(plan, materializeMissingPhotoClaims(plan, decoded, 7L, emptyList()))
        assertSame(plan, materializeMissingPhotoClaims(plan, decoded, 7L, listOf(physical, physical.copy(dataRowId = 13L))))
        val second = currentPhoto.copy(identity = AndroidValueIdentity("second-photo", 13L))
        assertSame(plan, materializeMissingPhotoClaims(plan, decoded.copy(rows = listOf(currentPhoto, second)), 7L, listOf(physical)))
        assertSame(plan, materializeMissingPhotoClaims(plan, decoded.copy(rows = emptyList()), 7L, listOf(physical)))
    }

    @Test
    fun `existing photo operations retain their original lifecycle without duplicate update`() {
        listOf(
            AndroidRowOperation.Update(currentPhoto.identity, desiredPhoto),
            AndroidRowOperation.Delete(currentPhoto.identity),
            AndroidRowOperation.Insert(desiredPhoto),
        ).forEach { operation ->
            val existing = plan.copy(operations = listOf(operation))
            assertSame(existing, materializeMissingPhotoClaims(existing, decoded, 7L, listOf(physical)))
        }
    }

    @Test
    fun `unrelated operations and opaque provider metadata are preserved`() {
        val opaque = physical.copy(dataRowId = 18L, mimeType = "vnd.sec.cursor.item/profile_card")
        val unrelated = AndroidRowOperation.Delete(AndroidValueIdentity("old-phone", 19L))
        val existing = plan.copy(operations = listOf(unrelated))
        val result = materializeMissingPhotoClaims(existing, decoded, 7L, listOf(physical, opaque))
        assertEquals(2, result.operations.size)
        assertSame(unrelated, result.operations.first())
        assertEquals(currentPhoto.identity, (result.operations.last() as AndroidRowOperation.Update).currentIdentity)
    }

    private fun apply(row: AndroidOwnedDataRow) =
        materializeMissingPhotoClaims(plan, decoded, 7L, listOf(row))
}
