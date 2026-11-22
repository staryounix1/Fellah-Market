-- Fellah-Market MVP — QA Test Users Seed (Synthetic Email + PIN Auth)
-- Prerequisite: Run migrations 001..004 and seed.sql first, and disable "Confirm email" in Supabase Auth.
-- Maps Moroccan E.164 phones to synthetic emails: +2126XXXXXXXX -> 2126XXXXXXXX@fellah.app
-- Default QA PIN for seeded accounts: 258369 (meets min 6 digits, non-sequential, non-repeated)

begin;

insert into auth.users (
  id, instance_id, aud, role, email, encrypted_password, email_confirmed_at, raw_user_meta_data, created_at, updated_at
)
values
  (
    'aaaaaaaa-0000-4000-8000-000000000001',
    '00000000-0000-0000-0000-000000000000',
    'authenticated',
    'authenticated',
    '212661112233@fellah.app',
    crypt('258369', gen_salt('bf')),
    now(),
    '{"phone":"+212661112233","full_name":"لحسن أمين (فلاح)","role":"farmer","region_id":"reg_souss_massa","city":"اشتوكة أيت باها"}'::jsonb,
    now(),
    now()
  ),
  (
    'aaaaaaaa-0000-4000-8000-000000000002',
    '00000000-0000-0000-0000-000000000000',
    'authenticated',
    'authenticated',
    '212662334455@fellah.app',
    crypt('258369', gen_salt('bf')),
    now(),
    '{"phone":"+212662334455","full_name":"مطعم تافراوت","role":"buyer","region_id":"reg_souss_massa","city":"أكادير"}'::jsonb,
    now(),
    now()
  ),
  (
    'aaaaaaaa-0000-4000-8000-000000000003',
    '00000000-0000-0000-0000-000000000000',
    'authenticated',
    'authenticated',
    '212660000001@fellah.app',
    crypt('258369', gen_salt('bf')),
    now(),
    '{"phone":"+212660000001","full_name":"مدير فلاح ماركت","role":"farmer","region_id":"reg_souss_massa","city":"أكادير"}'::jsonb,
    now(),
    now()
  )
on conflict (id) do nothing;

-- Approve Farmer & Buyer and promote Admin (only possible via postgres/service_role)
update public.profiles
set verification_status = 'verified'
where id in ('aaaaaaaa-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000002');

update public.profiles
set role = 'admin',
    verification_status = 'verified'
where id = 'aaaaaaaa-0000-4000-8000-000000000003';

insert into public.farmer_details (user_id, farm_or_coop_name, main_category_ids)
values ('aaaaaaaa-0000-4000-8000-000000000001', 'ضيعة سوس الخير', array['cat_veg','cat_citrus'])
on conflict (user_id) do nothing;

insert into public.buyer_details (user_id, business_name, business_type, interest_category_ids)
values ('aaaaaaaa-0000-4000-8000-000000000002', 'مطعم تافراوت', 'restaurant', array['cat_veg','cat_olives'])
on conflict (user_id) do nothing;

commit;
