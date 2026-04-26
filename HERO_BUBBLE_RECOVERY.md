# HERO BUBBLE work — extracted from session adc11ddb-024f-427b-ad2b-950b781f87ed.jsonl

Three Edit tool calls captured during the Apr 26 06:30 session that introduced the HERO BUBBLE refactor and replaced the 3-bubble picker. The verbatim old_string/new_string fields below are exact source-of-truth for the working-tree state that was destroyed by git checkout HEAD -- on Apr 26 ~11:09.

---

## Edit at jsonl line 2532 — activity_main.xml

### old_string
```
        <!-- ═══════════════════════════════════════════ -->
        <LinearLayout
            android:id="@+id/bubbleDock"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:orientation="vertical"
            android:gravity="center">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="PICK YOUR BUBBLE"
                android:textColor="@color/text_dim"
                android:textSize="13sp"
                android:fontFamily="monospace"
                android:letterSpacing="0.15"
                android:layout_marginBottom="18dp"
                android:alpha="0.75" />

            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center">

                <!-- BRONZE slot (reveal image pending — new art to be provided) -->
                <FrameLayout
                    android:id="@+id/dockBronze"
                    android:layout_width="72dp"
                    android:layout_height="72dp"
                    android:layout_marginStart="14dp"
                    android:layout_marginEnd="14dp"
                    android:background="@drawable/bubble_bg_idle"
                    android:elevation="4dp"
                    android:clickable="true"
                    android:focusable="true">

                    <FrameLayout
                        android:layout_width="44dp"
                        android:layout_height="44dp"
                        android:layout_gravity="center"
                        android:background="@drawable/bubble_inner_knob">

                        <View
                            android:layout_width="3dp"
                            android:layout_height="6dp"
                            android:layout_gravity="top|center_horizontal"
                            android:layout_marginTop="2dp"
                            android:background="@drawable/knob_tick" />

                        <TextView
                            android:id="@+id/dockBronzeLabel"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_gravity="center"
                            android:text="IDLE"
                            android:textColor="#FF555560"
                            android:textSize="8sp"
                            android:fontFamily="monospace"
                            android:letterSpacing="0.05"
                            android:textStyle="bold"
                            android:gravity="center"
                            android:maxLines="1" />
                    </FrameLayout>
                </FrameLayout>

                <!-- ALUMINUM slot -->
                <FrameLayout
                    android:id="@+id/dockAluminum"
                    android:layout_width="72dp"
                    android:layout_height="72dp"
                    android:layout_marginStart="14dp"
                    android:layout_marginEnd="14dp"
                    android:background="@drawable/bubble_bezel_aluminum"
                    android:elevation="4dp"
                    android:clickable="true"
                    android:focusable="true">

                    <FrameLayout
                        android:layout_width="44dp"
                        android:layout_height="44dp"
                        android:layout_gravity="center"
                        android:background="@drawable/bubble_knob_aluminum">

                        <View
                            android:layout_width="3dp"
                            android:layout_height="6dp"
                            android:layout_gravity="top|center_horizontal"
                            android:layout_marginTop="2dp"
                            android:background="@drawable/knob_tick" />

                        <TextView
                            android:id="@+id/dockAluminumLabel"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_gravity="center"
                            android:text="IDLE"
                            android:textColor="#FF555560"
                            android:textSize="8sp"
                            android:fontFamily="monospace"
                            android:letterSpacing="0.05"
                            android:textStyle="bold"
                            android:gravity="center"
                            android:maxLines="1" />
                    </FrameLayout>
                </FrameLayout>

                <!-- GUNMETAL slot — gold IDLE text (dark bg needs bright foreground) -->
                <FrameLayout
                    android:id="@+id/dockGunmetal"
                    android:layout_width="72dp"
                    android:layout_height="72dp"
                    android:layout_marginStart="14dp"
                    android:layout_marginEnd="14dp"
                    android:background="@drawable/bubble_bezel_gunmetal"
                    android:elevation="4dp"
                    android:clickable="true"
                    android:focusable="true">

                    <FrameLayout
                        android:layout_width="44dp"
                        android:layout_height="44dp"
                        android:layout_gravity="center"
                        android:background="@drawable/bubble_knob_gunmetal">

                        <View
                            android:layout_width="3dp"
                            android:layout_height="6dp"
                            android:layout_gravity="top|center_horizontal"
                            android:layout_marginTop="2dp"
                            android:background="@drawable/knob_tick" />

                        <TextView
                            android:id="@+id/dockGunmetalLabel"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_gravity="center"
                            android:text="IDLE"
                            android:textColor="#FFC8B48A"
                            android:textSize="8sp"
                            android:fontFamily="monospace"
                            android:letterSpacing="0.05"
                            android:textStyle="bold"
                            android:gravity="center"
                            android:maxLines="1" />
                    </FrameLayout>
                </FrameLayout>

            </LinearLayout>
        </LinearLayout>
```

### new_string
```
        <!-- ═══════════════════════════════════════════ -->
        <!-- HERO BUBBLE — single bubble, large, centered.   -->
        <!-- Replaces the 3-slot "PICK YOUR BUBBLE" dock.    -->
        <!-- Theme is pulled from `bubble_theme` pref on     -->
        <!-- resume; tap spawns the floating bubble at the   -->
        <!-- finger position; drag-and-drop-back-to-home     -->
        <!-- still parks via the single dock rect.           -->
        <!-- ═══════════════════════════════════════════ -->
        <LinearLayout
            android:id="@+id/bubbleDock"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:orientation="vertical"
            android:gravity="center">

            <FrameLayout
                android:id="@+id/heroBubble"
                android:layout_width="160dp"
                android:layout_height="160dp"
                android:background="@drawable/bubble_bg_idle"
                android:elevation="6dp"
                android:clickable="true"
                android:focusable="true">

                <FrameLayout
                    android:layout_width="96dp"
                    android:layout_height="96dp"
                    android:layout_gravity="center"
                    android:background="@drawable/bubble_inner_knob">

                    <View
                        android:layout_width="6dp"
                        android:layout_height="14dp"
                        android:layout_gravity="top|center_horizontal"
                        android:layout_marginTop="4dp"
                        android:background="@drawable/knob_tick" />

                    <TextView
                        android:id="@+id/heroBubbleLabel"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_gravity="center"
                        android:text="IDLE"
                        android:textColor="#FF555560"
                        android:textSize="14sp"
                        android:fontFamily="monospace"
                        android:letterSpacing="0.05"
                        android:textStyle="bold"
                        android:gravity="center"
                        android:maxLines="1" />
                </FrameLayout>
            </FrameLayout>
        </LinearLayout>
```

---

## Edit at jsonl line 2541 — MainActivity.kt

### old_string
```
    // ── Bubble dock: 3 parked bubble variants — coat rack model. ──
    // At rest: all three are "on the hanger." Tap one to pick it up — that
    // slot ghosts out (you took the coat) and the floating bubble appears.
    // Drag the bubble back onto any slot to hang it up again — that slot
    // becomes solid, the bubble disappears. Each slot mirrors the live
    // floating bubble's geometry (72dp bezel, 44dp knob, tick, IDLE label).
    private fun setupBubbleDock() {
        val dockBronze = findViewById<android.view.View>(R.id.dockBronze) ?: return
        val dockAluminum = findViewById<android.view.View>(R.id.dockAluminum) ?: return
        val dockGunmetal = findViewById<android.view.View>(R.id.dockGunmetal) ?: return
        val slots = mapOf(
            "bronze" to dockBronze,
            "aluminum" to dockAluminum,
            "gunmetal" to dockGunmetal
        )
        dockSlots = slots

        // Touch handler (not click) so we can read the finger's screen position
        // and spawn the bubble AT that spot — "natural pickup" feel. Tap and
        // drag both pick it up; drag just uses the latest finger position.
        val dragThresholdPx = (8f * resources.displayMetrics.density)
        slots.forEach { (key, view) ->
            var startRawX = 0f
            var startRawY = 0f
            var downRawX = 0f
            var downRawY = 0f
            var handled = false
            view.setOnTouchListener { tapped, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startRawX = event.rawX
                        startRawY = event.rawY
                        handled = false
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        if (!handled) {
                            // Drag past threshold → spawn bubble at current finger.
                            val dx = event.rawX - downRawX
                            val dy = event.rawY - downRawY
                            if (kotlin.math.hypot(dx, dy) > dragThresholdPx) {
                                handled = true
                                pickUpBubble(key, tapped, event.rawX.toInt(), event.rawY.toInt())
                            }
                        } else {
                            // Already picked up — pipe the finger's screen coords
                            // straight to the service so the overlay tracks the
                            // drag even though its window never received the touch.
                            BubbleService.instance?.moveBubbleTo(
                                event.rawX.toInt(),
                                event.rawY.toInt()
                            )
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!handled) {
                            // Tap (no drag) — spawn at slot center.
                            val loc = IntArray(2)
                            tapped.getLocationOnScreen(loc)
                            val cx = loc[0] + tapped.width / 2
                            val cy = loc[1] + tapped.height / 2
                            pickUpBubble(key, tapped, cx, cy)
                        } else {
                            // Drag finished — if the user released over any
                            // dock slot, magnetic-snap the bubble into it.
                            // Same-slot release re-parks (returns the bubble
                            // to its original home). Different-slot release
                            // both parks AND swaps the theme. Single-gesture
                            // pickup-and-redock without lifting the finger.
                            BubbleService.instance?.tryDockSnap()
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        handled = false
                        true
                    }
                    else -> false
                }
            }
        }

        // Initial paint — covers the case where BubbleService is already
        // running with a visible bubble when MainActivity opens.
        refreshDockGhost()

        // Publish dock rects once layout settles so BubbleService's drag-snap
        // knows where the dock is. Re-published on every resume (rotation /
        // window-metric changes would otherwise leave stale rects).
        dockBronze.post { publishDockRects(slots) }
    }
```

### new_string
```
    // ── Hero bubble: single bubble at home-screen center. ──
    // Replaces the old 3-slot "PICK YOUR BUBBLE" dock per the Chime-style
    // single-hero design call. The bubble's bezel + knob drawables are set
    // from the active `bubble_theme` pref each time MainActivity resumes.
    // Tap or drag spawns the floating bubble at the finger position; drag-
    // back-to-home snaps via the single dock rect.
    private fun setupBubbleDock() {
        val hero = findViewById<android.view.View>(R.id.heroBubble) ?: return
        val activeTheme = WimApp.bubbleTheme()
        // Apply the active theme's bezel + knob drawables to the hero.
        val (bezel, knob, labelColor) = when (activeTheme) {
            "aluminum" -> Triple(R.drawable.bubble_bezel_aluminum, R.drawable.bubble_knob_aluminum, 0xFF555560.toInt())
            "gunmetal" -> Triple(R.drawable.bubble_bezel_gunmetal, R.drawable.bubble_knob_gunmetal, 0xFFC8B48A.toInt())
            else       -> Triple(R.drawable.bubble_bg_idle,        R.drawable.bubble_inner_knob,    0xFF555560.toInt())
        }
        hero.setBackgroundResource(bezel)
        // The inner knob is a child FrameLayout — the first one inside the hero.
        (hero as? android.view.ViewGroup)?.getChildAt(0)?.setBackgroundResource(knob)
        findViewById<android.widget.TextView>(R.id.heroBubbleLabel)?.setTextColor(labelColor)

        val slots = mapOf(activeTheme to hero)
        dockSlots = slots

        val dragThresholdPx = (8f * resources.displayMetrics.density)
        var downRawX = 0f
        var downRawY = 0f
        var handled = false
        hero.setOnTouchListener { tapped, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    handled = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!handled) {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (kotlin.math.hypot(dx, dy) > dragThresholdPx) {
                            handled = true
                            pickUpBubble(activeTheme, tapped, event.rawX.toInt(), event.rawY.toInt())
                        }
                    } else {
                        BubbleService.instance?.moveBubbleTo(event.rawX.toInt(), event.rawY.toInt())
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!handled) {
                        val loc = IntArray(2)
                        tapped.getLocationOnScreen(loc)
                        val cx = loc[0] + tapped.width / 2
                        val cy = loc[1] + tapped.height / 2
                        pickUpBubble(activeTheme, tapped, cx, cy)
                    } else {
                        BubbleService.instance?.tryDockSnap()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> { handled = false; true }
                else -> false
            }
        }

        refreshDockGhost()
        hero.post { publishDockRects(slots) }
    }
```

---

## Edit at jsonl line 2556 — MainActivity.kt

### old_string
```
        // Re-publish dock slot screen rects so BubbleService's drag-snap is
        // aware while MainActivity is visible. Done after the next layout
        // pass; rects at onResume are often (0,0,0,0) before layout settles.
        val dockBronze = findViewById<android.view.View>(R.id.dockBronze)
        val dockAluminum = findViewById<android.view.View>(R.id.dockAluminum)
        val dockGunmetal = findViewById<android.view.View>(R.id.dockGunmetal)
        if (dockBronze != null && dockAluminum != null && dockGunmetal != null) {
            val slots = mapOf(
                "bronze" to dockBronze,
                "aluminum" to dockAluminum,
                "gunmetal" to dockGunmetal
            )
            dockBronze.post { publishDockRects(slots) }
        }
```

### new_string
```
        // Re-publish hero bubble screen rect so BubbleService's drag-snap is
        // aware while MainActivity is visible. Done after the next layout
        // pass; rects at onResume are often (0,0,0,0) before layout settles.
        // Re-runs setupBubbleDock to apply any theme change made via the
        // bubble menu since onPause.
        setupBubbleDock()
```

---
