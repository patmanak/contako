package com.patmanak.contako.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.patmanak.contako.ui.theme.ContakoElevation
import com.patmanak.contako.ui.theme.ContakoSpacing
import com.patmanak.contako.ui.theme.LocalContakoColors

val MinimumTouchTarget = 48.dp

@Composable
fun AppShell(
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) = Scaffold(
    contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
    topBar = topBar,
    bottomBar = bottomBar,
    floatingActionButton = floatingActionButton,
    content = content,
)

@Composable
fun LimitationBanner(
    icon: ImageVector,
    text: String,
    action: String,
    onAction: () -> Unit,
) {
    val colors = LocalContakoColors.current
    Surface(color = colors.warningContainer, contentColor = colors.warning) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ContakoSpacing.Gutter, vertical = ContakoSpacing.Standard),
            horizontalArrangement = Arrangement.spacedBy(ContakoSpacing.Standard),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(top = ContakoSpacing.Standard))
            Text(text, modifier = Modifier.weight(1f).padding(vertical = ContakoSpacing.Standard))
            SecondaryButton(action, onAction)
        }
    }
}

@Composable
fun StatusLabel(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ContakoSpacing.Compact)) {
        Icon(icon, contentDescription = null)
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.minimumTarget().focusRing(interaction),
    ) { Text(text) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.minimumTarget().focusRing(interaction),
    ) { Text(text) }
}

@Composable
fun InfoCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LocalContakoColors.current.border),
        elevation = CardDefaults.cardElevation(defaultElevation = ContakoElevation.None),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(ContakoSpacing.Gutter), verticalArrangement = Arrangement.spacedBy(ContakoSpacing.Standard)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

fun Modifier.minimumTarget() = sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)

@Composable
private fun Modifier.focusRing(interaction: MutableInteractionSource): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    return if (focused) border(2.dp, LocalContakoColors.current.focus, RoundedCornerShape(8.dp)).focusable(interactionSource = interaction) else this
}
