-- Fellah-Market MVP — Migration 004: PIN Reset Audit Log & RLS
-- Logs admin-initiated temporary PIN resets (never stores plain-text PINs)

create table if not exists public.pin_reset_audit (
  id uuid primary key default gen_random_uuid(),
  admin_id uuid not null references public.profiles(id),
  target_user_id uuid not null references public.profiles(id),
  created_at timestamptz not null default now()
);

create index if not exists idx_pin_reset_audit_target
  on public.pin_reset_audit (target_user_id, created_at desc);

alter table public.pin_reset_audit enable row level security;

-- Only administrators can read reset audit records.
-- Inserts are performed exclusively by the server-side admin-reset-pin Edge Function using service_role.
drop policy if exists "pin_reset_audit_admin_read" on public.pin_reset_audit;
create policy "pin_reset_audit_admin_read" on public.pin_reset_audit
for select to authenticated
using (public.is_admin());
