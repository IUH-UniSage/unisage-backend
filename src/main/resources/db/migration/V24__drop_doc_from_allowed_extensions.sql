-- UNISAGE-94: unisage-agent cannot parse legacy binary .doc (only OOXML .docx), so a .doc upload
-- was accepted and then failed at ingestion. Drop it from the admin-editable whitelist, keeping
-- whatever else an admin may have added.
UPDATE system_configs
SET value = (
    SELECT COALESCE(json_agg(ext ORDER BY ord), '[]'::json)::text
    FROM json_array_elements_text(value::json) WITH ORDINALITY AS t(ext, ord)
    WHERE ext <> '.doc'
)
WHERE config_key = 'ingest.allowed_file_extensions'
  AND value::jsonb ? '.doc';
