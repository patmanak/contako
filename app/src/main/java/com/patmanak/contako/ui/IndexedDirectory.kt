package com.patmanak.contako.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.patmanak.contako.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun <T> IndexedDirectory(
    index: DirectorySectionIndex<T>,
    key: (T) -> Any,
    initialIndex: Int,
    initialOffset: Int,
    reselectionRevision: Int,
    onScrollSettled: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (T) -> Unit,
) {
    val listState = rememberLazyListState(initialIndex, initialOffset)
    val entryReselectionRevision = remember { reselectionRevision }
    var showPicker by remember { mutableStateOf(false) }
    var activeLabel by remember { mutableStateOf<String?>(null) }
    var railHeight by remember { mutableIntStateOf(0) }
    var railWidth by remember { mutableIntStateOf(0) }
    var indicatorFraction by remember { mutableFloatStateOf(0f) }
    val jumpActionFocus = remember { FocusRequester() }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    val currentOnScrollSettled by rememberUpdatedState(onScrollSettled)
    LaunchedEffect(listState, reselectionRevision) {
        if (reselectionRevision == entryReselectionRevision) {
            // Scaffold updates its bottom insets during layout. Restore on the next frame so
            // the detail viewport cannot clamp an end-of-directory position prematurely.
            withFrameNanos { }
            listState.scrollToItem(initialIndex, initialOffset)
        } else {
            // Only a new tap on the active tab requests the beginning of the directory.
            listState.scrollToItem(0)
        }
        // Instant alphabet/tab jumps may finish between frames without a visible scrolling phase.
        snapshotFlow {
            if (listState.isScrollInProgress) null
            else listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.filterNotNull().collect { (item, offset) ->
            currentOnScrollSettled(item, offset)
        }
    }

    val spansViewport = listState.canScrollBackward || listState.canScrollForward
    val showAlphabet = spansViewport && index.sections.isNotEmpty()
    val locale = LocalConfiguration.current.locales[0]
    val railLabels = remember(index, locale) { index.railLabels(locale) }
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(
                    end = if (showAlphabet) with(LocalDensity.current) { railWidth.toDp() } else 0.dp,
                ),
                state = listState,
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                index.sections.forEach { section ->
                    stickyHeader(key = "section:${section.label}") {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                section.label,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    items(
                        count = section.items.size,
                        key = { itemIndex -> key(section.items[itemIndex]) },
                    ) { itemIndex -> row(section.items[itemIndex]) }
                }
            }

            if (showAlphabet) {
                AlphabetRail(
                    labels = railLabels,
                    availableLabels = index.labels,
                    selectedLabel = activeLabel,
                    modifier = Modifier.align(Alignment.CenterEnd).onSizeChanged { railWidth = it.width }
                        .focusRequester(jumpActionFocus),
                    onAccessibleSelect = { showPicker = true },
                    onHeightChanged = { railHeight = it },
                    onSection = { railIndex, fraction ->
                        val sectionIndex = index.nearestSection(railLabels, railIndex)
                        activeLabel = index.sections[sectionIndex].label
                        indicatorFraction = fraction
                        coroutineScope.launch {
                            listState.scrollToItem(index.lazyListIndexForSection(sectionIndex))
                        }
                    },
                    onFinished = { activeLabel = null },
                )
                activeLabel?.let { label ->
                    CurrentSectionIndicator(label, indicatorFraction, railHeight)
                }
            }
        }
    }

    if (showPicker) {
        SectionPicker(
            labels = index.labels,
            onDismiss = {
                showPicker = false
                jumpActionFocus.requestFocus()
            },
            onSelect = { sectionIndex ->
                coroutineScope.launch {
                    listState.scrollToItem(index.lazyListIndexForSection(sectionIndex))
                }
                activeLabel = null
                showPicker = false
                jumpActionFocus.requestFocus()
            },
        )
    }
}

private fun <T> DirectorySectionIndex<T>.lazyListIndexForSection(sectionIndex: Int): Int =
    sections.take(sectionIndex).sumOf { it.items.size + 1 }

@Composable
private fun AlphabetRail(
    labels: List<String>,
    availableLabels: List<String>,
    selectedLabel: String?,
    modifier: Modifier,
    onAccessibleSelect: () -> Unit,
    onHeightChanged: (Int) -> Unit,
    onSection: (Int, Float) -> Unit,
    onFinished: () -> Unit,
) {
    fun sectionAt(y: Float, height: Int): Pair<Int, Float> {
        val fraction = (y / height.coerceAtLeast(1)).coerceIn(0f, 0.9999f)
        return (fraction * labels.size).toInt().coerceIn(labels.indices) to fraction
    }
    val accessibilityLabel = stringResource(R.string.directory_section_list_title)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .padding(end = 2.dp)
            .fillMaxHeight(0.82f)
            .onSizeChanged { onHeightChanged(it.height) }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.key == Key.Enter || it.key == Key.Spacebar)) {
                    onAccessibleSelect()
                    true
                } else false
            }
            .focusable()
            .clearAndSetSemantics {
                testTag = "directory_alphabet_rail"
                contentDescription = accessibilityLabel
                onClick { onAccessibleSelect(); true }
            }
            .pointerInput(labels) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var event = down
                    do {
                        val (section, fraction) = sectionAt(event.position.y, size.height)
                        onSection(section, fraction)
                        val pointerEvent = awaitPointerEvent()
                        event = pointerEvent.changes.firstOrNull { it.id == down.id } ?: break
                        event.consume()
                    } while (event.pressed)
                    onFinished()
                }
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            labels.forEach { label ->
                Text(
                    label,
                    // Accessibility/keyboard activation of the rail opens full-size targets.
                    fontSize = (10f / LocalDensity.current.fontScale).sp,
                    lineHeight = (12f / LocalDensity.current.fontScale).sp,
                    fontWeight = if (label in availableLabels) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        label == selectedLabel -> MaterialTheme.colorScheme.primary
                        label in availableLabels -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                )
            }
        }
    }
}

@Composable
private fun BoxScope.CurrentSectionIndicator(label: String, fraction: Float, railHeight: Int) {
    val layoutDirection = LocalLayoutDirection.current
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .align(if (layoutDirection == LayoutDirection.Ltr) Alignment.TopEnd else Alignment.TopStart)
            .padding(end = if (layoutDirection == LayoutDirection.Ltr) 52.dp else 0.dp)
            .padding(start = if (layoutDirection == LayoutDirection.Rtl) 52.dp else 0.dp)
            .offset(y = maxOf(0.dp, (fraction * railHeight.coerceAtLeast(1)).dp /
                androidx.compose.ui.platform.LocalDensity.current.density - 28.dp))
            .size(56.dp)
            .semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun SectionPicker(
    labels: List<String>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val requesters = remember(labels) { List(labels.size) { FocusRequester() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.directory_section_list_title)) },
        text = {
            LazyColumn {
                items(labels.size) { index ->
                    TextButton(
                        onClick = { onSelect(index) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics { testTag = "section-picker:${labels[index]}" }
                            .focusRequester(requesters[index])
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val target = when (event.key) {
                                    Key.DirectionUp -> (index - 1).coerceAtLeast(0)
                                    Key.DirectionDown -> (index + 1).coerceAtMost(labels.lastIndex)
                                    Key.MoveHome -> 0
                                    Key.MoveEnd -> labels.lastIndex
                                    else -> return@onPreviewKeyEvent false
                                }
                                requesters[target].requestFocus()
                                true
                            },
                    ) { Text(labels[index]) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
    LaunchedEffect(labels) { requesters.firstOrNull()?.requestFocus() }
}
