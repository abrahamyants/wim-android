# WiM Wear OS Companion — Scaffold

Wear OS companion app for WiM. Tap a watch complication, record PCM on the
watch, stream it to the paired phone over Wearable Data Layer.

**Status:** scaffold only. Phone logs received PCM but does NOT feed it into
the reconstruction pipeline yet. Integration into `BubbleService.stopAndProcess`
is phase 2.

## Pair flow

No manual pairing code. The user:

1. Installs the phone app (`:app`) via whatever the normal distribution
   channel is.
2. Installs the watch app (`:wear`) via the Wear OS companion app on the
   phone (Play Store sideload or adb to the watch).
3. The Wearable Data Layer auto-discovers the paired phone — no QR codes,
   no pairing keys. As long as the two are already paired as Wear OS devices
   at the system level, the app-to-app Data Layer works immediately.

## Architecture

```
  [ Watch ]                          [ Phone ]
                                     
  WimComplicationService               WearListenerService
     |  (user taps)                    (WearableListenerService)
     v                                   ^
  MainActivity                           |  onChannelOpened()
     |  (start/stop button)              |
     v                                   |
  PhoneStreamer                          |
     |  AudioRecord 16kHz/mono/16bit     |
     +--> ChannelClient --> /wim/pcm-stream  -->
```

- **Channel path:** `/wim/pcm-stream`
- **PCM format:** 16 kHz / mono / 16-bit, little-endian. Matches phone-side
  `AudioRecorder.kt` so downstream consumers are format-compatible.
- **Transport:** `ChannelClient` (not `DataClient`, not `MessageClient`).
  Channels are designed for bulk streaming — bytes, not discrete events.

## Build

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew.bat :app:assembleDebug     # phone APK
./gradlew.bat :wear:assembleDebug    # watch APK
```

Debug builds only. No signing config, no App Bundle, no Play submission
metadata — this is a scaffold.

## How to test

1. Install both APKs (phone via adb to the phone, wear via adb to the watch
   or through the Wear OS companion).
2. Open the WiM complication picker on the watchface and drop the `WiM`
   complication onto any slot.
3. Tap the complication. The watch activity launches, asks for mic
   permission the first time, and starts recording immediately.
4. Speak into the watch mic for a few seconds.
5. On the phone, filter logcat for `WearListenerService`:

   ```
   adb logcat -s WearListenerService
   ```

   Expected output:
   ```
   WearListenerService: Channel opened from node=<watch-node-id> path=/wim/pcm-stream
   WearListenerService: WearListenerService received 1024 bytes
   WearListenerService: WearListenerService received 2048 bytes
   ...
   WearListenerService: Channel closed from node=<watch-node-id> reason=... code=0
   WearListenerService: Stream drained: total=<N> samples=<N/2> truncated=false
   ```

6. Tap the button on the watch to stop.

## Known gaps (scaffold scope)

- **No pipeline integration.** Phone receives and logs PCM; it does not feed
  those bytes to Whisper, the reconstruction engine, or `BubbleService`.
  Phase 2 work.
- **No UI feedback on the watch** beyond button text swap. No timer, no VU
  meter, no haptic on start/stop.
- **No phase-2 hooks** for partial-stream recovery if the channel drops
  mid-recording. If the channel closes unexpectedly, whatever bytes arrived
  are logged and discarded.
- **No retry logic** if no paired phone is reachable — `PhoneStreamer.start()`
  logs a warning and exits.
- **Wear module ic_launcher** is a placeholder using the phone's
  `ic_launcher_foreground.png` at one density. Real watch icon assets are
  future work.
