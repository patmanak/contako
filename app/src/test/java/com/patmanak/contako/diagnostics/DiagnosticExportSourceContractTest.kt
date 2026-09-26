package com.patmanak.contako.diagnostics

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportSourceContractTest {
    @Test
    fun exportUsesCreateDocumentAndAddsNoExportedComponent() {
        val source = projectFile(
            "src/main/java/com/patmanak/contako/ui/DiagnosticSettings.kt",
        ).readText()
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()

        assertTrue(source.contains("ActivityResultContracts.CreateDocument(\"text/plain\")"))
        assertFalse(source.contains("ACTION_SEND"))
        assertFalse(source.contains("FileProvider"))
        assertFalse(manifest.contains("FileProvider"))
        assertFalse(manifest.contains("com.patmanak.contako.diagnostics"))
        assertFalse(manifest.contains("androidx.core.content.FileProvider"))
    }

    @Test
    fun generationHasNoLifecycleOrBackgroundTrigger() {
        val production = projectFile("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val callers = production.filter { it.readText().contains("LocalDiagnosticReportGenerator.generate(") }

        assertTrue(callers.map { it.name } == listOf("DiagnosticSettings.kt"))
        val caller = callers.single().readText()
        assertTrue(caller.contains("Button("))
        assertTrue(caller.contains("onClick ="))
    }

    private fun projectFile(relativePath: String): File {
        val candidates = listOf(File(relativePath), File("app", relativePath), File("..", relativePath))
        return candidates.firstOrNull(File::exists) ?: error("Missing project file: $relativePath")
    }
}
