# Google Play foreground service declaration — WiM

WiM declares `microphone` and `specialUse` foreground service types. Both support the app's core, user-visible assistive dictation workflow.

## Microphone

- **Functionality:** Records speech only after the user taps or holds the visible WiM bubble. The microphone foreground-service type is activated when capture starts and removed as soon as capture stops.
- **Why immediate execution matters:** Deferring capture would cut off the beginning of the user's requested utterance. Interrupting it can discard a sentence that a person with a speech block worked to produce.
- **User control:** The bubble visibly changes to the recording state. The user stops capture by tapping again or releasing a hold. Android's microphone indicator and WiM's ongoing notification remain visible.

## Special use

- **Functionality:** Keeps the user-enabled floating assistive bubble available while another app is foreground. This is the cross-application control the user taps to start dictation and the surface that shows recording, processing, clarification, paste, and send-ready states.
- **Manifest subtype:** `User-enabled persistent accessibility overlay for cross-application voice dictation; visible while other apps are foreground and stoppable from its ongoing notification`.
- **Why immediate execution matters:** Deferring or interrupting the overlay removes the user's voice-input control from the app where they are communicating and forces them to leave that task, reopen WiM, and reacquire the text field.
- **User control:** The user can hide the bubble by dragging it to the bottom of the screen and restore it from the notification. The notification includes **STOP WiM**, which removes the overlay and terminates the foreground service.

## Review video

The same unlisted video used for the Accessibility declaration can demonstrate both types:

1. Launch WiM and show the floating bubble plus the ongoing notification, including **STOP WiM**.
2. Open a messaging or notes app and tap the bubble. Show the recording state and Android microphone indicator.
3. Tap again to stop. Show processing and text insertion, confirming microphone capture ended.
4. Drag the bubble to hide it, then restore it from the notification.
5. Tap **STOP WiM** in the notification and show that the bubble disappears and the service notification is removed.
