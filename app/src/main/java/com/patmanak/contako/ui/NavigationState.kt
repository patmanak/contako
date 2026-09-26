package com.patmanak.contako.ui

import androidx.lifecycle.SavedStateHandle

enum class RootDestination { CONTACTS, GROUPS, SYNC }

enum class SecondaryDestination {
    SETTINGS,
    ACTIONS,
    ABOUT,
    USAGE,
}

enum class NavigationMode { BOTTOM_BAR, RAIL }

internal fun actionRequiredBadgeText(count: Int): String =
    if (count > 99) "99+" else count.coerceAtLeast(0).toString()

fun navigationMode(widthDp: Int, fontScale: Float = 1f): NavigationMode {
    val effectiveWidthDp = widthDp / fontScale.coerceAtLeast(1f)
    return if (effectiveWidthDp < 600f) NavigationMode.BOTTOM_BAR else NavigationMode.RAIL
}

data class DestinationState(
    val query: String = "",
    val searchExpanded: Boolean = false,
    val selectedId: String? = null,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val reselectionRevision: Int = 0,
)

data class NavigationState(
    val destination: RootDestination = RootDestination.CONTACTS,
    val secondary: SecondaryDestination? = null,
    val destinationStates: Map<RootDestination, DestinationState> = RootDestination.entries
        .associateWith { DestinationState() },
) {
    val current: DestinationState
        get() = destinationStates.getValue(destination)
}

/** Saves only non-secret navigation values needed to restore directory context. */
class NavigationStateStore(private val savedState: SavedStateHandle = SavedStateHandle()) {
    private var state = run {
        val restoredSecondary = savedState.get<String>(KEY_SECONDARY)
            ?.let { runCatching { SecondaryDestination.valueOf(it) }.getOrNull() }
        NavigationState(
            destination = savedState.get<String>(KEY_DESTINATION)
                ?.let { runCatching { RootDestination.valueOf(it) }.getOrNull() }
                ?: RootDestination.CONTACTS,
            secondary = restoredSecondary,
            destinationStates = RootDestination.entries.associateWith { destination ->
                DestinationState(
                    query = savedState[key(destination, "query")] ?: "",
                    searchExpanded = savedState[key(destination, "searchExpanded")]
                        ?: !savedState.get<String>(key(destination, "query")).isNullOrEmpty(),
                    selectedId = savedState[key(destination, "selection")],
                    scrollIndex = savedState[key(destination, "scrollIndex")] ?: 0,
                    scrollOffset = savedState[key(destination, "scrollOffset")] ?: 0,
                    reselectionRevision = savedState[key(destination, "reselection")] ?: 0,
                )
            },
        )
    }

    fun snapshot(): NavigationState = state

    fun select(destination: RootDestination) {
        state = if (state.destination == destination && state.secondary == null) {
            updateDestination(destination) { it.copy(
                scrollIndex = 0,
                scrollOffset = 0,
                reselectionRevision = it.reselectionRevision + 1,
            ) }
        } else {
            state.copy(destination = destination, secondary = null)
        }
        persist()
    }

    fun open(destination: SecondaryDestination) {
        state = state.copy(secondary = destination)
        persist()
    }

    fun closeSecondary() {
        state = state.copy(secondary = null)
        persist()
    }

    fun updateQuery(query: String) = updateCurrent { it.copy(query = query, searchExpanded = true) }

    fun openSearch() = updateCurrent { it.copy(searchExpanded = true) }

    fun closeSearch() = updateCurrent { it.copy(query = "", searchExpanded = false) }

    fun selectItem(id: String?) = updateCurrent { it.copy(selectedId = id) }

    fun clearSelection(destination: RootDestination, expectedId: String) {
        state = updateDestination(destination) { current ->
            if (current.selectedId == expectedId) current.copy(selectedId = null) else current
        }
        persist()
    }

    fun updateScroll(index: Int, offset: Int) = updateCurrent {
        it.copy(scrollIndex = index.coerceAtLeast(0), scrollOffset = offset.coerceAtLeast(0))
    }

    fun back(): Boolean = when {
        state.secondary != null -> {
            closeSecondary()
            true
        }
        state.current.selectedId != null -> {
            selectItem(null)
            true
        }
        state.current.searchExpanded || state.current.query.isNotEmpty() -> {
            closeSearch()
            true
        }
        state.destination != RootDestination.CONTACTS -> {
            select(RootDestination.CONTACTS)
            true
        }
        else -> false
    }

    private fun updateCurrent(block: (DestinationState) -> DestinationState) {
        state = updateDestination(state.destination, block)
        persist()
    }

    private fun updateDestination(
        destination: RootDestination,
        block: (DestinationState) -> DestinationState,
    ): NavigationState = state.copy(
        destinationStates = state.destinationStates + (destination to block(state.destinationStates.getValue(destination))),
    )

    private fun persist() {
        savedState[KEY_DESTINATION] = state.destination.name
        savedState[KEY_SECONDARY] = state.secondary?.name
        state.destinationStates.forEach { (destination, value) ->
            savedState[key(destination, "query")] = value.query
            savedState[key(destination, "searchExpanded")] = value.searchExpanded
            savedState[key(destination, "selection")] = value.selectedId
            savedState[key(destination, "scrollIndex")] = value.scrollIndex
            savedState[key(destination, "scrollOffset")] = value.scrollOffset
            savedState[key(destination, "reselection")] = value.reselectionRevision
        }
    }

    private companion object {
        const val KEY_DESTINATION = "navigation.destination"
        const val KEY_SECONDARY = "navigation.secondary"
        fun key(destination: RootDestination, suffix: String) = "navigation.${destination.name}.$suffix"
    }
}
