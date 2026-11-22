-- Fellah-Market MVP — Migration 002: Private Storage Buckets & Storage RLS Policies

insert into storage.buckets (id, name, public)
values
  ('offer-photos', 'offer-photos', false),
  ('voice-notes', 'voice-notes', false),
  ('verification-docs', 'verification-docs', false)
on conflict (id) do update set public = false;

-- Helper: extract owner uuid folder prefix (<user_id>/<filename>)
-- Owners upload/update/delete ONLY within their own <auth.uid()>/... folder.

-- 1. offer-photos bucket
drop policy if exists "offer_photos_owner_write" on storage.objects;
create policy "offer_photos_owner_write" on storage.objects
for insert to authenticated
with check (
  bucket_id = 'offer-photos'
  and (storage.foldername(name))[1] = auth.uid()::text
);

drop policy if exists "offer_photos_owner_update" on storage.objects;
create policy "offer_photos_owner_update" on storage.objects
for update to authenticated
using (
  bucket_id = 'offer-photos'
  and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin())
);

drop policy if exists "offer_photos_owner_delete" on storage.objects;
create policy "offer_photos_owner_delete" on storage.objects
for delete to authenticated
using (
  bucket_id = 'offer-photos'
  and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin())
);

drop policy if exists "offer_photos_read_active_or_owner" on storage.objects;
create policy "offer_photos_read_active_or_owner" on storage.objects
for select to authenticated
using (
  bucket_id = 'offer-photos'
  and (
    (storage.foldername(name))[1] = auth.uid()::text
    or public.is_admin()
    or exists (
      select 1 from public.offer_photos op
      join public.offers o on o.id = op.offer_id
      where op.storage_path = name
        and o.status in ('active', 'reserved')
    )
  )
);

-- 2. voice-notes bucket
drop policy if exists "voice_notes_owner_write" on storage.objects;
create policy "voice_notes_owner_write" on storage.objects
for insert to authenticated
with check (
  bucket_id = 'voice-notes'
  and (storage.foldername(name))[1] = auth.uid()::text
);

drop policy if exists "voice_notes_owner_delete" on storage.objects;
create policy "voice_notes_owner_delete" on storage.objects
for delete to authenticated
using (
  bucket_id = 'voice-notes'
  and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin())
);

drop policy if exists "voice_notes_read_active_or_owner" on storage.objects;
create policy "voice_notes_read_active_or_owner" on storage.objects
for select to authenticated
using (
  bucket_id = 'voice-notes'
  and (
    (storage.foldername(name))[1] = auth.uid()::text
    or public.is_admin()
    or exists (
      select 1 from public.offers o
      where o.voice_note_url = name
        and o.status in ('active', 'reserved')
    )
  )
);

-- 3. verification-docs bucket (strictly private: owner and admins ONLY)
drop policy if exists "verification_docs_owner_write" on storage.objects;
create policy "verification_docs_owner_write" on storage.objects
for insert to authenticated
with check (
  bucket_id = 'verification-docs'
  and (storage.foldername(name))[1] = auth.uid()::text
);

drop policy if exists "verification_docs_owner_or_admin_read" on storage.objects;
create policy "verification_docs_owner_or_admin_read" on storage.objects
for select to authenticated
using (
  bucket_id = 'verification-docs'
  and (
    (storage.foldername(name))[1] = auth.uid()::text
    or public.is_admin()
  )
);

drop policy if exists "verification_docs_owner_or_admin_delete" on storage.objects;
create policy "verification_docs_owner_or_admin_delete" on storage.objects
for delete to authenticated
using (
  bucket_id = 'verification-docs'
  and (
    (storage.foldername(name))[1] = auth.uid()::text
    or public.is_admin()
  )
);
