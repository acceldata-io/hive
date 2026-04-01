-- HIVE-29516: Test that semijoin optimization handles missing column statistics gracefully
-- This test verifies that queries don't fail with NPE when basic table statistics exist
-- but column-level statistics are unavailable during semijoin removal optimization.

set hive.explain.user=false;
set hive.tez.dynamic.semijoin.reduction=true;
set hive.tez.dynamic.semijoin.reduction.threshold=0.1;
set hive.auto.convert.join=true;
set hive.auto.convert.join.noconditionaltask=true;
set hive.auto.convert.join.noconditionaltask.size=10000000000;

-- Disable automatic stats gathering so column stats won't be collected during insert
set hive.stats.autogather=false;
set hive.stats.column.autogather=false;

-- Create tables
drop table if exists t1_semijoin_nocolstats;
drop table if exists t2_semijoin_nocolstats;

create table t1_semijoin_nocolstats (id int, val string);
create table t2_semijoin_nocolstats (id int, val string);

-- Insert some data (column stats won't be collected due to autogather=false)
insert into t1_semijoin_nocolstats values (1, 'a'), (2, 'b'), (3, 'c'), (4, 'd'), (5, 'e');
insert into t2_semijoin_nocolstats values (1, 'x'), (2, 'y'), (6, 'z');

-- Set only basic table statistics (numRows, rawDataSize) WITHOUT column statistics
-- This simulates the scenario where column stats are unavailable (e.g., large TPC-DS datasets)
alter table t1_semijoin_nocolstats update statistics set('numRows'='100000000', 'rawDataSize'='2000000000');
alter table t2_semijoin_nocolstats update statistics set('numRows'='1000', 'rawDataSize'='20000');

-- This join query should trigger semijoin optimization evaluation
-- Previously this would cause NPE in StatsUtils.updateStats when column stats were null
-- With the fix, it should execute successfully by skipping semijoin optimization when column stats are unavailable
explain
select t1.id, t1.val, t2.val
from t1_semijoin_nocolstats t1 join t2_semijoin_nocolstats t2 on t1.id = t2.id;

select t1.id, t1.val, t2.val
from t1_semijoin_nocolstats t1 join t2_semijoin_nocolstats t2 on t1.id = t2.id
order by t1.id;

-- Cleanup
drop table t1_semijoin_nocolstats;
drop table t2_semijoin_nocolstats;
