# Privacy Policy for WiM (What I Meant)

**Last Updated: July 14, 2026**

This Privacy Policy describes how WiM ("we," "us," or "our") handles your information when you use the WiM mobile application ("the app"). Read it together with the Google Play store listing.

WiM is designed for people whose speech includes disfluencies (repetitions, prolongations, blocks, false starts, fillers) and for anyone who finds standard dictation tools rushed or inaccurate. The default behavior of the app is private and on-device. Cloud features are opt-in, require Google sign-in, and require an active WiM Cloud subscription.

## 1. What WiM does with your voice

WiM groups voice processing into four layers (L1 – L4). The layer you pick determines whether your audio leaves the device.

### Layer 1 — local on-device (default)
- All audio recording and transcription happen on your phone.
- Transcription runs against a local speech model (whisper-tiny.en, ~40 MB, bundled via the sherpa-onnx runtime).
- No dictation audio or transcript is transmitted to our servers or any speech provider. Standard reliability telemetry described in Section 5 may still be collected.
- You can also optionally enable a cloud transcription engine for L1 (OpenAI whisper-1) if you prefer cloud accuracy over local. This is off by default and surfaced as the "L1 Cloud" toggle in the bubble menu.

### Layer 2 / Layer 3 — cloud reconstruction (opt-in)
- Selecting L2 or L3 uploads your audio to our authenticated Cloud Function (`wim-reconstruct` on Google Cloud). The backend sends it to OpenAI's transcription API and sends the resulting transcript to OpenAI's GPT-4o model for grammar and tone cleanup.
- The third-party API key is held server-side, not on your device. A developer who explicitly enters a personal OpenAI key can instead use the direct API path.
- We do not store your audio. The Cloud Function keeps short-lived request logs (uid, latency, layer, model) for reliability; logs do not include the contents of your audio or transcripts.

### Layer 4 — deep speech-disfluency reconstruction (opt-in)
- L4 uploads audio through the same authenticated Cloud Function. The backend sends it to OpenAI's Whisper API (verbose mode) for confidence-aware transcription, then sends the transcript and derived acoustic context to Anthropic's Claude Sonnet model for deeper reconstruction.
- OpenAI and Anthropic credentials remain on the server and are not bundled into the release app.

### Script Prep and personal learning
- If you activate Script Prep, the notes you enter are sent with cloud transcription and reconstruction requests as context. The model is instructed not to insert those notes unless you actually dictate the content.
- WiM stores your personal speaking-rate, pitch, and energy baselines locally on your device. Cloud reconstruction can receive a short derived summary; the baseline history itself is not uploaded as profile data.

### What this means in practice
- Default local Layer 1 = no dictation audio or transcript leaves your phone. Standard reliability telemetry described in Section 5 may still be collected.
- Tap the layer selector and pick L2 or higher = audio and transcripts leave your phone via the providers above for that session.
- Switching back to L1 stops cloud transmission immediately.

## 2. Accessibility Service

WiM uses Android's Accessibility Service API to detect the text field you are typing into and to paste cleaned text back into that field via the floating bubble.

- The service is used only to find the active text field, inject the dictated text, and (if you enable Auto-Send) tap the Send button in the host app.
- We do not log or transmit the content of other apps, your password fields, or your interactions outside the moment you dictate.
- WiM blocks dictation entirely when the focused field is a password input.

## 3. Account, sync, and Firebase

If you sign in with Google, WiM creates an account in Google Firebase Authentication to associate your device with your subscription tier.

- Optional Firestore sync stores your learned vocabulary, corrections list, and per-session reconstruction history under your user ID in our Firestore database (`bakers-agent` project on Google Cloud).
- Sync is used only to keep your personal profile consistent across devices and to bill / quota-limit cloud requests.
- You can disable sync from the app's Settings overlay. You can delete the Firebase account and associated cloud data via Settings → User Profile → "Delete my cloud account and data," or request deletion at https://mybakingcreations.com/wim-delete-account.
- We do not share, sell, or rent your data to anyone.

## 4. Purchase information

WiM Cloud is an auto-renewing monthly subscription handled by Google Play Billing. The current US price is $5.99/month; Google Play displays the price that applies in your country before purchase. Each subscription billing period includes 300 cloud dictations, including up to 20 Layer IV dictations.

- Google Play processes your payment; WiM never receives or stores your card or bank details.
- The app sends the Play product ID, purchase token, and a one-way hash of your Firebase user ID to our authenticated backend.
- Our backend asks the Google Play Developer API to verify and acknowledge the subscription, then stores the product ID, base plan, Google Play order ID, subscription state, expiration time, verification time, and entitlement under your Firebase user ID.
- Purchase tokens are stored only as one-way hashes. Purchase records are used for access control, restoration, quota enforcement, fraud prevention, and customer support.
- You can cancel or manage the subscription in Google Play. Canceling stops future renewal; cloud access continues through the paid period unless Google Play reports a payment hold or other ineligible state.
- Deleting your cloud data removes the entitlement record from our database, but it does **not** cancel the Google Play subscription. Cancel it separately in Google Play. Your Google Play order remains in your Google account and in records Google must retain.

## 5. Crashes and product telemetry

The app uses Firebase Crashlytics, Firebase Performance, and Firebase Analytics to detect crashes and reliability issues.

- Crashlytics receives stack traces and device model. We do not attach transcripts or audio to crash payloads.
- Performance receives anonymous timing data (cold-start time, reconstruction latency).
- Analytics tracks anonymous feature usage so we can prioritize fixes.

These can be limited at the OS level via Google Play Services privacy controls.

## 6. Third-party services and processors

| Provider | Used for | Triggered when |
|---|---|---|
| Google Play Services | App distribution and sign-in | Always |
| Google Play Billing / Developer API | Subscription processing, verification, renewal status, and restoration | When you subscribe, restore, or use WiM Cloud |
| Google Firebase Authentication | Account creation | Only after you sign in with Google |
| Google Firestore | Profile + history sync | Only after sign-in, only if sync enabled |
| Google Firebase Crashlytics / Performance / Analytics | Reliability + telemetry | Always (can be limited via OS controls) |
| OpenAI (Whisper, GPT-4o) | Cloud transcription + reconstruction | Only when you choose L1 Cloud, L2, L3, or L4 |
| Anthropic (Claude Sonnet 4.6) | L4 reconstruction | Only when you choose L4 |

## 7. Local storage

All settings, learned vocabulary, your corrections list, and session history are stored on your device in standard Android SharedPreferences and SQLite. You can clear all of it at any time by clearing the app's data in Android Settings → Apps → What I Meant → Storage, or by uninstalling the app.

## 8. Children

WiM is not designed for children under 13 and is not directed at children.

## 9. Your rights

You can:
- Export the data we hold about you (Settings → User Profile → Export data).
- Delete the data we hold about you (Settings → User Profile → Delete account and cloud data).
- Limit Crashlytics, Performance, and Analytics via Google Play Services system settings.
- Stop all cloud transmission by selecting Layer 1 and disabling the L1 Cloud toggle.
- Cancel or manage WiM Cloud through Google Play subscriptions.

For users in jurisdictions with GDPR-style rights (EEA, UK, California, etc.), the export and delete tools above satisfy access and deletion requests.

## 10. Security

We use TLS for every network call to our backend and to third-party providers. No data transmission is 100% guaranteed, but we minimize what leaves the device and we never store raw audio.

## 11. Changes to this policy

If we update this policy we will publish the new version at the same URL and bump the "Last Updated" date. Material changes will be surfaced in the app on the next launch.

## 12. Contact

Questions about this policy: **gugosf@gmail.com**.
