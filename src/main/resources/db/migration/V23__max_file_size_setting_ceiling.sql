-- UNISAGE-94: the upload ceiling (spring.servlet.multipart.max-file-size) is now 100 MB, so the
-- admin setting can actually be raised up to it; say so where the admin edits it.
UPDATE system_configs
SET description = 'Dung lượng tối đa của một file tài liệu được phép tải lên (từ 1 đến 100 MB).'
WHERE config_key = 'ingest.max_file_size_mb';
