-- Read-only checks. Run yourself after applying the setup SQL.
select
 to_regprocedure('public.ctfly_staff_devices(text,jsonb)') is not null as devices_installed,
 to_regprocedure('public.ctfly_staff_device_auth(text,uuid,uuid)') is not null as login_installed;

select n.nspname as schema_name, c.relname as table_name, c.relrowsecurity as rls_enabled
from pg_class c join pg_namespace n on n.oid=c.relnamespace
where n.nspname='ctfly_private' and c.relname in ('staff_devices','staff_device_challenges');

select
 has_function_privilege('anon','public.ctfly_staff_device_auth(text,uuid,uuid)','execute') as anon_can_issue_login,
 has_function_privilege('authenticated','public.ctfly_staff_device_auth(text,uuid,uuid)','execute') as user_can_issue_login,
 has_function_privilege('service_role','public.ctfly_staff_device_auth(text,uuid,uuid)','execute') as server_can_issue_login;
-- Expected: false, false, true.
