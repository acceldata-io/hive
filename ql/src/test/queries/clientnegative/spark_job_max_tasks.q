--! qt:dataset:src1
set hive.spark.job.max.tasks=2;

add file ../../data/scripts/sleep.py;

EXPLAIN
SELECT TRANSFORM(key) USING 'ambari-python-wrap sleep.py' AS k
  FROM (SELECT key FROM src1 GROUP BY key) a ORDER BY k;

SELECT TRANSFORM(key) USING 'ambari-python-wrap sleep.py' AS k
  FROM (SELECT key FROM src1 GROUP BY key) a ORDER BY k;
