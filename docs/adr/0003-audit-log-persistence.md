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

## Update — hybrid extension (login/logout via domain events, sensitive reads via AOP)

- **Date**: 2026-09-20
- **Status**: Accepted
- **Context story**: UNISAGE-60 (same ticket) — extend the write-only Hibernate-listener trail with
  coverage for security/compliance-relevant actions that are **not** DB writes: login/logout, and
  export/download + view of sensitive data.

### Context

Mục 5 ở trên ("`AuditAction`: chỉ CREATE/UPDATE/DELETE — không thêm LOGIN/LOGOUT") lập luận rằng
login đã được ghi "miễn phí" qua field `lastLogin` (`UPDATE / USER`). Lập luận đó đúng về mặt kỹ
thuật nhưng không đủ cho một audit trail bảo mật thực sự:

- Nó chỉ ghi được **login thành công**. Một `LOGIN_FAILED` (sai mật khẩu, hoặc `code` không tồn
  tại) không đụng DB write nào — không có entity nào được insert/update/delete — nên hoàn toàn
  không được ghi nhận. Đây chính xác là loại sự kiện một audit trail bảo mật cần nhất (phát hiện
  brute-force/dò mật khẩu).
- `logout` không ghi gì xuống DB (đã ghi nhận đúng ở mục 5), nên không có cách nào "ăn theo" một
  DB write để được audit miễn phí.
- `UPDATE / USER / {lastLogin: {...}}` không tự thân nói rõ đây là một sự kiện đăng nhập — FE phải
  suy luận từ tên field thay đổi, không lọc trực tiếp được theo "hành động đăng nhập" qua
  `action=LOGIN`.

Tương tự, đọc dữ liệu nhạy cảm (xem chi tiết 1 document kèm link tải, xem chi tiết 1 user kèm PII)
hoàn toàn không phải DB write — không entity nào được insert/update/delete khi gọi
`GET /documents/{id}` hay `GET /users/{id}` — nên cơ chế Hibernate listener ở mục 1 **không thể**
và không nên mở rộng để phủ luôn trường hợp này (một `PostSelectEventListener` tổng quát không tồn
tại theo cách tương tự, và dù có, sẽ bắt được cả những read hoàn toàn không liên quan tới nghiệp
vụ, như query nội bộ của Hibernate).

Do đó quyết định bổ sung 2 cơ chế mới, cho 2 loại sự kiện khác bản chất nhau, thay vì cố nhét cả 2
vào cùng 1 cơ chế:

### Quyết định 1: Login/Logout — domain event (`ApplicationEventPublisher`/`@EventListener`), không phải Spring Security's `AuthenticationSuccessEvent`/`AbstractAuthenticationFailureEvent`

Xác nhận trước khi code: app này **không** xác thực qua `AuthenticationManager.authenticate(...)`.
`AuthServiceImpl.login()` tự fetch `User` bằng `userRepository.findByCode(...)` rồi so mật khẩu
tay bằng `passwordEncoder.matches(...)` — không đi qua `ProviderManager`/`AuthenticationProvider`
nào của Spring Security. Hệ quả: `AuthenticationSuccessEvent` và
`AbstractAuthenticationFailureEvent` (và các sự kiện con của nó) **không bao giờ được publish**
cho flow này — không có `AuthenticationManager` nào gọi `publishAuthenticationSuccess(...)`/
`publishAuthenticationFailure(...)`. Wiring một `@EventListener` cho các event có sẵn này sẽ không
bao giờ chạy — bị loại ngay từ đầu, không phải một phương án khả thi ở đây, khác với một app xác
thực chuẩn qua Spring Security.

Thay vào đó, dùng domain event tự định nghĩa, publish tường minh tại đúng nơi biết chuyện gì vừa
xảy ra:

- `audit/event/LoginSucceededEvent.java`, `LoginFailedEvent.java`, `LogoutEvent.java` — record đơn
  giản (không cần kế thừa `ApplicationEvent`, Spring publish/subscribe bất kỳ POJO nào qua
  `ApplicationEventPublisher.publishEvent(Object)`).
- `AuthServiceImpl.login()` publish `LoginFailedEvent(attemptedCode, reason)` ở cả 2 nhánh thất
  bại xác thực (`code` không tồn tại → `reason="CODE_NOT_FOUND"`; sai mật khẩu →
  `reason="BAD_PASSWORD"`) **trước khi** ném `AppException`, và publish
  `LoginSucceededEvent(user.getId(), user.getCode())` sau khi mọi check (mật khẩu, `isActive`,
  role active) đều pass, ngay trước khi trả `buildSession(user)`. Không bao giờ đưa raw password
  vào event hay vào `details`.
- `AuthServiceImpl.logout()` (method mới, gọi từ `AuthController.logout()`) đọc
  `UserPrincipal` hiện tại từ `SecurityContextHolder` và publish `LogoutEvent(userId, code)` — nếu
  không có principal hợp lệ (không nên xảy ra sau `SecurityFilterChain`, nhưng xử lý an toàn), đơn
  giản là không publish gì, không throw.
- `audit/AuthAuditListener.java` — `@Component` với 3 method `@EventListener`, build `AuditLog`
  rồi gọi thẳng `AuditLogWriter.persist(...)`. Đây là bean Spring bình thường (không phải Hibernate
  tự khởi tạo như `AuditEventListener`), nên constructor-inject `AuditLogWriter` trực tiếp — không
  cần `SpringContextHolder` như listener Hibernate. Chạy đồng bộ, không `@Async`: login/logout tần
  suất thấp, không đáng thêm phức tạp của một event queue.

**Xử lý `LOGIN_FAILED` không có `User` thật** (case `code` không tồn tại): `actorId`/`resourceId`
để `null` có chủ đích (không có user nào để trỏ tới), `code` người dùng gõ được ghi vào
`details.attemptedCode` — đủ để điều tra ai/gì đang dò mật khẩu (theo IP nếu sau này bổ sung
`ipAddress`, theo `code` bị dò nếu là 1 tài khoản cụ thể) mà không tạo audit row trỏ tới 1
`resourceId` không tồn tại. `LOGIN`/`LOGOUT` (thành công) luôn có `actorId`/`resourceId` vì tại đó
`User` chắc chắn tồn tại và đã xác thực.

**Không xóa mục 5 cũ** (login vẫn tiếp tục được ghi "miễn phí" qua `UPDATE / USER / {lastLogin}`
từ cơ chế Hibernate listener) — 2 dòng audit cho cùng 1 lần login thành công (`UPDATE` từ
`lastLogin` + `LOGIN` từ domain event) là trùng lặp thật, nhưng chấp nhận được: chúng phục vụ 2 mục
đích khác nhau (`UPDATE` là audit trail chung "field nào đổi", `LOGIN` là tín hiệu bảo mật lọc được
trực tiếp qua `action=LOGIN`/`LOGIN_FAILED`/`LOGOUT`), và tách `lastLogin` ra khỏi audit trail
Hibernate (loại trừ field này khỏi listener) sẽ phức tạp hơn lợi ích mang lại, cũng như làm mất đi
tiền lệ "mọi field UPDATE đều được ghi" đã document ở mục 4.

### Quyết định 2: Export/Download + View sensitive data — AOP `@Auditable`, tầng service, `@AfterReturning`

`audit/Auditable.java` (`@Target(METHOD)`, `@Retention(RUNTIME)`, thuộc tính `action()`/
`resourceType()`) + `audit/AuditableAspect.java` (`@Aspect @Component`,
`@AfterReturning("@annotation(auditable)")`).

**Tầng service, không phải tầng controller**: pointcut nhắm vào method của `*ServiceImpl` (vd.
`DocumentServiceImpl.getById`, `UserServiceImpl.getUserById`), không phải method của
`*Controller`. Lý do: tầng service là nơi thực sự xảy ra hành động nghiệp vụ ("lấy chi tiết 1
document/user") và đã có sẵn entity/id đã resolve (không chỉ path variable thô như ở tầng
controller) — nhất quán với cách `AuditEventListener` cũng bắt ở tầng gần dữ liệu nhất (persister),
không phải tầng HTTP.

**`@AfterReturning`, không phải `@Around`**: chọn có chủ đích, ghi rõ ở Javadoc của `@Auditable`.
Advice chỉ chạy khi method trả về bình thường — một lần gọi ném exception (not-found, permission
forbidden ở `resolveFileUrl`/`resolveMinAccessLevel`, v.v.) không tạo audit row. Đúng tinh thần
"ghi lại cái gì **đã thực sự** được xem/tải", không phải "cái gì được thử truy cập" — khác mục
đích với một audit log truy cập bị từ chối (authorization audit), vốn là một concern riêng, không
thuộc phạm vi `@Auditable` này.

**Hợp đồng resourceId — tham số đầu tiên của method phải là `UUID` hoặc `String`**:
`AuditableAspect.extractResourceId(JoinPoint)` lấy `joinPoint.getArgs()[0]`, ép kiểu, dùng thẳng
làm `resourceId`. Không dùng reflection dò trường `id` trên giá trị trả về — đơn giản hơn và không
phụ thuộc shape response DTO của từng service (mỗi `*Response` có thể có `id` ở vị trí khác nhau).
Nếu tham số đầu tiên không phải `UUID`/`String`, aspect log cảnh báo và ghi `resourceId = null`
thay vì throw — audit không bao giờ được phép làm hỏng lời gọi nó đang quan sát. Method tương lai
muốn dùng `@Auditable` phải tuân theo đúng hợp đồng này (xem Javadoc trên `Auditable.java`).

**Chạy inline/đồng bộ trong aspect** (không `TransactionSynchronization.afterCommit()` như
`AuditEventListener`): khác với Hibernate listener (chạy giữa lúc flush của 1 transaction đang
mutate dữ liệu, cần đợi commit để tránh ghi audit cho 1 thay đổi rồi rollback), aspect này bọc một
method **đọc** — không có transaction nghiệp vụ nào đang mutate mà có thể rollback để làm audit
row "nói dối". `AuditLogWriter.persist()` vẫn giữ nguyên `REQUIRES_NEW` (dùng lại y hệt, không tạo
đường ghi thứ 2) nên bản thân việc ghi audit vẫn tách biệt, chỉ là aspect không cần chờ điểm
commit nào trước khi gọi nó.

**2 method cụ thể được gắn `@Auditable`** (xem thêm ở mục Consequences về các method cân nhắc
nhưng KHÔNG gắn):

1. `DocumentServiceImpl.getById(UUID id)` — `@Auditable(action = DOWNLOAD, resourceType =
   DOCUMENT)`. Repo này không có endpoint download riêng biệt: link tải (presigned MinIO URL) nằm
   ngay trong response chi tiết 1 document, qua `resolveFileUrl(document)` gọi từ
   `mapToResponse(document)`. Vì vậy "xem chi tiết 1 document" được coi là tín hiệu download/view —
   đây là điểm truy cập rõ ràng nhất trong codebase hiện tại cho hành vi này.
   **Không gắn lên `resolveFileUrl`/`mapToResponse` trực tiếp** — 2 method này còn được gọi từ
   `getAll()` (list/pagination `mapToResponse` mỗi dòng), gắn ở đó sẽ tạo 1 audit row **mỗi
   document mỗi lần load 1 trang danh sách** — noise, không phải tín hiệu.
2. `UserServiceImpl.getUserById(UUID id)` — `@Auditable(action = VIEW, resourceType = USER)`. Trả
   `UserDetailResponse` chứa PII (email, phone, ...) không có trong response danh sách
   (`UserResponse`/`getAllUsers`). **Không gắn lên `getAllUsers`** cùng lý do trên.

### Migration: `V11__audit_log_actions_widen.sql`

`audit_logs.action` có `CHECK` constraint liệt kê tường minh `CREATE`/`UPDATE`/`DELETE`
(`V10__audit_logs.sql`) — bắt buộc phải có migration mới nới rộng constraint này, nếu không mọi
`INSERT` với `action` mới sẽ bị Postgres từ chối ở tầng DB dù code Java hoàn toàn hợp lệ.
`resource_type` không đổi (2 method mới đều dùng `DOCUMENT`/`USER`, đã có sẵn trong constraint
cũ), nên chỉ `action` cần nới.

### Consequences

**Tích cực**:

- Đăng nhập sai (kể cả `code` không tồn tại) giờ có 1 dòng `LOGIN_FAILED` tra cứu được trực tiếp
  qua `GET /audit-logs?action=LOGIN_FAILED` — trước đây hoàn toàn không có tín hiệu nào.
- `LOGIN`/`LOGOUT` là tín hiệu tường minh, không phải suy luận từ tên field `lastLogin` thay đổi.
- Đọc dữ liệu nhạy cảm (document detail kèm link tải, user detail kèm PII) giờ có audit trail,
  điều mà cơ chế Hibernate listener (chỉ bắt write) không bao giờ làm được dù có mở rộng thêm.
- 2 cơ chế mới đều tái sử dụng nguyên `AuditLogWriter`/`AuditLog`/`ResourceType` đã có — không có
  đường ghi audit thứ 2, thứ 3 song song với cơ chế cũ.

**Tiêu cực / rủi ro**:

- Login thành công giờ sinh **2** dòng audit (`UPDATE / USER / {lastLogin}` từ listener +
  `LOGIN / USER` từ domain event) — trùng lặp có chủ đích, chấp nhận được (xem lập luận ở trên),
  nhưng FE/người đọc audit log cần biết đây không phải lỗi ghi đúp.
- `@Auditable` dựa vào quy ước "tham số đầu tiên là `UUID`/`String`" — không được compiler cưỡng
  chế, chỉ cảnh báo runtime (log warning, `resourceId = null`) nếu vi phạm. Dev thêm `@Auditable`
  cho 1 method mới sai chữ ký sẽ không thấy lỗi biên dịch, chỉ thấy audit row thiếu `resourceId`
  nếu không đọc log — rủi ro giống hệt rủi ro "annotation dễ quên/dễ gắn sai" mà mục 1 (Alternative
  #2) đã cảnh báo cho hướng `@Audited` thủ công, nhưng ở đây phạm vi hẹp hơn nhiều (chỉ 2 method,
  không phải mọi entity write) nên đánh đổi được.
- `@AfterReturning` nghĩa là một lần "xem/tải bị từ chối" (not-found, permission forbidden) không
  để lại dấu vết audit nào — nếu sau này cần audit cả các lần truy cập bị từ chối (khác mục đích:
  đó là phát hiện dò quét trái phép, không phải "ghi nhận việc đã xem"), sẽ cần một cơ chế khác
  (`@Around` bắt cả exception, hoặc audit ở tầng authorization filter), không phải mở rộng
  `@Auditable` hiện tại.
- **Method cân nhắc nhưng KHÔNG gắn `@Auditable`, và vì sao**:
  - `DocumentServiceImpl.getAll()`, `UserServiceImpl.getAllUsers()` (list/pagination): loại vì sẽ
    audit noise theo từng trang, không phải theo từng lần "xem 1 tài nguyên cụ thể".
  - `DocumentServiceImpl.resolveFileUrl()`, `mapToResponse()` (private helper dùng chung cho cả
    detail lẫn list): loại vì gắn ở đây sẽ audit cả list path — chính lý do phải chọn `getById`
    làm điểm audit thay vì helper thực sự tạo ra URL.
  - `UserServiceImpl.getMyProfile()`: cân nhắc (cũng trả PII) nhưng loại — đây là user tự xem
    chính mình (`actorId == resourceId` luôn đúng), không phải một actor xem dữ liệu nhạy cảm của
    **người khác**, nên giá trị bảo mật/compliance của việc audit lại gần như bằng 0 (không phát
    hiện được gì mà đăng nhập thành công chưa nói lên).
  - `DocumentServiceImpl.createDocument`/`updateDocument`/`updateStatus`/`softDelete`: đã là DB
    write, đã được `AuditEventListener` bắt qua CREATE/UPDATE — gắn thêm `@Auditable` sẽ audit
    trùng 1 hành động qua 2 cơ chế mà không có lý do (khác với login, nơi domain event mang thông
    tin mà DB write không mang được).

### Test lock (hybrid extension)

Xác minh thủ công trên DB dev local (Docker `unisage-postgres` + `unisage-minio` đã chạy sẵn,
`./mvnw spring-boot:run`), cùng cách tiếp cận với Test lock gốc ở trên:

- Flyway áp `V11__audit_log_actions_widen.sql` thành công lên DB dev đã ở version 10
  (`Successfully applied 1 migration ... now at version v11`).
- `POST /auth/login` sai mật khẩu (`code=SA-001`, password sai) → 401, và 1 dòng
  `LOGIN_FAILED / USER`, `resourceId=null`, `actorId=null`,
  `details={"attemptedCode":"SA-001","reason":"BAD_PASSWORD"}`.
- `POST /auth/login` đúng mật khẩu → 200 kèm token, và 1 dòng `LOGIN / USER`,
  `resourceId=actorId=<id SUPER_ADMIN>`, `actorCode="SA-001"`, `details={"code":"SA-001"}`.
- `POST /auth/logout` (kèm Bearer token) → 200, và 1 dòng `LOGOUT / USER`,
  `resourceId=actorId=<id SUPER_ADMIN>`.
- `GET /users/{id}` (chi tiết 1 user) → 200, và 1 dòng `VIEW / USER`, `resourceId=<id đó>`.
- `GET /documents/{id}` (chi tiết 1 document, kèm `fileUrl` presigned) → 200, và 1 dòng
  `DOWNLOAD / DOCUMENT`, `resourceId=<id đó>`.
- `GET /users?page=1&limit=5` và `GET /documents?page=1&limit=5` (list/pagination) → 200, và
  **KHÔNG** sinh thêm dòng `VIEW`/`DOWNLOAD` nào — xác nhận `SELECT COUNT(*) FROM audit_logs WHERE
  action IN ('DOWNLOAD','VIEW')` giữ nguyên đúng 2 (1 từ mỗi lần gọi detail ở trên), không tăng
  theo số dòng/số trang list.
- `GET /audit-logs?action=DOWNLOAD` và `?action=LOGIN_FAILED` filter đúng, trả đúng field —
  xác nhận `AuditLogController`/`AuditLogServiceImpl` không cần sửa gì để phục vụ các `AuditAction`
  mới (tái sử dụng nguyên `Specification` hiện có, không hardcode danh sách action cũ nào).
- `./mvnw test` — toàn bộ 108 test hiện có + `AuthServiceImplTest` (sửa lại constructor call để
  truyền thêm `ApplicationEventPublisher` mock) pass không đổi; không thêm test tự động mới cho
  luồng domain-event/AOP mới, cùng lý do ADR-0002/0003 gốc (chưa có hạ tầng Testcontainers/embedded
  DB) — coi là nợ xác minh tự động, đã bù bằng kiểm thử thủ công ở trên.

## References (bổ sung)

- `src/main/java/com/unisage/backend/audit/event/LoginSucceededEvent.java`
- `src/main/java/com/unisage/backend/audit/event/LoginFailedEvent.java`
- `src/main/java/com/unisage/backend/audit/event/LogoutEvent.java`
- `src/main/java/com/unisage/backend/audit/AuthAuditListener.java`
- `src/main/java/com/unisage/backend/audit/Auditable.java`
- `src/main/java/com/unisage/backend/audit/AuditableAspect.java`
- `src/main/java/com/unisage/backend/service/auth/AuthServiceImpl.java` (`login`, `logout`)
- `src/main/java/com/unisage/backend/service/document/DocumentServiceImpl.java` (`getById`)
- `src/main/java/com/unisage/backend/service/user/UserServiceImpl.java` (`getUserById`)
- `src/main/resources/db/migration/V11__audit_log_actions_widen.sql`
- `pom.xml` (`spring-boot-starter-aop`)

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
