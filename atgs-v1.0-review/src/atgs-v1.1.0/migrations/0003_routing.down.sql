DROP TABLE IF EXISTS routing_events;
DROP SEQUENCE IF EXISTS routing_version_seq;
DROP INDEX IF EXISTS idx_instances_hostname_unique;
ALTER TABLE instances DROP COLUMN IF EXISTS host_port;
ALTER TABLE instances DROP COLUMN IF EXISTS hostname;
