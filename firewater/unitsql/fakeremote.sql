-- $Id$
-- Tests for Firewater query optimization for plans distributed over
-- remote servers (but faked so that we don't actually need
-- any storage nodes running)

create or replace server fakeremote_server
foreign data wrapper sys_firewater_fakeremote_wrapper
options (user_name 'sa');

create partition qp1 on (fakeremote_server);

create partition qp2 on (fakeremote_server);

create schema m;

create table m.t1(i int, j int)
options (partitioning 'HASH');

create table m.t2(i int, j int)
options (partitioning 'NONE');

create label l1;

drop label l1;

!set outputformat csv

-- test basic table access
explain plan for select * from m.t1;

-- test projection pushdown through union
explain plan for select i from m.t1;

-- test filter pushdown through union
explain plan for select i from m.t1 where j > 3;

-- test GROUP BY pushdown through union
explain plan for select i,sum(j),count(*) from m.t1 group by i;

-- test GROUP BY with AVG
explain plan for select i,avg(j) from m.t1 group by i;

-- test GROUP BY with DISTINCT COUNT
explain plan for select i,count(distinct j), sum(j) from m.t1 group by i;

-- test pushdown of GROUP BY with filter
explain plan for select i,sum(j) from m.t1 where i > 100 group by i;

-- test arbitrary choice of replica
explain plan for select * from m.t2;

-- test pushdown of JOIN
explain plan for select * from m.t1, m.t2 where t1.i=t2.i;
