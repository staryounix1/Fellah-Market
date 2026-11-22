-- Fellah-Market MVP — Migration 001: Idempotent Schema, Indexes, Views, RPCs & RLS
-- Default Launch Region: Souss-Massa (configurable via `regions` table)

create extension if not exists "pgcrypto";

-- 1. Regions (extensible without code changes)
create table if not exists public.regions (
  id text primary key,
  name_darija text not null,
  name_ar text not null,
  name_fr text not null,
  cities text[] not null default '{}',
  is_default boolean not null default false,
  is_active boolean not null default true,
  created_at timestamptz not null default now()
);

-- 2. Profiles (phone-only auth via Supabase Auth)
create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  phone text not null unique,
  full_name text not null default '',
  role text not null default 'farmer' check (role in ('farmer', 'buyer', 'admin')),
  verification_status text not null default 'pending' check (verification_status in ('pending', 'verified', 'rejected')),
  rejection_reason text default '',
  verification_doc_path text default '',
  must_change_pin boolean not null default false,
  region_id text not null references public.regions(id),
  city text not null default '',
  avatar_url text default '',
  rating_avg numeric(3,2) not null default 0.00,
  rating_count integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.profiles add column if not exists verification_doc_path text default '';
alter table public.profiles add column if not exists must_change_pin boolean not null default false;

create table if not exists public.farmer_details (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  farm_or_coop_name text default '',
  main_category_ids text[] not null default '{}'
);

create table if not exists public.buyer_details (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  business_name text not null,
  business_type text not null check (business_type in ('restaurant', 'hotel', 'grocery', 'wholesaler', 'cooperative')),
  interest_category_ids text[] not null default '{}'
);

-- 3. Data-driven Product Catalog
create table if not exists public.categories (
  id text primary key,
  name_darija text not null,
  name_ar text not null,
  name_fr text not null,
  icon_key text not null default 'veg',
  sort_order integer not null default 0,
  is_active boolean not null default true
);

create table if not exists public.products (
  id text primary key,
  category_id text not null references public.categories(id),
  name_darija text not null,
  name_ar text not null,
  name_fr text not null,
  allowed_units text[] not null,
  image_key text not null default 'hero',
  is_active boolean not null default true,
  created_at timestamptz not null default now()
);

create table if not exists public.product_attribute_schemas (
  id uuid primary key default gen_random_uuid(),
  product_id text not null references public.products(id) on delete cascade,
  schema_json jsonb not null default '[]'::jsonb,
  version integer not null default 1,
  unique (product_id, version)
);

-- 4. Offers & Photos
create table if not exists public.offers (
  id uuid primary key default gen_random_uuid(),
  farmer_id uuid not null references public.profiles(id) on delete cascade,
  product_id text not null references public.products(id),
  category_id text not null references public.categories(id),
  region_id text not null references public.regions(id),
  city text not null,
  quantity numeric(12,2) not null check (quantity > 0),
  unit text not null,
  asking_price numeric(12,2) not null check (asking_price > 0),
  ready_date date not null default current_date,
  expires_at timestamptz not null default (now() + interval '14 days'),
  attributes jsonb not null default '{}'::jsonb,
  voice_note_url text default '',
  voice_duration_sec integer not null default 0 check (voice_duration_sec between 0 and 60),
  media_status text not null default 'ready' check (media_status in ('ready', 'uploading_media', 'failed')),
  status text not null default 'active' check (status in ('active', 'reserved', 'sold', 'paused', 'expired')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.offers add column if not exists expires_at timestamptz not null default (now() + interval '14 days');
alter table public.offers add column if not exists media_status text not null default 'ready';

create table if not exists public.offer_photos (
  id uuid primary key default gen_random_uuid(),
  offer_id uuid not null references public.offers(id) on delete cascade,
  storage_path text not null,
  sort_order integer not null default 0
);

-- 5. Wanted Posts (Buy Requests)
create table if not exists public.buy_requests (
  id uuid primary key default gen_random_uuid(),
  buyer_id uuid not null references public.profiles(id) on delete cascade,
  product_id text not null references public.products(id),
  category_id text not null references public.categories(id),
  region_id text not null references public.regions(id),
  city text not null,
  quantity numeric(12,2) not null check (quantity > 0),
  unit text not null,
  max_price numeric(12,2) not null check (max_price > 0),
  needed_date date not null,
  notes text default '',
  status text not null default 'open' check (status in ('open', 'fulfilled', 'closed')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.buy_requests add column if not exists updated_at timestamptz not null default now();

-- 6. Deals, Status History & Ratings
create table if not exists public.deals (
  id uuid primary key default gen_random_uuid(),
  offer_id uuid references public.offers(id) on delete set null,
  buy_request_id uuid references public.buy_requests(id) on delete set null,
  farmer_id uuid not null references public.profiles(id),
  buyer_id uuid not null references public.profiles(id),
  product_id text not null references public.products(id),
  quantity numeric(12,2) not null check (quantity > 0),
  unit text not null,
  proposed_price numeric(12,2) not null check (proposed_price > 0),
  agreed_price numeric(12,2),
  delivery_type text not null check (delivery_type in ('pickup', 'delivery')),
  preferred_date date not null,
  note text default '',
  status text not null default 'requested' check (status in ('requested', 'countered', 'accepted', 'completed', 'cancelled')),
  last_actor_role text not null check (last_actor_role in ('farmer', 'buyer')),
  farmer_confirmed_completion boolean not null default false,
  buyer_confirmed_completion boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint deals_distinct_parties check (farmer_id <> buyer_id)
);

create table if not exists public.deal_status_history (
  id uuid primary key default gen_random_uuid(),
  deal_id uuid not null references public.deals(id) on delete cascade,
  status text not null,
  actor_id uuid not null references public.profiles(id),
  note text default '',
  price_at_step numeric(12,2) not null,
  created_at timestamptz not null default now()
);

create table if not exists public.ratings (
  id uuid primary key default gen_random_uuid(),
  deal_id uuid not null references public.deals(id) on delete cascade,
  from_user_id uuid not null references public.profiles(id),
  to_user_id uuid not null references public.profiles(id),
  stars integer not null check (stars between 1 and 5),
  comment text default '',
  created_at timestamptz not null default now(),
  unique (deal_id, from_user_id),
  constraint ratings_no_self check (from_user_id <> to_user_id)
);

-- 7. Notifications, Saved Filters, Reference Prices, Proposals & Reports
create table if not exists public.notifications (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  type text not null,
  title_darija text not null,
  title_ar text not null,
  title_fr text not null,
  body_text text not null,
  related_entity_id text default '',
  is_read boolean not null default false,
  created_at timestamptz not null default now()
);

create table if not exists public.saved_filters (
  id uuid primary key default gen_random_uuid(),
  buyer_id uuid not null references public.profiles(id) on delete cascade,
  label text not null,
  category_id text references public.categories(id),
  product_id text references public.products(id),
  city text,
  max_price numeric(12,2),
  min_qty numeric(12,2),
  created_at timestamptz not null default now()
);

create table if not exists public.reference_prices (
  id uuid primary key default gen_random_uuid(),
  product_id text not null references public.products(id),
  region_id text not null references public.regions(id),
  unit text not null,
  price_mad numeric(12,2) not null check (price_mad > 0),
  recorded_date date not null default current_date,
  source text not null default 'wholesale_market' check (source in ('wholesale_market', 'admin')),
  created_at timestamptz not null default now()
);

create table if not exists public.product_proposals (
  id uuid primary key default gen_random_uuid(),
  farmer_id uuid not null references public.profiles(id) on delete cascade,
  proposed_name text not null,
  category_id text not null references public.categories(id),
  suggested_unit text not null,
  notes text default '',
  status text not null default 'pending' check (status in ('pending', 'approved', 'rejected')),
  created_at timestamptz not null default now()
);

create table if not exists public.reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid not null references public.profiles(id) on delete cascade,
  target_type text not null check (target_type in ('offer', 'user')),
  target_id text not null,
  reason text not null,
  status text not null default 'open' check (status in ('open', 'resolved')),
  created_at timestamptz not null default now()
);

-- Indexes for common queries
create index if not exists idx_offers_market on public.offers (status, region_id, category_id, product_id, created_at desc);
create index if not exists idx_offers_city_expires on public.offers (status, city, expires_at desc);
create index if not exists idx_offers_farmer on public.offers (farmer_id, created_at desc);
create index if not exists idx_deals_farmer on public.deals (farmer_id, status, updated_at desc);
create index if not exists idx_deals_buyer on public.deals (buyer_id, status, updated_at desc);
create index if not exists idx_notifications_user_read on public.notifications (user_id, is_read, created_at desc);
create index if not exists idx_ref_prices_lookup on public.reference_prices (product_id, region_id, recorded_date desc);

-- Helper Functions for RLS
create or replace function public.is_admin()
returns boolean
language sql stable security definer set search_path = public
as $$
  select exists (
    select 1 from public.profiles
    where id = auth.uid() and role = 'admin'
  );
$$;

create or replace function public.is_verified_user()
returns boolean
language sql stable security definer set search_path = public
as $$
  select exists (
    select 1 from public.profiles
    where id = auth.uid() and verification_status = 'verified'
  );
$$;

-- Updated-at timestamp trigger
create or replace function public.touch_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

drop trigger if exists trg_profiles_updated on public.profiles;
create trigger trg_profiles_updated before update on public.profiles
for each row execute function public.touch_updated_at();

drop trigger if exists trg_offers_updated on public.offers;
create trigger trg_offers_updated before update on public.offers
for each row execute function public.touch_updated_at();

drop trigger if exists trg_deals_updated on public.deals;
create trigger trg_deals_updated before update on public.deals
for each row execute function public.touch_updated_at();

-- Server-side Deal State Machine Trigger
create or replace function public.enforce_deal_transition()
returns trigger
language plpgsql security definer set search_path = public
as $$
declare
  caller_role text;
begin
  if auth.uid() is not null and not public.is_admin() then
    if auth.uid() <> old.farmer_id and auth.uid() <> old.buyer_id then
      raise exception 'Not a participant of this deal';
    end if;
    caller_role := case when auth.uid() = old.farmer_id then 'farmer' else 'buyer' end;
  else
    caller_role := new.last_actor_role;
  end if;

  if new.status <> old.status then
    if old.status in ('completed', 'cancelled') then
      raise exception 'Cannot transition from terminal state %', old.status;
    end if;

    if old.status = 'requested' and new.status not in ('countered', 'accepted', 'cancelled') then
      raise exception 'Invalid transition from requested to %', new.status;
    elsif old.status = 'countered' and new.status not in ('countered', 'accepted', 'cancelled') then
      raise exception 'Invalid transition from countered to %', new.status;
    elsif old.status = 'accepted' and new.status not in ('completed', 'cancelled') then
      raise exception 'Invalid transition from accepted to %', new.status;
    end if;

    if new.status in ('countered', 'accepted') and caller_role = old.last_actor_role and not public.is_admin() then
      raise exception 'Not your turn to act on this negotiation';
    end if;

    if new.status = 'accepted' and new.agreed_price is null then
      new.agreed_price := new.proposed_price;
    end if;
  end if;

  -- Dual completion confirmation rule
  if old.status = 'accepted' and (new.farmer_confirmed_completion or new.buyer_confirmed_completion) then
    if new.farmer_confirmed_completion and new.buyer_confirmed_completion then
      new.status := 'completed';
    end if;
  end if;

  return new;
end;
$$;

drop trigger if exists trg_enforce_deal_transition on public.deals;
create trigger trg_enforce_deal_transition before update on public.deals
for each row execute function public.enforce_deal_transition();

-- Server-side RPC for Deal Actions (accept, counter, complete, cancel)
create or replace function public.deal_action(
  p_deal_id uuid,
  p_action text,
  p_new_price numeric default null,
  p_note text default ''
)
returns public.deals
language plpgsql security definer set search_path = public
as $$
declare
  v_deal public.deals;
  v_actor uuid := auth.uid();
  v_role text;
  v_next_status text;
  v_recipient uuid;
begin
  if v_actor is null then
    raise exception 'Authentication required';
  end if;

  select * into v_deal from public.deals where id = p_deal_id for update;
  if not found then
    raise exception 'Deal not found';
  end if;

  if v_actor <> v_deal.farmer_id and v_actor <> v_deal.buyer_id and not public.is_admin() then
    raise exception 'Forbidden: not a party to this deal';
  end if;

  v_role := case when v_actor = v_deal.farmer_id then 'farmer' else 'buyer' end;
  v_recipient := case when v_role = 'farmer' then v_deal.buyer_id else v_deal.farmer_id end;

  if p_action = 'counter' then
    if p_new_price is null or p_new_price <= 0 then
      raise exception 'Counter price must be positive';
    end if;
    update public.deals
      set proposed_price = p_new_price,
          status = 'countered',
          last_actor_role = v_role,
          note = coalesce(nullif(p_note, ''), note)
      where id = p_deal_id
      returning * into v_deal;
    v_next_status := 'countered';
  elsif p_action = 'accept' then
    update public.deals
      set agreed_price = proposed_price,
          status = 'accepted',
          last_actor_role = v_role
      where id = p_deal_id
      returning * into v_deal;
    if v_deal.offer_id is not null then
      update public.offers set status = 'reserved' where id = v_deal.offer_id;
    end if;
    v_next_status := 'accepted';
  elsif p_action = 'cancel' then
    update public.deals
      set status = 'cancelled',
          last_actor_role = v_role
      where id = p_deal_id
      returning * into v_deal;
    v_next_status := 'cancelled';
  elsif p_action = 'complete' then
    if v_deal.status <> 'accepted' then
      raise exception 'Only accepted deals can be confirmed complete';
    end if;
    update public.deals
      set farmer_confirmed_completion = farmer_confirmed_completion or (v_role = 'farmer'),
          buyer_confirmed_completion = buyer_confirmed_completion or (v_role = 'buyer'),
          last_actor_role = v_role
      where id = p_deal_id
      returning * into v_deal;
    v_next_status := v_deal.status;
    if v_deal.status = 'completed' and v_deal.offer_id is not null then
      update public.offers set status = 'sold' where id = v_deal.offer_id;
    end if;
  else
    raise exception 'Unknown deal action: %', p_action;
  end if;

  insert into public.deal_status_history (deal_id, status, actor_id, note, price_at_step)
  values (
    v_deal.id,
    v_next_status,
    v_actor,
    coalesce(nullif(p_note, ''), p_action),
    coalesce(v_deal.agreed_price, v_deal.proposed_price)
  );

  insert into public.notifications (user_id, type, title_darija, title_ar, title_fr, body_text, related_entity_id)
  values (
    v_recipient,
    'deal_' || v_next_status,
    'تحديث فـ الصفقة: ' || v_next_status,
    'تحديث في الصفقة: ' || v_next_status,
    'Mise à jour accord : ' || v_next_status,
    coalesce(v_deal.agreed_price, v_deal.proposed_price)::text || ' MAD / ' || v_deal.unit,
    v_deal.id::text
  );

  return v_deal;
end;
$$;

-- Trigger to update user rating average automatically
create or replace function public.refresh_user_rating()
returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  update public.profiles p
  set rating_avg = coalesce((select round(avg(r.stars)::numeric, 2) from public.ratings r where r.to_user_id = new.to_user_id), 0),
      rating_count = (select count(*) from public.ratings r where r.to_user_id = new.to_user_id)
  where p.id = new.to_user_id;
  return new;
end;
$$;

drop trigger if exists trg_refresh_user_rating on public.ratings;
create trigger trg_refresh_user_rating after insert on public.ratings
for each row execute function public.refresh_user_rating();

-- Safe public profile view that masks phone numbers until an accepted/completed deal exists
create or replace view public.public_profiles as
select
  p.id,
  p.full_name,
  p.role,
  p.verification_status,
  p.region_id,
  p.city,
  p.avatar_url,
  p.rating_avg,
  p.rating_count,
  case
    when p.id = auth.uid() or public.is_admin() then p.phone
    when exists (
      select 1 from public.deals d
      where d.status in ('accepted', 'completed')
        and ((d.farmer_id = auth.uid() and d.buyer_id = p.id)
          or (d.buyer_id = auth.uid() and d.farmer_id = p.id))
    ) then p.phone
    else null
  end as phone
from public.profiles p;

-- Server-side Market Price Aggregation View & RPC (last 30 days)
create or replace view public.market_price_averages as
with combined as (
  select
    d.product_id,
    coalesce(o.region_id, p.region_id) as region_id,
    d.unit,
    coalesce(d.agreed_price, d.proposed_price) as price_mad,
    1.5::numeric as weight
  from public.deals d
  left join public.offers o on o.id = d.offer_id
  join public.profiles p on p.id = d.farmer_id
  where d.status = 'completed' and d.updated_at >= now() - interval '30 days'
  union all
  select
    o.product_id,
    o.region_id,
    o.unit,
    o.asking_price as price_mad,
    1.0::numeric as weight
  from public.offers o
  where o.status in ('active', 'reserved') and o.created_at >= now() - interval '30 days'
)
select
  c.product_id,
  c.region_id,
  c.unit,
  round((sum(c.price_mad * c.weight) / nullif(sum(c.weight), 0))::numeric, 2) as weighted_avg_mad,
  count(*)::integer as sample_count
from combined c
group by c.product_id, c.region_id, c.unit;

create or replace function public.get_market_price_summary(
  p_product_id text,
  p_region_id text default null,
  p_unit text default null
)
returns jsonb
language sql stable security definer set search_path = public
as $$
  with avg_row as (
    select
      weighted_avg_mad,
      sample_count,
      unit
    from public.market_price_averages
    where product_id = p_product_id
      and (p_region_id is null or region_id = p_region_id)
      and (p_unit is null or unit = p_unit)
    order by sample_count desc
    limit 1
  ),
  ref_row as (
    select price_mad, unit
    from public.reference_prices
    where product_id = p_product_id
      and (p_region_id is null or region_id = p_region_id)
      and (p_unit is null or unit = p_unit)
    order by recorded_date desc
    limit 1
  ),
  trend as (
    select
      recorded_date,
      round(avg(price_mad)::numeric, 2) as avg_price
    from public.reference_prices
    where product_id = p_product_id
      and (p_region_id is null or region_id = p_region_id)
      and recorded_date >= current_date - 29
    group by recorded_date
    order by recorded_date asc
  )
  select jsonb_build_object(
    'product_id', p_product_id,
    'unit', coalesce((select unit from avg_row), (select unit from ref_row), coalesce(p_unit, 'kg')),
    'average_price', coalesce((select weighted_avg_mad from avg_row), (select price_mad from ref_row)),
    'wholesale_reference_price', (select price_mad from ref_row),
    'sample_count', coalesce((select sample_count from avg_row), 0),
    'trend_points', coalesce((select jsonb_agg(jsonb_build_object('date', recorded_date, 'avg_price', avg_price)) from trend), '[]'::jsonb)
  );
$$;

-- Enable Row Level Security on all tables
alter table public.regions enable row level security;
alter table public.profiles enable row level security;
alter table public.farmer_details enable row level security;
alter table public.buyer_details enable row level security;
alter table public.categories enable row level security;
alter table public.products enable row level security;
alter table public.product_attribute_schemas enable row level security;
alter table public.offers enable row level security;
alter table public.offer_photos enable row level security;
alter table public.buy_requests enable row level security;
alter table public.deals enable row level security;
alter table public.deal_status_history enable row level security;
alter table public.ratings enable row level security;
alter table public.notifications enable row level security;
alter table public.saved_filters enable row level security;
alter table public.reference_prices enable row level security;
alter table public.product_proposals enable row level security;
alter table public.reports enable row level security;

-- Catalog & Reference Prices: public read, admin write
drop policy if exists "regions_read_all" on public.regions;
create policy "regions_read_all" on public.regions for select using (true);
drop policy if exists "regions_admin_all" on public.regions;
create policy "regions_admin_all" on public.regions for all using (public.is_admin());

drop policy if exists "categories_read_all" on public.categories;
create policy "categories_read_all" on public.categories for select using (true);
drop policy if exists "categories_admin_all" on public.categories;
create policy "categories_admin_all" on public.categories for all using (public.is_admin());

drop policy if exists "products_read_all" on public.products;
create policy "products_read_all" on public.products for select using (true);
drop policy if exists "products_admin_all" on public.products;
create policy "products_admin_all" on public.products for all using (public.is_admin());

drop policy if exists "schemas_read_all" on public.product_attribute_schemas;
create policy "schemas_read_all" on public.product_attribute_schemas for select using (true);
drop policy if exists "schemas_admin_all" on public.product_attribute_schemas;
create policy "schemas_admin_all" on public.product_attribute_schemas for all using (public.is_admin());

drop policy if exists "ref_prices_read_all" on public.reference_prices;
create policy "ref_prices_read_all" on public.reference_prices for select using (true);
drop policy if exists "ref_prices_admin_all" on public.reference_prices;
create policy "ref_prices_admin_all" on public.reference_prices for all using (public.is_admin());

-- Profiles: own row only (use public_profiles view for masked cross-user reads)
drop policy if exists "profiles_own_read" on public.profiles;
create policy "profiles_own_read" on public.profiles for select using (id = auth.uid() or public.is_admin());
drop policy if exists "profiles_own_insert" on public.profiles;
create policy "profiles_own_insert" on public.profiles for insert with check (
  id = auth.uid() and role in ('farmer', 'buyer') and verification_status = 'pending'
);
drop policy if exists "profiles_own_update" on public.profiles;
create policy "profiles_own_update" on public.profiles for update using (id = auth.uid() or public.is_admin());

drop policy if exists "farmer_details_read" on public.farmer_details;
create policy "farmer_details_read" on public.farmer_details for select using (true);
drop policy if exists "farmer_details_write" on public.farmer_details;
create policy "farmer_details_write" on public.farmer_details for all using (user_id = auth.uid() or public.is_admin());

drop policy if exists "buyer_details_read" on public.buyer_details;
create policy "buyer_details_read" on public.buyer_details for select using (true);
drop policy if exists "buyer_details_write" on public.buyer_details;
create policy "buyer_details_write" on public.buyer_details for all using (user_id = auth.uid() or public.is_admin());

-- Offers & Photos
drop policy if exists "offers_read_active" on public.offers;
create policy "offers_read_active" on public.offers for select using (
  status in ('active', 'reserved')
  or farmer_id = auth.uid()
  or public.is_admin()
  or exists (select 1 from public.deals d where d.offer_id = offers.id and d.buyer_id = auth.uid())
);
drop policy if exists "offers_farmer_insert" on public.offers;
create policy "offers_farmer_insert" on public.offers for insert with check (farmer_id = auth.uid() and public.is_verified_user());
drop policy if exists "offers_farmer_update" on public.offers;
create policy "offers_farmer_update" on public.offers for update using (farmer_id = auth.uid() or public.is_admin());

drop policy if exists "offer_photos_read" on public.offer_photos;
create policy "offer_photos_read" on public.offer_photos for select using (true);
drop policy if exists "offer_photos_owner" on public.offer_photos;
create policy "offer_photos_owner" on public.offer_photos for all using (
  exists (select 1 from public.offers o where o.id = offer_id and (o.farmer_id = auth.uid() or public.is_admin()))
);

-- Buy Requests
drop policy if exists "buy_requests_read" on public.buy_requests;
create policy "buy_requests_read" on public.buy_requests for select using (true);
drop policy if exists "buy_requests_buyer_insert" on public.buy_requests;
create policy "buy_requests_buyer_insert" on public.buy_requests for insert with check (buyer_id = auth.uid() and public.is_verified_user());
drop policy if exists "buy_requests_owner_update" on public.buy_requests;
create policy "buy_requests_owner_update" on public.buy_requests for update using (buyer_id = auth.uid() or public.is_admin());

-- Deals & History
drop policy if exists "deals_participants_read" on public.deals;
create policy "deals_participants_read" on public.deals for select using (farmer_id = auth.uid() or buyer_id = auth.uid() or public.is_admin());
drop policy if exists "deals_verified_insert" on public.deals;
create policy "deals_verified_insert" on public.deals for insert with check (
  (farmer_id = auth.uid() or buyer_id = auth.uid())
  and public.is_verified_user()
  and status = 'requested'
);
drop policy if exists "deals_participants_update" on public.deals;
create policy "deals_participants_update" on public.deals for update using (farmer_id = auth.uid() or buyer_id = auth.uid() or public.is_admin());

drop policy if exists "deal_history_participants" on public.deal_status_history;
create policy "deal_history_participants" on public.deal_status_history for select using (
  exists (select 1 from public.deals d where d.id = deal_id and (d.farmer_id = auth.uid() or d.buyer_id = auth.uid() or public.is_admin()))
);
drop policy if exists "deal_history_insert" on public.deal_status_history;
create policy "deal_history_insert" on public.deal_status_history for insert with check (
  actor_id = auth.uid()
  and exists (select 1 from public.deals d where d.id = deal_id and (d.farmer_id = auth.uid() or d.buyer_id = auth.uid()))
);

-- Ratings: only participants of a completed deal can rate
drop policy if exists "ratings_read_all" on public.ratings;
create policy "ratings_read_all" on public.ratings for select using (true);
drop policy if exists "ratings_participant_insert" on public.ratings;
create policy "ratings_participant_insert" on public.ratings for insert with check (
  from_user_id = auth.uid()
  and exists (
    select 1 from public.deals d
    where d.id = deal_id
      and d.status = 'completed'
      and ((d.farmer_id = auth.uid() and d.buyer_id = to_user_id)
        or (d.buyer_id = auth.uid() and d.farmer_id = to_user_id))
  )
);

-- Notifications, Saved Filters, Proposals, Reports
drop policy if exists "notifications_own" on public.notifications;
create policy "notifications_own" on public.notifications for all using (user_id = auth.uid() or public.is_admin());

drop policy if exists "saved_filters_own" on public.saved_filters;
create policy "saved_filters_own" on public.saved_filters for all using (buyer_id = auth.uid() or public.is_admin());

drop policy if exists "proposals_own_or_admin" on public.product_proposals;
create policy "proposals_own_or_admin" on public.product_proposals for all using (farmer_id = auth.uid() or public.is_admin());

drop policy if exists "reports_insert_auth" on public.reports;
create policy "reports_insert_auth" on public.reports for insert with check (reporter_id = auth.uid());
drop policy if exists "reports_read_admin" on public.reports;
create policy "reports_read_admin" on public.reports for select using (reporter_id = auth.uid() or public.is_admin());
drop policy if exists "reports_update_admin" on public.reports;
create policy "reports_update_admin" on public.reports for update using (public.is_admin());
