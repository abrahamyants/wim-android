# WiM Play Console release checklist

Do these in order. Code cannot perform these account-owner confirmations.

1. **Payments profile:** Play Console → Download reports → Financial → set up/link the merchant payments profile if it is not already linked.
2. **Backend API user:** Play Console → Users and permissions → Invite new user → `wim-backend@bakers-agent.iam.gserviceaccount.com`.
   - Grant **View financial data, orders, and cancellation survey responses**.
   - Grant **Manage orders and subscriptions**.
3. **One-time product:** Monetize → Products → In-app products → create:
   - Product ID: `wim_cloud_unlock`
   - Name: `WiM Cloud Unlock`
   - Type: one-time, non-consumable
   - Description: `Permanent access to cloud speech and Layers II–IV.`
   - Activate it. Set the US base price to **$3.99** before testing/publishing; other currencies may use Google's conversion.
4. **Privacy policy URL:** `https://mybakingcreations.com/wim-privacy-policy`
5. **Data Safety:** enter [data_safety_answers.md](data_safety_answers.md).
6. **Accessibility Service:** enter [accessibility_service_declaration.md](accessibility_service_declaration.md) and upload the unlisted review video described there.
7. **Health apps:** select **My app doesn't provide any health features**, following [health_apps_declaration.md](health_apps_declaration.md).
8. **App signing:** enable Play App Signing when uploading the first AAB. Copy the **App signing key certificate SHA-1 and SHA-256** from Play Console → App integrity into the Android app in Firebase project `bakers-agent`; then download the refreshed `google-services.json` for the repository.
9. **Internal testing:** upload the signed AAB, create an internal tester list, and add tester accounts under Settings → License testing so billing uses test purchases.
10. **Run the phone matrix:** signed-in L2/L3/L4, Spanish/French/Russian/Portuguese, silent blocks, local-model fallback, Script Prep, baseline calibration, buy, pending/cancel behavior, restore on a second device/account reinstall, and account deletion.

Do not promote to production until the backend service-account invite, Play product activation, Firebase Play-signing fingerprints, and internal-track billing restore test all pass.
