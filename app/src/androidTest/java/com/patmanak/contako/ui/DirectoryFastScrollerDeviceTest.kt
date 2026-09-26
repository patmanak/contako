package com.patmanak.contako.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.contako.R
import com.patmanak.contako.ui.theme.ContakoTheme
import com.patmanak.contako.ui.theme.ThemeMode
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryFastScrollerDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun endOfDirectorySurvivesBottomBarRemovalForDetail() {
        var directoryVisible by mutableStateOf(true)
        var savedPosition = 0 to 0
        val index = DirectorySectionIndex.create(List(40) { "Alpha $it" } + "Zulu", Locale.ENGLISH) { it }
        compose.setContent {
            ContakoTheme {
                Scaffold(bottomBar = { if (directoryVisible) Box(Modifier.height(64.dp)) }) { padding ->
                    if (directoryVisible) {
                        IndexedDirectory(
                            index = index, key = { it },
                            initialIndex = savedPosition.first, initialOffset = savedPosition.second,
                            reselectionRevision = 0,
                            onScrollSettled = { item, offset -> savedPosition = item to offset },
                            modifier = Modifier.padding(padding),
                        ) { Text(it, Modifier.fillMaxWidth().padding(16.dp)) }
                    } else Text("Contact detail", Modifier.padding(padding))
                }
            }
        }
        compose.onNodeWithTag("directory_alphabet_rail").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("section-picker:Z", useUnmergedTree = true).performClick()
        val before = compose.onNodeWithText("Zulu").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val positionBefore = compose.runOnIdle { savedPosition }
        compose.runOnIdle { directoryVisible = false }
        compose.onNodeWithText("Contact detail").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Leaving detail must not rewrite directory position", positionBefore, savedPosition) }
        compose.runOnIdle { directoryVisible = true }
        val after = compose.onNodeWithText("Zulu").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { assertEquals("Restored row and offset", positionBefore, savedPosition) }
        assertEquals(before, after)
    }

    @Test
    fun detailReturnRestoresRowAndOffsetWithoutReplayingTabReselection() {
        var directoryVisible by mutableStateOf(true)
        var revision by mutableStateOf(3)
        var savedPosition = 10 to 23
        val index = fixtureIndex()
        val anchor = index.sections.first().items[10]
        compose.setContent {
            ContakoTheme {
                if (directoryVisible) {
                    IndexedDirectory(
                        index = index, key = { it },
                        initialIndex = savedPosition.first, initialOffset = savedPosition.second,
                        reselectionRevision = revision,
                        onScrollSettled = { item, offset -> savedPosition = item to offset },
                    ) { Text(it, Modifier.fillMaxWidth().padding(16.dp)) }
                } else {
                    Text("Contact detail")
                }
            }
        }

        compose.runOnIdle { assertEquals(10 to 23, savedPosition) }
        val before = compose.onNodeWithText(anchor).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { directoryVisible = false }
        compose.onNodeWithText("Contact detail").assertIsDisplayed()
        compose.runOnIdle { directoryVisible = true }
        compose.onNodeWithText(anchor).assertIsDisplayed().also {
            assertEquals(before, it.fetchSemanticsNode().boundsInRoot)
        }
        compose.runOnIdle { assertEquals(10 to 23, savedPosition) }

        // An explicit new tap on the active tab must still return to the top.
        compose.runOnIdle { revision++ }
        compose.onNodeWithText("Alpha 0").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0 to 0, savedPosition) }
    }

    @Test
    fun pickerUsesPresentSectionsAndAccessibleTargets() {
        val index = fixtureIndex()
        var savedIndex = 0
        compose.setContent {
            ContakoTheme {
                IndexedDirectory(
                    index = index,
                    key = { it },
                    initialIndex = 0,
                    initialOffset = 0,
                    reselectionRevision = 0,
                    onScrollSettled = { item, _ -> savedIndex = item },
                ) { Text(it, Modifier.padding(16.dp)) }
            }
        }

        compose.onNodeWithText(compose.activity.getString(R.string.directory_jump_to_section)).assertDoesNotExist()
        compose.onNodeWithTag("directory_alphabet_rail").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("section-picker:A", useUnmergedTree = true).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("section-picker:Z", useUnmergedTree = true).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Zulu 20").assertIsDisplayed()
        compose.runOnIdle { assertTrue("Alphabet jumps must update the saved position", savedIndex > 0) }
        compose.onNodeWithText(compose.activity.getString(R.string.directory_jump_to_section)).assertDoesNotExist()
    }

    @Test
    fun sectionActionStaysAboveRowsAtNormalAndLargeText() {
        var scale by mutableStateOf(1f)
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                ContakoTheme(themeMode = theme) {
                    IndexedDirectory(
                        index = fixtureIndex(), key = { it }, initialIndex = 0, initialOffset = 0,
                        reselectionRevision = 0, onScrollSettled = { _, _ -> },
                    ) { Text(it, Modifier.fillMaxWidth().padding(16.dp)) }
                }
            }
        }
        for ((mode, fontScale) in listOf(
            ThemeMode.LIGHT to 1f, ThemeMode.LIGHT to 2f,
            ThemeMode.DARK to 1f, ThemeMode.DARK to 2f,
        )) {
            compose.runOnIdle { scale = fontScale; theme = mode }
            compose.onNodeWithTag("directory_section_picker_action").assertDoesNotExist()
            val rail = compose.onNodeWithTag("directory_alphabet_rail")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val first = compose.onNodeWithText("Alpha 0").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Rail must not cover the first row", first.right <= rail.left)
            assertTrue("Rows must leave room for the alphabet rail",
                first.right < compose.onRoot().fetchSemanticsNode().boundsInRoot.right)
        }
    }

    @Test
    fun railDragShowsCurrentSectionAndJumpsContinuously() {
        val index = fixtureIndex()
        compose.setContent {
            ContakoTheme {
                IndexedDirectory(
                    index = index,
                    key = { it },
                    initialIndex = 0,
                    initialOffset = 0,
                    reselectionRevision = 0,
                    onScrollSettled = { _, _ -> },
                ) { Text(it, Modifier.padding(16.dp)) }
            }
        }

        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(width - 16f, height * 0.25f),
                end = Offset(width - 16f, height * 0.85f),
                durationMillis = 800,
            )
        }
        compose.onNodeWithText("Zulu 20").assertIsDisplayed()
    }

    private fun fixtureIndex(): DirectorySectionIndex<String> = DirectorySectionIndex.create(
        List(40) { index -> if (index < 20) "Alpha $index" else "Zulu $index" },
        Locale.ENGLISH,
    ) { it }
}
