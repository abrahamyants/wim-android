# WiM Play Console release checklist

Do these in order. Code cannot perform these account-owner confirmations.

1. **Payments profile:** Play Console → Download reports → Financial → set up/link the merchant payments profile if it is not already linked.
2. **Backend API user:** Play Console → Users and permissions → Invite new user → `wim-backend@bakers-agent.iam.gserviceaccount.com`.
   - Grant **View financial data, orders, and cancellation survey responses**.
   - Grant **Manage orders and subscriptions**.
   - Backend lifecycle worker: deploy entry point `billing_event` as `wim-billing-events`, triggered by Pub/Sub topic `projects/bakers-agent/topics/wim-play-billing`.
   - Grant `google-play-developer-notifications@system.gserviceaccount.com` **Pub/Sub Publisher** on that topic.
3. **Subscription:** Monetize → Products → Subscriptions → create:
   - Product ID: `wim_cloud_monthly`
   - Name: `WiM Cloud`
   - Description: `300 cloud dictations, including up to 20 Layer IV dictations, each month.`
   - Base plan ID: `monthly`
   - Type: auto-renewing, monthly
   - Activate it. Set the US base price to **$5.99/month** before testing/publishing; other currencies may use Google's conversion.
   - Do not add a free trial or introductory offer at launch. Keep the purchase terms simple until real usage and cost data exist.
   - Monetization setup → Real-time developer notifications: set `projects/bakers-agent/topics/wim-play-billing`, choose subscription notifications, then send a test notification and confirm `wim-billing-events` logs it.
4. **Privacy policy URL:** `https://mybakingcreations.com/wim-privacy-policy`
5. **Data Safety:** enter [data_safety_answers.md](data_safety_answers.md).
6. **Accessibility Service:** enter [accessibility_service_declaration.md](accessibility_service_declaration.md) and upload the unlisted review video described there.
7. **Health apps:** select **My app doesn't provide any health features**, following [health_apps_declaration.md](health_apps_declaration.md).
8. **App signing:** enable Play App Signing when uploading the first AAB. Copy the **App signing key certificate SHA-1 and SHA-256** from Play Console → App integrity into the Android app in Firebase project `bakers-agent`; then download the refreshed `google-services.json` for the repository.
9. **Internal testing:** upload the signed AAB, create an internal tester list, and add tester accounts under Settings → License testing so billing uses test purchases.
10. **Run the phone matrix:** signed-in L2/L3/L4, Spanish/French/Russian/Portuguese, silent blocks, local-model fallback, Script Prep, baseline calibration, subscribe, renew, cancel-at-period-end, payment hold, restore on a second device/account reinstall, quota exhaustion, and account deletion.

Do not promote to production until the backend service-account invite, Play product activation, RTDN test, Firebase Play-signing fingerprints, and internal-track billing lifecycle tests all pass.
