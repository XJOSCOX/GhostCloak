-- Review once as PostgreSQL administrator. Set passwords with interactive \password,
-- never literals in this script, shell history or command arguments.
CREATE ROLE ghostcloak_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE ghostcloak_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE ghostcloak OWNER ghostcloak_migrator;
\connect ghostcloak
REVOKE ALL ON DATABASE ghostcloak FROM PUBLIC;
GRANT CONNECT ON DATABASE ghostcloak TO ghostcloak_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ghostcloak_app;
-- Run grants below AFTER the migrator has applied V001 (see DEPLOYMENT.md).
-- GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ghostcloak_app;
-- REVOKE ALL ON schema_history FROM ghostcloak_app;
-- GRANT SELECT ON schema_history TO ghostcloak_app;
