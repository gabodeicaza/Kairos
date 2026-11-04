-- Kairos · esquema de Supabase para guardar el perfil de cada persona.
-- Ejecútalo una vez en Supabase → SQL Editor. Es seguro volver a ejecutarlo.
--
-- Protección: Row Level Security (RLS). Cada fila pertenece a un usuario y la base de datos
-- solo deja leer, escribir o borrar la fila cuyo user_id coincide con la sesión (auth.uid()).
-- La clave "anon" que usa la web NO puede saltarse estas reglas.

create table if not exists public.profiles (
  user_id    uuid primary key references auth.users (id) on delete cascade,
  data       jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now(),
  constraint profiles_data_small check (pg_column_size(data) < 20000)  -- evita usar la tabla como almacén de archivos
);

alter table public.profiles enable row level security;
alter table public.profiles force row level security;

drop policy if exists "profiles_select_own" on public.profiles;
drop policy if exists "profiles_insert_own" on public.profiles;
drop policy if exists "profiles_update_own" on public.profiles;
drop policy if exists "profiles_delete_own" on public.profiles;

create policy "profiles_select_own" on public.profiles for select to authenticated using (auth.uid() = user_id);
create policy "profiles_insert_own" on public.profiles for insert to authenticated with check (auth.uid() = user_id);
create policy "profiles_update_own" on public.profiles for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "profiles_delete_own" on public.profiles for delete to authenticated using (auth.uid() = user_id);

-- Sin sesión (rol anon) no hay ningún acceso a la tabla.
revoke all on public.profiles from anon;
grant select, insert, update, delete on public.profiles to authenticated;

create or replace function public.touch_updated_at() returns trigger language plpgsql as $$
begin new.updated_at = now(); return new; end $$;
drop trigger if exists profiles_touch on public.profiles;
create trigger profiles_touch before update on public.profiles for each row execute function public.touch_updated_at();

-- Borrado de cuenta iniciado por la propia persona: elimina su usuario y, en cascada, su perfil.
create or replace function public.delete_my_account() returns void
language sql security definer set search_path = public, auth as $$
  delete from auth.users where id = auth.uid();
$$;
revoke all on function public.delete_my_account() from public, anon;
grant execute on function public.delete_my_account() to authenticated;
