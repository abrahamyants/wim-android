# Privacy Policy for WiM

**Last Updated: [Date]**

This Privacy Policy describes how WiM ("we," "us," or "our") collects, uses, and protects your information when you use our mobile application. 

WiM is designed as an offline-first, privacy-respecting voice-to-text application. Our core philosophy is that your voice and your data belong to you.

## 1. Data Collection and Usage

### A. Microphone and Audio Data
To provide voice-to-text functionality, WiM requires access to your device's microphone. 
- **On-Device Processing:** By default, all audio processing and transcription are performed locally on your device using on-device models (such as Vosk or Moonshine). Your audio data and raw transcripts are **not** sent to our servers.
- **Optional Cloud Transcription (OpenAI Whisper):** If you explicitly choose to enable our optional cloud-based transcription service (e.g., OpenAI Whisper) for enhanced accuracy, your audio data will be securely transmitted to that third-party provider solely for the purpose of transcription. We do not store this audio on our servers.

### B. Accessibility Service
WiM may utilize the Android Accessibility Service API to enable seamless text insertion (e.g., placing transcribed text directly into other applications via our floating bubble). 
- We only use this service to paste the text you have explicitly dictated.
- We **do not** use the Accessibility Service to monitor, log, or collect data from other applications on your device, nor do we track your interactions outside of the text insertion process.

## 2. Data Storage and Syncing

### A. Primary Local Storage
All generated text, settings, and usage preferences are stored locally on your device. We do not have access to this data.

### B. Optional Cloud Sync (Firebase/Firestore)
Users may optionally create an account and sign in to enable cross-device syncing. 
- If you opt into this feature, your user profile, app settings, and saved transcripts will be securely stored in Google Firebase/Firestore.
- This data is used exclusively to sync your experience across devices. We do not sell, rent, or share this data with unauthorized third parties.

## 3. Third-Party Services
WiM may use the following third-party services, which have their own privacy policies:
- **Google Play Services:** For app distribution and billing.
- **Google Firebase/Firestore:** (Optional) For user authentication and data syncing.
- **OpenAI:** (Optional) If you enable cloud-based transcription via Whisper.

## 4. User Rights and Data Retention (GDPR & HIPAA Compatibility)
While WiM is not inherently a medical application, we adhere to strict data minimization principles compatible with standard health and privacy regulations (like GDPR).
- **Data Deletion:** If you use our optional cloud sync, you can delete your account and all associated data directly within the app settings. Upon deletion, all your data is permanently removed from our Firestore database.
- **Local Data:** You can clear all locally stored data at any time by clearing the app's data in your Android system settings or uninstalling the app.
- **Data Retention:** We retain your optional cloud data only for as long as your account is active.

## 5. Security
We implement industry-standard security measures to protect any data transmitted via our optional cloud features. However, please remember that no method of transmission over the internet or electronic storage is 100% secure.

## 6. Changes to This Privacy Policy
We may update our Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy on this page and updating the "Last Updated" date.

## 7. Contact Us
If you have any questions or suggestions about our Privacy Policy, do not hesitate to contact us at: **[Your Support Email Address Placeholder]**.
