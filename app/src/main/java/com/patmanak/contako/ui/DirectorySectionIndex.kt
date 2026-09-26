package com.patmanak.contako.ui

import java.text.Collator
import java.util.Locale

data class DirectorySection<T>(
    val label: String,
    val items: List<T>,
    val firstItemIndex: Int,
)

data class DirectorySectionIndex<T>(
    val sections: List<DirectorySection<T>>,
) {
    val labels: List<String> = sections.map(DirectorySection<T>::label)

    /** Visual landmarks stay stable even when a result contains only one initial. */
    fun railLabels(locale: Locale): List<String> {
        val collator = Collator.getInstance(locale)
        return (('A'..'Z').map(Char::toString) + labels.filter { it != "#" })
            .distinct().sortedWith { a, b -> collator.compare(a, b) } + "#"
    }

    fun nearestSection(rail: List<String>, position: Int): Int =
        labels.indices.minByOrNull { kotlin.math.abs(rail.indexOf(labels[it]) - position) } ?: 0

    companion object {
        fun <T> create(
            items: List<T>,
            locale: Locale,
            displayName: (T) -> String,
        ): DirectorySectionIndex<T> {
            val collator = Collator.getInstance(locale)
            val baseCollator = (collator.clone() as Collator).apply { strength = Collator.PRIMARY }
            val indexed = items.mapIndexed { ordinal, item ->
                val name = displayName(item)
                IndexedItem(item, name, ordinal, sectionLabel(name, locale, baseCollator))
            }.sortedWith { left, right ->
                val leftSection = left.section
                val rightSection = right.section
                when {
                    leftSection == HASH && rightSection != HASH -> 1
                    leftSection != HASH && rightSection == HASH -> -1
                    leftSection != rightSection -> collator.compare(leftSection, rightSection)
                    else -> collator.compare(left.name, right.name).takeIf { it != 0 }
                        ?: left.ordinal.compareTo(right.ordinal)
                }
            }

            var firstItemIndex = 0
            val sections = indexed.groupBy(IndexedItem<T>::section)
                .map { (label, sectionItems) ->
                    DirectorySection(
                        label = label,
                        items = sectionItems.map(IndexedItem<T>::item),
                        firstItemIndex = firstItemIndex,
                    ).also { firstItemIndex += sectionItems.size }
                }
            return DirectorySectionIndex(sections)
        }

        private fun sectionLabel(name: String, locale: Locale, collator: Collator): String {
            val initial = name.trimStart().takeFirstCodePoint()
            if (initial.isEmpty() || !initial.codePointAt(0).let(Character::isLetter)) return HASH

            val upperInitial = initial.uppercase(locale)
            return LATIN_BASE_LETTERS.firstOrNull { collator.compare(upperInitial, it) == 0 }
                ?: upperInitial
        }

        private fun String.takeFirstCodePoint(): String = if (isEmpty()) {
            this
        } else {
            substring(0, Character.charCount(codePointAt(0)))
        }

        private data class IndexedItem<T>(
            val item: T,
            val name: String,
            val ordinal: Int,
            val section: String,
        )

        private const val HASH = "#"
        private val LATIN_BASE_LETTERS = ('A'..'Z').map(Char::toString)
    }
}
