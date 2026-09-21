package com.spop.poverlay.overlay

import android.view.WindowManager.LayoutParams
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.ceil

@Suppress("LiftReturnOrAssignment")
class OverlayDialogViewModel(
    private val screenSize: Size
    ) {
    companion object {
        // If the overlay is dragged within this range of pixels from the center of the screen
        // snap to the center of the screen instead
        private val HorizontalDragSnapRange = -20f..20f
    }

    val dialogOrigin = MutableStateFlow(Offset.Zero)
    // Defined as width to height
    val dialogSizeParams = MutableStateFlow(LayoutParams.WRAP_CONTENT to LayoutParams.WRAP_CONTENT)
    val minimizedDialogSizeParams = MutableStateFlow(LayoutParams.WRAP_CONTENT to LayoutParams.WRAP_CONTENT)
    val dialogLocation = MutableStateFlow(OverlayLocation.Bottom)
    val dialogGravity = MutableStateFlow(dialogLocation.value.gravity)


    // Takes the current horizontal progress of a drag and returns a new progress
    // - If the gesture is near the center of the screen, keep view at 0 (allows snapping to center)
    // - If the gesture is on screen, move the view to follow the gesture
    // - If the gesture would move the view offscreen, clamp it to screen bounds
    fun processHorizontalDrag(distance: Float): Float {
        when {
            HorizontalDragSnapRange.contains(distance) -> {
                // View is near the center of the screen, snap to center
                updateOrigin(dialogOrigin, x = 0f)
                return distance
            }
            horizontalDragScreenRange.contains(distance) -> {
                // View fits on screen despite drag
                updateOrigin(dialogOrigin, x = distance)
                return distance
            }
            else -> {
                // View would go off screen, clamp drag gesture
                val clampedX = distance.coerceIn(horizontalDragScreenRange)
                updateOrigin(dialogOrigin, x = clampedX)
                return clampedX
            }
        }
    }
    fun onOverlayLayout(size : IntSize){
        horizontalDragScreenRange = calculateHorizontalDragScreenRange(size.width)
        val (_, currentHeight) = dialogSizeParams.value
        dialogSizeParams.value = size.width to currentHeight
    }

    fun onTimerOverlayLayout(size : IntSize){
        horizontalDragScreenRange = calculateHorizontalDragScreenRange(size.width)
        val (_, currentHeight) = dialogSizeParams.value
        minimizedDialogSizeParams.value = size.width to currentHeight
    }
    private var horizontalDragScreenRange = calculateHorizontalDragScreenRange(0)

    // Takes the current vertical progress of a drag and returns a new progress
    // - Reset the progress to 0 and move the view once drag is halfway across screen
    // - Keep the current progress otherwise
    fun processVerticalDrag(distance: Float): Float {
        if (abs(distance) > screenSize.height * OverlayService.VerticalMoveDragThreshold) {
            val newLocation = verticalToggleOverlayLocation(dialogLocation.value)
            dialogLocation.value = newLocation
            dialogGravity.value = newLocation.gravity
            return 0f
        } else {
            return distance // Continue drag gesture
        }
    }

    private fun updateOrigin(
        origin: MutableStateFlow<Offset>,
        x: Float = origin.value.x,
        y: Float = origin.value.y
    ) {
        origin.value = origin.value.copy(x = x, y = y)
    }


    private fun calculateHorizontalDragScreenRange(overlayWidthPx : Int): ClosedFloatingPointRange<Float> {
        val dragRange = abs(ceil((screenSize.width - overlayWidthPx) / 2f).toInt())
        return -dragRange.toFloat()..dragRange.toFloat()
    }

    private fun verticalToggleOverlayLocation(location: OverlayLocation) =
        if (location == OverlayLocation.Bottom) {
            OverlayLocation.Top
        } else {
            OverlayLocation.Bottom
        }
}