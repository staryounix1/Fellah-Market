# Supabase Backend Setup — Fellah-Market

## 1. Prerequisites
- A Supabase project (`https://app.supabase.com`)
- **Email Auth** enabled (**Authentication → Providers → Email**) with **Confirm email = OFF** (disabled).
  - The Android app maps normalized Moroccan phone numbers (`+212...`) to hidden synthetic emails (`2126XXXXXXXX@fellah.app`) under the hood for **Phone Number + PIN** authentication.
  - **No SMS provider and no OTP** are used anywhere in the app.
- Recommended password/PIN policy in **Authentication → Providers → Email**: minimum password length `6` (client also blocks weak repeating/sequential PINs).
- Rate limiting enabled in **Authentication → Rate Limits** to complement the app's persisted 5-attempt / 15-minute lockout.
- Supabase CLI (`npm i -g supabase`) OR direct access to the **Supabase SQL Editor**.

## 2. Applying Migrations

### Option A: Supabase CLI (Recommended)
```bash
supabase login
supabase link --project-ref YOUR_PROJECT_REF
supabase db push
```

### Option B: Supabase Dashboard SQL Editor
Run the scripts in exact order:
1. `supabase/migrations/001_initial_schema.sql`
2. `supabase/migrations/002_storage_buckets.sql`
3. `supabase/migrations/003_auth_triggers.sql`
4. `supabase/migrations/004_pin_auth_and_reset_audit.sql`
5. `supabase/seed.sql` (regions, 8 categories, 24 products + schemas, sample reference prices)

## 3. Deploy the Admin PIN Reset Edge Function (`admin-reset-pin`)
Because there is no SMS/OTP recovery, verified admins can verify a user's identity by phone call and set a strong temporary PIN (forcing a PIN change on next login and writing an audit row without ever storing plain-text PINs).

Deploy the Edge Function from the project root:
```bash
supabase functions deploy admin-reset-pin
```
- Supabase Edge Functions automatically receive `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SUPABASE_SERVICE_ROLE_KEY` in Deno runtime (`Deno.env.get(...)`).
- Never place `SUPABASE_SERVICE_ROLE_KEY` in Android config or client code.

## 4. Creating the First Admin User
Admin roles can **never** be self-assigned from the Android app (enforced by RLS and `trg_prevent_profile_privilege_escalation`).

1. Sign up in the Android app with your admin phone number (`0660000001`) + strong PIN (at least 6 digits).
2. Run this SQL in the Supabase SQL Editor (running as `postgres` bypasses `auth.uid()` restriction):
```sql
update public.profiles
set role = 'admin',
    verification_status = 'verified',
    full_name = 'Fellah Admin'
where phone = '+212660000001';
```

## 5. Seed Test Users for QA (Optional Staging)
Execute `supabase/tests/test_users.sql` in staging to create QA Farmer (`0661112233`), Buyer (`0662334455`), and Admin (`0660000001`) accounts (default QA PIN `258369`).

## 6. Configuring the Android App
Set only your **Project URL** and **anon public key** (never the `service_role` key!) in AI Studio Secrets or `.env` (see `.env.example` / `local.properties.example`):
```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_ANON_KEY=eyJ...
DEMO_MODE=false
```

## 7. Running RLS Verification Tests
Execute `supabase/tests/rls_tests.sql` against a local or staging Postgres database:
```bash
psql "$SUPABASE_DB_URL" -v ON_ERROR_STOP=1 -f supabase/tests/rls_tests.sql
```
