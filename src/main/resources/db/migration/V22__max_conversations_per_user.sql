-- UNISAGE-94: the guest-session cleanup batch size is an internal tuning knob, not something an
-- admin needs to edit, so it goes back to a constant in GuestSessionCleanupJob. In its place,
-- admins set how many conversations each user (or guest session) keeps: ConversationRetentionJob
-- soft-deletes the older ones daily. Creating a conversation is never blocked.
DELETE FROM system_configs
WHERE config_key = 'maintenance.guest_session.cleanup_batch_size';

INSERT INTO system_configs
    (id, created_at, is_active, config_key, value, value_type, category, label, description, is_editable)
VALUES
    (gen_random_uuid(), now(), true,
     'maintenance.conversation.max_per_user', '10', 'NUMBER', 'MAINTENANCE',
     'Số cuộc hội thoại tối đa mỗi người dùng',
     'Tác vụ dọn dẹp chạy hằng ngày chỉ giữ lại số cuộc hội thoại gần nhất này cho mỗi người dùng hoặc khách, các cuộc cũ hơn sẽ bị xóa. Đặt 0 để không giới hạn.',
     true)
ON CONFLICT (config_key) DO NOTHING;
