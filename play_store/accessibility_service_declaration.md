# Google Play Accessibility Service declaration — WiM

## Classification

- Is the app an accessibility tool? **Yes.**
- Primary purpose: voice typing for people whose speech includes stuttering, blocks, repetitions, prolongations, false starts, and other disfluencies.
- Manifest configuration: `android:isAccessibilityTool="true"`.

## What the service does

WiM uses AccessibilityService only while providing its user-invoked dictation workflow:

1. Detect the currently focused editable text field.
2. Insert the transcript the user created by tapping WiM's floating microphone bubble.
3. If the user separately enables Auto-Send, locate and tap the host app's Send button after showing the user the completed text.
4. Refuse to capture or insert text when the focused field is a password field.

## What it does not do

- No continuous monitoring of screen contents.
- No collection of passwords, private messages, browsing behavior, or unrelated interactions.
- No autonomous decisions or actions unrelated to dictation.
- No advertising, profiling, or sale of accessibility data.
- Auto-Send is off by default and requires an explicit user opt-in.

## Prominent disclosure text

> WiM uses Accessibility Service to find the text box you are currently typing in and paste your dictated words into it. If you turn on Auto-Send, WiM can also tap that app's Send button after showing your completed text. WiM does not read password fields or monitor unrelated activity. Accessibility access stays off until you enable it in Android Settings, and you can turn it off at any time.

## Review video script (roughly 45–60 seconds)

1. Open WiM onboarding and show the Accessibility explanation before the system settings link.
2. Open Android Accessibility settings and enable WiM.
3. Open a messaging or notes app and focus a normal text field.
4. Tap the WiM bubble, dictate one sentence, stop, and show WiM inserting the result.
5. Focus a password field and show that WiM blocks dictation.
6. Return to WiM Settings, show Auto-Send off by default, then show the Accessibility permission can be disabled.

Upload this unlisted video in the Play Console declaration and use the exact same explanation in the reviewer notes.
