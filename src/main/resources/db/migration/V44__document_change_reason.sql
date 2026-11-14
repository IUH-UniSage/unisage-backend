-- Why a document was edited (required by the edit form). documents holds the reason for the
-- current state; when a file is replaced, the archived document_versions row keeps the reason
-- that version was created with.
ALTER TABLE documents ADD COLUMN change_reason VARCHAR(500);
ALTER TABLE document_versions ADD COLUMN change_reason VARCHAR(500);
