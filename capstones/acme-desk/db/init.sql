-- Runs once, when the database is first created. The database owner's part, which an
-- application never does: the role the app connects as, which is subject to row-level security
-- (not a superuser, not BYPASSRLS, not the owner of the chunk table).
CREATE EXTENSION IF NOT EXISTS vector;
CREATE ROLE desk_app LOGIN PASSWORD 'desk_app' NOSUPERUSER NOBYPASSRLS;
GRANT USAGE, CREATE ON SCHEMA public TO desk_app;
