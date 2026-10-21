# ADR-0008: Sao lưu Postgres định kỳ bằng pg_basebackup PG17 (Full / Differential / Transaction log)

- **Date**: 2026-10-13
- **Status**: Accepted
- **Context story**: UNISAGE-102 — backup Database định kỳ, tham số trong SystemSetting, phân biệt
  Backup Full, Differential và Transaction Logs
- **Decision owners**: TranNgocHuyen19

## Context

Trước UNISAGE-102 không có cơ chế sao lưu nào cho `unisage-db` (`pgvector/pgvector:pg17`): mất
volume `postgres_data` là mất toàn bộ người dùng, hội thoại, cấu hình model, audit log. Yêu cầu:

- Lịch sao lưu (mỗi ngày / tuần / tháng / năm) do admin chỉnh trong trang Cài đặt
  (`system_configs`), không phải sửa biến môi trường rồi deploy lại.
- Có đủ ba loại bản sao lưu theo cách gọi quen thuộc của SQL Server: **Full**, **Differential**
  (chỉ phần thay đổi kể từ bản Full gần nhất) và **Transaction log** (khôi phục tới một thời điểm).

Postgres không có sẵn ba khái niệm này dưới cùng tên, nên cần quyết định công cụ nào đóng vai trò
nào, chạy ở đâu, lưu ở đâu và dọn dẹp ra sao.

## Decision

### 1. Sao lưu vật lý (physical) bằng `pg_basebackup` 17, không dùng `pg_dump`

Ánh xạ ba loại backup sang tính năng của PG17:

| Loại | Công cụ | Ghi chú |
|---|---|---|
| Full | `pg_basebackup -Ft -z -X stream --checkpoint=fast` | Tự đủ: `-X stream` gói kèm WAL cần để bản sao nhất quán |
| Differential | `pg_basebackup --incremental=<backup_manifest của Full>` | Incremental của PG17, **luôn** lấy mốc là Full gần nhất nên tương đương Differential |
| Transaction log | `archive_mode=on` + `archive_command` + đẩy WAL lên MinIO | Cho phép khôi phục tới thời điểm bất kỳ (PITR) |

Incremental backup của PG17 có thể xếp chuỗi (incremental của incremental). Hệ thống cố ý không
làm vậy: mọi Differential đều so với Full, nên khi khôi phục chỉ cần **Full + một Differential mới
nhất** (`pg_combinebackup full diff`), không phải ghép cả chuỗi, và mất một Differential không
làm hỏng các bản sau.

Incremental cần `summarize_wal=on` trên server. Bản tóm tắt WAL mặc định chỉ giữ 10 ngày
(`wal_summary_keep_time`), ngắn hơn chu kỳ Full theo tháng/năm, nên đặt `wal_summary_keep_time=400d`.
Bản tóm tắt rất nhỏ so với WAL.

### 2. Lịch chạy nằm trong `system_configs`, backend tự quyết định lúc nào tới hạn

V38 thêm các dòng nhóm MAINTENANCE `maintenance.backup.*` (bật/tắt, tần suất + giờ cho Full,
Differential, Qdrant, chu kỳ phút cho Transaction log, số bản giữ lại). Trang Cài đặt hiện có tự
hiển thị chúng, không cần màn hình mới. `SystemConfigServiceImpl.updateValue` kiểm tra giá trị qua
`BackupSettings.isValid` (Full không được OFF; giờ `HH:mm`; WAL 0–1440 phút; giữ 1–365 bản), lỗi
`SYSTEM_CONFIG_BACKUP_VALUE_INVALID` (2159).

`BackupJob` chạy mỗi phút (`app.backup.tick-cron`) và giao việc cho `BackupCoordinator` trên một
thread riêng, vì scheduler mặc định chỉ có 1 thread dùng chung cho mọi job khác và một bản Full có
thể chạy cả giờ. Tick đến khi tick trước chưa xong thì bỏ qua, nên không bao giờ có hai backup
chạy song song.

Quy tắc "tới hạn" (`BackupFrequency.latestSlot`): WEEKLY là Chủ nhật, MONTHLY là ngày 1, YEARLY là
1/1, theo `app.timezone`. Một loại tới hạn khi mốc gần nhất mới hơn mốc mà lần chạy trước đã trả
lời (`backup_runs.scheduled_for`). Lần chạy lỗi vẫn tính là đã trả lời mốc đó, để không thử lại
mỗi phút; mốc kế tiếp sẽ thử lại. Hệ quả có chủ đích: vừa bật sao lưu là có ngay một bản Full, thứ
mà mọi Differential cần. Differential mà chưa có Full thành công thì bỏ qua và không ghi lại, để
mốc đó vẫn còn hạn tới khi có Full.

### 3. Lịch sử chạy ở bảng `backup_runs`, file ở bucket MinIO riêng

`backup_runs` (V39) ghi mỗi lần chạy: loại, trạng thái RUNNING/SUCCEEDED/FAILED, mốc lịch, thời
gian, Full gốc của Differential (`base_run_id`), prefix trong storage, dung lượng, lỗi. Bảng
không kế thừa `BaseEntity` vì do scheduler ghi, không phải người dùng (giống `system_health_checks`).
Khi ứng dụng khởi động lại, các dòng còn RUNNING được chuyển thành FAILED.

File nằm trong bucket `unisage-backups` (`app.backup.bucket`), tách khỏi `unisage-documents`, để
quyền truy cập hay lifecycle rule của bucket này không ảnh hưởng bucket kia:

```
postgres/full/<yyyy-MM-dd_HH-mm-ss>/{base.tar.gz, pg_wal.tar.gz, backup_manifest}
postgres/differential/<yyyy-MM-dd_HH-mm-ss>/{...}
postgres/wal/<tên segment WAL>
```

Tên thư mục là giờ bắt đầu theo `app.timezone` (giờ Việt Nam), ví dụ `2026-10-21_09-45-58`: đọc
được ngay trong MinIO và trong tên file tải về, vẫn sắp xếp đúng theo thời gian, và không có `:`
nên dùng làm tên file được trên mọi hệ điều hành. Bản tạo trước thay đổi này giữ tên cũ
`yyyyMMddTHHmmssZ` (UTC); code không đọc lại thời gian từ tên nên hai kiểu cùng tồn tại được. Tên
segment WAL do Postgres đặt và `restore_command` cần đúng tên đó, nên không đổi.

### 4. Lưu giữ: giữ N bản Full, xoá kèm Differential và WAL cũ hơn

Sau mỗi Full thành công, `PostgresBackupService.applyRetention` giữ `retention.full_count` bản Full
mới nhất. Full cũ hơn bị xoá cùng các Differential của nó, và WAL được tải lên trước thời điểm bắt
đầu của Full cũ nhất còn giữ cũng bị xoá: không bản sao lưu nào còn lại có thể replay nó.

### 5. Hạ tầng Docker

- `unisage-db` chạy với `-c hba_file=/etc/postgresql/pg_hba.conf` (file
  `docker/postgres/pg_hba.conf`, cho phép kết nối `replication` có mật khẩu từ container khác).
  Dùng tham số dòng lệnh thay vì script trong `docker-entrypoint-initdb.d` vì script init chỉ chạy
  với volume trống, còn tham số áp dụng cả cho volume đang có dữ liệu.
- `archive_command` chép ra `<tên>.tmp` rồi `mv`, nên backend không bao giờ đẩy một segment đang
  ghi dở; `archive_timeout=300` đóng segment ít nhất 5 phút một lần.
- Volume `pg_wal_archive` dùng chung giữa `unisage-db` và `backend-java` tại `/wal-archive`.
  Backend gọi `pg_switch_wal()` trước khi đẩy WAL để thay đổi mới nhất cũng được lưu.
- Image backend cài `postgresql17-client` (`pg_basebackup` không được cũ hơn server).
- Kết nối dùng `DB_USER` (superuser `postgres`). Nếu sau này đổi sang user ít quyền, user đó cần
  thuộc tính `REPLICATION` và quyền `EXECUTE` trên `pg_switch_wal()`.

## Restore runbook

1. Tải `postgres/full/<F>/`, `postgres/differential/<D mới nhất có base là F>/` và `postgres/wal/`
   từ bucket `unisage-backups`.
2. Giải nén mỗi bản: `base.tar.gz` vào thư mục gốc, `pg_wal.tar.gz` vào `pg_wal/` của nó, chép
   `backup_manifest` vào cùng chỗ.
3. `pg_combinebackup full diff -o combined` rồi `pg_verifybackup -n combined` (có trong image
   server `pgvector/pgvector:pg17`, không có trong `postgresql17-client`). Nếu chỉ có Full, bỏ
   qua bước combine.
4. Trong `combined/postgresql.auto.conf`: `restore_command = 'cp /đường/dẫn/wal/%f %p'`, tuỳ chọn
   `recovery_target_time = '...'`; tạo file `recovery.signal`.
5. Khởi động Postgres trên thư mục đó; khi `pg_is_in_recovery()` trả `false` là xong.

## Consequences

**Tích cực**:

- Đủ ba loại backup, khôi phục được tới một thời điểm bất kỳ trong khoảng WAL còn giữ.
- Admin đổi lịch ngay trên trang Cài đặt, có hiệu lực từ phút kế tiếp.
- Differential nhỏ (bản thử: Full 4,5 MB, Differential 0,3 MB) và khôi phục chỉ cần hai bản.

**Tiêu cực / rủi ro**:

- Sao lưu cả cluster: không khôi phục riêng một bảng hay một database. Muốn vậy phải khôi phục ra
  chỗ khác rồi `pg_dump` phần cần lấy.
- Bản sao lưu chỉ khôi phục được trên Postgres 17 cùng kiến trúc. Nâng major version cần một bản
  Full mới ngay sau khi nâng.
- `archive_mode` đòi restart Postgres một lần khi deploy thay đổi này.
- Nếu backend tắt lâu, WAL dồn trong volume `pg_wal_archive`; nếu `archive_command` lỗi (đầy
  đĩa), Postgres giữ WAL trong `pg_wal` và đĩa DB có thể đầy theo.
- Backup cùng MinIO với dữ liệu gốc: chưa có bản sao ngoài máy (off-site). Nên bật MinIO
  replication hoặc lifecycle sang S3 khác khi đưa vào vận hành thật.
- Chưa có giao diện xem `backup_runs`; hiện xem qua log và DB.

## Alternatives considered

1. **`pg_dump` theo lịch**: đơn giản, khôi phục từng bảng được, nhưng chỉ có Full. Không có
   Differential hay Transaction log, không PITR. Không đáp ứng yêu cầu.
2. **pgBackRest / WAL-G / Barman**: đầy đủ và đã được kiểm chứng, nhưng thêm một tiến trình/sidecar
   có lịch và cấu hình riêng nằm ngoài `system_configs`, trái với yêu cầu chỉnh lịch trong trang
   Cài đặt. Là lựa chọn hợp lý nếu sau này cần nén/mã hoá/song song hoá.
3. **Chuỗi incremental (mỗi bản so với bản trước)**: nhỏ hơn mỗi ngày, nhưng khôi phục phải ghép cả
   chuỗi và một mắt xích hỏng là mất các bản sau. Chọn Differential để khôi phục đơn giản.
4. **`archive_command` đẩy thẳng lên MinIO**: image Postgres không có client S3; tự cài vào image DB
   làm phức tạp image và đặt credential MinIO trong container DB. Thư mục dùng chung + backend đẩy
   lên giữ credential ở một chỗ.

## Test lock

- Unit test: `BackupFrequencyTest`, `BackupSettingsTest`, `SystemConfigServiceImplTest` (giá trị
  backup sai bị từ chối), `BackupCoordinatorTest` (mốc tới hạn, Differential cần Full, chu kỳ WAL,
  lỗi được ghi lại), `PostgresBackupServiceTest` (retention), `BackupJobTest` (không chạy chồng).
- Kiểm tra thủ công ngày 2026-10-13 với stack Docker tạm (Postgres cấu hình y như compose, MinIO
  riêng, image backend build từ nhánh này, Qdrant dev):
  - Bật `maintenance.backup.enabled`, tick đầu tiên tạo Full (mốc Chủ nhật 02:00), Differential
    (mốc 03:00 hôm nay, `base_run_id` trỏ tới Full), WAL (9 segment) và snapshot Qdrant, đều
    SUCCEEDED.
  - Ghi một dòng đánh dấu sau Differential, chờ lần đẩy WAL kế tiếp, tải mọi thứ từ MinIO về, chạy
    runbook ở trên: `pg_verifybackup` báo "backup successfully verified", WAL replay xong
    ("archive recovery complete") và dòng đánh dấu có trong DB đã khôi phục.
  - Đặt giữ 1 Full và làm một Full mới tới hạn: Full cũ và Differential của nó bị xoá khỏi MinIO
    và đánh dấu `purged_at`, WAL cũ hơn Full mới bị xoá.
  - Lần chạy đầu phát hiện `BackupJob` có hai constructor mà thiếu `@Autowired` (Spring không khởi
    tạo được), đã sửa. Đây là lỗi unit test không bắt được.

## References

- `service/backup/` (`BackupCoordinator`, `PostgresBackupService`, `WalShippingService`,
  `BackupSettings`, `BackupStorage`, `PgToolRunner`), `scheduler/BackupJob.java`
- `db/migration/V38__backup_schedule_settings.sql`, `V39__backup_runs.sql`
- `docker-compose.yml`, `docker-compose.prod.yml`, `docker/postgres/pg_hba.conf`, `Dockerfile`
- ADR-0009 (snapshot Qdrant dùng chung lịch và hạ tầng này)
- PostgreSQL 17: `pg_basebackup --incremental`, `pg_combinebackup`, `summarize_wal`, continuous
  archiving and PITR
