# Fellah-Market (فلاح ماركت)

Direct B2B agricultural marketplace connecting Moroccan farmers & cooperatives directly to professional buyers (restaurants, hotels, grocery stores, cooperatives, and wholesalers), starting with **Souss-Massa** (Agadir, Taroudant, Chtouka Ait Baha, Inezgane, Ouled Teima, Tiznit).

> **Stack:** Kotlin + Jetpack Compose + Material 3 + Room (offline cache & sync queue) + DataStore + Android Keystore (AES-256-GCM session encryption) + OkHttp/Moshi + WorkManager + **Supabase** (Phone + PIN Auth via hidden synthetic email, PostgreSQL + PostgREST + RPCs + RLS, Edge Function `admin-reset-pin`, Private Storage Buckets).

---

## 0. Version 2.1.0 — Favorites & marketplace refinement

- Refined the agricultural brand palette with a richer emerald green, softer field surfaces, and warmer accent colors.
- Standardized the visible app name to **Fellah Market** across the default, Arabic, and French Android resources.
- Added account-scoped, offline-persistent saved offers (favorite heart action on offer cards).
- Added a marketplace Favorites filter so users can quickly view saved offers.
- Added Room schema migration `3 → 4` for the `favorite_offers` table.
- Added localized favorite labels in Darija, Arabic, and French.
- Updated Android version metadata to `versionCode = 3` and `versionName = 2.1.0`.
- Existing marketplace, offers, buyer requests, deals, ratings, notifications, offline queue, and localization flows are retained.

> Favorites are currently stored locally on this device and are scoped to the signed-in account; cross-device cloud sync is not implemented yet. Build and device verification are still required before distributing this version.

## 1. Supabase Backend Setup

1. Create a project at [supabase.com](https://app.supabase.com).
2. Under **Authentication → Providers → Email**, keep **Email** enabled and **disable "Confirm email"** (no SMS provider, no OTP; the app maps Moroccan E.164 phone numbers to hidden synthetic emails such as `2126XXXXXXXX@fellah.app` and stores the real number in `profiles.phone`). Set minimum password length to `6` and review **Authentication → Rate Limits**.
3. Apply the migrations and seed data using either the **Supabase CLI** or the **Supabase SQL Editor**:

   ```bash
   supabase login
   supabase link --project-ref YOUR_PROJECT_REF
   supabase db push
   psql "$SUPABASE_DB_URL" -f supabase/seed.sql
   supabase functions deploy admin-reset-pin
   ```

   Or execute sequentially in the **Supabase Dashboard → SQL Editor**:
   1. `supabase/migrations/001_initial_schema.sql` — Schema (`must_change_pin`), indexes, `public_profiles` phone-masking view, `market_price_averages` & `get_market_price_summary`, `enforce_deal_transition` trigger, `deal_action` RPC, and strict RLS policies.
   2. `supabase/migrations/002_storage_buckets.sql` — Private buckets (`offer-photos`, `voice-notes`, `verification-docs`) and storage RLS policies.
   3. `supabase/migrations/003_auth_triggers.sql` — `on_auth_user_created` profile bootstrap (email-based signup + duplicate phone rejection + `pending` default) and `trg_prevent_profile_privilege_escalation`.
   4. `supabase/migrations/004_pin_auth_and_reset_audit.sql` — `public.pin_reset_audit` table with admin-only read RLS (no plain-text PINs stored).
   5. `supabase/seed.sql` — Souss-Massa region, 8 categories, 24 agricultural products + dynamic JSON attribute schemas, and sample reference prices.

4. **Promote the First Admin User** (admins can never self-assign via the client app):
   - Sign up in the Android app with your admin phone (`+212660000001`) + strong PIN.
   - Run in the SQL Editor:
     ```sql
     update public.profiles
     set role = 'admin',
         verification_status = 'verified',
         full_name = 'Fellah Admin'
     where phone = '+212660000001';
     ```

---

## 2. Environment Configuration & `DEMO_MODE` Switching

Only the **Project URL** and **anon public key** are ever placed in the mobile app (never `service_role`):

- **AI Studio Secrets / `.env`:** Copy `.env.example` to `.env` or set values in AI Studio Secrets.
- **Local Developer Workstation:** Copy `local.properties.example` to `local.properties` (never commit secrets).

```properties
SUPABASE_URL=https://YOUR_PROJECT_ID.supabase.co
SUPABASE_ANON_KEY=YOUR_SUPABASE_ANON_KEY
DEMO_MODE=false
```

| Mode | Configuration | Behavior |
|---|---|---|
| **Production / Staging (`DEMO_MODE=false`)** | `SUPABASE_URL` & `SUPABASE_ANON_KEY` set, `DEMO_MODE=false` | Real Phone + PIN via `/auth/v1/signup`, `/auth/v1/token?grant_type=password`, `/auth/v1/user`, `/functions/v1/admin-reset-pin`, 1-tap demo accounts disabled, PostgREST + RPC + Private Storage + foreground polling sync active. |
| **Demo / Offline Fallback (`DEMO_MODE=true`)** | Omit keys or set `DEMO_MODE=true` | Runs against local Room seed data with salted SHA-256 PIN storage in DataStore (default QA demo PIN `258369` for seeded accounts), displays quick demo Farmer/Buyer/Admin login buttons. |

**Security protections enforced in code:**
- Hidden synthetic email helper (`PhoneValidator.toSyntheticEmail`) is never displayed in UI or logs.
- Login errors return a generic wrong phone or PIN message without revealing which field was wrong; duplicate signups return a generic registration message without leaking registered phone numbers.
- Client-side lockout persists failed attempts across app restarts and locks login for **15 minutes after 5 failed attempts**.
- Tokens (`access_token`, `refresh_token`) are encrypted at rest via `AndroidKeystoreEncryptedStore` (`AES/GCM/NoPadding`).
- Tokens, full phone numbers (on Diagnostics screen), synthetic emails, and PINs are never logged or stored in plain text.
- Partner phone numbers are masked in Postgres (`public.public_profiles`) and in the UI until a deal reaches `accepted` or `completed`.

---

## 3. Running Tests

### Android Unit, Integration (`MockWebServer`), & Robolectric Compose UI Tests
```bash
gradle :app:testDebugUnitTest
```
Includes:
- `FellahDomainUnitTests` — Deal state machine transitions, dual completion, price analytics, dynamic JSON schema validation, Moroccan `+212`/`06`/`07` normalization, synthetic email mapping, diagnostic phone masking, PIN strength validator, 5-attempt/15-min lockout manager, and sync backoff progression.
- `SupabaseRepositoryIntegrationTest` — `MockWebServer` + in-memory Room tests for Phone + PIN signup/login, wrong credentials + 5-failure lockout, Change PIN + `must_change_pin` clearing, `admin-reset-pin` Edge Function call, token auto-refresh/revocation, `Range` pagination + `Content-Range` parsing, offline `sync_queue` backoff/retry & LWW conflict merge, `deal_action` & `get_market_price_summary` RPCs, and `SignedUrlMediaCache`.
- `ExampleRobolectricTest` & `FellahUiComponentsRobolectricTest` — End-to-end Compose onboarding flow, `DEMO_MODE=false` Phone+PIN verification, Diagnostics screen phone-masking assertion, and failed-sync retry banner tests.

### Supabase SQL RLS & Trigger Verification Suite
Run against a local or staging Supabase PostgreSQL instance (`BEGIN ... ROLLBACK` wrapped, leaves zero test data):
```bash
psql "$SUPABASE_DB_URL" -v ON_ERROR_STOP=1 -f supabase/tests/rls_tests.sql
```

### Build Debug APK
```bash
gradle :app:assembleDebug
```

---

## 4. Verification Status & Capabilities Matrix

| Capability | Verification Status | Implementation Notes |
|---|---|---|
| **Localization & RTL (Darija / AR / FR)** | Verified locally (Unit + Robolectric) | `values*/strings.xml` synchronized across all 3 locales with live RTL/LTR switching |
| **Phone + PIN Auth & Lockout** | Verified against `MockWebServer` + Unit + Robolectric | `/auth/v1/signup`, `/auth/v1/token?grant_type=password`, 5-attempt / 15-min DataStore lockout, generic error messages; requires manual QA against live Supabase |
| **Change PIN & Forced Temp PIN Gate** | Verified against `MockWebServer` + Room | Re-authenticates current PIN, `PUT /auth/v1/user`, clears `must_change_pin` |
| **Admin Phone-Call PIN Reset (`admin-reset-pin`)** | Verified client call (`MockWebServer`) + SQL audit schema | Edge Function TypeScript authored; requires live Supabase Edge Function deployment & QA |
| **SQL Triggers, Schema 004 & RLS** | Authored (`001`–`004`, `rls_tests.sql`) | Not executed in container (no Postgres runtime); verify via `psql ... -f supabase/tests/rls_tests.sql` |
| **Catalog, Offers, Deals & Market Prices** | Verified against `MockWebServer` + Unit Tests | Server-enforced transitions (`deal_action` RPC), phone masking until `accepted`, pagination & charts |
| **Offline Cache, Sync Queue & Diagnostics** | Verified against `MockWebServer` + Robolectric | Room cache, LWW merge, backoff (`5s..300s`), Diagnostics screen masks phone numbers (`+212••••33`) |
| **Private Media Uploads & Signed Cache** | Verified (`SignedUrlMediaCache` MockWebServer); WorkManager needs device | `offer-photos` / `voice-notes` / `verification-docs` via WorkManager |
| **Push Notifications / WhatsApp Alerts** | Foreground polling implemented | Background FCM/WhatsApp requires external webhook trigger (`NotificationChannelPort` ready) |

---

## 5. Known Risks & Limitations
1. **Live Supabase & Deno Edge Function Execution:** The container environment has no PostgreSQL or Supabase CLI runtime; `supabase/migrations/*.sql`, `supabase/functions/admin-reset-pin/index.ts`, and `supabase/tests/rls_tests.sql` must be verified against your live Supabase project using `/docs/LIVE_TEST_PLAN.md`.
2. **Killed-App Push Delivery:** Without an Edge Function + Firebase Cloud Messaging (FCM) webhook, notifications arrive while the app is open via `PollingNotificationSource` (~35s interval).
3. **Room Migration Policy:** `FellahDatabase` uses `fallbackToDestructiveMigration()` during MVP iteration; production app store upgrades should add explicit Room migrations.
4. **Device Constraints:** Hardware voice recording (`MediaRecorder`) and `WorkManager` background constraints require physical Android / emulator testing.

See `/docs/LIVE_TEST_PLAN.md` for step-by-step live verification scripts.
