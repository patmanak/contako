package com.patmanak.contako.data.sync

import com.patmanak.contako.data.android.mapping.AndroidGroupSnapshotContextFailure
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProjectionRepairDiagnosticsTest {
    @Test
    fun aggregateAcceptsOnlyClosedCategoriesAndResetsWithoutIdentifiers() {
        val aggregate = AndroidProjectionRepairAggregate()

        aggregate.onRepair(AndroidProjectionRepairCategory.PROVIDER_MALFORMED_DATA)
        aggregate.onRepair(AndroidProjectionRepairCategory.PROVIDER_MALFORMED_DATA)
        aggregate.onRepair(AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VERIFICATION)
        com.patmanak.contako.data.android.provider.AndroidPhotoProviderRepairCategory.entries.forEach {
            aggregate.onPhotoFailure(it)
        }

        assertEquals(
            mapOf(
                AndroidProjectionRepairCategory.PROVIDER_MALFORMED_DATA to 2,
                AndroidProjectionRepairCategory.POST_WRITE_CONTACT_VERIFICATION to 1,
            ),
            aggregate.snapshotAndReset(),
        )
        assertTrue(aggregate.snapshotAndReset().isEmpty())
        assertEquals(100, AndroidProjectionRepairCategory.entries.size)
    }

    @Test
    fun onlyPreferredEmailContextChangeCanBeRebasedDuringProjection() {
        assertTrue(
            isRecoverableMembershipProjectionContextFailure(
                AndroidGroupSnapshotContextFailure.PREFERRED_EMAIL_CONTEXT_MISMATCH,
            ),
        )
        assertFalse(
            isRecoverableMembershipProjectionContextFailure(
                AndroidGroupSnapshotContextFailure.ACCOUNT_SCOPE_MISMATCH,
            ),
        )
        assertFalse(
            isRecoverableMembershipProjectionContextFailure(
                AndroidGroupSnapshotContextFailure.CONTACT_IDENTITY_MISMATCH,
            ),
        )
    }

    @Test
    fun everySingleContactRepairExitUsesTheCategoryOnlyBoundary() {
        val source = projectFile(
            "src/main/java/com/patmanak/contako/data/sync/ProductionAndroidProjectionItemExecutor.kt",
        ).readText()
        val classifiedBody = source.substringBefore("private fun repair(category:")
        val repairCalls = Regex("\\brepair\\(AndroidProjectionRepairCategory\\.[A-Z_]+\\)")
            .findAll(classifiedBody)
            .count()

        assertFalse(classifiedBody.contains("return AndroidBoundedPageResult.RepairRequired"))
        assertTrue("Expected explicit category coverage for repair exits", repairCalls >= 20)
        assertTrue(source.contains("repair(failure.category.toProjectionRepairCategory())"))
        assertTrue(source.contains("return AndroidBoundedPageResult.RepairRequired"))

        val diagnostics = projectFile(
            "src/main/java/com/patmanak/contako/data/sync/AndroidProjectionRepairDiagnostics.kt",
        ).readText()
        val observer = diagnostics.substringAfter("internal fun interface AndroidProjectionRepairObserver")
            .substringBefore("/** Thread-safe")
        assertTrue(observer.contains("onRepair(category: AndroidProjectionRepairCategory)"))
        assertFalse(observer.contains("String"))
        assertFalse(observer.contains("Long"))
        assertFalse(observer.contains("Throwable"))
    }

    private fun projectFile(relativePath: String): File {
        val candidates = listOf(File(relativePath), File("app", relativePath), File("..", relativePath))
        return candidates.firstOrNull(File::exists) ?: error("Missing project file: $relativePath")
    }
}
