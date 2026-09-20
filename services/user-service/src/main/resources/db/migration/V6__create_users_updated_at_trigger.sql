-- The database is the single source of truth for time: created_at defaults to CURRENT_TIMESTAMP (V5)
-- and this trigger stamps updated_at on every UPDATE, whoever issues it (Hibernate, a native query,
-- psql), so no writer can forget it or bring its own clock. version is deliberately not touched here -
-- Hibernate's @Version (or an explicit version + 1 in native updates) owns it.
CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
