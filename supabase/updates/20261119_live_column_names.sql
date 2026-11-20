-- Rewrite installed CTFLY functions to the live primary-key names.
-- Columns are already renamed in Supabase. This only replaces function text.
-- Run this in the CTFLY project shfatnjwpdyxdbehvjil. Do not run it on any other project.
begin;
do $$
declare
 fn record;
 item record;
 typed record;
 definition text;
 original_definition text;
 updated text;
 pk text;
 trig text;
 job_pk text;
 device_pk text;
 challenge_pk text;
 pass int;
begin
 select a.attname into job_pk
  from pg_index i
  join pg_attribute a on a.attrelid=i.indrelid and a.attnum=any(i.indkey) and a.attnum>0 and not a.attisdropped
  join pg_class c on c.oid=i.indrelid
  join pg_namespace n on n.oid=c.relnamespace
  where i.indisprimary and n.nspname='private' and c.relname='order_push_jobs'
  limit 1;
 select a.attname into device_pk
  from pg_index i
  join pg_attribute a on a.attrelid=i.indrelid and a.attnum=any(i.indkey) and a.attnum>0 and not a.attisdropped
  join pg_class c on c.oid=i.indrelid
  join pg_namespace n on n.oid=c.relnamespace
  where i.indisprimary and n.nspname='ctfly_private' and c.relname='staff_devices'
  limit 1;
 select a.attname into challenge_pk
  from pg_index i
  join pg_attribute a on a.attrelid=i.indrelid and a.attnum=any(i.indkey) and a.attnum>0 and not a.attisdropped
  join pg_class c on c.oid=i.indrelid
  join pg_namespace n on n.oid=c.relnamespace
  where i.indisprimary and n.nspname='ctfly_private' and c.relname='staff_device_challenges'
  limit 1;
 if job_pk is null or device_pk is null or challenge_pk is null or job_pk='id' or device_pk='id' or challenge_pk='id' then
  raise exception 'order_push_jobs primary key is %, staff_devices is %, staff_device_challenges is %', job_pk, device_pk, challenge_pk;
 end if;
 for fn in
  select p.oid
  from pg_proc p
  join pg_namespace n on n.oid=p.pronamespace
  where p.prokind='f' and n.nspname in ('public','private','ctfly_private')
 loop
  definition:=pg_get_functiondef(fn.oid);
  original_definition:=definition;
  definition:=replace(definition, $old0$ev.id$old0$, $new0$ev.event_id$new0$);
  definition:=replace(definition, $old1$pr.id$old1$, $new1$pr.product_id$new1$);
  definition:=replace(definition, $old2$rw.id$old2$, $new2$rw.reward_id$new2$);
  definition:=replace(definition, $old3$sh.id$old3$, $new3$sh.shift_id$new3$);
  definition:=replace(definition, $old4$tk.id$old4$, $new4$tk.ticket_id$new4$);
  definition:=replace(definition, $old5$promo.id$old5$, $new5$promo.promotion_id$new5$);
  definition:=replace(definition, $old6$invoice.id$old6$, $new6$invoice.bill_id$new6$);
  definition:=regexp_replace(definition, '\mold\.id\M', 'old.event_id', 'g');
  definition:=replace(definition, $old8$pay.id$old8$, $new8$pay.payment_id$new8$);
  definition:=replace(definition, $old9$held.id$old9$, $new9$held.order_id$new9$);
  definition:=regexp_replace(definition, '\mo\.id\M', 'o.order_id', 'g');
  definition:=regexp_replace(definition, '\mmm\.a\M', 'mm.team_a', 'g');
  definition:=regexp_replace(definition, '\mmm\.b\M', 'mm.team_b', 'g');
  definition:=regexp_replace(definition, '\mm\.id\M', 'm.match_id', 'g');
  definition:=regexp_replace(definition, '\mm\.a\M', 'm.team_a', 'g');
  definition:=regexp_replace(definition, '\mm\.b\M', 'm.team_b', 'g');
  definition:=replace(definition, 'slot,a,b)', 'slot,team_a,team_b)');
  definition:=replace(definition, 'update matches set a=', 'update matches set team_a=');
  definition:=replace(definition, 'update matches set b=', 'update matches set team_b=');
  definition:=replace(definition, $old12$from events where id=$old12$, $new12$from events where event_id=$new12$);
  definition:=replace(definition, $old13$from public.events where id=$old13$, $new13$from public.events where event_id=$new13$);
  definition:=replace(definition, $old14$update events set status='Running' where id=$old14$, $new14$update events set status='Running' where event_id=$new14$);
  definition:=replace(definition, $old15$update events set status='Completed' where id=$old15$, $new15$update events set status='Completed' where event_id=$new15$);
  definition:=replace(definition, $old16$perform 1 from events where id=$old16$, $new16$perform 1 from events where event_id=$new16$);
  definition:=replace(definition, $old17$g.event_id=e.id$old17$, $new17$g.event_id=e.event_id$new17$);
  definition:=replace(definition, $old18$on e.id=r.event_id$old18$, $new18$on e.event_id=r.event_id$new18$);
  definition:=replace(definition, $old19$on e.id=g.event_id$old19$, $new19$on e.event_id=g.event_id$new19$);
  definition:=replace(definition, $old20$where id=old.event_id$old20$, $new20$where event_id=old.event_id$new20$);
  definition:=replace(definition, $old21$where id=ev.event_id$old21$, $new21$where event_id=ev.event_id$new21$);
  definition:=replace(definition, $old22$from products where id=$old22$, $new22$from products where product_id=$new22$);
  definition:=replace(definition, $old23$update products set stock=stock-n where id=$old23$, $new23$update products set stock=stock-n where product_id=$new23$);
  definition:=replace(definition, $old24$update products set stock=stock+n where id=$old24$, $new24$update products set stock=stock+n where product_id=$new24$);
  definition:=replace(definition, $old25$update products set stock=stock+i.qty where id=$old25$, $new25$update products set stock=stock+i.qty where product_id=$new25$);
  definition:=replace(definition, $old26$update products set stock=stock+(item->>'qty')::int where id=$old26$, $new26$update products set stock=stock+(item->>'qty')::int where product_id=$new26$);
  definition:=replace(definition, $old27$jsonb_agg(p order by id) from products$old27$, $new27$jsonb_agg(p order by product_id) from products$new27$);
  definition:=replace(definition, $old28$jsonb_agg(p order by id),'[]'::jsonb) from products$old28$, $new28$jsonb_agg(p order by product_id),'[]'::jsonb) from products$new28$);
  definition:=replace(definition, $old29$jsonb_agg(c order by id) from categories$old29$, $new29$jsonb_agg(c order by category_id) from categories$new29$);
  definition:=replace(definition, $old30$jsonb_agg(r order by id) from rewards$old30$, $new30$jsonb_agg(r order by reward_id) from rewards$new30$);
  definition:=replace(definition, $old31$jsonb_agg(r order by id),'[]'::jsonb) from rewards$old31$, $new31$jsonb_agg(r order by reward_id),'[]'::jsonb) from rewards$new31$);
  definition:=replace(definition, $old32$from rewards where id=$old32$, $new32$from rewards where reward_id=$new32$);
  definition:=replace(definition, $old33$update rewards set stock=stock-1 where id=$old33$, $new33$update rewards set stock=stock-1 where reward_id=$new33$);
  definition:=replace(definition, $old34$where id=rw.reward_id$old34$, $new34$where reward_id=rw.reward_id$new34$);
  definition:=replace(definition, $old35$where id=pr.product_id$old35$, $new35$where product_id=pr.product_id$new35$);
  definition:=replace(definition, $old36$from orders where id=$old36$, $new36$from orders where order_id=$new36$);
  definition:=replace(definition, $old37$from public.orders where id=$old37$, $new37$from public.orders where order_id=$new37$);
  definition:=replace(definition, $old38$update orders set total=amount where id=$old38$, $new38$update orders set total=amount where order_id=$new38$);
  definition:=replace(definition, $old39$update orders set status='Cancelled' where id=$old39$, $new39$update orders set status='Cancelled' where order_id=$new39$);
  definition:=replace(definition, $old40$update orders set paid=true where id=$old40$, $new40$update orders set paid=true where order_id=$new40$);
  definition:=replace(definition, $old41$update orders set paid=false where id=$old41$, $new41$update orders set paid=false where order_id=$new41$);
  definition:=replace(definition, $old42$update orders set status=p_data->>'status' where id=$old42$, $new42$update orders set status=p_data->>'status' where order_id=$new42$);
  definition:=replace(definition, $old43$update public.orders set status='Received',received_at=now(),received_by=me.profile_id where id=$old43$, $new43$update public.orders set status='Received',received_at=now(),received_by=me.profile_id where order_id=$new43$);
  definition:=replace(definition, $old44$select id from orders where$old44$, $new44$select order_id from orders where$new44$);
  definition:=replace(definition, $old45$order by id for update loop$old45$, $new45$order by order_id for update loop$new45$);
  definition:=replace(definition, $old46$where id=held.order_id$old46$, $new46$where order_id=held.order_id$new46$);
  definition:=replace(definition, $old47$from registrations where id=$old47$, $new47$from registrations where registration_id=$new47$);
  definition:=replace(definition, $old48$update registrations set paid=true where id=$old48$, $new48$update registrations set paid=true where registration_id=$new48$);
  definition:=replace(definition, $old49$update registrations set paid=false where id=$old49$, $new49$update registrations set paid=false where registration_id=$new49$);
  definition:=replace(definition, $old50$update registrations set status='Withdrawn' where id=$old50$, $new50$update registrations set status='Withdrawn' where registration_id=$new50$);
  definition:=replace(definition, $old51$registrations a on a.id=$old51$, $new51$registrations a on a.registration_id=$new51$);
  definition:=replace(definition, $old52$registrations b on b.id=$old52$, $new52$registrations b on b.registration_id=$new52$);
  definition:=replace(definition, $old53$registrations w on w.id=$old53$, $new53$registrations w on w.registration_id=$new53$);
  definition:=replace(definition, $old54$registrations r where r.id=$old54$, $new54$registrations r where r.registration_id=$new54$);
  definition:=replace(definition, $old55$where r.id=p_id and r.user_id=p_uid and not r.paid$old55$, $new55$where r.registration_id=p_id and r.user_id=p_uid and not r.paid$new55$);
  definition:=replace(definition, $old56$array_agg(id order by id) into ids from registrations$old56$, $new56$array_agg(registration_id order by registration_id) into ids from registrations$new56$);
  definition:=replace(definition, $old57$select id into oid from registrations$old57$, $new57$select registration_id into oid from registrations$new57$);
  definition:=replace(definition, $old58$where id=m.match_id$old58$, $new58$where match_id=m.match_id$new58$);
  definition:=replace(definition, $old59$from matches where id=$old59$, $new59$from matches where match_id=$new59$);
  definition:=replace(definition, $old60$from bills where id=$old60$, $new60$from bills where bill_id=$new60$);
  definition:=replace(definition, $old61$from public.bills b where b.id=$old61$, $new61$from public.bills b where b.bill_id=$new61$);
  definition:=replace(definition, $old62$bills b where b.id=$old62$, $new62$bills b where b.bill_id=$new62$);
  definition:=replace(definition, $old63$if b.id is null then raise exception 'Receipt not found$old63$, $new63$if b.bill_id is null then raise exception 'Receipt not found$new63$);
  definition:=replace(definition, $old64$values(new.id,new.user_id)$old64$, $new64$values(new.bill_id,new.user_id)$new64$);
  definition:=replace(definition, $old65$'Bill:'||b.id$old65$, $new65$'Bill:'||b.bill_id$new65$);
  definition:=replace(definition, $old66$'Bill:' || b.id$old66$, $new66$'Bill:' || b.bill_id$new66$);
  definition:=replace(definition, $book0$where id=b.id$book0$, $book1$where booking_id=b.booking_id$book1$);
  definition:=replace(definition, $book2$from bookings where id=$book2$, $book3$from bookings where booking_id=$book3$);
  definition:=replace(definition, $book4$from public.bookings where id=$book4$, $book5$from public.bookings where booking_id=$book5$);
  definition:=replace(definition, $book6$bookings b where b.id=$book6$, $book7$bookings b where b.booking_id=$book7$);
  definition:=replace(definition, $book6b$where b.id=p_id$book6b$, $book7b$where b.booking_id=p_id$book7b$);
  definition:=replace(definition, $book8$if b.id is null or not b.paid$book8$, $book9$if b.booking_id is null or not b.paid$book9$);
  definition:=replace(definition, $book10$values(b.id,n,amount)$book10$, $book11$values(b.booking_id,n,amount)$book11$);
  definition:=replace(definition, $book12$'Booking:'||b.id$book12$, $book13$'Booking:'||b.booking_id$book13$);
  definition:=replace(definition, $book14$'Booking:' || b.id$book14$, $book15$'Booking:' || b.booking_id$book15$);
  definition:=replace(definition, $book16$r.name as title,b.id::text as reference$book16$, $book17$r.name as title,b.booking_id::text as reference$book17$);
  definition:=replace(definition, $book18$jsonb_build_object('id',b.id,'category','Bookings'$book18$, $book19$jsonb_build_object('id',b.booking_id,'category','Bookings'$book19$);
  definition:=replace(definition, $book20$jsonb_build_object('id', b.id, 'category', 'Bookings'$book20$, $book21$jsonb_build_object('id', b.booking_id, 'category', 'Bookings'$book21$);
  definition:=regexp_replace(definition, $book22$(update[[:space:]]+(?:public\.)?bookings[[:space:]]+set[[:space:]]+[^;]*?)where[[:space:]]+id\M$book22$, $book23$\1where booking_id$book23$, 'gi');
  definition:=regexp_replace(definition, $book24$(insert[[:space:]]+into[[:space:]]+(?:public\.)?bookings\([^;]*?)returning[[:space:]]+id[[:space:]]+into$book24$, $book25$\1returning booking_id into$book25$, 'gi');
  definition:=replace(definition, $old68$jsonb_build_object('id',b.id,'category','Transactions'$old68$, $new68$jsonb_build_object('id',b.bill_id,'category','Transactions'$new68$);
  definition:=replace(definition, $old69$where id=invoice.bill_id$old69$, $new69$where bill_id=invoice.bill_id$new69$);
  definition:=replace(definition, $old70$from redemptions where id=$old70$, $new70$from redemptions where redeem_id=$new70$);
  definition:=replace(definition, $old71$update redemptions set status='Collected' where id=$old71$, $new71$update redemptions set status='Collected' where redeem_id=$new71$);
  definition:=replace(definition, $old72$from shifts where id=$old72$, $new72$from shifts where shift_id=$new72$);
  definition:=replace(definition, $old73$update shifts set status='Cancelled' where id=$old73$, $new73$update shifts set status='Cancelled' where shift_id=$new73$);
  definition:=replace(definition, $old74$where id=sh.shift_id$old74$, $new74$where shift_id=sh.shift_id$new74$);
  definition:=replace(definition, $old75$from leave_requests where id=$old75$, $new75$from leave_requests where leave_request_id=$new75$);
  definition:=replace(definition, $old76$update leave_requests set status=p_data->>'status' where id=$old76$, $new76$update leave_requests set status=p_data->>'status' where leave_request_id=$new76$);
  definition:=replace(definition, $old77$from tickets where id=$old77$, $new77$from tickets where ticket_id=$new77$);
  definition:=replace(definition, $old78$where id=tk.ticket_id$old78$, $new78$where ticket_id=tk.ticket_id$new78$);
  definition:=replace(definition, $old79$from tasks where id=$old79$, $new79$from tasks where task_id=$new79$);
  definition:=replace(definition, $old80$update tasks set done=not done where id=$old80$, $new80$update tasks set done=not done where task_id=$new80$);
  definition:=replace(definition, $old81$from handovers where id=$old81$, $new81$from handovers where handover_id=$new81$);
  definition:=replace(definition, $old82$from categories where id=$old82$, $new82$from categories where category_id=$new82$);
  definition:=replace(definition, $old83$update categories set name=trim(p_data->>'name') where id=$old83$, $new83$update categories set name=trim(p_data->>'name') where category_id=$new83$);
  definition:=replace(definition, $old84$from app_feedback where id=$old84$, $new84$from app_feedback where feedback_id=$new84$);
  definition:=replace(definition, $old85$update app_feedback set status=p_data->>'status',updated_at=now(),updated_by=me.profile_id
   where id=$old85$, $new85$update app_feedback set status=p_data->>'status',updated_at=now(),updated_by=me.profile_id
   where feedback_id=$new85$);
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
  definition:=replace(definition, $old87$'Feedback:'||f.id$old87$, $new87$'Feedback:'||f.feedback_id$new87$);
  definition:=replace(definition, $old88$'Feedback:'||f.feedback_id,f.status,p.name,f.title,f.id::text$old88$, $new88$'Feedback:'||f.feedback_id,f.status,p.name,f.title,f.feedback_id::text$new88$);
  definition:=replace(definition, $old89$from promotions where id=$old89$, $new89$from promotions where promotion_id=$new89$);
  definition:=replace(definition, $old90$where id=promo.promotion_id$old90$, $new90$where promotion_id=promo.promotion_id$new90$);
  definition:=replace(definition, $old91$c.promotion_id=p.id$old91$, $new91$c.promotion_id=p.promotion_id$new91$);
  definition:=replace(definition, $old92$from test_payments where id=$old92$, $new92$from test_payments where payment_id=$new92$);
  definition:=replace(definition, $old93$update test_payments set status='Refunded',settled_at=now() where id=$old93$, $new93$update test_payments set status='Refunded',settled_at=now() where payment_id=$new93$);
  definition:=replace(definition, $old94$update test_payments set status='Succeeded',bill_id=bid,settled_at=now() where id=$old94$, $new94$update test_payments set status='Succeeded',bill_id=bid,settled_at=now() where payment_id=$new94$);
  definition:=replace(definition, $old95$update test_payments set status=case outcome when 'Failure' then 'Failed' when 'Cancel' then 'Cancelled' else 'Processing' end where id=$old95$, $new95$update test_payments set status=case outcome when 'Failure' then 'Failed' when 'Cancel' then 'Cancelled' else 'Processing' end where payment_id=$new95$);
  definition:=replace(definition, $old96$update test_payments set promotion_id=(q->>'promotion_id')::uuid,promo_code=q->>'promo_code' where id=$old96$, $new96$update test_payments set promotion_id=(q->>'promotion_id')::uuid,promo_code=q->>'promo_code' where payment_id=$new96$);
  definition:=replace(definition, $old97$on t.id=c.payment_id$old97$, $new97$on t.payment_id=c.payment_id$new97$);
  definition:=replace(definition, $old98$where payment_id=new.id$old98$, $new98$where payment_id=new.payment_id$new98$);
  definition:=replace(definition, $old99$'Test:'||t.id$old99$, $new99$'Test:'||t.payment_id$new99$);
  definition:=replace(definition, $old100$'Test:' || t.id$old100$, $new100$'Test:' || t.payment_id$new100$);
  definition:=replace(definition, $old101$jsonb_build_object('id',t.id$old101$, $new101$jsonb_build_object('id',t.payment_id$new101$);
  definition:=replace(definition, $old102$order by i.id)$old102$, $new102$order by i.order_item_id)$new102$);
  definition:=replace(definition, $old103$order by id) from order_items$old103$, $new103$order by order_item_id) from order_items$new103$);
  definition:=replace(definition, $old104$'Event:'||r.id$old104$, $new104$'Event:'||r.registration_id$new104$);
  definition:=replace(definition, $old105$r.id::text$old105$, $new105$r.registration_id::text$new105$);
  definition:=replace(definition, $old106$jsonb_build_object('id',r.id,'category','Competitions'$old106$, $new106$jsonb_build_object('id',r.registration_id,'category','Competitions'$new106$);
  definition:=replace(definition, $old107$'Reward:'||r.id$old107$, $new107$'Reward:'||r.reward_id$new107$);
  definition:=replace(definition, $old108$jsonb_build_object('id',r.id,'category','Rewards'$old108$, $new108$jsonb_build_object('id',r.reward_id,'category','Rewards'$new108$);
  definition:=replace(definition, $old109$values(new.user_id,new.id)$old109$, $new109$values(new.user_id,new.order_id)$new109$);
  definition:=replace(definition, $old110$returning id into notice$old110$, $new110$returning notice_id into notice$new110$);
  definition:=replace(definition, $old111$select id from public.order_notifications$old111$, $new111$select notice_id from public.order_notifications$new111$);
  definition:=replace(definition, $old112$n.id=j.notice_id$old112$, $new112$n.notice_id=j.notice_id$new112$);
  definition:=replace(definition, $old113$select id,stock,'Opening stock' from products$old113$, $new113$select product_id,stock,'Opening stock' from products$new113$);
  definition:=replace(definition, $old114$where g.id=$old114$, $new114$where g.registration_id=$new114$);
  definition:=replace(definition, $old115$else update products set category_id=(p_data->>'category_id')::bigint,name=p_data->>'name',price=(p_data->>'price')::int,threshold=(p_data->>'threshold')::int,image=p_data->>'image',active=(p_data->>'active')::boolean where id=bid$old115$, $new115$else update products set category_id=(p_data->>'category_id')::bigint,name=p_data->>'name',price=(p_data->>'price')::int,threshold=(p_data->>'threshold')::int,image=p_data->>'image',active=(p_data->>'active')::boolean where product_id=bid$new115$);
  definition:=replace(definition, $old116$else update rewards set name=p_data->>'name',points=(p_data->>'points')::int,stock=(p_data->>'stock')::int,active=(p_data->>'active')::boolean where id=bid$old116$, $new116$else update rewards set name=p_data->>'name',points=(p_data->>'points')::int,stock=(p_data->>'stock')::int,active=(p_data->>'active')::boolean where reward_id=bid$new116$);
  definition:=replace(definition, $old117$update events set name=p_data->>'name',game=p_data->>'game',starts_at=(p_data->>'starts_at')::timestamptz,ends_at=(p_data->>'ends_at')::timestamptz,fee=(p_data->>'fee')::int,capacity=(p_data->>'capacity')::int,status=p_data->>'status' where id=bid$old117$, $new117$update events set name=p_data->>'name',game=p_data->>'game',starts_at=(p_data->>'starts_at')::timestamptz,ends_at=(p_data->>'ends_at')::timestamptz,fee=(p_data->>'fee')::int,capacity=(p_data->>'capacity')::int,status=p_data->>'status' where event_id=bid$new117$);
  definition:=replace(definition, $old118$'attendance',coalesce((select jsonb_agg(a) from attendance a join shifts s on s.id=a.shift_id where s.staff_id=me.profile_id or me.role='admin'),'[]'::jsonb),$old118$, $new118$'attendance',coalesce((select jsonb_agg(a) from attendance a join shifts s on s.shift_id=a.shift_id where s.staff_id=me.profile_id or me.role='admin'),'[]'::jsonb),$new118$);
  definition:=replace(definition, $old119$  bid:=nullif(p_data->>'id','')::bigint;
  if p_action='save_resource' then
   bid:=nullif(coalesce(p_data->>'resource_id',p_data->>'id'),'')::bigint;$old119$, $new119$  bid:=nullif(coalesce(case p_action when 'save_resource' then p_data->>'resource_id' when 'save_category' then p_data->>'category_id' when 'save_product' then p_data->>'product_id' when 'restock' then p_data->>'product_id' when 'save_event' then p_data->>'event_id' when 'save_reward' then p_data->>'reward_id' else null end,p_data->>'id'),'')::bigint;
  if p_action='save_resource' then$new119$);
  definition:=replace(definition, $old120$where l.id=(p_data->>'leave_id')::uuid$old120$, $new120$where l.leave_request_id=(p_data->>'leave_id')::uuid$new120$);
  definition:=replace(definition, $old121$where id=p_id and user_id=p_uid and not paid and status='Pending' for update;$old121$, $new121$where order_id=p_id and user_id=p_uid and not paid and status='Pending' for update;$new121$);
  definition:=replace(definition, $old122$update orders set hold_expires_at=deadline where id=p_id and user_id=p_uid and hold_expires_at is null;$old122$, $new122$update orders set hold_expires_at=deadline where order_id=p_id and user_id=p_uid and hold_expires_at is null;$new122$);
  definition:=replace(definition, $old123$update registrations set hold_expires_at=deadline where id=p_id and user_id=p_uid and hold_expires_at is null;$old123$, $new123$update registrations set hold_expires_at=deadline where registration_id=p_id and user_id=p_uid and hold_expires_at is null;$new123$);
  definition:=replace(definition, $old124$update orders set paid=false,status='Cancelled' where id=pay.source_id and paid and status='Pending';$old124$, $new124$update orders set paid=false,status='Cancelled' where order_id=pay.source_id and paid and status='Pending';$new124$);
  definition:=replace(definition, $old125$update registrations set paid=false,status='Withdrawn' where id=pay.source_id and paid and status='Registered'$old125$, $new125$update registrations set paid=false,status='Withdrawn' where registration_id=pay.source_id and paid and status='Registered'$new125$);
  definition:=replace(definition, $old126$update bills set status='Void',void_reason='Payment refunded.' where id=pay.bill_id and environment='test';$old126$, $new126$update bills set status='Void',void_reason='Payment refunded.' where bill_id=pay.bill_id and environment='test';$new126$);
  definition:=replace(definition, $old127$pay.reference) returning id into bid;$old127$, $new127$pay.reference) returning bill_id into bid;$new127$);
  definition:=replace(definition, $old128$if nullif(d->>'id','') is not null then
  select * into old from events where event_id=(d->>'id')::bigint for update;$old128$, $new128$if nullif(coalesce(d->>'event_id',d->>'id'),'') is not null then
  select * into old from events where event_id=nullif(coalesce(d->>'event_id',d->>'id'),'')::bigint for update;$new128$);
  definition:=replace(definition, $old129$where event_id=old.event_id returning id into eid;$old129$, $new129$where event_id=old.event_id returning event_id into eid;$new129$);
  definition:=replace(definition, $old130$) returning id into eid;$old130$, $new130$) returning event_id into eid;$new130$);
  definition:=replace(definition, $old131$if nullif(d->>'id','') is not null then
  select * into promo from promotions where promotion_id=(d->>'id')::uuid for update;$old131$, $new131$if nullif(coalesce(d->>'promotion_id',d->>'id'),'') is not null then
  select * into promo from promotions where promotion_id=nullif(coalesce(d->>'promotion_id',d->>'id'),'')::uuid for update;$new131$);
  definition:=replace(definition, $old132$where promotion_id=promo.promotion_id returning id into result_id;$old132$, $new132$where promotion_id=promo.promotion_id returning promotion_id into result_id;$new132$);
  definition:=replace(definition, $old133$) returning id into result_id;$old133$, $new133$) returning promotion_id into result_id;$new133$);
  definition:=replace(definition, $old134$values(b.id,b.user_id)$old134$, $new134$values(b.bill_id,b.user_id)$new134$);
  definition:=replace(definition, $old135$where bill_id=b.id)$old135$, $new135$where bill_id=b.bill_id)$new135$);
  definition:=replace(definition, $old136$join bills b on b.id=j.bill_id$old136$, $new136$join bills b on b.bill_id=j.bill_id$new136$);
  definition:=replace(definition, $old137$'notice_id',n.id$old137$, $new137$'notice_id',n.notice_id$new137$);
  definition:=replace(definition, $old138$select r.created_at,'Reward:'||r.reward_id,'Rewards',p.name,r.name,r.registration_id::text,
   jsonb_build_object('id',r.reward_id,'category','Rewards'$old138$, $new138$select r.created_at,'Reward:'||r.redeem_id,'Rewards',p.name,r.name,r.redeem_id::text,
   jsonb_build_object('id',r.redeem_id,'category','Rewards'$new138$);
  definition:=replace(definition, $old139$where b.id = t.bill_id$old139$, $new139$where b.bill_id = t.bill_id$new139$);
  definition:=replace(definition, $old140$values(me.profile_id,sid,0) returning feedback_id into oid;$old140$, $new140$values(me.profile_id,sid,0) returning order_id into oid;$new140$);
  definition:=replace(definition, $old141$bid:=nullif(p_data->>'id','')::bigint;$old141$, $new141$bid:=nullif(coalesce(case p_action when 'save_resource' then p_data->>'resource_id' when 'save_category' then p_data->>'category_id' when 'save_product' then p_data->>'product_id' when 'restock' then p_data->>'product_id' when 'save_event' then p_data->>'event_id' when 'save_reward' then p_data->>'reward_id' else null end,p_data->>'id'),'')::bigint;$new141$);
  definition:=replace(definition, $old142$order by id) from resources$old142$, $new142$order by resource_id) from resources$new142$);
  definition:=replace(definition, $old143$order by id) from public.resources$old143$, $new143$order by resource_id) from public.resources$new143$);
  definition:=replace(definition, $old144$order by id),'[]'::jsonb) from resources$old144$, $new144$order by resource_id),'[]'::jsonb) from resources$new144$);
  definition:=replace(definition, $old145$order by id),'[]'::jsonb) from public.resources$old145$, $new145$order by resource_id),'[]'::jsonb) from public.resources$new145$);
  definition:=replace(definition, $old146$join resources r on r.id=$old146$, $new146$join resources r on r.resource_id=$new146$);
  definition:=replace(definition, $old147$join public.resources r on r.id=$old147$, $new147$join public.resources r on r.resource_id=$new147$);
  definition:=replace(definition, $old148$from resources where id=$old148$, $new148$from resources where resource_id=$new148$);
  definition:=replace(definition, $old149$from resources r where r.id=$old149$, $new149$from resources r where r.resource_id=$new149$);
  definition:=replace(definition, $old150$if r.id is null$old150$, $new150$if r.resource_id is null$new150$);
  for pass in 1..40 loop
   updated:=regexp_replace(definition, $sweepb$((?:from|join|update)[[:space:]]+(?:public\.)?bookings[[:space:]]+b\M[^;]*?)b\.id\M$sweepb$, $sweepb2$\1b.booking_id$sweepb2$, 'i');
   exit when updated=definition;
   definition:=updated;
  end loop;
  for pass in 1..40 loop
   updated:=regexp_replace(definition, $sweepi$(into[[:space:]]+b[[:space:]]+from[[:space:]]+(?:public\.)?bookings\M[^;]*?)b\.id\M$sweepi$, $sweepi2$\1b.booking_id$sweepi2$, 'i');
   exit when updated=definition;
   definition:=updated;
  end loop;
  for pass in 1..40 loop
   updated:=regexp_replace(definition, $sweepl$((?:from|join)[[:space:]]+(?:public\.)?bills[[:space:]]+b\M[^;]*?)b\.id\M$sweepl$, $sweepl2$\1b.bill_id$sweepl2$, 'i');
   exit when updated=definition;
   definition:=updated;
  end loop;
  for pass in 1..40 loop
   updated:=regexp_replace(definition, $sweepbill$(into[[:space:]]+b[[:space:]]+from[[:space:]]+(?:public\.)?bills\M[^;]*?)b\.id\M$sweepbill$, $sweepbill2$\1b.bill_id$sweepbill2$, 'i');
   exit when updated=definition;
   definition:=updated;
  end loop;
  if definition ~* '\mb[[:space:]]+(?:public\.)?bookings\M' and definition !~* '\mb[[:space:]]+(?:public\.)?bills\M' then
   definition:=regexp_replace(definition, '\mb\.id\M', 'b.booking_id', 'g');
  elsif definition ~* '\mb[[:space:]]+(?:public\.)?bills\M' and definition !~* '\mb[[:space:]]+(?:public\.)?bookings\M' then
   definition:=regexp_replace(definition, '\mb\.id\M', 'b.bill_id', 'g');
  end if;
  for item in
   select * from (values
    ('resources','resource_id'),
    ('bookings','booking_id'),
    ('booking_extensions','booking_extension_id'),
    ('categories','category_id'),
    ('products','product_id'),
    ('stock_records','stock_id'),
    ('orders','order_id'),
    ('order_items','order_item_id'),
    ('events','event_id'),
    ('registrations','registration_id'),
    ('matches','match_id'),
    ('bills','bill_id'),
    ('rewards','reward_id'),
    ('redemptions','redeem_id'),
    ('points_transactions','point_id'),
    ('shifts','shift_id'),
    ('attendance','attendance_id'),
    ('leave_requests','leave_request_id'),
    ('tasks','task_id'),
    ('tickets','ticket_id'),
    ('handovers','handover_id'),
    ('promotions','promotion_id'),
    ('test_payments','payment_id'),
    ('app_feedback','feedback_id'),
    ('order_notifications','notice_id'),
    ('ctfly_sessions','session_id')
   ) as renamed(tbl, pk)
  loop
   for pass in 1..30 loop
    updated:=regexp_replace(definition,
     format($tblwhere$((?:from|join|update|perform[[:space:]]+1[[:space:]]+from)[[:space:]]+(?:public\.)?%s\M(?:(?![[:space:]]union[[:space:]])[^;])*?)where[[:space:]]+id\M$tblwhere$, item.tbl),
     format($tblwhere2$\1where %s$tblwhere2$, item.pk), 'i');
    exit when updated=definition;
    definition:=updated;
   end loop;
   for pass in 1..30 loop
    updated:=regexp_replace(definition,
     format($tblalias$((?:from|join)[[:space:]]+(?:public\.)?%s[[:space:]]+)([a-zA-Z_][a-zA-Z0-9_]*)((?:(?![[:space:]]union[[:space:]])[^;])*?)\2\.id\M$tblalias$, item.tbl),
     format($tblalias2$\1\2\3\2.%s$tblalias2$, item.pk), 'i');
    exit when updated=definition;
    definition:=updated;
   end loop;
   for pass in 1..30 loop
    updated:=regexp_replace(definition,
     format($tblinto$(into[[:space:]]+)([a-zA-Z_][a-zA-Z0-9_]*)([[:space:]]+from[[:space:]]+(?:public\.)?%s\M(?:(?![[:space:]]union[[:space:]])[^;])*?)\2\.id\M$tblinto$, item.tbl),
     format($tblinto2$\1\2\3\2.%s$tblinto2$, item.pk), 'i');
    exit when updated=definition;
    definition:=updated;
   end loop;
   definition:=replace(definition, format('order by id) from %s', item.tbl), format('order by %s) from %s', item.pk, item.tbl));
   definition:=replace(definition, format('order by id) from public.%s', item.tbl), format('order by %s) from public.%s', item.pk, item.tbl));
   definition:=replace(definition, format($tblorder$order by id),'[]'::jsonb) from %s$tblorder$, item.tbl), format($tblorder2$order by %s),'[]'::jsonb) from %s$tblorder2$, item.pk, item.tbl));
   definition:=replace(definition, format($tblorderp$order by id),'[]'::jsonb) from public.%s$tblorderp$, item.tbl), format($tblorderp2$order by %s),'[]'::jsonb) from public.%s$tblorderp2$, item.pk, item.tbl));
   definition:=regexp_replace(definition,
    format($tblret$(insert[[:space:]]+into[[:space:]]+(?:public\.)?%s\M[^;]*?)returning[[:space:]]+id\M$tblret$, item.tbl),
    format($tblret2$\1returning %s$tblret2$, item.pk), 'gi');
   definition:=regexp_replace(definition,
    format($tblsel$select[[:space:]]+id\M(?=[[:space:]]*(?:,[^;]{0,120})?from[[:space:]]+(?:public\.)?%s\M)$tblsel$, item.tbl),
    format($tblsel2$select %s$tblsel2$, item.pk), 'gi');
  end loop;
  for typed in
   select m[1] as alias, m[2] as tbl
   from regexp_matches(definition, $rowtype$([a-zA-Z_][a-zA-Z0-9_]*)[[:space:]]+(?:public\.)?(resources|bookings|booking_extensions|categories|products|stock_records|orders|order_items|events|registrations|matches|bills|rewards|redemptions|points_transactions|shifts|attendance|leave_requests|tasks|tickets|handovers|promotions|test_payments|app_feedback|order_notifications|ctfly_sessions)\M$rowtype$, 'gi') as found(m)
  loop
   if typed.alias in ('from','join','update','into','table','perform','select','insert','delete','on','left','right','inner','cross','natural','public','outer','full','where','and','or','set','values','returning','group','order','limit','offset','having','union','except','intersect','as','by','desc','asc','case','when','then','else','end','not','null','true','false','is','in','exists','all','any','some','between','like','ilike','similar','to','distinct','using','lateral','with','for','loop','while','if','elsif','raise','return','execute','declare','begin','exception','found','new','old','before','after','each','row','statement','constraint','references') then
    continue;
   end if;
   pk:=case typed.tbl
    when 'resources' then 'resource_id'
    when 'bookings' then 'booking_id'
    when 'booking_extensions' then 'booking_extension_id'
    when 'categories' then 'category_id'
    when 'products' then 'product_id'
    when 'stock_records' then 'stock_id'
    when 'orders' then 'order_id'
    when 'order_items' then 'order_item_id'
    when 'events' then 'event_id'
    when 'registrations' then 'registration_id'
    when 'matches' then 'match_id'
    when 'bills' then 'bill_id'
    when 'rewards' then 'reward_id'
    when 'redemptions' then 'redeem_id'
    when 'points_transactions' then 'point_id'
    when 'shifts' then 'shift_id'
    when 'attendance' then 'attendance_id'
    when 'leave_requests' then 'leave_request_id'
    when 'tasks' then 'task_id'
    when 'tickets' then 'ticket_id'
    when 'handovers' then 'handover_id'
    when 'promotions' then 'promotion_id'
    when 'test_payments' then 'payment_id'
    when 'app_feedback' then 'feedback_id'
    when 'order_notifications' then 'notice_id'
    when 'ctfly_sessions' then 'session_id'
   end;
   if pk is not null then
    definition:=regexp_replace(definition, format('\m%s\.id\M', typed.alias), format('%s.%s', typed.alias, pk), 'g');
   end if;
  end loop;
  select c.relname into trig
   from pg_trigger t
   join pg_class c on c.oid=t.tgrelid
   join pg_namespace n on n.oid=c.relnamespace
   where t.tgfoid=fn.oid and not t.tgisinternal and n.nspname='public'
   limit 1;
  pk:=case trig
   when 'resources' then 'resource_id'
   when 'bookings' then 'booking_id'
   when 'booking_extensions' then 'booking_extension_id'
   when 'categories' then 'category_id'
   when 'products' then 'product_id'
   when 'stock_records' then 'stock_id'
   when 'orders' then 'order_id'
   when 'order_items' then 'order_item_id'
   when 'events' then 'event_id'
   when 'registrations' then 'registration_id'
   when 'matches' then 'match_id'
   when 'bills' then 'bill_id'
   when 'rewards' then 'reward_id'
   when 'redemptions' then 'redeem_id'
   when 'points_transactions' then 'point_id'
   when 'shifts' then 'shift_id'
   when 'attendance' then 'attendance_id'
   when 'leave_requests' then 'leave_request_id'
   when 'tasks' then 'task_id'
   when 'tickets' then 'ticket_id'
   when 'handovers' then 'handover_id'
   when 'promotions' then 'promotion_id'
   when 'test_payments' then 'payment_id'
   when 'app_feedback' then 'feedback_id'
   when 'order_notifications' then 'notice_id'
   when 'ctfly_sessions' then 'session_id'
  end;
  if position('order_push_jobs' in definition)>0 then
   definition:=replace(definition, 'select id from private.order_push_jobs', 'select '||job_pk||' from private.order_push_jobs');
   definition:=replace(definition, 'j.id=chosen.id', 'j.'||job_pk||'=chosen.'||job_pk);
   definition:=replace(definition, '''id'',j.id', '''id'',j.'||job_pk);
   definition:=replace(definition, 'order_push_jobs set status=d->>''status'',next_attempt=now()+interval ''1 minute'' where id=', 'order_push_jobs set status=d->>''status'',next_attempt=now()+interval ''1 minute'' where '||job_pk||'=');
  end if;
  if position('staff_devices' in definition)>0 then
   definition:=replace(definition, 'staff_devices(id)', 'staff_devices('||device_pk||')');
   definition:=replace(definition, 'staff_devices(id,', 'staff_devices('||device_pk||',');
   definition:=replace(definition, 'where id=device', 'where '||device_pk||'=device');
   definition:=replace(definition, 'on conflict(id)', 'on conflict('||device_pk||')');
   definition:=replace(definition, 'where id=(p_data->>''device_id'')', 'where '||device_pk||'=(p_data->>''device_id'')');
   definition:=replace(definition, '''id'',id,''staff_name''', '''id'','||device_pk||',''staff_name''');
   definition:=replace(definition, 'order by created_at,id', 'order by created_at,'||device_pk);
   definition:=replace(definition, 'where id=p_device', 'where '||device_pk||'=p_device');
   definition:=replace(definition, 'd.id is null', 'd.'||device_pk||' is null');
  end if;
  if position('staff_device_challenges' in definition)>0 then
   definition:=replace(definition, 'where id=p_challenge', 'where '||challenge_pk||'=p_challenge');
   definition:=replace(definition, '''challenge_id'',c.id', '''challenge_id'',c.'||challenge_pk);
   definition:=replace(definition, 'c.id is null', 'c.'||challenge_pk||' is null');
  end if;
  definition:=replace(definition, 'insert into requests values(me.profile_id,p_request,result)', 'insert into requests(user_id,request_id,result) values(me.profile_id,p_request,result)');
  if pk is not null then
   definition:=regexp_replace(definition, '\mnew\.id\M', 'new.'||pk, 'g');
   definition:=regexp_replace(definition, '\mold\.id\M', 'old.'||pk, 'g');
   if trig<>'events' then
    definition:=replace(definition, 'old.event_id', 'old.'||pk);
    definition:=replace(definition, 'new.event_id', 'new.'||pk);
   end if;
  end if;
  if definition is distinct from original_definition then
   begin
    execute definition;
   exception when others then
    raise exception '% still has an old column name: %', fn.oid::regprocedure, sqlerrm;
   end;
  end if;
 end loop;
end $$;

do $$
declare fn record; body text;
begin
 for fn in
  select p.oid, p.prosrc as src
  from pg_proc p
  join pg_namespace n on n.oid=p.pronamespace
  where p.prokind='f' and n.nspname in ('public','private','ctfly_private')
 loop
  body:=fn.src;
  if position('from orders where id=' in body)>0
   or position('from products where id=' in body)>0
   or position('from events where id=' in body)>0
   or position('from bills where id=' in body)>0
   or position('from rewards where id=' in body)>0
   or position('order by id) from resources' in body)>0
   or body ~ '\m(ev|pr|o|invoice|b)\.id\M'
   or body ~* 'from[[:space:]]+(public\.)?bookings[[:space:]]+where[[:space:]]+id\M' then
   raise warning 'Function % still mentions an old id column', fn.oid::regprocedure;
  end if;
 end loop;
end $$;
notify pgrst, 'reload schema';
commit;
