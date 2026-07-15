# Google Play Data Safety answers — WiM

Use these answers for the release represented by this repository. Re-check them whenever an SDK or data flow changes.

## Security and account controls

- Does the app collect or share required user data types? **Yes — collects.**
- Is all collected user data encrypted in transit? **Yes.**
- Can users request deletion? **Yes — in-app Settings → User Profile → Delete account and cloud data.**
- Account creation: **Optional for local L1; required for paid cloud features.**
- Data shared with third parties: **No for the Play form.** OpenAI, Anthropic, Firebase, and Google Cloud act as service providers processing data on our behalf. Do not select advertising, sale, or cross-company profiling.

## Data types to declare as collected

| Play category | Data type | Required? | Ephemeral? | Purpose |
|---|---|---:|---:|---|
| Personal info | Email address | Optional | No | Account management |
| Personal info | User IDs | Optional | No | Account management, app functionality, fraud prevention |
| Financial info | Purchase history | Optional | No | App functionality, account management, fraud prevention |
| Audio | Voice or sound recordings | Optional | **Yes** | App functionality |
| App activity | App interactions | Automatic | No | Analytics, app functionality |
| App activity | Other user-generated content | Optional | No when sync is enabled; otherwise ephemeral for cloud requests | App functionality, personalization |
| App info and performance | Crash logs | Automatic | No | Analytics |
| App info and performance | Diagnostics | Automatic | No | Analytics, app functionality |
| App info and performance | Other app performance data | Automatic | No | Analytics |
| Device or other IDs | Device or other IDs | Automatic | No | Analytics, fraud prevention |

## Do not select

- Location, contacts, browsing history, health and fitness, SMS/call logs, photos/videos, files/documents, calendar, or payment-card/bank details.
- Advertising or marketing, creditworthiness, or data sale.

## Notes for reviewer consistency

- Local L1 sends no audio off device.
- Cloud audio is processed and not stored by WiM; mark voice recordings as processed ephemerally.
- Synced vocabulary, corrections, Script Prep/context, transcripts, and session history belong under other user-generated content.
- Google Play handles payment credentials. WiM stores only subscription entitlement metadata (product, base plan, order, state, expiry, and verification), quota counters, and a one-way purchase-token hash.
