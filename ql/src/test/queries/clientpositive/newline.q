--! qt:dataset:src
set hive.mapred.mode=nonstrict;
add file ../../data/scripts/newline.py;
set hive.transform.escape.input=true;

-- SORT_QUERY_RESULTS

create table tmp_tmp_n0(key string, value string) stored as rcfile;
insert overwrite table tmp_tmp_n0
SELECT TRANSFORM(key, value) USING
'ambari-python-wrap newline.py' AS key, value FROM src limit 6;

select * from tmp_tmp_n0;

drop table tmp_tmp_n0;

add file ../../data/scripts/escapednewline.py;
add file ../../data/scripts/escapedtab.py;
add file ../../data/scripts/doubleescapedtab.py;
add file ../../data/scripts/escapedcarriagereturn.py;

create table tmp_tmp_n0(key string, value string) stored as rcfile;
insert overwrite table tmp_tmp_n0
SELECT TRANSFORM(key, value) USING
'ambari-python-wrap escapednewline.py' AS key, value FROM src limit 5;

select * from tmp_tmp_n0;

SELECT TRANSFORM(key, value) USING
'cat' AS (key, value) FROM tmp_tmp_n0;

insert overwrite table tmp_tmp_n0
SELECT TRANSFORM(key, value) USING
'ambari-python-wrap escapedcarriagereturn.py' AS key, value FROM src limit 5;

select * from tmp_tmp_n0;

SELECT TRANSFORM(key, value) USING
'cat' AS (key, value) FROM tmp_tmp_n0;

insert overwrite table tmp_tmp_n0
SELECT TRANSFORM(key, value) USING
'ambari-python-wrap escapedtab.py' AS key, value FROM src limit 5;

select * from tmp_tmp_n0;

SELECT TRANSFORM(key, value) USING
'cat' AS (key, value) FROM tmp_tmp_n0;

insert overwrite table tmp_tmp_n0
SELECT TRANSFORM(key, value) USING
'ambari-python-wrap doubleescapedtab.py' AS key, value FROM src limit 5;

select * from tmp_tmp_n0;

SELECT TRANSFORM(key, value) USING
'cat' AS (key, value) FROM tmp_tmp_n0;

SELECT key FROM (SELECT TRANSFORM ('a\tb', 'c') USING 'cat' AS (key, value) FROM src limit 1)a ORDER BY key ASC;

SELECT value FROM (SELECT TRANSFORM ('a\tb', 'c') USING 'cat' AS (key, value) FROM src limit 1)a ORDER BY value ASC;
