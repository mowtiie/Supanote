-- ============================================================
-- Supanote — database schema
-- Run this in your own Supabase project:
--   Dashboard → SQL Editor → New query → paste → Run.
--
-- It is safe to run again, so it is also how you upgrade an
-- existing project to a newer version of the app.
-- ============================================================

-- 1. Notes table.
--    user_id is filled automatically from the signed-in user's JWT
--    (auth.uid()), so the client never sends it.
create table if not exists notes (
  id         bigint generated always as identity primary key,
  user_id    uuid not null default auth.uid()
                  references auth.users (id) on delete cascade,
  title      text not null,
  content    text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- 1b. Upgrade for projects created before updated_at existed.
--     Existing notes get their creation time, so they are not shown as
--     "edited". Does nothing on a fresh install or on a second run.
alter table notes add column if not exists updated_at timestamptz;
update notes set updated_at = created_at where updated_at is null;
alter table notes alter column updated_at set default now();
alter table notes alter column updated_at set not null;

-- 1c. Pinned notes, trash, tags and archive.
--     Pinned notes are listed first. A note with deleted_at set is in the
--     trash until it is restored or deleted for good.
--     tags is a list of short labels used to filter the notes list.
--     archived notes are kept but hidden from the main list.
alter table notes add column if not exists pinned boolean not null default false;
alter table notes add column if not exists deleted_at timestamptz;
alter table notes add column if not exists tags text[] not null default '{}';
alter table notes add column if not exists archived boolean not null default false;

-- 2. Keep updated_at current whenever the text of a note changes.
--    Pinning, archiving, trashing and restoring leave it alone, so they do not make
--    a note look "edited".
create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.title is distinct from old.title
     or new.content is distinct from old.content then
    new.updated_at = now();
  else
    new.updated_at = old.updated_at;
  end if;
  return new;
end;
$$;

drop trigger if exists notes_set_updated_at on notes;
create trigger notes_set_updated_at
  before update on notes
  for each row execute function public.set_updated_at();

-- 3. Row Level Security: every user can read and write only their own notes.
alter table notes enable row level security;

drop policy if exists "read own notes" on notes;
create policy "read own notes" on notes
  for select to authenticated
  using (auth.uid() = user_id);

drop policy if exists "insert own notes" on notes;
create policy "insert own notes" on notes
  for insert to authenticated
  with check (auth.uid() = user_id);

drop policy if exists "update own notes" on notes;
create policy "update own notes" on notes
  for update to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

drop policy if exists "delete own notes" on notes;
create policy "delete own notes" on notes
  for delete to authenticated
  using (auth.uid() = user_id);

-- ============================================================
-- One-time dashboard settings (not SQL — do these in the UI):
--   • Authentication → Sign In / Providers → Email: enable it.
--       For easiest setup, turn OFF "Confirm email" for now.
-- ============================================================
