SELECT 'Upgrading MetaStore schema from 3.1.4.3.2.2.0-1 to 3.1.4' AS MESSAGE;

-- These lines need to be last.  Insert any changes above.
UPDATE VERSION SET SCHEMA_VERSION='3.1.4', VERSION_COMMENT='Hive release version 3.1.4' where VER_ID=1;
SELECT 'Finished upgrading MetaStore schema from 3.1.4.3.2.2.0-1 to 3.1.4' AS MESSAGE;
