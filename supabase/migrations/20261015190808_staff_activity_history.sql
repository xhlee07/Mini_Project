begin;

create index if not exists bookings_history_date_idx on public.bookings(starts_at desc,id);
create index if not exists orders_history_date_idx on public.orders(created_at desc,id);
create index if not exists order_items_history_order_idx on public.order_items(order_id);
create index if not exists test_payments_history_date_idx on public.test_payments(created_at desc,id);
create index if not exists redemptions_history_date_idx on public.redemptions(created_at desc,id);
create index if not exists bills_history_date_idx on public.bills(created_at desc,id);
create index if not exists feedback_history_date_idx on public.app_feedback(created_at desc,id);

-- Read-only staff history. Explicit role checks are required because table RLS is
-- bypassed by this function. No profile contacts, entry tokens or device tokens leave it.
create or replace function public.ctfly_staff_history(
 p_module text,
 p_day date default null,
 p_category text default 'All',
 p_search text default '',
 p_before timestamptz default null,
 p_before_key text default null
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare result jsonb; day_start timestamptz; day_end timestamptz;
begin
 if not exists(select 1 from public.profiles where id=auth.uid() and active and role in ('staff','admin')) then
  raise exception 'Staff access required.';
 end if;
 if p_module not in ('Activity','Transactions','Feedback','Checklist','Report') or p_module is null then
  raise exception 'Choose a supported history module.';
 end if;
 if p_category is null or (p_module='Activity' and p_category not in ('All','Bookings','Orders','Competitions','Rewards'))
 or (p_module='Feedback' and p_category not in ('All','New','Reviewed','Resolved'))
 or (p_module='Transactions' and p_category not in ('All','Succeeded','Paid','Pending','Processing','Failed','Cancelled','Expired','Refunded','Void')) then
  raise exception 'Choose a supported history category.';
 end if;
 if p_search is null or char_length(p_search)>100 then raise exception 'Search must be 100 characters or fewer.';end if;
 if (p_before is null)<>(p_before_key is null) then raise exception 'Invalid history cursor.';end if;
 day_start:=p_day::timestamp at time zone 'Asia/Kuala_Lumpur';
 day_end:=(p_day+1)::timestamp at time zone 'Asia/Kuala_Lumpur';

 if p_module='Checklist' then
  return jsonb_build_object('items',coalesce((select jsonb_agg(jsonb_build_object('item',c.item,'history_key',c.item))
   from public.checklist c where c.user_id=auth.uid() and c.day=coalesce(p_day,(now() at time zone 'Asia/Kuala_Lumpur')::date)),'[]'::jsonb),'has_more',false);
 elsif p_module='Report' then
  if not exists(select 1 from public.profiles where id=auth.uid() and role='admin') then raise exception 'Manager access required.';end if;
  return jsonb_build_object('items',jsonb_build_array(jsonb_build_object('history_key','summary',
   'completed_sessions',(select count(*) from public.bookings b where b.status='Completed' and (p_day is null or b.starts_at>=day_start and b.starts_at<day_end)),
   'worked_minutes',(select coalesce(sum(a.minutes),0) from public.attendance a where p_day is null or a.clock_in>=day_start and a.clock_in<day_end),
   'late_minutes',(select coalesce(sum(a.late_minutes),0) from public.attendance a where p_day is null or a.clock_in>=day_start and a.clock_in<day_end)
  )),'has_more',false);
 end if;

 with records as (
  select b.starts_at as sort_at,'Booking:'||b.id as key,'Bookings' as category,p.name as customer,
   r.name as title,b.id::text as reference,
   jsonb_build_object('id',b.id,'category','Bookings','customer_name',p.name,'title',r.name,
    'status',b.status,'paid',b.paid,'total',b.total,'starts_at',b.starts_at,'ends_at',b.ends_at,
    'created_at',b.created_at,'guests',b.guests,'minutes',extract(epoch from (b.ends_at-b.starts_at))::int/60,
    'detail',r.kind||' · '||r.zone) as payload
  from public.bookings b join public.profiles p on p.id=b.user_id join public.resources r on r.id=b.resource_id
  where p_module='Activity' and p_category in ('All','Bookings')
   and (p_day is null or b.starts_at>=day_start and b.starts_at<day_end)
  union all
  select o.created_at,'Order:'||o.id,'Orders',p.name,'Café order',o.id::text,
   jsonb_build_object('id',o.id,'category','Orders','customer_name',p.name,'title','Café order',
    'status',o.status,'paid',o.paid,'total',o.total,'created_at',o.created_at,'received_at',o.received_at,
    'detail',coalesce((select string_agg(i.qty||' × '||i.name,', ' order by i.id) from public.order_items i where i.order_id=o.id),''))
  from public.orders o join public.profiles p on p.id=o.user_id
  where p_module='Activity' and p_category in ('All','Orders')
   and (p_day is null or o.created_at>=day_start and o.created_at<day_end)
  union all
  select e.starts_at,'Event:'||r.id,'Competitions',p.name,e.name,r.id::text,
   jsonb_build_object('id',r.id,'category','Competitions','customer_name',p.name,'title',e.name,
    'status',case when r.status='Withdrawn' then r.status when e.status in ('Completed','Cancelled') then e.status else r.status end,
    'paid',r.paid,'total',r.fee,'starts_at',e.starts_at,'ends_at',e.ends_at,'detail',r.team||' · '||e.game)
  from public.registrations r join public.events e on e.id=r.event_id join public.profiles p on p.id=r.user_id
  where p_module='Activity' and p_category in ('All','Competitions')
   and (p_day is null or e.starts_at>=day_start and e.starts_at<day_end)
  union all
  select r.created_at,'Reward:'||r.id,'Rewards',p.name,r.name,r.id::text,
   jsonb_build_object('id',r.id,'category','Rewards','customer_name',p.name,'title',r.name,
    'status',r.status,'points',r.points,'created_at',r.created_at,'detail','Points redemption')
  from public.redemptions r join public.profiles p on p.id=r.user_id
  where p_module='Activity' and p_category in ('All','Rewards')
   and (p_day is null or r.created_at>=day_start and r.created_at<day_end)
  union all
  select t.created_at,'Test:'||t.id,t.status,p.name,t.kind,t.reference,
   jsonb_build_object('id',t.id,'category','Transactions','customer_name',p.name,'title',t.kind,
    'status',t.status,'total',t.total,'created_at',t.created_at,'method',t.method,'reference',t.reference,
    'environment','test','payment',to_jsonb(t),'receipt',(select to_jsonb(b) from public.bills b where b.id=t.bill_id))
  from public.test_payments t join public.profiles p on p.id=t.user_id
  where p_module='Transactions' and (p_category='All' or t.status=p_category)
   and (p_day is null or t.created_at>=day_start and t.created_at<day_end)
  union all
  select b.created_at,'Bill:'||b.id,b.status,p.name,b.kind,coalesce(b.payment_reference,b.id::text),
   jsonb_build_object('id',b.id,'category','Transactions','customer_name',p.name,'title',b.kind,
    'status',b.status,'total',b.total,'created_at',b.created_at,'method',b.method,
    'reference',coalesce(b.payment_reference,b.id::text),'environment','counter','receipt',to_jsonb(b))
  from public.bills b join public.profiles p on p.id=b.user_id
  where p_module='Transactions' and b.environment<>'test' and (p_category='All' or b.status=p_category)
   and (p_day is null or b.created_at>=day_start and b.created_at<day_end)
  union all
  select f.created_at,'Feedback:'||f.id,f.status,p.name,f.title,f.id::text,
   to_jsonb(f)||jsonb_build_object('category','Feedback','feedback_category',f.category,'customer_name',p.name)
  from public.app_feedback f join public.profiles p on p.id=f.user_id
  where p_module='Feedback' and (p_category='All' or f.status=p_category)
   and (p_day is null or f.created_at>=day_start and f.created_at<day_end)
 ), page as (
  select sort_at,key,payload from records
  where (btrim(p_search)='' or strpos(lower(customer||' '||title||' '||reference||' '||coalesce(payload->>'detail','')),lower(btrim(p_search)))>0)
   and (p_before is null or (sort_at,key)<(p_before,p_before_key))
  order by sort_at desc,key desc limit 41
 ), visible as (
  select * from page order by sort_at desc,key desc limit 40
 )
 select jsonb_build_object('items',coalesce((select jsonb_agg(payload||jsonb_build_object('history_key',key,'sort_at',sort_at) order by sort_at desc,key desc) from visible),'[]'::jsonb),
  'has_more',(select count(*)>40 from page),
  'next_before',(select sort_at from visible order by sort_at,key limit 1),
  'next_key',(select key from visible order by sort_at,key limit 1)) into result;
 return result;
end $$;
revoke all on function public.ctfly_staff_history(text,date,text,text,timestamptz,text) from public,anon,authenticated;
grant execute on function public.ctfly_staff_history(text,date,text,text,timestamptz,text) to authenticated;

commit;
