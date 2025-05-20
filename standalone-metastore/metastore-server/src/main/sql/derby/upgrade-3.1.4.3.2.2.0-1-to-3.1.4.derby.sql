-- Upgrade MetaStore schema from 3.1.4.3.2.2.0-1 to 3.1.4

-- This needs to be the last thing done.  Insert any changes above this line.
UPDATE "APP".VERSION SET SCHEMA_VERSION='3.1.4', VERSION_COMMENT='Hive release version 3.1.4' where VER_ID=1;