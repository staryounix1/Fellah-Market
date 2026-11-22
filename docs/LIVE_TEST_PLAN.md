# Fellah-Market — Live Verification & QA Test Plan

This document covers the end-to-end tests to run against a **real Supabase project** once you have configured `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `DEMO_MODE=false`, disabled **Confirm email** in Supabase Auth, applied migrations `001`–`004` + `seed.sql`, and deployed `supabase/functions/admin-reset-pin`.

---

## 1. Automated Verification Summary (Container)

| Suite | Status | Scope Covered |
|---|---|---|
| `gradle :app:testDebugUnitTest` (Domain Unit Tests) | Run locally in workspace | Moroccan phone normalization, `toSyntheticEmail` (`2126XXXXXXXX@fellah.app`), diagnostic masking (`+212••••33`), weak/repeated/sequential PIN rejection, 5-failure / 15-minute lockout timer, deal state machine, analytics, dynamic schema validator |
| `gradle :app:testDebugUnitTest` (`MockWebServer` + Room) | Run locally in workspace | `/auth/v1/signup` & `/token?grant_type=password`, duplicate signup mapping, 5-failure lockout blocking 6th HTTP call, `changePin` + `must_change_pin=false`, `admin-reset-pin` Bearer call, token auto-refresh, PostgREST pagination, offline sync backoff, RPCs, `SignedUrlMediaCache` |
| `gradle :app:testDebugUnitTest` (Robolectric Compose UI) | Run locally in workspace | Language onboarding, `PhonePinAuthScreen` (`DEMO_MODE=false` hides demo shortcuts, Sign Up reveals confirm PIN), `DiagnosticsScreen` masks phone & hides synthetic email, `OfflineStatusBanner` retry |
| `supabase/tests/rls_tests.sql` | Needs live/local Postgres (`psql`) | Duplicate phone rejection trigger, privilege escalation prevention (`role`/`verification_status`/`must_change_pin`), `pin_reset_audit` admin-only RLS, offers/deals/storage RLS |
| `supabase/functions/admin-reset-pin/index.ts` | Needs live Supabase Edge runtime | Admin role verification, strong temporary PIN check, Admin Auth `updateUserById`, audit log insertion |
| End-to-End Android Device / Streaming Emulator Flows | Needs live Android device + Supabase | Full UI walkthrough with real network, `PickVisualMedia`, `WorkManager` upload, hardware `MediaRecorder` |

---

## 2. Backend Pre-Flight Checks

1. Run SQL migrations `001` through `004` and `seed.sql`.
2. Verify **Authentication → Providers → Email → Confirm email** is **OFF**.
3. Run SQL RLS test suite (rolls back cleanly):
   ```bash
   psql "$SUPABASE_DB_URL" -v ON_ERROR_STOP=1 -f supabase/tests/rls_tests.sql
   ```
4. Deploy the Admin PIN Reset Edge Function:
   ```bash
   supabase functions deploy admin-reset-pin
   ```
5. Optionally seed QA accounts (`0661112233`, `0662334455`, `0660000001`, PIN `258369`) via `supabase/tests/test_users.sql`.

---

## 3. Live End-to-End Test Cases

### Test 1: Sign-Up Validation, Duplicate Protection & No SMS/OTP
1. Launch app with `DEMO_MODE=false` configured against your Supabase project.
2. Confirm quick demo buttons are **not** visible, and there is no SMS/OTP screen or resend timer.
3. Tap **Sign Up** tab:
   - Enter `0661998877` + weak PIN `123456` → confirm client blocks weak sequence (`pin_weak_sequence_error`).
   - Enter `0661998877` + `111111` → confirm client blocks repeated digits (`pin_weak_repeated_error`).
   - Enter mismatched confirm PIN → confirm mismatch error (`pin_mismatch_error`).
   - Enter strong PIN `482916` + confirm `482916` → complete Farmer profile + submit.
   - Verify in Supabase **Authentication → Users** that the account exists with hidden email `212661998877@fellah.app` and `user_metadata.phone = "+212661998877"`, and `public.profiles` has `verification_status = 'pending'`.
4. Log out, tap **Sign Up** with the **same** phone `0661998877` → confirm generic error (`phone_already_registered_error`) appears without leaking account details.

### Test 2: Login, Generic Error & 5-Attempt Client Lockout
1. On **Login** tab:
   - Enter an unregistered phone + PIN `482916` → confirm error says **"Phone or PIN is incorrect"** (generic).
   - Enter registered phone `0661998877` + wrong PIN `994411` → same generic error.
   - Fail **5 times** total → confirm **5th attempt triggers 15-minute lockout countdown** and disables the Login button.
   - Force-close and reopen the app → confirm lockout countdown persists via DataStore.

### Test 3: Change PIN from Profile
1. Log in as Farmer (`0661998877` / `482916`).
2. Open **Profile → Change PIN**.
3. Enter wrong current PIN → confirm `wrong_current_pin_error`.
4. Enter current PIN `482916`, new PIN `739158`, confirm `739158` → save → verify success banner.
5. Log out and verify old PIN fails while new PIN `739158` logs in cleanly.

### Test 4: Admin Phone-Call PIN Reset + Audit Log + Forced Change PIN
1. Sign in as Admin (`0660000001`).
2. Open **Admin → Verifications** tab, locate farmer `0661998877`, tap **Reset PIN**.
3. Verify warning prompts admin to confirm caller identity by phone call; enter strong temporary PIN `619482` and confirm.
4. Check SQL Editor:
   ```sql
   select id, admin_id, target_user_id, created_at from public.pin_reset_audit order by created_at desc limit 5;
   select id, must_change_pin from public.profiles where phone = '+212661998877';
   ```
   - Confirm audit row exists and **contains no PIN column**.
   - Confirm `must_change_pin = true`.
5. Log in as farmer `0661998877` with temporary PIN `619482`:
   - Confirm app immediately displays `ForceChangePinScreen` blocking marketplace access until user sets a new personal PIN `385194`.
   - After changing PIN, confirm `must_change_pin = false` in DB and normal dashboard loads.

### Test 5: Diagnostics Phone Masking
1. Open **Profile → Diagnostics**.
2. Confirm:
   - Phone displays masked as `+212••••77` (never `+212661998877` or `0661998877`).
   - Synthetic email (`212661998877@fellah.app`) and JWT tokens never appear on screen or in `adb logcat`.

### Test 6: Marketplace Core Flow (Verification, Offers, Negotiation, Phone Unmasking, Offline Sync)
1. Approve farmer via Admin → Verifications (including signed verification document view).
2. Create an offer with compressed image + <=60s voice note (`MediaUploadWorker` uploads to private buckets).
3. As verified Buyer, verify farmer phone is hidden before deal acceptance, counter/accept via `deal_action` RPC, verify phone unmasks on `accepted`, complete deal via dual confirmation, and submit rating.
