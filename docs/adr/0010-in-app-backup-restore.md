# ADR-0010: Khôi phục Postgres và Qdrant từ giao diện quản trị

- **Date**: 2026-10-14
- **Status**: Accepted
- **Context story**: UNISAGE-102 — trang "Sao lưu & khôi phục" để khôi phục dữ liệu mà không phải
  tự lên MinIO chạy lệnh
- **Decision owners**: TranNgocHuyen19

## Context

ADR-0008 và ADR-0009 tạo bản sao lưu theo lịch, nhưng khôi phục chỉ có runbook: tải file từ MinIO,
dừng Postgres, giải nén, `pg_combinebackup`, viết `recovery.signal`, chạy lại. Người quản trị không
làm được việc đó trong lúc sự cố. Cần một trang trong ứng dụng để:

- xem các bản sao lưu còn dùng được và chạy sao lưu ngay;
- khôi phục Postgres về một bản sao lưu, hoặc về một thời điểm bất kỳ (PITR) nhờ WAL đã lưu;
- khôi phục một collection Qdrant từ snapshot;
- theo dõi tiến độ và xem lịch sử.

Khó khăn chính: khôi phục bản vật lý nghĩa là thay thư mục `PGDATA` khi Postgres **đã dừng**.
Backend chạy trong container khác, không có quyền Docker và không được có quyền Docker (mount
`docker.sock` tương đương root trên máy chủ). Trong lúc Postgres dừng, chính backend cũng mất DB.

## Decision

### 1. Container `unisage-db` tự khôi phục; backend chỉ chuẩn bị và theo dõi

Entrypoint của `unisage-db` đổi thành `docker/postgres/unisage-entrypoint.sh`. Script chạy
`docker-entrypoint.sh` của image như **tiến trình con** chứ không `exec`, nên container vẫn sống
khi Postgres dừng. Backend và DB chia sẻ thư mục `/restore` (dev: bind `./.backup-restore`, prod:
volume `pg_restore`). Script ghi `/restore/.unisage-entrypoint` khi khởi động; backend chỉ bật nút
khôi phục khi thấy file này (`RestoreJobs.isAvailable`).

**Giao thức file** trong `/restore/jobs/<restoreRunId>/`:

| File | Ai ghi | Nội dung |
|---|---|---|
| `full/`, `diff/` | backend | bản Full (và Differential nếu có) tải từ MinIO |
| `wal/` | backend | các đoạn WAL từ lúc bản Full bắt đầu, kèm file `.history` |
| `target` | backend | `immediate` hoặc thời điểm UTC `yyyy-MM-dd HH:mm:ss+00` |
| `carry-over.json` | backend | toàn bộ `backup_runs` và `restore_runs` trước khi khôi phục |
| `READY` | backend | báo đã chuẩn bị xong |
| `status` | cả hai | dòng 1 là phase, dòng 2 là thông báo tiếng Việt |
| `FINISHED` | backend | đã ghi lại lịch sử, job kết thúc |

**Phase**: `PREPARING` (backend tải file) → `APPLYING` (script giải nén, ghép, thay dữ liệu) →
`RECOVERING` (Postgres phát lại WAL) → `SUCCEEDED` / `FAILED`.

**Script khi thấy `READY`**: dừng Postgres (SIGINT, fast shutdown), giải nén bản Full, chạy
`pg_combinebackup` nếu có Differential, xoá các dòng `restore_command`/`recovery_target*` cũ rồi
ghi mới vào `postgresql.auto.conf`, tạo `recovery.signal`, chuyển dữ liệu hiện tại sang
`/var/lib/postgresql/restore-previous` và đưa dữ liệu khôi phục vào `PGDATA`, rồi chạy lại
Postgres. Khi `pg_is_in_recovery()` trả `f` thì job `SUCCEEDED` và dữ liệu cũ bị xoá. Nếu Postgres
thoát trong lúc recovery (ví dụ thời điểm đích nằm sau WAL đã có), script trả lại dữ liệu cũ, ghi
`FAILED` và chạy lại Postgres như trước. `target` được kiểm tra bằng regex trước khi ghi vào cấu
hình.

### 2. Trước khi khôi phục luôn có một bản an toàn

`PostgresRestoreService` chạy trên luồng của `BackupTaskRunner`:

1. đẩy WAL hiện có lên MinIO, rồi chụp một bản **Full an toàn**; một trong hai lỗi thì dừng, không
   động vào dữ liệu;
2. tải bản cần khôi phục và WAL vào thư mục job, ghi `target` và `carry-over.json`, tạo `READY`.

Nhờ bản an toàn, khôi phục nhầm vẫn quay lại được thời điểm ngay trước đó. Qdrant tương tự:
`QdrantRestoreService` snapshot collection hiện tại trước (nếu collection còn), lỗi thì không khôi
phục.

### 3. Lịch sử sao lưu không bị "quay ngược" theo dữ liệu

Khôi phục Postgres về hôm qua thì bảng `backup_runs` cũng về hôm qua, mất dòng của bản an toàn và
các bản mới hơn (file vẫn còn trên MinIO). Vì vậy backend lưu `carry-over.json` trước khi giao job.
Khi Postgres chạy lại, `finishCompletedJobs` (gọi mỗi phút từ `BackupJob` và mỗi lần trang hỏi
trạng thái):

1. chạy `flyway.migrate()` (bản sao lưu có thể cũ hơn schema hiện tại);
2. upsert lại toàn bộ `backup_runs` (Full trước, vì Differential tham chiếu Full) và `restore_runs`
   bằng `INSERT ... ON CONFLICT (id) DO UPDATE`;
3. cập nhật `restore_runs` thành `SUCCEEDED`/`FAILED`, tạo `FINISHED`.

Job đã `READY` mà sau `app.backup.restore-handoff-minutes` (mặc định 5) container vẫn chưa nhận thì
đánh dấu `FAILED`. Khi backend khởi động lại, các `restore_runs` còn `RUNNING` mà không có job đang
chạy bị đánh dấu `FAILED`.

### 4. Một luồng cho mọi tác vụ sao lưu/khôi phục

`BackupTaskRunner` là executor một luồng. Sao lưu theo lịch, sao lưu tay và khôi phục đều đi qua
nó; khi đang bận, API trả `409 BACKUP_TASK_BUSY`. Khi có job khôi phục đang chạy, lịch sao lưu bỏ
qua lượt đó.

### 5. API `/admin/backups`

| Method | Path | Ý nghĩa |
|---|---|---|
| GET | `/overview` | trạng thái tổng: bản mới nhất mỗi loại, điểm khôi phục, khoảng PITR, snapshot Qdrant, job đang chạy |
| GET / POST | `/runs` | lịch sử sao lưu / sao lưu ngay (`FULL`, `DIFFERENTIAL`, `WAL`, `QDRANT_SNAPSHOT`), trả 202 |
| GET | `/restores` | lịch sử khôi phục |
| POST | `/restores/postgres` | `{backupRunId}` hoặc `{targetTime}` (UTC), đúng một trong hai |
| GET | `/restores/postgres/status` | phase và thông báo của job gần nhất |
| POST | `/restores/qdrant` | `{backupRunId}` của một snapshot |

Quyền `BACKUP_ALL` / `BACKUP_READ` (V37), cấp `BACKUP_ALL` cho `SUPER_ADMIN`.

## Consequences

**Tích cực**:

- Khôi phục bằng vài cú bấm, có xác nhận và tiến độ; không cần SSH, MinIO console hay lệnh `pg_*`.
- Hỏng giữa chừng thì tự quay lại dữ liệu cũ; khôi phục nhầm thì còn bản an toàn.
- Không cấp quyền Docker cho backend.

**Tiêu cực / rủi ro**:

- Trong lúc khôi phục Postgres, toàn hệ thống mất DB (thường dưới một phút với dữ liệu dev, lâu
  hơn tuỳ dung lượng). Kiểm tra quyền (`DynamicAuthorizationManager`) cũng đọc DB, nên các request,
  kể cả hỏi trạng thái, có thể treo hoặc lỗi; giao diện phải chịu được điều này và hỏi lại.
- Dữ liệu ghi sau thời điểm khôi phục bị mất (đó là mục đích), trừ lịch sử sao lưu được ghi lại.
- Khôi phục về bản cũ hơn schema hiện tại phụ thuộc vào việc migration Flyway chạy lại được.
- Muốn dùng phải tạo lại container `unisage-db` với compose mới. Backend chạy ngoài Docker (dev trên
  Windows) phải đặt `BACKUP_RESTORE_DIR=./.backup-restore`; không đặt thì nút khôi phục bị tắt.
- Postgres và Qdrant khôi phục riêng nên có thể lệch nhau (xem ADR-0009).

## Alternatives considered

1. **Backend gọi Docker API** (mount `docker.sock`) để dừng/chạy container: đơn giản nhưng trao
   quyền root máy chủ cho một ứng dụng web.
2. **`pg_dump`/`pg_restore` logic**: khôi phục online được nhưng không dùng được bản vật lý và WAL
   của ADR-0008, không có PITR.
3. **Sidecar riêng làm việc khôi phục**: vẫn phải dừng Postgres trong container khác, nên vẫn cần
   quyền Docker hoặc chung PID namespace.
4. **Chỉ có runbook**: là tình trạng trước ADR này; người dùng không làm được khi có sự cố.

## Test lock

- Unit test: `RestoreJobsTest` (đọc/ghi giao thức file), `PostgresRestoreServiceTest` (chọn Full cho
  Differential, chọn bản cho PITR, kiểm tra khoảng thời gian, bận, ghi lại lịch sử Full trước, job
  quá hạn bàn giao), `QdrantRestoreServiceTest` (snapshot an toàn trước, lỗi thì không khôi phục),
  `BackupTaskRunnerTest`.
- Kiểm tra thủ công ngày 2026-10-14 trên stack tạm `unisage-e2e`:
  - PITR về thời điểm T: các dòng đánh dấu A, B, C còn, D (ghi sau T) mất, timeline 2; lịch sử
    sao lưu sau T (bản an toàn, WAL, Qdrant) được ghi lại; khoảng 17 giây.
  - Khôi phục bản Differential (`immediate`): chỉ còn A, B; cả hai `restore_runs` còn trong lịch sử.
  - Qdrant: collection 5 điểm bị xoá còn 2, khôi phục về 5; có snapshot an toàn.
  - Thời điểm năm 2099: Postgres báo "recovery ended before configured recovery target was
    reached", script trả lại dữ liệu cũ, dòng E còn, timeline không đổi, job `FAILED`.
  - Hikari tự kết nối lại sau khi Postgres chạy lại.

## References

- `docker/postgres/unisage-entrypoint.sh`, `docker-compose.yml`, `docker-compose.prod.yml`
- `service/backup/RestoreJobs.java`, `PostgresRestoreService.java`, `QdrantRestoreService.java`,
  `BackupTaskRunner.java`, `BackupAdminService.java`, `controller/BackupController.java`
- `db/migration/V37__backup_restores.sql`
- ADR-0008, ADR-0009
- PostgreSQL 17: Continuous Archiving and PITR, `pg_combinebackup`
