# WiM Play Console release checklist

Do these in order. Code cannot perform these account-owner confirmations.

Permanent Play package: `com.wimlabs.wim`.

1. **Payments profile:** Play Console → Download reports → Financial → set up/link the merchant payments profile if it is not already linked.
2. **Backend API user:** Play Console → Users and permissions → Invite new user → `wim-backend@bakers-agent.iam.gserviceaccount.com`.
   - Grant **View financial data, orders, and cancellation survey responses**.
   - Grant **Manage orders and subscriptions**.
   - Backend lifecycle worker: deploy entry point `billing_event` as `wim-billing-events`, triggered by Pub/Sub topic `projects/bakers-agent/topics/wim-play-billing`.
   - Grant `google-play-developer-notifications@system.gserviceaccount.com` **Pub/Sub Publisher** on that topic.
   - Grant the Pub/Sub service agent **Service Account Token Creator** so authenticated Eventarc delivery works.
   - Grant `wim-backend@bakers-agent.iam.gserviceaccount.com` **Eventarc Event Receiver** and **Cloud Run Invoker** on `wim-billing-events`.
3. **Subscription:** Monetize → Products → Subscriptions → create:
   - Product ID: `wim_cloud_monthly`
   - Name: `WiM Cloud`
   - Description: `300 cloud dictations, including up to 20 Layer IV dictations, each month.`
   - Base plan ID: `monthly`
   - Type: auto-renewing, monthly
   - Activate it. Set the US base price to **$9.99/month** (pinned 2026-07-16; cost ceiling ~$12/user) before testing/publishing; other currencies may use Google's conversion.
   - Do not add a free trial or introductory offer at launch. Keep the purchase terms simple until real usage and cost data exist.
   - Monetization setup → Real-time developer notifications: set `projects/bakers-agent/topics/wim-play-billing`, choose subscription notifications, then send a test notification and confirm `wim-billing-events` logs it.
4. **Privacy policy URL:** `https://mybakingcreations.com/wim-android/privacy.html` (repo-controlled at `docs/privacy.html`; the older `/wim-privacy-policy` page stays live but the repo page is canonical)
   - Account deletion URL: `https://mybakingcreations.com/wim-delete-account`
5. **Data Safety:** enter [data_safety_answers.md](data_safety_answers.md).
6. **Accessibility Service:** enter [accessibility_service_declaration.md](accessibility_service_declaration.md) and upload the unlisted review video described there.
7. **Foreground services:** declare `microphone` and `specialUse` using [foreground_service_declaration.md](foreground_service_declaration.md). The Accessibility review video can cover both declarations if it includes the notification's **STOP WiM** action.
8. **Health apps:** select **My app doesn't provide any health features**, following [health_apps_declaration.md](health_apps_declaration.md).
9. **App signing:** the upload certificate SHA-1/SHA-256 are already registered on Firebase app `com.wimlabs.wim`. Enable Play App Signing when uploading the first AAB, then copy the separate **App signing key certificate SHA-1 and SHA-256** from Play Console → App integrity into that Firebase app and download the refreshed `google-services.json`.
10. **Closed testing (production-access gate):** upload the signed AAB to a **closed** track — the 12-testers-for-14-days requirement counts closed-track testers only. Create the email-list of testers (see [tester_recruitment_post.md](tester_recruitment_post.md)), publish the track, then share the opt-in link. Internal track is optional extra; License testing accounts still go under Settings → License testing so billing uses test purchases.
11. **Run the phone matrix:** signed-in L2/L3/L4, Spanish/French/Russian/Portuguese, silent blocks, local-model fallback, Script Prep, baseline calibration, subscribe, renew, cancel-at-period-end, payment hold, restore on a second device/account reinstall, quota exhaustion, and account deletion.

Do not promote to production until the backend service-account invite, Play product activation, RTDN test, Firebase Play-signing fingerprints, and internal-track billing lifecycle tests all pass.
