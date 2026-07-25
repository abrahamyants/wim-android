# Google Play Accessibility Service declaration — WiM

## Classification

- Is the app an accessibility tool? **Yes.**
- Disability type: **Other — speech and communication disabilities.**
- Target users: people whose speech includes stuttering, silent blocks, repetitions, prolongations, false starts, or other disfluencies.
- Manifest configuration: `android:isAccessibilityTool="true"`.

## Core accessibility need

A person managing a speech block may already be holding alternate words, sentence repairs, and intended meaning in working memory. Leaving the conversation, finding the text field again, or visually hunting for a Send button that moves when the keyboard appears adds cognitive load at exactly that moment.

WiM keeps a user-invoked voice-typing control available across apps. The Accessibility Service lets WiM return the user's reconstructed words to the field they chose without making them switch apps or reacquire changing controls.

## What the service does

1. Receives window-change and view-focus events so it can identify the foreground app and currently focused editable field.
2. Rejects password fields.
3. When the user invokes dictation, reads up to 200 characters immediately before the cursor to preserve sentence continuity. Local Layer 1 keeps this context on the device. If the user deliberately selects a cloud layer, the short context is encrypted in transit and included in that reconstruction request.
4. Inserts or replaces only the dictated text in the focused field. If the write fails, it preserves the user's words on the clipboard instead of losing them.
5. With tap-to-send assist active, locates the visible Send control. WiM may move its bubble to that control, but it clicks Send only after a separate user tap on the green bubble. The five-second window expires without sending. The assist is enabled by default and can be switched off in Settings; enabling it does not send anything on its own, it only lets the bubble offer.

## What it does not do

- Does not subscribe to text-change, click, scroll, or key events.
- Does not read or act on password fields.
- Does not send messages automatically or when a countdown expires.
- Does not make autonomous decisions or perform unrelated actions.
- Does not use Accessibility data for advertising, sale, or cross-app profiling.

## In-app disclosure shown before Android Settings

> Speech blocks already use attention. WiM's Accessibility Service finds the text field you chose, reads up to 200 characters before the cursor to keep your sentence coherent, and inserts your dictated text. Local Layer 1 keeps this on your phone; cloud layers send the short context with your dictation. Tap-to-send assist is on by default: WiM finds Send but presses it only after your separate green-bubble tap, and you can switch the assist off in Settings. Password fields are blocked, and unrelated content is not read.

The screen provides **OPEN ACCESSIBILITY SETTINGS** and **NOT YET** actions. Accessibility access remains off unless the user enables WiM in Android Settings and can be disabled there at any time.

## Review video script (roughly 60–75 seconds)

1. Open WiM from a clean install and navigate to the Accessibility explanation.
2. Keep the full disclosure visible long enough to read, including the local-versus-cloud context sentence.
3. Tap **NOT YET** to show that denial is respected, then return and reopen the explanation.
4. Tap **OPEN ACCESSIBILITY SETTINGS**, enable WiM, and return to the app.
5. Open a messaging or notes app and focus a normal text field.
6. Tap the WiM bubble, dictate one sentence, stop, and show WiM inserting the result.
7. Dictate again with tap-to-send assist (on by default), show the green five-second SEND state, and let it expire without sending. Then show the Settings switch that turns the assist off.
8. Repeat and tap the green bubble to demonstrate that the separate tap is the final authorization.
9. Focus a password field and show that WiM blocks dictation.
10. Return to Android Settings and show that the Accessibility Service can be disabled.

Upload the unlisted video in the Play Console declaration and use this same explanation in reviewer notes.
