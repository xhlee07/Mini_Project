-- Rewrite every saved function that still uses an old column name.
-- Run on shfatnjwpdyxdbehvjil only. Word boundaries keep from.id, to.id and hold.id unchanged.
begin;
do $$
declare
 fn record;
 definition text;
 original_definition text;
 item record;
begin
 for fn in
  select p.oid
  from pg_proc p
  join pg_namespace n on n.oid=p.pronamespace
  where p.prokind='f' and n.nspname in ('public','private','ctfly_private')
 loop
  definition:=pg_get_functiondef(fn.oid);
  original_definition:=definition;
  definition:=replace(definition, 'registrations a on a.id=', 'registrations a on a.registration_id=');
  definition:=replace(definition, 'registrations b on b.id=', 'registrations b on b.registration_id=');
  definition:=replace(definition, 'registrations w on w.id=', 'registrations w on w.registration_id=');
  definition:=replace(definition, 'registrations r where r.id=', 'registrations r where r.registration_id=');
  definition:=replace(definition, 'slot,a,b)', 'slot,team_a,team_b)');
  definition:=replace(definition, 'update matches set a=', 'update matches set team_a=');
  definition:=replace(definition, 'update matches set b=', 'update matches set team_b=');
  definition:=replace(definition, '(r.rate*n+30)/60) returning id into oid;', '(r.rate*n+30)/60) returning booking_id into oid;');
  definition:=replace(definition, '(r.rate*n+30)/60) returning feedback_id into oid;', '(r.rate*n+30)/60) returning booking_id into oid;');
  definition:=replace(definition, 'values(me.profile_id,sid,0) returning id into oid;', 'values(me.profile_id,sid,0) returning order_id into oid;');
  definition:=replace(definition, 'values(me.profile_id,sid,0) returning feedback_id into oid;', 'values(me.profile_id,sid,0) returning order_id into oid;');
  definition:=replace(definition, 'values(b.booking_id,n,amount) returning id into oid;', 'values(b.booking_id,n,amount) returning booking_extension_id into oid;');
  definition:=replace(definition, 'values(b.booking_id,n,amount) returning feedback_id into oid;', 'values(b.booking_id,n,amount) returning booking_extension_id into oid;');
  definition:=replace(definition, 'values(b.id,n,amount) returning id into oid;', 'values(b.booking_id,n,amount) returning booking_extension_id into oid;');
  definition:=replace(definition, 'values(b.id,n,amount) returning feedback_id into oid;', 'values(b.booking_id,n,amount) returning booking_extension_id into oid;');
  definition:=regexp_replace(definition, '(insert[[:space:]]+into[[:space:]]+(?:public\.)?orders\M[^;]*?)returning[[:space:]]+feedback_id\M', '\1returning order_id', 'gi');
  definition:=regexp_replace(definition, '(insert[[:space:]]+into[[:space:]]+(?:public\.)?bookings\M[^;]*?)returning[[:space:]]+feedback_id\M', '\1returning booking_id', 'gi');
  definition:=regexp_replace(definition, '(insert[[:space:]]+into[[:space:]]+(?:public\.)?booking_extensions\M[^;]*?)returning[[:space:]]+feedback_id\M', '\1returning booking_extension_id', 'gi');
  definition:=regexp_replace(definition, '(update[[:space:]]+(?:public\.)?orders\M[^;]*?)where[[:space:]]+feedback_id\M', '\1where order_id', 'gi');
  definition:=regexp_replace(definition, '(update[[:space:]]+(?:public\.)?bookings\M[^;]*?)where[[:space:]]+feedback_id\M', '\1where booking_id', 'gi');
  for item in
   select * from (values
    ('\mmm\.a\M','mm.team_a'),
    ('\mmm\.b\M','mm.team_b'),
    ('\mm\.a\M','m.team_a'),
    ('\mm\.b\M','m.team_b'),
    ('\mm\.id\M','m.match_id'),
    ('\mo\.id\M','o.order_id'),
    ('\mev\.id\M','ev.event_id'),
    ('\mpr\.id\M','pr.product_id'),
    ('\mrw\.id\M','rw.reward_id'),
    ('\msh\.id\M','sh.shift_id'),
    ('\mtk\.id\M','tk.ticket_id'),
    ('\mpromo\.id\M','promo.promotion_id'),
    ('\minvoice\.id\M','invoice.bill_id'),
    ('\mpay\.id\M','pay.payment_id'),
    ('\mheld\.id\M','held.order_id')
   ) as renamed(pattern, replacement)
  loop
   definition:=regexp_replace(definition, item.pattern, item.replacement, 'g');
  end loop;
  if definition is distinct from original_definition then
   execute definition;
  end if;
 end loop;
end $$;
commit;
