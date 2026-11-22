-- Fellah-Market MVP — Migration 003: Auth User Triggers (Phone+PIN synthetic email) & Privilege Escalation Protection

alter table public.profiles add column if not exists must_change_pin boolean not null default false;

-- 1. Auto-create profile row when a new auth.users record is created via synthetic email + PIN signup
create or replace function public.handle_new_auth_user()
returns trigger
language plpgsql security definer set search_path = public
as $$
declare
  v_default_region text;
  v_default_city text;
  v_phone text;
begin
  select id, cities[1]
    into v_default_region, v_default_city
  from public.regions
  where is_default = true
  limit 1;

  if v_default_region is null then
    v_default_region := 'reg_souss_massa';
    v_default_city := 'أكادير';
  end if;

  -- Phone is passed in raw_user_meta_data->>'phone' for synthetic email+password signups, or auth.users.phone
  v_phone := trim(coalesce(nullif(new.phone, ''), nullif(new.raw_user_meta_data->>'phone', ''), ''));
  if v_phone = '' then
    raise exception 'Phone number is required for registration';
  end if;

  -- Enforce duplicate phone rejection inside the signup transaction (profiles.phone is also UNIQUE)
  if exists (select 1 from public.profiles where phone = v_phone) then
    raise exception 'Registration rejected: duplicate phone';
  end if;

  insert into public.profiles (
    id,
    phone,
    full_name,
    role,
    verification_status,
    must_change_pin,
    region_id,
    city
  )
  values (
    new.id,
    v_phone,
    coalesce(new.raw_user_meta_data->>'full_name', ''),
    case
      when (new.raw_user_meta_data->>'role') in ('farmer', 'buyer') then new.raw_user_meta_data->>'role'
      else 'farmer'
    end,
    'pending',
    false,
    coalesce(nullif(new.raw_user_meta_data->>'region_id', ''), v_default_region),
    coalesce(nullif(new.raw_user_meta_data->>'city', ''), v_default_city)
  )
  on conflict (id) do nothing;

  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
after insert on auth.users
for each row execute function public.handle_new_auth_user();

-- 2. Prevent non-admins from self-assigning 'admin' role, changing verification_status, or spoofing ratings
create or replace function public.prevent_profile_privilege_escalation()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  if auth.uid() is not null and not public.is_admin() then
    if new.role = 'admin' and old.role <> 'admin' then
      raise exception 'Admin role cannot be self-assigned';
    end if;
    if new.verification_status <> old.verification_status then
      raise exception 'Only administrators can change verification status';
    end if;
    if new.rejection_reason <> old.rejection_reason then
      raise exception 'Only administrators can change rejection reason';
    end if;
    if new.rating_avg <> old.rating_avg or new.rating_count <> old.rating_count then
      raise exception 'Rating aggregates are managed server-side';
    end if;
    if new.must_change_pin = true and old.must_change_pin = false then
      raise exception 'Only administrators can require PIN reset';
    end if;
  end if;
  return new;
end;
$$;

drop trigger if exists trg_prevent_profile_privilege_escalation on public.profiles;
create trigger trg_prevent_profile_privilege_escalation
before update on public.profiles
for each row execute function public.prevent_profile_privilege_escalation();
