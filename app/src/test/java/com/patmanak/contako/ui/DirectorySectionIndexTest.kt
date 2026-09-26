package com.patmanak.contako.ui

import java.util.Locale
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorySectionIndexTest {
    @Test
    fun persistentRailPreservesLandmarksAndMapsOnlyToAvailableSections() {
        val index = DirectorySectionIndex.create(listOf("Charlie", "Charles"), Locale.FRENCH) { it }
        val rail = index.railLabels(Locale.FRENCH)
        assertEquals(('A'..'Z').map(Char::toString) + "#", rail)
        rail.indices.forEach { assertEquals(0, index.nearestSection(rail, it)) }
        val accented = DirectorySectionIndex.create(listOf("Alice", "Östen", "#"), Locale.forLanguageTag("sv-SE")) { it }
        val localRail = accented.railLabels(Locale.forLanguageTag("sv-SE"))
        assertTrue(localRail.contains("Ö"))
        accented.labels.forEachIndexed { expected, label ->
            assertEquals(expected, accented.nearestSection(localRail, localRail.indexOf(label)))
        }
    }

    @Test
    fun presentSectionsUseLocaleCollationAndPutUnmappableNamesInHash() {
        val index = DirectorySectionIndex.create(
            items = listOf("Zulu", "! symbol", "Élodie", "Eve", "Åke", "Alice", "42", ""),
            locale = Locale.FRENCH,
            displayName = { it },
        )

        assertEquals(listOf("A", "E", "Z", "#"), index.labels)
        assertEquals(listOf("Åke", "Alice"), index.sections[0].items)
        assertEquals(listOf("Élodie", "Eve"), index.sections[1].items)
        assertEquals(listOf("", "! symbol", "42"), index.sections.last().items)
        assertEquals(listOf(0, 2, 4, 5), index.sections.map(DirectorySection<String>::firstItemIndex))
    }

    @Test
    fun activeCollatorDecidesWhetherAccentedInitialJoinsBaseLetter() {
        val english = DirectorySectionIndex.create(listOf("Östen", "Oscar"), Locale.ENGLISH) { it }
        val swedish = DirectorySectionIndex.create(listOf("Östen", "Oscar"), Locale.forLanguageTag("sv-SE")) { it }

        assertEquals(listOf("O"), english.labels)
        assertEquals(listOf("O", "Ö"), swedish.labels)
    }

    @Test
    fun nonLatinSectionsRemainAvailableAndHashSortsLast() {
        val index = DirectorySectionIndex.create(
            listOf("Борис", "Анна", "# tag"),
            Locale.forLanguageTag("ru-RU"),
        ) { it }

        assertEquals(listOf("А", "Б", "#"), index.labels)
    }

    @Test
    fun filteredResultsRecalculateSectionsWithoutMutatingInput() {
        val source = listOf("Alice", "Bob", "Bea", "Charlie")
        val filtered = source.filter { it.startsWith("B") }

        val index = DirectorySectionIndex.create(filtered, Locale.ENGLISH) { it }

        assertEquals(listOf("B"), index.labels)
        assertEquals(source, listOf("Alice", "Bob", "Bea", "Charlie"))
    }

    @Test
    fun fiveThousandItemsProduceStableFirstRowTargetsWithinBudget() {
        val items = List(5_000) { index ->
            "${('A'.code + index % 26).toChar()} item ${index.toString().padStart(4, '0')}"
        }
        lateinit var result: DirectorySectionIndex<String>

        val elapsed = measureTimeMillis {
            result = DirectorySectionIndex.create(items, Locale.ENGLISH) { it }
        }

        assertEquals(26, result.sections.size)
        assertEquals(5_000, result.sections.sumOf { it.items.size })
        assertEquals(result.sections.map { it.firstItemIndex }.sorted(), result.sections.map { it.firstItemIndex })
        assertTrue("Index construction took ${elapsed}ms", elapsed < 2_000)
    }
}
