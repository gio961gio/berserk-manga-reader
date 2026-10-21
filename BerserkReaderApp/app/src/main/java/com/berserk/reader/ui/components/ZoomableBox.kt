package com.berserk.reader.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    minScale: Float = 1f,
    maxScale: Float = 4f,
    resetKey: Any? = null,
    onTap: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Auto-reset when page or chapter changes
    LaunchedEffect(resetKey) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (scale > 1.1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                            val centerX = containerSize.width / 2f
                            val centerY = containerSize.height / 2f
                            val targetOffsetX = (centerX - tapOffset.x) * (2.5f - 1f)
                            val targetOffsetY = (centerY - tapOffset.y) * (2.5f - 1f)
                            val maxX = ((containerSize.width * 2.5f - containerSize.width) / 2f).coerceAtLeast(0f)
                            val maxY = ((containerSize.height * 2.5f - containerSize.height) / 2f).coerceAtLeast(0f)
                            offsetX = targetOffsetX.coerceIn(-maxX, maxX)
                            offsetY = targetOffsetY.coerceIn(-maxY, maxY)
                        }
                    },
                    onTap = {
                        onTap?.invoke()
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        val isMultiTouch = event.changes.size > 1
                        val isZooming = zoomChange != 1f
                        val isZoomedIn = scale > 1.05f

                        if (isMultiTouch || isZooming || isZoomedIn) {
                            val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)
                            scale = newScale

                            val maxX = ((containerSize.width * scale - containerSize.width) / 2f).coerceAtLeast(0f)
                            val maxY = ((containerSize.height * scale - containerSize.height) / 2f).coerceAtLeast(0f)

                            if (scale > 1f) {
                                offsetX = (offsetX + panChange.x).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + panChange.y).coerceIn(-maxY, maxY)
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }

                            // Consume pointer movement when zooming or zoomed-in
                            event.changes.forEach { it.consume() }
                        }
                        // When scale == 1f and single touch, we do not consume,
                        // allowing HorizontalPager or LazyColumn to scroll smoothly!
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
        ) {
            content()
        }
    }
}
