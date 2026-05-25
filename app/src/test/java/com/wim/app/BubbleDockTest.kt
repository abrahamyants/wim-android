package com.wim.app

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks in BubbleDock.checkSnap geometry — the 80% magnet-radius
 * release-snap math is what makes the bubble feel "really fucking
 * strong" when the user releases near a dock slot. If the radius
 * shrinks or grows accidentally, the dock feel breaks.
 *
 * Doesn't test the park() animation — that's a coroutine + animator
 * chain that needs an actual frame loop. checkSnap is the pure
 * geometry function on the hot path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BubbleDockTest {

    private lateinit var context: Context
    private lateinit var wm: WindowManager
    private var slotRects: Map<String, Rect>? = null
    // Stub bubble view — width is what the snap math reads.
    private lateinit var stubView: View

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 120dp bubble width at default density (1.0 in Robolectric).
        stubView = View(context).apply {
            measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            layout(0, 0, 120, 120)
        }
    }

    private fun dock(): BubbleDock = BubbleDock(
        windowManager = wm,
        getBubbleView = { stubView },
        getSlotRects = { slotRects },
        getVibrator = { null },
        onApplyTheme = {},
        onMenuRepositioned = {},
        onHideBubble = {},
        onNotificationText = {},
    )

    private fun paramsAt(x: Int, y: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams().apply { this.x = x; this.y = y }

    @Test fun noSlotRectsReturnsNull() {
        slotRects = null
        assertNull(dock().checkSnap(paramsAt(500, 500)))
    }

    @Test fun bubbleInsideSlotReturnsThemeAndCenter() {
        // 100×100 slot at (200,300)..(300,400) → center (250, 350).
        slotRects = mapOf("bronze" to Rect(200, 300, 300, 400))
        // Bubble center at (250, 350) — directly on slot center.
        val result = dock().checkSnap(paramsAt(190, 290))
        assertNotNull(result)
        assertEquals("bronze", result!!.first)
        assertEquals(250, result.second.x)
        assertEquals(350, result.second.y)
    }

    @Test fun bubbleFarFromSlotReturnsNull() {
        slotRects = mapOf("bronze" to Rect(200, 300, 300, 400))
        // Bubble center far outside the 80% magnet pad.
        val result = dock().checkSnap(paramsAt(2000, 2000))
        assertNull(result)
    }

    @Test fun bubbleWithinMagnetRadiusSnaps() {
        // 100-wide slot → magnet pad = 80px. Bubble within ±80px on each
        // side counts as a snap.
        slotRects = mapOf("aluminum" to Rect(200, 300, 300, 400))
        // Bubble center at (200 + 60/2) = 230 (inside slot horizontally)
        // and (300 - 50) = 250 (above the slot top but within 80px pad).
        val result = dock().checkSnap(paramsAt(170, 190))
        assertNotNull(result)
        assertEquals("aluminum", result!!.first)
    }

    @Test fun firstMatchingSlotWins() {
        // Order-preservation: if the bubble is in the magnet zone of two
        // slots simultaneously, the first iterated wins. LinkedHashMap
        // preserves insertion order.
        slotRects = linkedMapOf(
            "bronze" to Rect(0, 0, 100, 100),
            "aluminum" to Rect(50, 50, 150, 150)  // overlapping
        )
        // Bubble center (60, 60) is inside both rect boundaries.
        val result = dock().checkSnap(paramsAt(0, 0))
        assertNotNull(result)
        assertEquals("bronze", result!!.first)  // first hit wins
    }

    @Test fun exactlyOnSlotEdgeStillSnaps() {
        slotRects = mapOf("gunmetal" to Rect(100, 100, 200, 200))
        // Bubble center exactly at slot top-left corner (100, 100).
        val result = dock().checkSnap(paramsAt(40, 40))
        assertNotNull(result)
        assertEquals("gunmetal", result!!.first)
    }
}
