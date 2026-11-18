package com.karelisio.fichio.ui.browser

import com.karelisio.fichio.core.model.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureMathTest {
    private val w = 1000f
    private val fling = 2000f

    @Test
    fun dragFollowsFingerThenResists() {
        assertEquals(100f, SwipeMath.drag(0f, 100f, w), 0.01f)
        assertEquals(-300f, SwipeMath.drag(-200f, -100f, w), 0.01f)
        // Past 60 % the row moves at a quarter of the finger speed, and never beyond the width.
        assertEquals(625f, SwipeMath.drag(600f, 100f, w), 0.01f)
        assertEquals(500f, SwipeMath.drag(600f, -100f, w), 0.01f) // coming back is not resisted
        assertEquals(1000f, SwipeMath.drag(999f, 50_000f, w), 0.01f)
        assertEquals(0f, SwipeMath.drag(10f, 10f, 0f), 0.01f)
    }

    @Test
    fun armedPastThreshold() {
        assertNull(SwipeMath.armed(300f, w))
        assertEquals(SwipeDirection.RIGHT, SwipeMath.armed(330f, w))
        assertEquals(SwipeDirection.LEFT, SwipeMath.armed(-330f, w))
        assertEquals(1f, SwipeMath.progress(-500f, w), 0.001f)
        assertEquals(0.5f, SwipeMath.progress(160f, w), 0.001f)
    }

    @Test
    fun releaseResolution() {
        // Past the threshold, a slow release triggers.
        assertEquals(SwipeDirection.LEFT, SwipeMath.resolve(-400f, 0f, w, fling))
        // ...unless flung back.
        assertNull(SwipeMath.resolve(-400f, 3000f, w, fling))
        // Below the threshold: only a fast fling in the same direction, from 12 %.
        assertNull(SwipeMath.resolve(200f, 500f, w, fling))
        assertEquals(SwipeDirection.RIGHT, SwipeMath.resolve(200f, 2500f, w, fling))
        assertNull(SwipeMath.resolve(80f, 5000f, w, fling))
        assertNull(SwipeMath.resolve(200f, -2500f, w, fling))
        assertNull(SwipeMath.resolve(0f, 5000f, w, fling))
    }

    @Test
    fun pinchSteps() {
        assertEquals(0, PinchMath.step(1.1f))
        assertEquals(1, PinchMath.step(1.3f))
        assertEquals(-1, PinchMath.step(0.75f))
        assertEquals(ViewMode.GRID, PinchMath.next(ViewMode.LIST, zoomIn = true))
        assertEquals(ViewMode.LARGE, PinchMath.next(ViewMode.GRID, zoomIn = true))
        assertNull(PinchMath.next(ViewMode.LARGE, zoomIn = true))
        assertEquals(ViewMode.GRID, PinchMath.next(ViewMode.LARGE, zoomIn = false))
        assertNull(PinchMath.next(ViewMode.LIST, zoomIn = false))
        assertEquals(1.075f, PinchMath.feedback(2f), 0.001f)
        assertEquals(1f, PinchMath.feedback(1f), 0.001f)
    }
}
