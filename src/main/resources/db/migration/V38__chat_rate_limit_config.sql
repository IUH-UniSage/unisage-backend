-- Per-minute message rate limit (MessageRateLimiter): admin-editable like the other CHAT
-- settings. The counters themselves live in Redis (sliding 60 s log per user / guest session);
-- no table is added. 0 turns the limit off.
INSERT INTO public.system_configs
    (id, created_at, is_active, config_key, value, value_type, category, label, description, is_editable)
SELECT gen_random_uuid(), now(), true,
       'chat.rate_limit.messages_per_minute', '3', 'NUMBER', 'CHAT',
       'Giới hạn tin nhắn mỗi phút',
       'Số tin nhắn tối đa một người dùng (hoặc phiên khách) được gửi trong 60 giây gần nhất. 0 = tắt.',
       true
WHERE NOT EXISTS (
    SELECT 1 FROM public.system_configs WHERE config_key = 'chat.rate_limit.messages_per_minute'
);
