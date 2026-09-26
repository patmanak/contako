package com.patmanak.contako.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactEditorLayoutContractTest {
    @Test
    fun editorUsesTheApprovedVisibleProgressiveOrder() {
        val source = projectFile("src/main/java/com/patmanak/contako/ui/ContakoApp.kt").readText()
        assertTrue(source.contains("ContactValueFamily(editor, ContactValueKind.NICKNAME, viewModel)"))
        val screen = source.substringAfter("private fun ContactEditorScreen")
            .substringAfter("ContactValueFamily(editor, ContactValueKind.NICKNAME, viewModel)")
            .substringBefore("private fun EditorCard")

        assertOrdered(
            screen,
            "R.string.section_photos",
            "R.string.contact_email_section",
            "ContactGroupAssignmentEditor(editor, viewModel)",
            "editorSections.forEach",
        )

        val sections = source.substringAfter("private val editorSections")
            .substringBefore("private fun ContactGroupAssignmentEditor")
        assertOrdered(
            sections,
            "R.string.section_communication",
            "R.string.section_organization_relationships",
            "R.string.contact_addresses_section",
            "R.string.contact_dates_section",
            "R.string.contact_notes_section",
            "R.string.section_advanced",
        )
        assertFalse(sections.contains("R.string.section_addresses_dates_notes"))
        assertTrue(sections.contains("ContactValueKind.ROLE"))
    }

    @Test
    fun groupAssignmentEditorIsPerEmailAndNeverCollapsed() {
        val source = projectFile("src/main/java/com/patmanak/contako/ui/ContakoApp.kt").readText()
        val editor = source.substringAfter("private fun ContactGroupAssignmentEditor")
            .substringBefore("private fun ContactValueFamily")

        assertTrue(editor.contains("ContactValueKind.EMAIL"))
        assertTrue(editor.contains("ContactGroupAssignment(group.id, email.id)"))
        assertTrue(editor.contains("toggleContactGroupAssignment(group.id, email.id)"))
        assertFalse(editor.contains("DropdownMenu"))
        assertFalse(editor.contains("AnimatedVisibility"))
    }

    private fun assertOrdered(source: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val index = source.indexOf(token)
            assertTrue("Missing layout token: $token", index >= 0)
            assertTrue("Layout token out of order: $token", index > previous)
            previous = index
        }
    }

    private fun projectFile(relativePath: String): File {
        val candidates = listOf(File(relativePath), File("app", relativePath), File("..", relativePath))
        return candidates.firstOrNull(File::exists) ?: error("Missing project file: $relativePath")
    }
}
