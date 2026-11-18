-- pgcrypto lives in the extensions schema on Supabase.
-- Login failed with: function crypt(text, text) does not exist.
begin;

do $$
begin
 if not exists (select 1 from pg_extension where extname='pgcrypto') then
  execute 'create extension pgcrypto with schema extensions';
 end if;
end $$;

alter function public.ctfly_uid() set search_path = public, extensions, pg_temp;
alter function public.ctfly_issue_session(uuid) set search_path = public, extensions, pg_temp;
alter function public.ctfly_login(text, text) set search_path = public, extensions, pg_temp;
alter function public.ctfly_signup(text, text, text, text, text) set search_path = public, extensions, pg_temp;
alter function public.ctfly_recover(text) set search_path = public, extensions, pg_temp;
alter function public.ctfly_verify(text, text, text) set search_path = public, extensions, pg_temp;
alter function public.ctfly_new_password(text) set search_path = public, extensions, pg_temp;
alter function public.ctfly_refresh_token(text) set search_path = public, extensions, pg_temp;
alter function public.ctfly_logout() set search_path = public, extensions, pg_temp;

notify pgrst, 'reload schema';
commit;
