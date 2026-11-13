-- Accent-insensitive list search (utils/SearchPatterns): "nguyen" must find "Nguyễn".
-- unaccent is a trusted extension (PG13+), so the app's own DB role can create it.
CREATE EXTENSION IF NOT EXISTS unaccent;
