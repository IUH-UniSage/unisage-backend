# ADR-0002: Audit Fields (`createdBy`/`updatedBy`) as a `User` Relation

- **Date**: 2026-09-05
- **Status**: Accepted
- **Context story**: UNISAGE-56 — resolve audit user names without N+1 queries
- **Decision owners**: TranNgocHuyen19

## Context

`BaseEntity` (`entity/BaseEntity.java`) khai báo `createdBy`/`updatedBy` là cột `String` thuần,
được Spring Data JPA auditing (`AuditingConfiguration.java` với `AuditorAware<String>`) tự động
gán giá trị `userId.toString()` (hoặc literal `"anonymous"` khi request không xác thực). Mọi
entity cần audit trail — `AccessLevel`, `Category`, `ChatModel`, `Conversation`, `Department`,
`Document`, `Message`, `Permission`, `Role`, `UsageLimit`, và cả `User` — đều kế thừa field này
qua `@MappedSuperclass`.

Response hiện tại trả thẳng chuỗi ID này ra ngoài (VD `DocumentResponse.createdBy`), FE không thể
hiển thị gì hữu ích ngoài một UUID khó đọc thay vì tên người thực hiện. Cách sửa ngây thơ nhất —
gọi `userRepository.findById(entity.getCreatedBy())` ngay trong vòng lặp map response — sẽ tạo ra
N+1 query trên mọi endpoint list/pagination (`CategoryController`, `AccessLevelController`,
`ChatModelController`, `UserController`, `DocumentController`, `RbacController`,
`DepartmentController` đều trả về danh sách entity có audit field, có phân trang hoặc không).

Quyết định này ảnh hưởng tới 11 entity thông qua 1 `@MappedSuperclass` dùng chung, không dễ đảo
ngược một khi response DTO và phía FE đã phụ thuộc vào shape mới, và phải chọn giữa nhiều phương
án thực sự khác nhau (xem bên dưới) — đủ tiêu chí để cần một ADR theo quy ước của repo này.

## Decision

### 1. `createdBy`/`updatedBy` chuyển thành `@ManyToOne User`, không dùng shadow ID column

`BaseEntity.createdBy`/`updatedBy` đổi từ `String` sang:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "created_by")
private User createdBy;
```

(tương tự cho `updatedBy`). Đi theo đúng pattern đã có sẵn của `Document.ingestedBy`
(`entity/Document.java:66-69`) — quan hệ `User` duy nhất khác đang tồn tại trong codebase.

Chọn quan hệ thật thay vì giữ cột `UUID`/`String` rồi gắn thêm 1 association read-only song song,
vì `BaseEntity` dùng chung cho 11 entity — dù theo hướng nào thì kiểu dữ liệu của field cũng phải
đổi mới join được, nên việc duy trì 2 đại diện song song cho cùng một sự thật (1 cột ghi + 1
association chỉ đọc) chỉ tăng gấp đôi bề mặt migration mà không có lợi ích thực chất.

### 2. `AuditorAware<User>` qua `EntityManager.getReference`, không phải `AuditorAware<String>`

Cơ chế ghi của Spring Data auditing gán thẳng giá trị `AuditorAware.getCurrentAuditor()` trả về
vào field bằng reflection. Vì field giờ là kiểu `User`, auditor phải trả về một `User` (hoặc proxy
chưa init của nó), không phải một ID. `AuditingConfiguration.auditorProvider()` được viết lại để
inject `EntityManager` và trả `Optional.of(entityManager.getReference(User.class,
userPrincipal.getUserId()))` cho request đã xác thực — `getReference` trả về proxy lazy, không
phát sinh `SELECT` phụ tại thời điểm ghi.

### 3. FK cho phép null; không seed user "system" cho các dòng do guest tạo

`PredefinedPublicPaths.PUBLIC_PATHS` cho phép `POST /conversations` và `POST /messages` không cần
xác thực (guest chat). Hiện tại các dòng này nhận `createdBy = "anonymous"` (một chuỗi literal).
Khi field trở thành FK thật, `"anonymous"` không còn là giá trị hợp lệ. Auditor provider trả về
`Optional.empty()` cho trường hợp chưa xác thực/anonymous; Spring Data auditing sẽ để field là
`null` khi provider trả về empty. Cột `created_by`/`updated_by` vẫn giữ nullable (không thêm
`nullable = false`). Không seed một `User` "system" giả — việc đó sẽ làm bẩn bảng `users`, cần
thêm logic seed/tra cứu mà không mang lại lợi ích gì hơn so với `null` + fallback hiển thị
"System"/"Hệ thống" mà FE đã có sẵn cho giá trị falsy.

Cùng quy tắc FK-nullable này cũng xử lý luôn trường hợp `User` tự tham chiếu chính nó (vì `User`
kế thừa `BaseEntity` nên `createdBy`/`updatedBy` của nó là self-referencing FK) — user
bootstrap/đầu tiên không có ai tạo ra nó, nên là `null`.

### 4. Response shape: thêm mới `createdByName`/`updatedByName`, giữ nguyên `createdBy`/`updatedBy` cũ

Response DTO vẫn giữ `createdBy`/`updatedBy` là `String` ID như hiện tại (lấy từ
`entity.getCreatedBy().getId().toString()`, có null-check), và bổ sung thêm field mới
`createdByName`/`updatedByName` kiểu `String`. Phía FE (`unisage-web`) hiện đang bind
`createdBy`/`updatedBy` như chuỗi ID thuần ở 6 feature (access-level, categories, rbac,
departments, documents, chat-models), tự có logic fallback khi null — nếu đổi field cũ thành
object `{id, name}` sẽ buộc FE phải sửa đồng thời, gắn chặt 2 task lại với nhau. Thêm field mới
theo kiểu additive giúp task FE (UNISAGE-58) tự chọn thời điểm áp dụng field tên mới, độc lập với
task backend này.

### 5. Chống N+1: `@EntityGraph` (hoặc `JOIN FETCH` tường minh) trên các query list/pagination, không tra cứu theo từng dòng

Mọi repository method phục vụ endpoint list/pagination được thêm
`@EntityGraph(attributePaths = {"createdBy", "updatedBy"})` (riêng `DocumentRepository
.findAllActive` đã là JPQL tùy biến sẵn, nên thêm thẳng `LEFT JOIN FETCH d.createdBy LEFT JOIN
FETCH d.updatedBy` vào câu query). Đây là pattern hoàn toàn mới với codebase — tiền lệ fetch-join
duy nhất trước đó là `UserRepository.findByIdWithPermissions` (chỉ áp dụng cho 1 entity đơn) — nên
cần xác nhận lại bằng `spring.jpa.show-sql=true` lúc implement rằng `@EntityGraph` có resolve đúng
attribute kế thừa từ `@MappedSuperclass` hay không; nếu không thì dùng `LEFT JOIN FETCH` JPQL làm
phương án dự phòng.

### 6. Migration dữ liệu: chấp nhận reset ở môi trường dev, không cố backfill

Repo này không có Flyway/Liquibase; thay đổi schema dựa vào
`spring.jpa.hibernate.ddl-auto=update`. Dữ liệu `varchar` hiện tại của `created_by`/`updated_by`
có chứa literal `"anonymous"` cho mọi dòng do guest tạo — giá trị này không phải UUID hợp lệ và
không thể làm FK — nên một phép cast an toàn kiểu `ALTER COLUMN ... USING created_by::uuid` là
không khả thi nếu không null hóa các dòng đó trước, và kể cả vậy cũng không đảm bảo mọi giá trị
còn lại đều khớp với một `users.id` đang tồn tại trên máy dev của từng người. Với giai đoạn hiện
tại của dự án (pre-production, đồ án KLTN, một dev), quyết định là chấp nhận đây là một breaking
change chỉ ảnh hưởng môi trường dev: ghi rõ trong PR, và để mỗi dev tự drop/reset lại các cột này
ở local thay vì cố viết script migrate giữ lại dữ liệu cũ.

## Consequences

**Tích cực**:

- Response list/detail có thể trả tên người thực hiện dễ đọc mà số round-trip DB không đổi so với
  hiện tại (1 query mỗi trang, không phải 1 query mỗi dòng).
- FE nhận field mới theo kiểu additive, không phá vỡ tương thích ngược, có thể áp dụng độc lập với
  thay đổi này.
- Nhất quán với tiền lệ quan hệ `User` duy nhất đang có (`Document.ingestedBy`) thay vì tạo thêm
  một pattern khác cho cùng một loại tham chiếu.

**Tiêu cực / rủi ro**:

- Đây là lần đầu tiên `@EntityGraph` được dùng trong codebase; nếu nó không resolve đúng attribute
  kế thừa từ `@MappedSuperclass` như kỳ vọng, mọi repository liên quan phải chuyển sang JPQL fetch
  join — rủi ro cần xác minh sớm trong lúc implement.
- Dữ liệu dev hiện có ở `created_by`/`updated_by` không được giữ lại qua lần đổi này (xem quyết
  định migration ở trên) — ai có dữ liệu cần giữ phải được cảnh báo trước khi merge.
- `Document.ingestedBy` vẫn chưa được fetch-join và vẫn N+1-prone trên `DocumentRepository
  .findAllActive` trừ khi được tiện thể sửa luôn trong cùng PR — ADR này không bắt buộc phải sửa,
  chỉ ghi nhận đây là nợ kỹ thuật liên quan, có sẵn từ trước.
- Thêm FK constraint thật trên `created_by`/`updated_by` nghĩa là không thể hard-delete `User` mà
  không vỡ ràng buộc tham chiếu ở bất kỳ entity nào họ từng tạo — dự án hiện đã tránh hard-delete
  `User` (dùng soft status qua `UserStatus`) nên dự kiến không phát sinh vấn đề, nhưng đây vẫn là
  một ràng buộc cứng mới, chưa từng tồn tại khi cột còn là chuỗi tự do.

## Alternatives considered

1. **Batch-resolve ở tầng response-mapping** (`userRepository.findAllById(ids)` một lần mỗi
   request, dựng `Map<UUID, String>` trong bộ nhớ): bị loại làm phương án chính — phải lặp lại
   cùng một logic gom-id-rồi-map ở 7+ `ServiceImpl` mà không có lợi ích gì về schema, và không cho
   query planner cơ hội gộp thành 1 round-trip như quan hệ thật làm được. Vẫn là một pattern dự
   phòng hợp lý cho một trường hợp đơn lẻ trong tương lai không đủ lý do để đổi schema.
2. **Denormalize snapshot tên lúc ghi** (cột `createdByName` ghi một lần, không lấy từ join sống):
   bị loại cho use case này — đây là các entity nghiệp vụ đang sống, "ai đang sở hữu cái này" nên
   phản ánh tên hiện tại của user, không phải snapshot lúc tạo. Denormalize snapshot vẫn là lựa
   chọn đúng cho một bảng audit-log thực sự bất biến nếu sau này có (không phải trường hợp ở đây).
3. **Giữ `AuditorAware<String>`/`<UUID>` và thêm 1 association read-only, không managed, song
   song** (shadow ID column + association `insertable=false, updatable=false` chỉ để đọc): bị
   loại — không giảm rủi ro hay bề mặt migration so với đổi thẳng kiểu field, vì `BaseEntity` vẫn
   dùng chung cho cả 11 entity theo cả 2 hướng, và để lại 2 đại diện cho cùng một sự thật cần giữ
   đồng bộ.
4. **Seed một `User` "system"/"anonymous" giả** cho các dòng do guest tạo thay vì FK nullable: bị
   loại — làm bẩn bảng `users`, cần thêm logic seed trong `DataInitializer`, trong khi `null` +
   fallback "System" FE đã có sẵn đã giải quyết được vấn đề.

## Test lock

Đã xác minh thủ công trên DB dev local (`spring.jpa.show-sql=true`) thay vì viết test tự động mới,
vì bộ test hiện tại chưa có hạ tầng Testcontainers/embedded DB để assert việc này:

- `POST /categories` (đã xác thực) lưu đúng `created_by`/`updated_by` là FK tới `User` của caller
  và **không phát sinh `SELECT` phụ** lúc ghi — xác nhận `EntityManager.getReference` trong
  `AuditingConfiguration.auditorProvider()` trả về proxy lazy, không phải entity đã load.
- Response của lần tạo đó trả cả `createdBy` (chuỗi UUID) lẫn `createdByName` mới
  (`"System Administrator"`) — xác nhận mapping null-safe trong
  `CategoryServiceImpl.mapToResponse`.
- `GET /categories` (endpoint list) chỉ phát sinh **1 query duy nhất** với
  `LEFT JOIN users cb1_0 ON cb1_0.id = c1_0.created_by` và
  `LEFT JOIN users ub2_0 ON ub2_0.id = c1_0.updated_by` — xác nhận
  `@EntityGraph(attributePaths = {"createdBy", "updatedBy"})` trên `CategoryRepository.findAll()`
  resolve đúng attribute kế thừa từ `@MappedSuperclass` `BaseEntity` (rủi ro nêu ở mục Consequences
  không xảy ra; không cần dùng tới phương án dự phòng `LEFT JOIN FETCH` JPQL ở đâu cả, kể cả
  `DocumentRepository.findAllActive` vốn đã dùng cách đó theo đúng quyết định).
- Một dòng có sẵn từ trước với `created_by = null` (dữ liệu cũ trước khi đổi) vẫn hiển thị đúng
  `createdBy: null, createdByName: null` trong cùng response list — xác nhận quyết định FK-nullable
  không seed system-user vẫn an toàn với dữ liệu cũ.
- `./mvnw test` — toàn bộ 28 test hiện có pass không đổi (không có test nào assert
  `createdBy`/`updatedBy` là kiểu `String`).

Chưa xác minh riêng từng entity còn lại (`AccessLevel`, `ChatModel`, `User`, `Role`, `Permission`,
`Department`, `Document`) ngoài việc compile thành công và lần kiểm tra sống ở trên — các entity
này dùng chung cơ chế `BaseEntity`/`AuditingConfiguration` và cùng pattern `@EntityGraph`/
`LEFT JOIN FETCH`, nên kiểm tra trên `Category` mang tính đại diện, không phải kiểm tra toàn bộ.

## References

- `src/main/java/com/unisage/backend/entity/BaseEntity.java`
- `src/main/java/com/unisage/backend/config/AuditingConfiguration.java`
- `src/main/java/com/unisage/backend/entity/Document.java` (tiền lệ `ingestedBy`)
- `src/main/java/com/unisage/backend/repository/UserRepository.java`
  (`findByIdWithPermissions`, tiền lệ fetch-join duy nhất trước đó)
- `src/main/java/com/unisage/backend/predefined/PredefinedPublicPaths.java` (các endpoint tạo
  entity cho phép truy cập không xác thực)
- `docs/adr/0001-document-file-upload-validation-policy.md` — format ADR được áp dụng lại ở đây
