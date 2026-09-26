package com.patmanak.contako.ui

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.patmanak.contako.R

/** Intertwined octopus wordmark; respects the app's theme, not just the OS setting. */
@Composable
internal fun ContakoBrand(modifier: Modifier = Modifier) {
    val darkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Image(
        painter = painterResource(
            if (darkSurface) R.drawable.contako_brand_dark else R.drawable.contako_brand_light,
        ),
        contentDescription = stringResource(R.string.brand_text_fallback),
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
