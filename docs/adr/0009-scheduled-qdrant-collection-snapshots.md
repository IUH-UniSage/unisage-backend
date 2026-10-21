# ADR-0009: Tự động snapshot collection Qdrant theo lịch, lưu vào MinIO

- **Date**: 2026-10-13
- **Status**: Accepted
- **Context story**: UNISAGE-102 — nghiên cứu cách tự động tạo snapshot cho collection Qdrant
- **Decision owners**: TranNgocHuyen19

## Context

Vector của tài liệu đã ingest nằm trong Qdrant (`unisage_chunks`, do `unisage-agent` ghi). Postgres
có backup theo ADR-0008, nhưng nếu mất Qdrant thì phải ingest lại toàn bộ tài liệu: tốn thời gian
và tốn tiền gọi embedding. Cần một cơ chế sao lưu Qdrant chạy cùng lịch trong `system_configs`.

## Nghiên cứu: Qdrant cung cấp gì

Kiểm tra trên Qdrant 1.15.4 (bản devcontainer của `unisage-agent`).

**Snapshot theo collection (REST)**:

| Thao tác | Endpoint |
|---|---|
| Liệt kê collection | `GET /collections` |
| Tạo snapshot | `POST /collections/{c}/snapshots?wait=true` trả về `{name, creation_time, size, checksum}` (checksum SHA-256) |
| Tải snapshot | `GET /collections/{c}/snapshots/{name}` |
| Xoá snapshot | `DELETE /collections/{c}/snapshots/{name}` |
| Khôi phục từ URL/file | `PUT /collections/{c}/snapshots/recover` với `{"location": "...", "priority": "snapshot"}` |
| Khôi phục bằng upload | `POST /collections/{c}/snapshots/upload?priority=snapshot` (multipart) |

Snapshot được ghi vào thư mục `snapshots` trên chính node Qdrant, nên nếu không xoá thì nó chiếm
đĩa Qdrant và mất theo khi mất máy đó.

**Những điểm cần lưu ý**:

- **Cluster**: mỗi node chỉ snapshot các shard nó giữ. Ở chế độ phân tán phải gọi API trên từng
  node. UniSage chạy một node nên một lần gọi là đủ.
- **Phiên bản**: snapshot chỉ khôi phục được trên cùng phiên bản hoặc phiên bản minor kế tiếp. Nâng
  Qdrant nhiều bậc thì phải nâng lần lượt, hoặc ingest lại.
- **Snapshot toàn bộ storage** (`POST /snapshots`): gồm mọi collection và alias, nhưng chỉ hỗ trợ
  single-node và chỉ khôi phục bằng CLI `--storage-snapshot` khi khởi động. Không khôi phục online
  được.
- **Lưu thẳng lên S3** (`storage.snapshots_config.snapshots_storage: s3`, từ v1.10): Qdrant tự ghi
  snapshot lên S3/MinIO. Tiện, nhưng cấu hình nằm trong file config của Qdrant (repo
  `unisage-agent`), credential nằm ở Qdrant, và vẫn cần thứ gì đó gọi API theo lịch.
- **Lịch chạy**: Qdrant không có scheduler nội bộ. Bắt buộc phải có tiến trình bên ngoài gọi API.
- **Thời gian**: snapshot `unisage_chunks` (40.831 điểm) nặng khoảng 812 MiB, tạo mất khoảng 26 giây.
  Trong lúc tạo, collection vẫn đọc/ghi bình thường.

## Decision

### 1. Backend gọi REST API snapshot theo collection, dùng chung lịch và hạ tầng của ADR-0008

`BackupCoordinator` chạy loại `QDRANT_SNAPSHOT` theo `maintenance.backup.qdrant.frequency` / `.time`
(OFF / DAILY / WEEKLY / MONTHLY / YEARLY). Mỗi mốc lịch: liệt kê collection, snapshot từng
collection, mỗi collection một dòng `backup_runs` (`target` = tên collection). Nếu không liệt kê
được collection, ghi một dòng FAILED để mốc đó tính là đã trả lời và lỗi hiện trong lịch sử.

### 2. Stream thẳng vào MinIO, kiểm tra checksum, rồi xoá trên Qdrant

`QdrantSnapshotService.snapshot`: tạo snapshot (`wait=true`), tải về và stream thẳng vào
`unisage-backups/qdrant/<collection>/<collection>_<yyyy-MM-dd_HH-mm-ss>.snapshot` (giờ theo
`app.timezone`, như tên thư mục Postgres ở ADR-0008) qua `DigestInputStream`, không ghi ra đĩa
backend. Sau đó so SHA-256 với `checksum` Qdrant trả về; lệch thì xoá object và đánh dấu FAILED.
Cuối cùng (kể cả khi lỗi) xoá snapshot trên node Qdrant để đĩa Qdrant không đầy dần. Chỉ xoá
snapshot do job này tạo; snapshot tạo tay vẫn được giữ.

### 3. Giữ N snapshot mới nhất mỗi collection

`maintenance.backup.qdrant.retention_count` (mặc định 7). Snapshot cũ hơn bị xoá khỏi MinIO và đánh
dấu `purged_at`.

### 4. Kết nối

`QDRANT_URL` (`app.backup.qdrant.url`) và `QDRANT_API_KEY` (gửi header `api-key` nếu có). Compose dev
mặc định `http://host.docker.internal:6333`. Prod lấy từ `.ENV` vì Qdrant chạy ở stack khác.
Timeout đọc `app.backup.qdrant.timeout-minutes` (mặc định 30) vì tạo và tải snapshot lớn mất vài phút.

## Restore runbook

1. Tải `qdrant/<collection>/<snapshot>` từ bucket `unisage-backups`.
2. Dùng Qdrant cùng phiên bản (hoặc minor kế tiếp) với lúc tạo snapshot. Một trong hai cách:
   - Đặt file vào `/qdrant/snapshots/<collection>/` của node rồi
     `PUT /collections/<collection>/snapshots/recover` với
     `{"location": "file:///qdrant/snapshots/<collection>/<snapshot>", "priority": "snapshot"}`.
   - Hoặc upload: `POST /collections/<collection>/snapshots/upload?priority=snapshot` (multipart).
3. So `POST /collections/<collection>/points/count {"exact": true}` với số điểm mong đợi.

`priority=snapshot` làm dữ liệu trong snapshot thắng dữ liệu đang có, đúng ý nghĩa khôi phục.

## Consequences

**Tích cực**:

- Mất Qdrant không còn nghĩa là ingest lại toàn bộ.
- Một chỗ cấu hình lịch (trang Cài đặt) cho cả Postgres và Qdrant; một bucket, một lịch sử.
- Không cần sửa repo `unisage-agent` hay cấu hình Qdrant.

**Tiêu cực / rủi ro**:

- Snapshot của Qdrant và backup của Postgres không cùng một thời điểm. Sau khi khôi phục cả hai, tài
  liệu ingest giữa hai mốc có thể có ở một bên mà không có ở bên kia; cần ingest lại các tài liệu
  đó.
- Mỗi lần là một bản đầy đủ (Qdrant không có snapshot incremental), tốn dung lượng MinIO: khoảng
  0,8 GB × số bản giữ.
- Trong lúc snapshot, Qdrant dùng thêm đĩa bằng kích thước snapshot.
- Nếu chuyển Qdrant sang cluster, phải sửa client để gọi từng node.

## Alternatives considered

1. **`snapshots_config` S3 của Qdrant**: Qdrant tự ghi lên MinIO, bỏ được bước stream qua backend.
   Không chọn vì cấu hình và credential nằm ở repo/stack khác, và vẫn cần backend gọi API theo lịch.
   Có thể chuyển sang sau mà không đổi lịch hay `backup_runs`.
2. **Snapshot toàn bộ storage**: một file cho mọi collection, nhưng chỉ khôi phục bằng cách khởi
   động lại Qdrant với cờ CLI. Snapshot theo collection khôi phục online, từng phần.
3. **Chép thư mục `/qdrant/storage` hoặc snapshot volume**: không nhất quán nếu Qdrant đang ghi,
   phải dừng Qdrant.
4. **Không sao lưu, ingest lại khi cần**: chấp nhận được với ít tài liệu, nhưng tốn chi phí
   embedding và thời gian, và file gốc trong MinIO cũng có thể đã bị xoá.

## Test lock

- Unit test: `QdrantSnapshotServiceTest` (checksum khớp thì lưu và xoá trên Qdrant; lệch thì xoá
  object, báo lỗi và vẫn xoá trên Qdrant), `BackupCoordinatorTest` (mỗi collection một lần chạy,
  retention chỉ khi thành công, không liệt kê được collection thì ghi một dòng FAILED).
- Kiểm tra thủ công ngày 2026-10-13 (cùng stack tạm của ADR-0008, Qdrant dev 1.15.4):
  - Job tạo snapshot `unisage_chunks` 851.696.640 byte, stream vào MinIO trong khoảng 2 phút,
    checksum khớp, snapshot trên Qdrant bị xoá; snapshot tạo tay từ 2026-09-10 vẫn còn.
  - Tải snapshot từ MinIO, khôi phục vào một Qdrant v1.15.4 trống bằng `recover` từ `file://`:
    mất khoảng 92 giây, số điểm 40.831 khớp collection gốc.

## References

- `integration/QdrantSnapshotClient.java`, `service/backup/QdrantSnapshotService.java`,
  `service/backup/BackupCoordinator.java`
- ADR-0008 (lịch, `backup_runs`, bucket `unisage-backups`)
- Qdrant docs: Snapshots (collection, full storage, S3 `snapshots_config`, recover priority)
