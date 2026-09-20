# ADR-0003: Audit Log Persistence (Hibernate Event Listener, Denormalized Actor Snapshot)

- **Date**: 2026-09-20
- **Status**: Accepted
- **Context story**: UNISAGE-60 — Build audit log API (`AuditLogController` + event persistence)
- **Decision owners**: TranNgocHuyen19

## Context

Yêu cầu: mọi thao tác ghi (create/update/delete) trên mọi entity nghiệp vụ — `Category`,
`Document`, `User`, `Role`, `Permission`, `Department`, `AccessLevel`, `ChatModel`,
`Conversation`, `Message`, `Ticket`, `UserDepartmentAccess`, `UsageLimit`, v.v. — phải được ghi
lại vào một bảng audit-log bất biến (append-only): ai làm, làm gì, trên resource nào, khi nào, và
đủ chi tiết để dựng lại thay đổi. `ResourceType`
(`entity/enums/ResourceType.java`) và permission `AUDIT_LOG_ALL`/`AUDIT_LOG_READ` trên
`/audit-logs/**` đã tồn tại sẵn từ trước (chuẩn bị cho ticket này), nên phần việc thực chất là
chọn **cơ chế ghi** và **shape lưu trữ**.

Ràng buộc quan trọng nhất: đây là 11+ entity qua nhiều `*ServiceImpl` khác nhau, và danh sách này
sẽ còn tăng. Gọi tay `auditLogService.record(...)` trong từng service method là phương án dễ nhất
để code nhưng lại đúng là thứ dễ quên nhất — một entity mới, hoặc một code path update không đi
qua service chuẩn (vd. `saveAndFlush` trực tiếp trong 1 nhánh xử lý concurrency), sẽ âm thầm không
được audit mà không có cách nào phát hiện ra ở compile-time hay review nếu không nhớ rà lại.

`docs/adr/0002-audit-fields-user-relation.md` (mục "Alternatives considered" #2) đã bàn tới đúng
use-case này khi quyết định KHÔNG denormalize tên actor cho `BaseEntity.createdBy/updatedBy` —
nhưng ghi rõ: "Denormalize snapshot vẫn là lựa chọn đúng cho một bảng audit-log thực sự bất biến
nếu sau này có". ADR này là "sau này có" đó.

## Decision

### 1. Cơ chế ghi: Hibernate `Integrator` + `PostInsertEventListener`/`PostUpdateEventListener`/`PostDeleteEventListener` toàn cục, không phải AOP quanh service/repository

`audit/AuditEventListener.java` implement cả 3 interface trên, đăng ký cho **mọi entity trong
session factory** — không cho từng entity riêng lẻ — qua
`EventListenerRegistry.appendListeners(EventType.POST_INSERT/POST_UPDATE/POST_DELETE, listener)`
trong `audit/AuditHibernateIntegrator.java`. Integrator này được nối vào Hibernate qua setting
`hibernate.integrator_provider` (`config/AuditConfig.java`, một `HibernatePropertiesCustomizer`
bean) — cách chuẩn của Spring Boot để đăng ký `Integrator` mà không cần file
`META-INF/services/org.hibernate.integrator.spi.Integrator` (không khả thi để ghép nhiều provider
vào 1 file service khi ứng dụng lớn dần).

Chọn hướng này thay vì Spring AOP `@Aspect` quanh `JpaRepository.save`/`deleteById` vì:

- **Bắt được ở tầng ORM, không phải tầng gọi**: mọi entity `save()` dù gọi trực tiếp từ
  `*ServiceImpl` hay phát sinh do cascade (vd. lưu `Role` kèm `RolePermission` con) đều đi qua
  cùng một flush cycle của Hibernate — AOP quanh repository method sẽ bỏ sót các entity con được
  cascade-persist mà không có lệnh `save()` riêng của chúng.
- **Không cần một annotation `@Audited` phải nhớ gắn** — chính là điều task này muốn tránh.
- Repo hiện **không có native query/`@Modifying` nào ghi trực tiếp SQL bỏ qua entity state**
  (đã grep `nativeQuery`/`@Modifying`: các chỗ dùng `@Modifying` — `ConversationRepository`,
  `MessageRepository`, `UsageLimitRepository`, `UserDepartmentAccessRepository` — đều là JPQL
  update/delete theo entity, không phải native SQL), nên khoảng trống "AOP không thấy raw SQL"
  không tồn tại ở đây; nhưng bản thân JPQL bulk update/delete (`@Modifying`) **cũng không sinh ra
  Hibernate entity event** (nó bypass persistence context) — đây là giới hạn thật của cả 2 hướng
  (Hibernate listener lẫn AOP quanh JPQL), ghi nhận ở mục Consequences bên dưới, không phải một
  gap riêng của phương án đã chọn.

**DI gotcha đã biết trước và xử lý**: `Integrator`/`EventListener` do chính Hibernate khởi tạo
bằng `new AuditEventListener()` trong `AuditHibernateIntegrator.integrate(...)`, xảy ra lúc
bootstrap `SessionFactory` — sớm hơn và tách rời khỏi vòng đời bean của Spring, nên
`@Autowired`/constructor injection không hoạt động trên listener này. Giải quyết bằng
`audit/SpringContextHolder.java` — 1 `@Component implements ApplicationContextAware` giữ
`ApplicationContext` ở static field, để `AuditEventListener` lấy `AuditLogWriter` và
`ObjectMapper` qua `SpringContextHolder.getBean(...)` tại thời điểm sự kiện xảy ra (không phải
lúc khởi tạo listener) — tại đó context chắc chắn đã sẵn sàng vì mọi write DB đều xảy ra sau khi
ứng dụng đã start xong (kể cả ghi trong `DataInitializer.run()`, vốn cũng chạy sau khi context
refresh xong, chỉ trước khi `main()` return).

### 2. Ghi audit row trong transaction MỚI, sau khi transaction gốc commit — không ghi ngay trong flush đang chạy

`AuditEventListener` không lưu `AuditLog` ngay lúc `onPostInsert`/`onPostUpdate`/`onPostDelete`
chạy (lúc đó session gốc đang giữa chừng flush). Thay vào đó:

- Nếu có transaction Spring đang active
  (`TransactionSynchronizationManager.isSynchronizationActive()`), đăng ký một
  `TransactionSynchronization.afterCommit()` để gọi `AuditLogWriter.persist(...)` — đảm bảo audit
  row chỉ được ghi (và chỉ hiển thị qua `GET /audit-logs`) nếu thay đổi gốc **đã commit thành
  công**; nếu transaction gốc rollback, `afterCommit()` không bao giờ chạy, không để lại audit row
  ma cho một thay đổi chưa từng xảy ra.
- `AuditLogWriter.persist()` được đánh dấu
  `@Transactional(propagation = Propagation.REQUIRES_NEW)` — chạy trong transaction riêng, độc
  lập hoàn toàn với transaction nghiệp vụ gốc (lúc này đã commit và đóng). Việc ghi audit log thất
  bại (vd. lỗi DB tạm thời) chỉ bị log cảnh báo (`AuditLogWriter` bắt `Exception`), **không bao
  giờ làm rollback hay ném lỗi ngược lại thao tác nghiệp vụ đã hoàn tất** — audit là thứ theo sau,
  không phải điều kiện tiên quyết của request.
- Nếu không có transaction Spring nào active tại thời điểm sự kiện (hiếm, nhưng an toàn để xử
  lý), ghi ngay lập tức thay vì bỏ qua.

Loại bỏ hẳn `AuditLog` khỏi vòng lặp audit của chính nó bằng cách check tên entity `"AuditLog"` ở
đầu `AuditEventListener.handle(...)` và return sớm — nếu không, `AuditLogWriter.persist()` gọi
`auditLogRepository.save()` sẽ tự sinh thêm 1 `PostInsertEvent` cho chính `AuditLog`, đệ quy vô
hạn.

### 3. `AuditLog` không kế thừa `BaseEntity`; actor được denormalize (snapshot), không phải FK sống

`entity/AuditLog.java` là entity độc lập, không có `updatedAt`/`updatedBy`, không bị chính cơ chế
ở mục 1 audit lại (xem loại trừ ở trên) — đúng tinh thần "bất biến, chỉ insert" của bảng log thật
sự.

`actorId`/`actorName`/`actorCode` là 3 cột thường (`actorId` là `UUID` thô, **không** có FK
constraint tới `users(id)`), được điền một lần lúc ghi:

- `actorId`/`actorCode` lấy trực tiếp từ `UserPrincipal` (`SecurityContextHolder`) ngay tại thời
  điểm sự kiện Hibernate xảy ra (vẫn còn trên cùng request thread, trước khi response trả về) —
  không cần query DB.
- `actorName` (không có sẵn trên `UserPrincipal`) được `AuditLogWriter.persist()` tra thêm 1 lần
  qua `userRepository.findById(actorId)` — nhưng trong transaction MỚI của chính audit write, nên
  không ảnh hưởng gì tới transaction/flush nghiệp vụ gốc.

Điều này đi thẳng theo hướng ADR-0002 đã chỉ ra: entity nghiệp vụ (`Category`, `Document`, ...)
cần phản ánh **tên hiện tại** của user nên dùng FK sống (`@ManyToOne User`, có thể đổi tên/xóa
sau), còn audit-log mô tả một **sự kiện đã xảy ra ở một thời điểm cố định** — nếu user đó bị đổi
tên hay bị xóa sau này, dòng audit vẫn phải đọc đúng như lúc ghi. Không đặt FK ràng buộc cũng có
nghĩa: audit-log không bao giờ chặn việc xóa `User` sau này (khác với `BaseEntity.createdBy/
updatedBy` sau ADR-0002, vốn đã tạo FK cứng — xem mục Consequences của ADR đó).

`actorId = null` là giá trị hợp lệ và có chủ đích cho các thao tác không xác thực (guest chat qua
`PredefinedPublicPaths.PUBLIC_PATHS`) — không seed user "system" giả, cùng lý do đã chọn ở
ADR-0002 mục 3.

### 4. `details`: JSON dạng text (không phải cột `jsonb` + `@JdbcTypeCode`), field-diff cho UPDATE, full-snapshot cho CREATE/DELETE

Cột `details` là `TEXT` chứa JSON string do `ObjectMapper` serialize — theo đúng pattern đã có
(`User.extraInfo`, `Ticket.description`: `columnDefinition = "text"` cho nội dung tự do), **không**
dùng `@JdbcTypeCode(SqlTypes.JSON)` + cột `jsonb` như `Message.metadata`/`Message.citations` (đó
là kiểu `Object`/`Map` map thẳng — ở đây field Java là `String` đã serialize sẵn, ép nó vào
`jsonb` qua JDBC type mapping cho `String` là bề mặt lỗi không cần thiết so với lợi ích; đọc lại
vẫn ra đúng JSON string, FE tự `JSON.parse` nếu cần).

Nội dung `details` build từ `EntityPersister.getPropertyNames()` cùng `oldState`/`newState` (hoặc
`deletedState`) mà Hibernate cung cấp sẵn trong mỗi event — không cần load lại entity:

- `CREATE`: field → giá trị tại lúc tạo (bỏ qua field `null`).
- `DELETE`: field → giá trị tại lúc xóa.
- `UPDATE`: chỉ field thực sự đổi (so sánh `!Objects.equals(old, new)`) → `{ "old": ..., "new": ... }`.
  Nếu không có field nào đổi (vd. Hibernate fire update event chỉ vì version-touch), **không ghi
  audit row nào** — tránh rác trong log.
- Giá trị kiểu association (vd. field `ManyToOne User` của `Ticket`) chỉ lấy `getId()` của entity
  liên kết (kể cả khi là Hibernate proxy chưa init — `getId()` không trigger load), không bao giờ
  serialize nguyên object liên kết — tránh cả lỗi serialize vòng lặp bidirectional lẫn N+1 lúc ghi
  audit.
- Field nhạy cảm (`passwordHash`, `apiKeyEncrypted`) bị loại khỏi `details` tuyệt đối — audit
  log không phải chỗ để rò rỉ secret, kể cả dạng hash/đã mã hóa.
- Luôn kèm `_entity` (simple class name, vd. `"Category"`) trong `details` — xem mục 5 về khoảng
  trống của `ResourceType` không phủ hết mọi entity.

### 5. `AuditAction`: chỉ `CREATE`/`UPDATE`/`DELETE` — không thêm `LOGIN`/`LOGOUT`

Cân nhắc thêm `LOGIN`/`LOGOUT` như một `AuditAction` riêng nhưng quyết định KHÔNG làm, vì đăng
nhập **đã tự động được ghi nhận miễn phí**: `AuthServiceImpl.login()` cập nhật
`User.lastLogin`, và field đó nằm trong `oldState`/`newState` mà `AuditEventListener` bắt được như
mọi field khác — kiểm chứng thủ công thấy đúng 1 dòng
`UPDATE / USER / {lastLogin: {old, new}}` sinh ra mỗi lần login (xem mục Test lock). Thêm một
`AuditAction.LOGIN` riêng sẽ là ghi trùng cùng một sự kiện 2 lần qua 2 cơ chế khác nhau, không có
lợi ích gì thêm, đúng tinh thần "không mở rộng scope quá cần thiết" của ticket này. Không có
logout event tương đương để bắt miễn phí kiểu này (logout hiện tại không ghi gì xuống DB), nên
cũng không thêm.

### 6. `ResourceType` không phủ hết mọi entity — fallback `OTHER`, không tạo enum song song

`ResourceType` (đã tồn tại sẵn, dùng chung với hệ thống permission) không có giá trị cho
`UsageLimit`, `RolePermission` (bảng nối), hay `GuestSession`. Theo đúng yêu cầu "tái sử dụng
enum này, không tạo một enum song song", các entity không map được rơi vào `ResourceType.OTHER`
thay vì bị loại khỏi audit — `details._entity` vẫn ghi rõ tên class thật (vd.
`"UsageLimit"`) nên không mất thông tin, chỉ là FE không filter được theo `resourceType` cho
những entity này cho tới khi `ResourceType` được bổ sung (xem Consequences).

## Consequences

**Tích cực**:

- "Mọi thao tác CUD trên mọi entity JPA đều được ghi" là đúng theo nghĩa đen ở tầng ORM — thêm một
  entity mới vào hệ thống tự động được audit, không cần đụng gì vào cơ chế này.
- Audit write không bao giờ có thể làm fail hay rollback ngược request nghiệp vụ (REQUIRES_NEW +
  catch-and-log), và không bao giờ ghi audit row cho một thay đổi rốt cuộc bị rollback
  (afterCommit hook).
- Actor snapshot đúng bản chất bất biến của audit-log, tách bạch rõ với quyết định ngược lại có
  chủ đích ở ADR-0002 cho entity nghiệp vụ.

**Tiêu cực / rủi ro**:

- **JPQL bulk update/delete (`@Modifying`) không sinh Hibernate entity event, nên KHÔNG được
  audit** — đây là khoảng trống thật, không phải giả thuyết. Cụ thể các method đang dùng
  `@Modifying`: `ConversationRepository` (unclaim/cleanup hàng loạt), `MessageRepository`,
  `UsageLimitRepository`, `UserDepartmentAccessRepository` (2 method). Đây đều là các thao tác
  dọn dẹp/batch nội bộ (không phải action trực tiếp của người dùng qua 1 entity cụ thể), nên rủi
  ro thực tế thấp, nhưng cần ghi nhận tường minh: nếu tương lai cần audit cả các batch update này,
  phải thêm lời gọi tường minh riêng cho từng method đó (không thể tự động qua cơ chế ORM-level
  hiện tại).
- `UsageLimit`, `RolePermission`, `GuestSession` chưa có `ResourceType` riêng — audit vẫn ghi
  (dưới `OTHER`, với `_entity` trong `details`) nhưng FE chưa filter theo `resourceType` được cho
  3 entity này cho tới khi `ResourceType` được mở rộng ở một ticket khác.
- Audit write là "eventually persisted" ngay sau commit, không phải cùng transaction/cùng
  millisecond với thay đổi gốc — nếu app crash đúng khoảng giữa `afterCommit()` được gọi và
  `AuditLogWriter.persist()` chạy xong (cửa sổ rất hẹp, cùng 1 request/thread, không có I/O chờ ở
  giữa ngoài chính câu INSERT), audit row của thay đổi cuối cùng có thể bị mất dù thay đổi đã
  commit. Chấp nhận đánh đổi này để đổi lấy việc audit write không bao giờ ảnh hưởng ngược lại
  transaction nghiệp vụ.
- `Integrator` tạo `new AuditEventListener()` trực tiếp, ngoài vòng đời Spring — nếu sau này
  listener cần thêm dependency phức tạp hơn `SpringContextHolder.getBean(...)` cho phép (vd. một
  bean có state cần khởi tạo theo thứ tự), sẽ cần thiết kế lại phần DI này.

## Alternatives considered

1. **Spring AOP `@Aspect` quanh `JpaRepository.save`/`deleteById`** (pointcut
   `execution(* ..repository..*Repository.save*(..))`): bị loại làm phương án chính — không thấy
   được entity con phát sinh do cascade nếu cascade không tự gọi `save()` riêng (Hibernate flush
   tự động, không qua repository method nào), nên yếu hơn Hibernate listener đúng ở điểm mấu chốt
   của yêu cầu ("mọi thao tác đụng DB"). Vẫn có cùng giới hạn với JPQL `@Modifying` như phương án
   đã chọn.
2. **Annotation `@Audited` gắn thủ công trên method service cần audit**: bị loại ngay từ đầu theo
   yêu cầu tường minh của ticket — đây chính xác là "dễ quên" mà task muốn tránh, tương đương gọi
   tay `auditLogService.record(...)`.
3. **Hibernate Envers**: cân nhắc nhưng loại — Envers thiết kế để lưu **toàn bộ lịch sử phiên bản**
   của entity (revision table song song cho mỗi entity + revision-info table), nặng hơn nhiều so
   với nhu cầu ở đây (1 bảng audit-log tổng hợp, không cần dựng lại toàn bộ entity ở một revision
   bất kỳ), và không tự nhiên cho ra một actor snapshot đơn giản kiểu `{actorId, actorName,
   action, resourceType}` — sẽ phải viết thêm code tương đương để map từ revision entity sang
   shape mong muốn, không tiết kiệm công sức so với viết thẳng `PostInsertEventListener`.
4. **Ghi audit row NGAY trong cùng transaction/session đang flush** (không đợi `afterCommit`): bị
   loại — nếu transaction nghiệp vụ rollback sau đó (exception ném ra sau khi entity đã flush),
   audit row (nếu cùng transaction) cũng rollback theo, nghe có vẻ nhất quán, nhưng lại tạo phụ
   thuộc ngược nguy hiểm hơn: nếu chính bước ghi audit thất bại (lỗi ràng buộc, lỗi serialize
   details, hết connection pool), toàn bộ transaction nghiệp vụ ăn theo bị rollback vì một lỗi ở
   tính năng phụ trợ — vi phạm nguyên tắc "audit theo sau, không phải điều kiện tiên quyết".
5. **FK thật từ `AuditLog.actorId` tới `users(id)`**: bị loại — lặp lại đúng vấn đề ADR-0002 đã
   giải quyết cho chiều ngược lại (entity nghiệp vụ), và ở đây còn tệ hơn: audit-log của một user
   đã bị xóa/vô hiệu hóa (case sa thải nhân viên, khóa tài khoản) vẫn phải đọc được, mà FK cứng thì
   không cho phép giữ lại thông tin actor khi hàng `users` biến mất (trừ khi cascade null, nhưng
   như vậy lại mất `actorId` — đúng thứ cần giữ nhất trong audit-log).

## Test lock

Đã xác minh thủ công trên DB dev local (Docker `unisage-postgres` + `unisage-minio`,
`spring.jpa.show-sql=true`, `./mvnw spring-boot:run`) thay vì viết test tự động mới — cùng lý do
ADR-0002: chưa có hạ tầng Testcontainers/embedded DB. `./mvnw test` — toàn bộ 108 test hiện có
(kể cả các test mới thêm từ UNISAGE-82/80/7 sau lần audit ADR-0002) pass không đổi.

- Khởi động ứng dụng: Flyway áp `V10__audit_logs.sql` thành công lên DB dev đã tồn tại từ trước
  (`Successfully applied 3 migrations ... now at version v10`), bảng `audit_logs` được tạo, và vì
  `DataInitializer` in ra `"System already initialised — skipping DataInitializer"` (DB dev này đã
  seed từ trước UNISAGE-60), phần seed `AUDIT_LOG_ALL`/`AUDIT_LOG_READ` idempotent trong chính
  migration (không phải `DataInitializer`) là đường duy nhất áp dụng được — xác nhận đúng lý do
  V10 cần tự seed permission thay vì chỉ dựa vào `DataInitializer` (giống hệt tình huống V8 với
  `TICKET_*`).
- Đăng nhập `SUPER_ADMIN` (`admin@unisage.com` / code `SA-001`) trả về JWT có claim
  `permissions` chứa `AUDIT_LOG_ALL` — xác nhận migration đã gán đúng quyền.
- `POST /categories` (đã xác thực) → 1 dòng `audit_logs` mới:
  `action=CREATE, resourceType=CATEGORY, resourceId=<id category>, actorId=<id SUPER_ADMIN>,
  actorName="System Administrator", actorCode="SA-001"`, `details` chứa đủ field khởi tạo
  (`name`, `description`, `status`, `isActive`, `createdBy`, ...).
- `PUT /categories/{id}` đổi `name` + `description` → 1 dòng `UPDATE` mới với `details` **chỉ**
  chứa 3 field thực sự đổi (`name`, `description`, `updatedAt` — không lặp lại field không đổi
  như `status`), đúng thiết kế field-diff ở mục 4.
- `DELETE /categories/{id}` (soft-delete qua `isActive=false`) → 1 dòng `UPDATE` mới với
  `details = {"isActive": {"old": true, "new": false}, "updatedAt": {...}}` — xác nhận soft-delete
  qua `CategoryServiceImpl.deleteCategory` (không có `AuditAction.DELETE` thật sự phát sinh vì
  repo không hard-delete `Category`) vẫn được ghi nhận đúng bản chất là một UPDATE.
- `GET /audit-logs?resourceType=CATEGORY&action=CREATE` và `GET /audit-logs?resourceType=USER`
  lọc đúng, độc lập với nhau; `GET /audit-logs?actorId=...&fromDate=2099-01-01T00:00:00` (mốc
  tương lai) trả `data: [], totalItems: 0, totalPages: 0` đúng như kỳ vọng một filter không khớp
  gì — xác nhận cả 2 nhánh Specification (khớp và không khớp) hoạt động, và phân trang
  (`page`/`limit`, 1-indexed) khớp đúng `PageResponse` convention.
- Đăng nhập chính lần test ở trên tự sinh 3 dòng `UPDATE / USER / {lastLogin: {...}}` với
  `actorId = null` — xác nhận mục 5 (login được audit "miễn phí" qua field `lastLogin`, không cần
  `AuditAction.LOGIN` riêng), và `actorId = null` hợp lệ cho thao tác không có `UserPrincipal`
  trong `SecurityContextHolder` tại thời điểm ghi (đăng nhập cập nhật `lastLogin` **trước khi**
  JWT được cấp/đặt vào context của response).
- `POST /conversations` không kèm JWT (guest chat, path công khai theo
  `PredefinedPublicPaths.PUBLIC_PATHS`) → 1 dòng `CREATE / CONVERSATION` với
  `actorId = null, actorName = null, actorCode = null`, `details` chứa `guestSession: <id>` (lấy
  qua `getId()` phản chiếu trên association, không serialize nguyên `GuestSession`) — xác nhận
  guest-path write được audit đầy đủ với actor null có chủ đích, đúng mục 3, không phải một trường
  hợp bị bỏ sót.
- Không kiểm thử riêng `UPDATE`/`DELETE` cho các entity còn lại (`Document`, `Role`, `Permission`,
  `Department`, `ChatModel`, `Message`, `Ticket`, `UserDepartmentAccess`) — cơ chế là ORM-level,
  dùng chung 1 listener cho mọi entity không phân biệt, nên kiểm chứng trên `Category` +
  `Conversation` (2 entity, 2 tình huống actor khác nhau: có JWT / không JWT) mang tính đại diện,
  không phải kiểm tra toàn bộ. `UsageLimit`/`RolePermission`/`GuestSession` (map `OTHER`) và giới
  hạn với `@Modifying` JPQL (mục Consequences) chưa được kiểm thử trực tiếp — ghi nhận là nợ xác
  minh, không phải giả định chưa kiểm chứng về mặt cơ chế.

## References

- `src/main/java/com/unisage/backend/audit/AuditEventListener.java`
- `src/main/java/com/unisage/backend/audit/AuditHibernateIntegrator.java`
- `src/main/java/com/unisage/backend/audit/AuditLogWriter.java`
- `src/main/java/com/unisage/backend/audit/SpringContextHolder.java`
- `src/main/java/com/unisage/backend/config/AuditConfig.java`
- `src/main/java/com/unisage/backend/entity/AuditLog.java`
- `src/main/java/com/unisage/backend/entity/enums/AuditAction.java`
- `src/main/java/com/unisage/backend/entity/enums/ResourceType.java` (tái sử dụng, không đổi)
- `src/main/java/com/unisage/backend/controller/AuditLogController.java`
- `src/main/java/com/unisage/backend/service/auditlog/AuditLogServiceImpl.java`
  (tiền lệ `Specification<T>` dùng lại từ `TicketServiceImpl.buildSpec`)
- `src/main/resources/db/migration/V10__audit_logs.sql`
- `docs/adr/0002-audit-fields-user-relation.md` — quyết định ngược lại có chủ đích cho entity
  nghiệp vụ, và là nơi đầu tiên nêu ra hướng denormalize cho audit-log
- `src/main/java/com/unisage/backend/predefined/PredefinedPublicPaths.java` — các path guest ghi
  DB không cần xác thực
