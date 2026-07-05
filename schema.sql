-- ============================================================
-- Supanote — database schema
-- Run this ONCE in your own Supabase project:
--   Dashboard → SQL Editor → New query → paste → Run.
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
  created_at timestamptz not null default now()
);

-- 2. Row Level Security: every user can read and write only their own notes.
alter table notes enable row level security;

create policy "read own notes" on notes
  for select to authenticated
  using (auth.uid() = user_id);

create policy "insert own notes" on notes
  for insert to authenticated
  with check (auth.uid() = user_id);

create policy "update own notes" on notes
  for update to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "delete own notes" on notes
  for delete to authenticated
  using (auth.uid() = user_id);

-- ============================================================
-- One-time dashboard settings (not SQL — do these in the UI):
--   • Authentication → Sign In / Providers → Email: enable it.
--       For easiest setup, turn OFF "Confirm email" for now.
-- ============================================================