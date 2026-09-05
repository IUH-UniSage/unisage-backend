# ADR-0003: Adopt Flyway for Schema Migrations

- **Date**: 2026-09-05
- **Status**: Accepted
- **Context story**: theo sau ADR-0002 — phát hiện dự án không có công cụ migration nào, phải
  chấp nhận "reset dev" mỗi lần đổi schema
- **Decision owners**: Backend team (unisage-backend)

## Context

Trước ADR này, schema hoàn toàn dựa vào `spring.jpa.hibernate.ddl-auto=update` — không có
Flyway/Liquibase, không có thư mục migration nào trong `src/main/resources/`. Hibernate tự sinh và
tự sửa bảng theo entity mỗi lần app khởi động. Cách này có 3 vấn đề đã lộ rõ khi làm ADR-0002:

1. **Không có lịch sử schema**: không ai biết chính xác schema đã thay đổi qua những bước nào,
   không rollback được.
2. **Không migrate được dữ liệu an toàn khi đổi kiểu cột**: ADR-0002 phải chấp nhận "reset dev"
   thay vì viết migration, đơn giản vì không có công cụ nào để viết migration.
3. **Rủi ro khi nhiều người cùng làm**: `ddl-auto=update` chạy độc lập trên máy mỗi dev — không
   đảm bảo 2 máy có cùng một schema nếu thứ tự entity thay đổi hoặc một người quên pull code mới
   nhất trước khi chạy app.

Đây là quyết định cross-cutting (ảnh hưởng cách toàn bộ team quản lý schema từ giờ về sau), khó
đảo ngược sau khi đã có nhiều migration file chồng lên nhau, và chọn giữa các công cụ thực sự khác
nhau — đủ tiêu chí cần ADR.

## Decision

### Dùng Flyway, không dùng Liquibase

Chọn Flyway vì: Spring Boot auto-config sẵn (`spring.flyway.*`), chỉ cần viết SQL thuần
(`V{n}__description.sql` trong `src/main/resources/db/migration/`) — không cần học thêm cú pháp
XML/YAML riêng như Liquibase. Dự án chỉ dùng 1 loại DB (PostgreSQL), không có nhu cầu migration
đa-dialect hay rollback có cấu trúc phức tạp mà Liquibase mạnh hơn — nên phần phức tạp thêm của
Liquibase không mang lại lợi ích tương xứng ở quy mô hiện tại (đồ án KLTN, 1 dev).

Thêm 2 dependency vào `pom.xml`: `org.flywaydb:flyway-core` và
`org.flywaydb:flyway-database-postgresql` (bắt buộc từ Flyway 10 trở đi — hỗ trợ PostgreSQL đã
tách khỏi `flyway-core`, không thêm module này Flyway sẽ báo lỗi "Unsupported Database:
PostgreSQL"). Không cần khai version — do `spring-boot-starter-parent` quản lý qua BOM.

### Baseline tách làm 2 file, dump từ schema thật, không tự viết tay từ entity

Thay vì gộp luôn thay đổi của ADR-0002 vào baseline, migration được tách làm 2 bước để lịch sử
schema phản ánh đúng trình tự thực tế:

- **`V1__baseline_schema.sql`**: schema **trước** ADR-0002 (`created_by`/`updated_by` còn là
  `varchar`, chưa có FK). Được sinh bằng cách tạm revert `BaseEntity.java`/
  `AuditingConfiguration.java` về bản `String` cũ, chạy app với `ddl-auto=update` nhắm vào một DB
  Postgres trống để Hibernate tự dựng đúng schema cũ, rồi dump lại
  (`pg_dump -U postgres -d assistant_DB --schema-only --no-owner --no-privileges --no-comments`).
- **`V2__audit_fields_user_relation.sql`**: đúng nội dung ADR-0002 — với mỗi bảng trong 11 entity,
  `DROP COLUMN`/`ADD COLUMN` lại `created_by`/`updated_by` thành `uuid` rồi thêm FK về `users(id)`
  (tên constraint đặt tường minh, VD `fk_documents_created_by`, khác với style tên tự sinh của
  Hibernate ở `V1`). Không cố cast dữ liệu cũ — khớp đúng quyết định "chấp nhận reset dev" đã ghi
  trong ADR-0002.

Cả 2 cách trên (dump từ DB thật cho V1, viết tay DROP/ADD cho V2) đều chính xác hơn hẳn so với tự
suy diễn DDL từ annotation của 11 entity bằng tay — tránh sai lệch kiểu dữ liệu, độ dài cột, tên
constraint do Hibernate tự sinh (VD `fk1rm8m98c861dhhm81q18decoi` trong `V1`).

### `spring.flyway.baseline-on-migrate=true` + `baseline-version=2`

DB dev hiện tại vật lý đã ở đúng trạng thái **sau** V2 (vì `ddl-auto=update` đã tự áp dụng thay đổi
của ADR-0002 trước khi Flyway được thêm vào). Vì vậy `baseline-version` trỏ vào **2**, không phải
1 — cấu hình `baseline-on-migrate=true` cho phép Flyway đánh dấu DB đó là đã ở version 2 (ghi 1
dòng `BASELINE` vào bảng `flyway_schema_history` tự tạo) thay vì chạy lại V1/V2, tránh lỗi
"relation already exists"/"column already exists". Với một DB hoàn toàn trống (dev mới clone repo
lần đầu, hoặc restart container Postgres), Flyway sẽ chạy `V1` rồi `V2` tuần tự như migration bình
thường, vì `baseline-on-migrate` chỉ có tác dụng khi schema đã tồn tại sẵn (non-empty).

Đã verify cả 2 kịch bản trên DB thật (container Postgres tạm, tách biệt khỏi `unisage-postgres`):

- **DB trống hoàn toàn**: log Flyway hiện `Migrating schema "public" to version "1 - baseline
  schema"` rồi `"2 - audit fields user relation"`, kết thúc bằng `Successfully applied 2
  migrations ... now at version v2`; app khởi động thành công, Hibernate `validate` khớp.
- **DB dev thật đã có sẵn dữ liệu** (đã ở trạng thái sau V2): sau khi xóa bảng
  `flyway_schema_history` cũ (baseline sai ở version 1 từ lần thử trước) và đổi cấu hình về
  `baseline-version=2`, chạy lại app cho ra đúng `Successfully baselined schema with version: 2`
  rồi `Schema "public" is up to date. No migration necessary.` — xác nhận `flyway_schema_history`
  chỉ có đúng 1 dòng `version=2, type=BASELINE, success=true`.

### `spring.jpa.hibernate.ddl-auto` đổi từ `update` sang `validate`

Từ giờ Flyway là nguồn sự thật duy nhất cho schema — Hibernate không còn được phép tự ý
tạo/sửa bảng. `ddl-auto=validate` khiến Hibernate chỉ kiểm tra mapping entity có khớp với schema
DB hay không lúc khởi động (throw lỗi rõ ràng nếu lệch), không tự sửa gì. Mọi thay đổi schema từ
giờ **phải** đi qua một file `V{n+1}__description.sql` mới, review được trong PR như code thường,
thay vì âm thầm xảy ra khi Hibernate khởi động.

## Consequences

**Tích cực**:

- Có lịch sử schema tường minh, review được qua PR, thay vì phụ thuộc vào việc Hibernate suy ra
  đúng DDL từ annotation.
- Từ ADR-0002 trở đi (và mọi thay đổi schema sau này), có thể viết migration file thật sự thay vì
  phải "chấp nhận reset dev" như đã ghi trong ADR-0002.
- `ddl-auto=validate` bắt lỗi sớm (app không start được) nếu mapping entity và schema DB lệch
  nhau, thay vì Hibernate âm thầm tự sửa sai chỗ.

**Tiêu cực / rủi ro**:

- Từ giờ mọi thay đổi entity kèm thay đổi cột/bảng đều cần thêm 1 file migration SQL thủ công —
  tốn thêm một bước so với việc chỉ sửa entity rồi để Hibernate tự lo (trade-off có chủ đích, đổi
  lấy khả năng kiểm soát và review được).
- Baseline `V1`/`V2` được dump/viết từ đúng 1 máy dev tại 1 thời điểm — nếu máy dev khác đã lỡ tự
  `ddl-auto=update` ra một schema hơi khác (VD thiếu 1 cột do chưa pull code mới nhất), Flyway
  baseline trên máy đó sẽ đánh dấu "đã ở version 2" dù thực tế schema không khớp 100% với 2 file
  SQL — cần đồng bộ lại thủ công (`git pull` + kiểm tra, hoặc tạo lại DB từ đầu, xem README) trước
  khi bật Flyway trên các máy khác.
- Tên constraint trong `V1` (VD `fk1rm8m98c861dhhm81q18decoi`) là tên tự sinh của Hibernate, không
  có ý nghĩa — chấp nhận giữ nguyên để khớp chính xác schema thật đã dump; `V2` trở đi đặt tên
  constraint tường minh (VD `fk_documents_created_by`), và các migration mới sau này nên tiếp tục
  theo cách đó.

## Alternatives considered

1. **Liquibase**: bị loại — mạnh hơn ở khả năng đa-dialect và rollback có cấu trúc, nhưng dự án
   chỉ dùng PostgreSQL và không cần rollback phức tạp; chi phí học thêm cú pháp XML/YAML/JSON
   riêng không tương xứng lợi ích ở quy mô hiện tại.
2. **Tự viết tay baseline SQL từ annotation của entity** thay vì dump từ DB thật: bị loại — dễ sai
   sót (kiểu dữ liệu, độ dài, tên constraint do Hibernate tự sinh), trong khi dump trực tiếp từ DB
   đang chạy đảm bảo khớp 100% với trạng thái thực tế.
3. **Giữ nguyên `ddl-auto=update`, chỉ thêm Flyway cho các migration tương lai**: bị loại — vẫn để
   Hibernate có quyền tự sửa schema song song với Flyway sẽ gây xung đột nguồn sự thật (2 cơ chế
   cùng có thể đổi schema), mất hết lợi ích kiểm soát mà Flyway mang lại.

## Test lock

Verify thủ công (đã chạy trong quá trình viết ADR này, dùng các container Postgres tạm tách biệt
khỏi `unisage-postgres`, dọn dẹp sau mỗi lần test):

- `./mvnw compile` không lỗi sau khi thêm dependency Flyway.
- **DB hoàn toàn trống**: chạy app với code hiện tại (đã có `User` relation) trỏ vào DB trống —
  log Flyway báo `Current version of schema "public": << Empty Schema >>`, rồi
  `Migrating schema "public" to version "1 - baseline schema"`, rồi
  `"2 - audit fields user relation"`, kết thúc `Successfully applied 2 migrations ... now at
  version v2`; app khởi động thành công hoàn toàn, Hibernate `validate` khớp.
- **Sinh `V1` từ đúng schema cũ**: tạm revert `BaseEntity.java`/`AuditingConfiguration.java` về
  bản `String`, chạy app (`ddl-auto=update`, Flyway tắt) nhắm vào DB trống khác — app compile và
  start thành công trên schema cũ, dump lại đúng như `V1__baseline_schema.sql`.
- **DB dev thật đã có dữ liệu sẵn** (schema vật lý đã ở trạng thái sau V2 vì từng chạy qua
  `ddl-auto=update`): xóa `flyway_schema_history` cũ, đổi `baseline-version=2`, chạy lại app — log
  cho `Successfully baselined schema with version: 2` rồi `Schema "public" is up to date. No
  migration necessary.`; `SELECT * FROM flyway_schema_history` trả về đúng 1 dòng
  `version=2, type=BASELINE, success=true`.

## References

- `src/main/resources/db/migration/V1__baseline_schema.sql`
- `src/main/resources/db/migration/V2__audit_fields_user_relation.sql`
- `pom.xml` (dependency `flyway-core`, `flyway-database-postgresql`)
- `src/main/resources/application.properties` (`spring.flyway.*`, `spring.jpa.hibernate.ddl-auto`)
- `README.md` — mục "Database migration (Flyway)", cách chạy/thêm migration mới
- `docs/adr/0002-audit-fields-user-relation.md` — lý do phát sinh nhu cầu này
