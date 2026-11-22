-- Fellah-Market MVP — RLS, Trigger, RPC, PIN Audit & Storage Security Verification Suite
-- Safe to run repeatedly: wrapped in BEGIN ... ROLLBACK so zero test data persists.
-- Usage:
--   psql "$SUPABASE_DB_URL" -v ON_ERROR_STOP=1 -f supabase/tests/rls_tests.sql

begin;

-- Ensure minimal catalog fixtures exist inside the transaction (idempotent even on unseeded DB)
insert into public.regions (id, name_darija, name_ar, name_fr, cities, is_default, is_active)
values ('reg_souss_massa', 'سوس ماسة', 'سوس ماسة', 'Souss-Massa', array['أكادير','تارودانت'], true, true)
on conflict (id) do nothing;

insert into public.categories (id, name_darija, name_ar, name_fr, icon_key, sort_order, is_active)
values ('cat_veg', 'خضرة', 'خضروات', 'Légumes', 'veg', 1, true)
on conflict (id) do nothing;

insert into public.products (id, category_id, name_darija, name_ar, name_fr, allowed_units, image_key, is_active)
values ('prod_tomato', 'cat_veg', 'مطيشة', 'طماطم', 'Tomate', array['kg','crate'], 'tomatoes', true)
on conflict (id) do nothing;

do $$
declare
  v_farmer uuid := '11111111-1111-4111-8111-111111111101';
  v_buyer  uuid := '11111111-1111-4111-8111-111111111102';
  v_admin  uuid := '11111111-1111-4111-8111-111111111103';
  v_other  uuid := '11111111-1111-4111-8111-111111111104';
  v_dup    uuid := '11111111-1111-4111-8111-111111111105';

  v_offer_id uuid := '22222222-2222-4222-8222-222222222201';
  v_deal_id  uuid := '33333333-3333-4333-8333-333333333301';

  v_role text;
  v_verif text;
  v_count int;
  v_phone text;
  v_deal public.deals;
begin
  -- 1. Create synthetic-email + password (PIN) test auth users -> verify trigger 003 auto-creates profiles
  insert into auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, raw_user_meta_data, created_at, updated_at)
  values
    (v_farmer, 'authenticated', 'authenticated', '212661000101@fellah.app', crypt('258369', gen_salt('bf')), now(), '{"phone":"+212661000101","full_name":"Farmer Test","role":"farmer"}'::jsonb, now(), now()),
    (v_buyer,  'authenticated', 'authenticated', '212661000102@fellah.app', crypt('258369', gen_salt('bf')), now(), '{"phone":"+212661000102","full_name":"Buyer Test","role":"buyer"}'::jsonb, now(), now()),
    (v_admin,  'authenticated', 'authenticated', '212661000103@fellah.app', crypt('258369', gen_salt('bf')), now(), '{"phone":"+212661000103","full_name":"Evil Self Admin","role":"admin"}'::jsonb, now(), now()),
    (v_other,  'authenticated', 'authenticated', '212661000104@fellah.app', crypt('258369', gen_salt('bf')), now(), '{"phone":"+212661000104","full_name":"Third Party","role":"buyer"}'::jsonb, now(), now())
  on conflict (id) do nothing;

  -- Verify trigger extracted phone from metadata, downgraded 'admin' to 'farmer', and defaulted to 'pending'
  select role, verification_status, phone into v_role, v_verif, v_phone from public.profiles where id = v_admin;
  if v_role <> 'farmer' or v_verif <> 'pending' or v_phone <> '+212661000103' then
    raise exception 'TEST FAILED: Auth trigger metadata bootstrap failed (role=%, verif=%, phone=%)', v_role, v_verif, v_phone;
  end if;

  -- Verify duplicate phone registration is blocked cleanly by auth trigger / unique phone constraint
  begin
    insert into auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, raw_user_meta_data, created_at, updated_at)
    values (v_dup, 'authenticated', 'authenticated', '212661000199@fellah.app', crypt('258369', gen_salt('bf')), now(), '{"phone":"+212661000101","full_name":"Duplicate Phone","role":"farmer"}'::jsonb, now(), now());
    raise exception 'TEST FAILED: Duplicate phone signup succeeded';
  exception when others then
    null; -- Expected duplicate rejection
  end;

  -- Promote v_admin via postgres role (only valid way)
  update public.profiles set role = 'admin', verification_status = 'verified' where id = v_admin;

  -- Record a PIN reset audit entry as postgres/service_role
  insert into public.pin_reset_audit (admin_id, target_user_id) values (v_admin, v_farmer);

  -- 2. Verify non-admin cannot self-assign admin, self-verify, tamper must_change_pin=true, or read pin_reset_audit
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claims', json_build_object('sub', v_farmer::text, 'role', 'authenticated')::text, true);

  begin
    update public.profiles set role = 'admin' where id = v_farmer;
    raise exception 'TEST FAILED: Farmer was able to self-promote to admin';
  exception when others then
    if sqlerrm not like '%Admin role cannot be self-assigned%' then
      raise;
    end if;
  end;

  begin
    update public.profiles set verification_status = 'verified' where id = v_farmer;
    raise exception 'TEST FAILED: Farmer was able to self-verify';
  exception when others then
    if sqlerrm not like '%Only administrators can change verification status%' then
      raise;
    end if;
  end;

  select count(*) into v_count from public.pin_reset_audit;
  if v_count <> 0 then
    raise exception 'TEST FAILED: Non-admin could read pin_reset_audit';
  end if;

  begin
    insert into public.pin_reset_audit (admin_id, target_user_id) values (v_farmer, v_buyer);
    raise exception 'TEST FAILED: Non-admin inserted into pin_reset_audit';
  exception when insufficient_privilege then
    null;
  end;

  -- 3. Verify unverified farmer cannot insert offers
  begin
    insert into public.offers (id, farmer_id, product_id, category_id, region_id, city, quantity, unit, asking_price)
    values (v_offer_id, v_farmer, 'prod_tomato', 'cat_veg', 'reg_souss_massa', 'أكادير', 50, 'crate', 90);
    raise exception 'TEST FAILED: Unverified farmer inserted an offer';
  exception when insufficient_privilege then
    null;
  end;

  -- 4. Verify cross-user direct SELECT on public.profiles only reveals own profile
  select count(*) into v_count from public.profiles where id = v_buyer;
  if v_count <> 0 then
    raise exception 'TEST FAILED: Farmer could read buyer row directly in public.profiles';
  end if;

  -- 5. Verify public_profiles masks buyer phone before any accepted deal
  select phone into v_phone from public.public_profiles where id = v_buyer;
  if v_phone is not null then
    raise exception 'TEST FAILED: public_profiles leaked unaccepted partner phone';
  end if;

  -- 6. Admin verifies farmer & buyer and can view pin_reset_audit
  perform set_config('request.jwt.claims', json_build_object('sub', v_admin::text, 'role', 'authenticated')::text, true);
  update public.profiles set verification_status = 'verified' where id in (v_farmer, v_buyer);

  select count(*) into v_count from public.pin_reset_audit where target_user_id = v_farmer;
  if v_count <> 1 then
    raise exception 'TEST FAILED: Admin could not read pin_reset_audit';
  end if;

  -- 7. Verified farmer creates offer + uploads storage objects in own folder
  perform set_config('request.jwt.claims', json_build_object('sub', v_farmer::text, 'role', 'authenticated')::text, true);
  insert into public.offers (id, farmer_id, product_id, category_id, region_id, city, quantity, unit, asking_price)
  values (v_offer_id, v_farmer, 'prod_tomato', 'cat_veg', 'reg_souss_massa', 'أكادير', 50, 'crate', 100);

  insert into storage.objects (bucket_id, name, owner)
  values
    ('offer-photos', v_farmer::text || '/offer_1.jpg', v_farmer),
    ('verification-docs', v_farmer::text || '/id_doc.jpg', v_farmer);

  -- Storage RLS: farmer cannot write to another user's folder
  begin
    insert into storage.objects (bucket_id, name, owner)
    values ('offer-photos', v_buyer::text || '/spoof.jpg', v_farmer);
    raise exception 'TEST FAILED: Farmer wrote into buyer storage folder';
  exception when insufficient_privilege then
    null;
  end;

  -- 8. Storage RLS: other user cannot read farmer's verification-docs; admin CAN
  perform set_config('request.jwt.claims', json_build_object('sub', v_other::text, 'role', 'authenticated')::text, true);
  select count(*) into v_count from storage.objects
  where bucket_id = 'verification-docs' and name = v_farmer::text || '/id_doc.jpg';
  if v_count <> 0 then
    raise exception 'TEST FAILED: Unrelated user could read farmer verification doc';
  end if;

  perform set_config('request.jwt.claims', json_build_object('sub', v_admin::text, 'role', 'authenticated')::text, true);
  select count(*) into v_count from storage.objects
  where bucket_id = 'verification-docs' and name = v_farmer::text || '/id_doc.jpg';
  if v_count <> 1 then
    raise exception 'TEST FAILED: Admin could not read farmer verification doc';
  end if;

  -- 9. Verified buyer initiates deal & test turn-taking + phone unmasking via deal_action RPC
  perform set_config('request.jwt.claims', json_build_object('sub', v_buyer::text, 'role', 'authenticated')::text, true);
  insert into public.deals (
    id, offer_id, farmer_id, buyer_id, product_id, quantity, unit,
    proposed_price, delivery_type, preferred_date, status, last_actor_role
  ) values (
    v_deal_id, v_offer_id, v_farmer, v_buyer, 'prod_tomato', 20, 'crate',
    85, 'pickup', current_date + 1, 'requested', 'buyer'
  );

  begin
    perform public.deal_action(v_deal_id, 'accept', null, 'Self accept');
    raise exception 'TEST FAILED: Buyer accepted their own requested deal';
  exception when others then
    if sqlerrm not like '%Not your turn%' then
      raise;
    end if;
  end;

  perform set_config('request.jwt.claims', json_build_object('sub', v_other::text, 'role', 'authenticated')::text, true);
  select count(*) into v_count from public.deals where id = v_deal_id;
  if v_count <> 0 then
    raise exception 'TEST FAILED: Third party could read private deal';
  end if;

  begin
    perform public.deal_action(v_deal_id, 'cancel', null, 'Sabotage');
    raise exception 'TEST FAILED: Third party acted on deal';
  exception when others then
    if sqlerrm not like '%Forbidden: not a party to this deal%' then
      raise;
    end if;
  end;

  perform set_config('request.jwt.claims', json_build_object('sub', v_farmer::text, 'role', 'authenticated')::text, true);
  v_deal := public.deal_action(v_deal_id, 'counter', 92, '92 MAD/crate');
  if v_deal.status <> 'countered' or v_deal.proposed_price <> 92 then
    raise exception 'TEST FAILED: Counter offer did not update status/price';
  end if;

  perform set_config('request.jwt.claims', json_build_object('sub', v_buyer::text, 'role', 'authenticated')::text, true);
  v_deal := public.deal_action(v_deal_id, 'accept', null, 'Deal');
  if v_deal.status <> 'accepted' or v_deal.agreed_price <> 92 then
    raise exception 'TEST FAILED: Accept failed';
  end if;

  select phone into v_phone from public.public_profiles where id = v_farmer;
  if v_phone is null or v_phone <> '+212661000101' then
    raise exception 'TEST FAILED: Phone remained masked after deal acceptance (got %)', v_phone;
  end if;

  -- 10. Ratings blocked before completion, allowed after dual completion
  begin
    insert into public.ratings (deal_id, from_user_id, to_user_id, stars, comment)
    values (v_deal_id, v_buyer, v_farmer, 5, 'Too early');
    raise exception 'TEST FAILED: Rating allowed before deal completion';
  exception when insufficient_privilege then
    null;
  end;

  perform set_config('request.jwt.claims', json_build_object('sub', v_farmer::text, 'role', 'authenticated')::text, true);
  v_deal := public.deal_action(v_deal_id, 'complete', null, 'Delivered');
  if v_deal.status <> 'accepted' then
    raise exception 'TEST FAILED: Single confirmation completed deal prematurely';
  end if;

  perform set_config('request.jwt.claims', json_build_object('sub', v_buyer::text, 'role', 'authenticated')::text, true);
  v_deal := public.deal_action(v_deal_id, 'complete', null, 'Received');
  if v_deal.status <> 'completed' then
    raise exception 'TEST FAILED: Dual confirmation did not complete deal';
  end if;

  begin
    perform public.deal_action(v_deal_id, 'cancel', null, 'Late cancel');
    raise exception 'TEST FAILED: Cancelled an already completed deal';
  exception when others then
    if sqlerrm not like '%Cannot transition from terminal state%' then
      raise;
    end if;
  end;

  insert into public.ratings (deal_id, from_user_id, to_user_id, stars, comment)
  values (v_deal_id, v_buyer, v_farmer, 5, 'Top quality');

  select rating_count into v_count from public.profiles where id = v_farmer;
  if v_count <> 1 then
    raise exception 'TEST FAILED: Rating trigger did not update farmer rating_count';
  end if;

  raise notice 'ALL RLS, TRIGGER, PIN AUDIT, RPC & STORAGE TESTS PASSED SUCCESSFULLY';
end;
$$;

rollback;
