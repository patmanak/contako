package com.patmanak.contako.data.proton

import com.patmanak.contako.data.gateway.GatewayContactHydrationCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

class ProtonVCardUnfoldingTest {
    @Test
    fun highlyFoldedImageSizedPropertiesHaveLinearMeasuredWork() {
        // LOGO values have the same unfolding cost as PHOTO, without any provider/image decoding.
        // The deterministic work oracle detects quadratic copying without machine-speed limits.
        val allocationMeter = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
            ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
        unfoldBoundedVCard("NOTE:" + "A\r\n ".repeat(1_024)) // Warm the scanner, not the corpus.
        listOf(64 * 1_024, 256 * 1_024, 1_024 * 1_024).forEach { size ->
            val value = "A".repeat(size)
            val folded = "BEGIN:VCARD\r\nVERSION:4.0\r\nLOGO:" +
                value.chunked(16).joinToString("\r\n ") + "\r\nEND:VCARD\r\n"
            val threadId = Thread.currentThread().id
            val allocatedBefore = allocationMeter?.getThreadAllocatedBytes(threadId)
            val started = System.nanoTime()
            val actual = unfoldBoundedVCard(folded)
            val nanos = System.nanoTime() - started
            val allocatedBytes = allocatedBefore?.let { requireNotNull(allocationMeter).getThreadAllocatedBytes(threadId) - it }
            val compact = unfoldBoundedVCard("BEGIN:VCARD\r\nVERSION:4.0\r\nLOGO:$value\r\nEND:VCARD\r\n")
            assertEquals("Unfolding a $size-character property", compact.lines, actual.lines)
            assertEquals(folded.length, actual.scannedCharacters)
            assertEquals(compact.appendedCharacters, actual.appendedCharacters)
            assertEquals(size / 16 + 3, actual.physicalLines)
            assertTrue(actual.scannedCharacters + actual.appendedCharacters <= 2 * folded.length)
            allocatedBytes?.let {
                assertTrue("Unfolding must not allocate a growing copy for every continuation: $it",
                    it <= 12L * folded.length + 512 * 1_024)
            }
            // Timing is evidence only; correctness cannot depend on scheduler, JIT or host load.
            println("vcard_unfold synthetic_chars=$size scanned=${actual.scannedCharacters} " +
                "appended=${actual.appendedCharacters} physical_lines=${actual.physicalLines} " +
                "allocated_bytes=${allocatedBytes ?: "unavailable"} nanos=$nanos")
        }
    }

    @Test
    fun physicalLogicalCharacterAndUtf8ByteBudgetsRejectBeforeOvergrownOutput() {
        fun rejected(block: () -> Unit) {
            val error = runCatching(block).exceptionOrNull()
            assertTrue(error is ProtonVCardParseFailure)
            assertEquals(GatewayContactHydrationCategory.VCARD_PARSE_BOUNDS, (error as ProtonVCardParseFailure).category)
        }
        rejected { unfoldBoundedVCard("NOTE:a\r\n b\r\n c", maxPhysicalLines = 2) }
        rejected { unfoldBoundedVCard("\n".repeat(11), maxPhysicalLines = 10) }
        rejected { unfoldBoundedVCard("A\nB\nC", maxLogicalLines = 2) }
        rejected { unfoldBoundedVCard("NOTE:abc\r\n def", maxLogicalLineChars = 10) }
        rejected { unfoldBoundedVCard("NOTE:ééé", maxBytes = 10) }
        assertEquals(listOf("NOTE:éé"), unfoldBoundedVCard("NOTE:éé", maxBytes = 9).lines)
        assertEquals(listOf("NOTE:😀"), unfoldBoundedVCard("NOTE:😀", maxBytes = 9).lines)
        rejected { unfoldBoundedVCard("NOTE:😀", maxBytes = 8) }
        assertEquals(listOf("NOTE:abc def"), unfoldBoundedVCard("NOTE:abc\r\n  def").lines)
        assertEquals(listOf("A", "BC", "D"), unfoldBoundedVCard("A\rB\n\tC\r\nD").lines)
    }

    @Test
    fun productionPhysicalLineBoundAllowsNormalLargeImageFoldingButStopsTinyContinuationFloods() {
        val ordinary = "LOGO:" + "A".repeat(4 * 1_024 * 1_024).chunked(76).joinToString("\r\n ")
        assertEquals(4 * 1_024 * 1_024 + 5, unfoldBoundedVCard(ordinary).lines.single().length)
        val flood = "NOTE:a\n" + " \n".repeat(200_000)
        val error = runCatching { unfoldBoundedVCard(flood) }.exceptionOrNull()
        assertTrue(error is ProtonVCardParseFailure)
        assertEquals(GatewayContactHydrationCategory.VCARD_PARSE_BOUNDS, (error as ProtonVCardParseFailure).category)
    }
}
