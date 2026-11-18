# Noted Backend — Kiến trúc & Hướng dẫn triển khai

Backend cho ứng dụng ghi chú **Noted**, xây dựng theo Clean Architecture, phục vụ:
- Đăng nhập bằng Google (OAuth2 + JWT nội bộ + refresh token rotation)
- Lưu metadata note trong **MySQL**, nội dung file thật trên **filesystem của Ubuntu server**
- Đồng bộ nền (background job, "Debounce Sync") lên **Google Drive**
- Không cho phép 2 note active cùng tên trong 1 user — trùng tên ở `create`/`rename` thì **ghi đè** note đang có (giữ nguyên id/uuid); `duplicate` vẫn tự tránh trùng bằng hậu tố "(copy)"
- Hỗ trợ **offline-first**: client dùng IndexedDB làm buffer, đồng bộ lại khi server online trở lại

> Đây là nửa **backend** của dự án Noted. Nửa **frontend** (React/Vite/TS, local-first) nằm ở
> thư mục cùng cấp [`../web`](../web/README.md) — 2 repo git tách biệt nhưng là
> **một sản phẩm duy nhất**; xem README bên đó để biết cách chạy full-stack cùng nhau
> (port, biến môi trường `VITE_API_BASE_URL`/`ALLOWED_ORIGINS` phải khớp nhau giữa 2 phía).
>
> Stack: Java 17, Spring Boot 3.3.2, Spring Security OAuth2 Client, Spring Data JPA, MySQL 8,
> Flyway (migration `V1`..`V9` trong `src/main/resources/db/migration`).

## 1. Kiến trúc phân lớp (Clean Architecture)

```
controller/     ← REST API, chỉ nhận request/trả response, KHÔNG chứa business logic
service/        ← Business logic (interface + impl), transaction boundary
repository/     ← Spring Data JPA, chỉ thao tác DB
domain/entity/  ← JPA entity, model nghiệp vụ thuần
domain/enums/   ← SyncState và các enum nghiệp vụ khác
dto/            ← Request/Response, tách biệt hoàn toàn khỏi entity (không leak entity ra API)
security/       ← JWT provider, filter, OAuth2 success handler, RateLimitInterceptor
event/          ← ApplicationEvent + @TransactionalEventListener cho side-effect bất đồng bộ
exception/      ← Custom exception + GlobalExceptionHandler (@RestControllerAdvice)
util/           ← HashUtil (SHA-256/MD5), CryptoUtil (AES-256-GCM cho refresh token), TokenBucket
config/         ← SecurityConfig, CORS
```

Nguyên tắc dependency: `controller → service → repository`, không có chiều ngược lại;
`entity` không bao giờ được serialize thẳng ra JSON (luôn qua DTO).

## 2. Vì sao DB + Filesystem, không chỉ Drive

| | MySQL | Filesystem Ubuntu | Google Drive |
|---|---|---|---|
| Vai trò | Metadata, auth, chống trùng tên | Nội dung file thật (source of truth) | Bản sao đồng bộ / backup |
| Trên critical path khi lưu? | Có | Có | **Không** (chạy nền, `@Async` + `@Scheduled`) |

→ Người dùng gõ chữ không bao giờ bị chặn bởi tốc độ/rate-limit của Google Drive API.

## 3. Trùng tên file — GHI ĐÈ thay vì từ chối (đổi từ SEC-11)

Trước đây trùng tên bị từ chối 409 (`DuplicateFileNameException`/`DUPLICATE_FILE_NAME`), bảo vệ bằng
2 lớp (service check + `UNIQUE(user_id, display_name)` ở DB). Từ bản này, hành vi đổi hoàn toàn:

- **`createNote()`**: tên trùng với 1 note active đang có → **ghi đè nội dung note đó** (giữ nguyên
  `id`/`uuid`/`drive_file_id` của note cũ), không tạo note mới, không báo lỗi.
- **`rename()`**: đổi tên trùng với 1 note **khác** đang có (target) → **ghi đè nội dung target**
  bằng nội dung của note đang đổi tên (source), giữ nguyên `id`/`uuid` của target; note nguồn (source)
  bị xoá mềm (cùng luồng với `delete()`, dọn Drive ngay qua `NoteDeletedEvent`). Response trả về là
  của **target** (`id` khác `id` request ban đầu) — FE (`useRename.ts`) dựa vào `id` khác nhau này để
  biết đây là 1 lần merge, không phải đổi tên bình thường.
- **`duplicate()`**: **không đổi** — tên "X (copy)" là tự sinh (không phải người dùng gõ), vẫn tự tìm
  tên trống qua `resolveAvailableName()` thay vì ghi đè, tránh mất dữ liệu bất ngờ chỉ vì tên hệ thống tự đặt bị trùng.

Không còn `UNIQUE(user_id, display_name)` ở DB (đã drop từ `V4__drop_notes_unique_name.sql`, vì Drive
cho phép trùng tên) nên không có race-condition fallback riêng nữa — "ghi đè" tự nó là kết quả an toàn
kể cả khi 2 request tạo/đổi tên gần như đồng thời (ai `save()` sau sẽ thắng).

FE hiện cảnh báo (không chặn) khi phát hiện trùng tên trước khi gọi API, yêu cầu người dùng bấm
Lưu/Enter thêm 1 lần nữa để xác nhận ghi đè — xem `RenameModal.tsx`/`useRename.ts` ở `../web`.

## 4. Ghi file atomic (chống hỏng file khi server crash giữa chừng)

`LocalFileStorageServiceImpl.writeAtomic()`:
1. Ghi nội dung ra file tạm `{uuid}.txt.{nanoTime}.tmp`
2. `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` — thao tác atomic ở cấp OS, file gốc không bao giờ ở trạng thái ghi dở dang.

Tên file vật lý trên disk **luôn là UUID**, không dùng tên hiển thị do người dùng đặt → tránh path traversal, ký tự đặc biệt, và cho phép rename mà không cần đụng vào file thật.

## 5. Luồng Google OAuth2 — 2 giai đoạn tách biệt

| Giai đoạn | Mục đích | Scope |
|---|---|---|
| **Login** (`/oauth2/authorization/google`) | Xác thực danh tính, tạo `User` trong DB, sinh JWT nội bộ | `openid email profile` |
| **Connect Drive** (`GET /api/drive/connect` → `GET /api/drive/callback`) | Xin quyền `drive` (full) + `access_type=offline`, đổi `code` lấy `refresh_token` thật qua `GoogleTokenExchangeService`, tạo/tìm app folder ngay để phát hiện lỗi sớm | `drive` |

**Đổi từ `drive.file` sang `drive` (2026-08-18)** — lý do: `drive.file` là scope "per-file access", app **CHỈ thấy file do chính nó tạo ra**, không thấy được file người dùng tự tay tải lên thư mục "NotedApp" qua giao diện Drive web (xác nhận trực tiếp qua Drive API thật — file tự upload hoàn toàn vô hình với app dù đúng thư mục). Không dùng `drive.readonly` được vì app cần **ghi** (upload/update/xoá note). Người dùng **đã kết nối Drive từ trước** (lúc còn scope `drive.file`) phải **ngắt kết nối rồi kết nối lại** — `refresh_token` cũ không tự có quyền rộng hơn.

⚠️ **Hệ quả kỹ thuật quan trọng của việc đổi scope**: `drive.file` trước đây vô tình làm Drive Changes API (`incrementalPull()`) tự giới hạn trong phạm vi file của app (do Google chỉ trả về những gì app *thấy được*). Với scope `drive` đầy đủ, Changes API giờ báo **thay đổi của TOÀN BỘ Drive** người dùng — nếu không lọc, bất kỳ file `.txt` nào ở bất kỳ đâu trong Drive cũng có thể bị nhận nhầm thành note. Đã thêm lọc bắt buộc theo `parents` (thư mục cha) ngay trong `GoogleDriveServiceImpl.listChanges()` — chỉ giữ lại thay đổi của file thực sự nằm trong thư mục "NotedApp", trước khi đưa cho `pullOneFile()` xử lý.

`refresh_token` được mã hóa AES-256-GCM (`CryptoUtil`) trước khi lưu vào cột `users.drive_refresh_token_enc`.
`state` param của bước connect được ký JWT riêng (`JwtTokenProvider.generateDriveStateToken`) để chống CSRF,
vì `GET /api/drive/callback` là điều hướng trình duyệt thuần túy (không có header `Authorization`) nên phải
`permitAll` ở `SecurityConfig`.

## 6. JWT nội bộ + Refresh Token Rotation

- Access token JWT: sống ngắn (1 giờ).
- Refresh token: chuỗi ngẫu nhiên lưu DB (bảng `refresh_tokens`), **rotate** mỗi lần gọi
  `POST /api/auth/refresh` thành công (token cũ bị thu hồi, cấp token mới) — `RefreshTokenServiceImpl`.
- `POST /api/auth/logout` — thu hồi refresh token của phiên hiện tại.
- `POST /api/auth/logout-all` — thu hồi toàn bộ refresh token của user (mọi thiết bị).
- `RateLimitInterceptor` (token bucket theo `userId`, không theo IP) chặn spam
  `PATCH /api/notes/{id}/content` — bảo vệ server dù frontend có debounce sẵn, không được chỉ dựa vào FE.

## 7. Đồng bộ Drive — kiến trúc "Debounce Sync" (đã thay thế sync theo sự kiện tức thời)

**Lưu ý quan trọng nếu đọc code cũ hơn**: kiến trúc này đã đổi. Trước đây mỗi lần sửa note đều
publish `NoteContentChangedEvent` để kích hoạt sync gần như ngay lập tức. File `NoteContentChangedEvent.java`
vẫn còn tồn tại trong repo nhưng **không còn được publish/lắng nghe ở đâu nữa** — có thể coi là dead code
chờ dọn dẹp. Cơ chế hiện tại (`DriveSyncServiceImpl`):

Mỗi lần nội dung/tên note đổi, note chỉ được đánh dấu `dirty=true` + `syncState=PENDING_DRIVE`. Việc
**thật sự** đẩy lên Drive đi theo 3 kênh:

1. **Debounce định kỳ** (`runDebouncedSyncBatch`, `@Scheduled` mỗi `app.drive.sync-job-fixed-delay-ms`
   = 30s): tìm note `dirty=true` đã "yên tĩnh" (không sửa thêm) qua ngưỡng
   `app.drive.sync-debounce-idle-ms` (hiện cấu hình **5s** trong `application.yml`), dựa trên cột
   `updated_at` có sẵn. Thất bại quá `sync-retry-max-attempts` (5) lần thì ngừng thử.
2. **Flush thủ công** (`flushDirtyNotes`, qua `POST /api/drive/sync-all`): đẩy TOÀN BỘ note dirty của
   riêng user đó, bỏ qua ngưỡng idle, rồi pull thay đổi từ Drive về. Frontend gọi endpoint này ở
   3 chỗ: nút "Đồng bộ ngay", tự động 2.5s sau mỗi lần lưu (`useDriveSync.ts`), và lúc đóng tab.
3. **Flush lúc đóng tab**: frontend gọi CÙNG endpoint `POST /api/drive/sync-all` qua `fetch(..., {keepalive:true})` trong sự kiện `pagehide`/`visibilitychange`.

Vì có nhiều đường cùng gọi `sync-all`, `DriveSyncServiceImpl` giữ khoá `pullingUserIds` để chặn
2 lượt pull chồng nhau cho cùng 1 user (trước đây 2 lượt cùng thấy "chưa có note nào giữ
`drive_file_id` này" nên tạo 2 note trùng). `V9__unique_drive_file_id.sql` thêm `UNIQUE` cho
`drive_file_id` làm lưới an toàn ở tầng DB.

**Đồng bộ đổi tên 2 chiều.** So md5 chỉ nói về nội dung, nên giờ so cả **nội dung lẫn tên**:
- Đẩy lên: khớp cả hai → không gọi gì; chỉ khác tên → `renameFile()` (chỉ sửa metadata);
  khác nội dung → `updateFile()` kèm tên mới. `getFileMeta()` lấy md5 + name trong 1 lần gọi.
- Kéo về (`pullOneFile()`): tên trên Drive khác thì áp tên đó về note, **trừ khi** note đang dirty
  (giữ tên local chưa kịp đẩy). Không có gì đổi thì không ghi DB.

**Chỉ dùng Drive của chính tài khoản đó (2026-08-22).** Với scope `drive`, app nhìn thấy cả thư
mục "NotedApp" người khác chia sẻ cho mình — từng xảy ra chuyện tài khoản A ghi note vào thư mục
của tài khoản B. Bốn chốt chặn:
- `findFolderByName()` thêm điều kiện `'me' in owners`.
- `ensureAppFolder()` xác minh id thư mục đã lưu bằng `isFolderOwnedByMe()`; phải đổi thư mục thì
  gỡ `drive_file_id` của mọi note để upload lại vào đúng chỗ.
- `listFilesInFolder()` + guard `ownedByMe` trong `pullOneFile()`.
- `isFileUsable()` trước `updateFile()`: kiểm trashed + ownedByMe + nằm trong app-folder trong 1
  lần gọi API (thay cho `isFileTrashed()` cũ). Sai điều nào thì bỏ `driveFileId`, upload lại.

Truy vấn note theo file Drive luôn lọc kèm user (`findAllByUserIdAndDriveFileId`).
`GET /api/drive/status` trả thêm `folderName` để UI hiện tên thư mục thật.

Ngoại lệ duy nhất còn xử lý **ngay lập tức** (không debounce): xóa note → `NoteDeletedEvent` +
`NoteSyncEventListener` (`@TransactionalEventListener(phase = AFTER_COMMIT)`) xóa file tương ứng trên
Drive ngay, tránh file "mồ côi" tồn tại vô ích. Vì sao `AFTER_COMMIT`: các thao tác Drive là `@Async`
(chạy thread riêng gần như ngay lập tức) — nếu gọi trước khi transaction gốc commit, thread nền có thể
đọc DB qua connection khác và không thấy thay đổi vừa lưu.

**Tối ưu `md5Checksum`**: trước khi `updateFile()` cho note đã có `driveFileId`, so sánh MD5 nội dung
local với `md5Checksum` Google đã tính sẵn — khớp thì bỏ qua update (note bị sửa rồi sửa lại về y hệt
cũ trước khi kịp debounce).

**Nguyên tắc nghiệp vụ chốt (SEC-15)**: Google Drive KHÔNG bắt buộc tên file duy nhất trong 1 folder —
hệ thống này **cho phép trùng tên trên Drive**. Định danh duy nhất cần quan tâm CHỈ LÀ `driveFileId`
(do Google cấp phát) — `uploadFile()` luôn tạo file mới (không tìm theo tên), `updateFile()` luôn định
danh bằng `fileId` có sẵn (không bao giờ tìm lại theo tên).

**FIX quan trọng (đã sửa): self-invocation làm `@Async` mất tác dụng.** `runDebouncedSyncBatch()`/
`flushDirtyNotes()` trước đây gọi thẳng `syncNote(...)` (self-invocation, bỏ qua Spring AOP proxy) —
`@Async`/`@Transactional` riêng của `syncNote()` **không bao giờ có hiệu lực thật**, nó chạy đồng bộ
trên transaction `readOnly=true` sẵn có của hàm gọi. Hibernate coi entity trong session read-only là
read-only (bỏ qua dirty-checking khi flush) → `driveFileId`/`dirty`/`syncState` **không bao giờ được
ghi xuống DB** dù Drive API upload thật sự thành công → note bị upload lại thành file MỚI mỗi chu kỳ
debounce, vĩnh viễn (rác file trùng tích luỹ vô hạn trên Drive). Fix: tiêm 1 tham chiếu chính bean này
qua proxy (`@Lazy DriveSyncService self`), gọi `self.syncNote(...)` thay vì `syncNote(...)`.

**Incremental sync — Drive Changes API (đã implement, thay full-listing mỗi lần pull).**
`pullFromDrive()` giờ tách 2 nhánh dựa vào `users.drive_changes_page_token`
(`V8__add_drive_changes_page_token.sql`, NULL = chưa từng pull):
- **Bootstrap** (lần đầu, token NULL): vẫn quét toàn bộ folder như cũ (`listFilesInFolder`) — đảm bảo
  không bỏ sót file có sẵn từ trước khi bắt đầu theo dõi incremental — rồi gọi `getStartPageToken()`
  NGAY SAU ĐÓ và lưu lại. Thứ tự quan trọng: full-listing trước, lấy token sau, để thay đổi xảy ra giữa
  2 bước không bị lọt khỏi cả 2 nguồn.
- **Incremental** (đã có token): chỉ hỏi Drive Changes API "gì đã đổi từ token này" (`listChanges()`,
  tự lặp qua nhiều trang nếu có `nextPageToken`), xử lý từng file đổi qua đúng `pullOneFile()` như cũ, rồi
  lưu lại `newStartPageToken` cho lần sau. File bị Drive báo xóa/mất quyền **chưa** tự động xóa note
  tương ứng ở Noted (giữ nguyên tính nhất quán với hành vi full-listing cũ — vốn cũng không bao giờ tự
  xóa note khi file biến mất khỏi danh sách) — chỉ log lại.
- `disconnect()` reset token về NULL — lần connect lại sau phải bootstrap lại từ đầu.
- ⚠️ **Lưu ý khi test**: đây là phần duy nhất trong các thay đổi gần đây gọi trực tiếp API Google Drive
  Changes (`drive.changes().getStartPageToken()`/`drive.changes().list()`) — đã compile-check thành công
  đối chiếu đúng thư viện `google-api-services-drive` thật trên classpath, nhưng **chưa được test chức
  năng với tài khoản Drive thật** (không có credential trong môi trường phát triển này) — nên tự kiểm
  tra kỹ luồng "Đồng bộ ngay" nhiều lần liên tiếp (đặc biệt lần thứ 2 trở đi, khi đã có token) trước khi
  tin tưởng hoàn toàn.

**Fix: note bị "kẹt" sync mãi mãi khi file gốc trên Drive bị xoá trực tiếp (đã sửa, 2 phần).**

Phần 1 — `updateFile()` gọi tới 1 `driveFileId` đã bị xoá trên Drive luôn ném lỗi 404, nhưng code cũ coi
đó là lỗi *tạm thời*, tăng `driveSyncAttempts` rồi retry với **đúng `driveFileId` cũ** ở lần sau → thất
bại y hệt mãi mãi. Giờ `GoogleDriveServiceImpl.updateFile()` phát hiện riêng lỗi 404
(`GoogleJsonResponseException.getStatusCode()`), ném `DriveFileNotFoundException` — `DriveSyncServiceImpl`
bắt riêng exception này: xoá `driveFileId` cũ, **upload lại ngay trong cùng 1 lần gọi** thành file hoàn
toàn mới.

Phần 2 (phát hiện thêm khi test thực tế) — Phần 1 **chỉ** có tác dụng nếu note đang `dirty=true` (đang có
gì đó cần push). Note **đã sync xong từ trước** (`dirty=false`) mà bị xoá file trực tiếp trên Drive thì
`flushDirtyNotes()` (chạy TRƯỚC `pullFromDrive()` trong cùng 1 lần "Đồng bộ ngay") bỏ qua hoàn toàn vì
note không hề dirty — không bao giờ chạm tới code fix ở Phần 1. `incrementalPull()`/`bootstrapPull()`
trước đây chỉ *log lại* danh sách file bị xoá trên Drive (`removedFileIds`), không hành động gì. Giờ 2
hàm này chủ động: tìm note theo `driveFileId` bị xoá, xoá `driveFileId` cũ + đánh dấu `dirty=true`, rồi
gọi `self.syncNote()` ngay lập tức — note tự động được tạo lại trên Drive **trong cùng 1 lần** "Đồng bộ
ngay", không cần bấm lần 2.

Phần 3 (xác nhận trực tiếp qua Drive API thật, không chỉ log/DB) — hoá ra 2 note bị "kẹt" không phải do
xoá vĩnh viễn, mà do bấm "Xoá" trên giao diện Drive (mặc định = **chuyển vào Thùng rác**, không xoá hẳn).
File trong thùng rác **KHÔNG** trả 404 — Drive API vẫn cho đọc/ghi nội dung bình thường, chỉ ẩn khỏi thư
mục — nên Phần 1/2 (dựa vào 404/"removed") không phát hiện được. Thêm `GoogleDriveService.isFileTrashed()`,
kiểm tra riêng TRƯỚC khi `updateFile()` (đường push) và trong `pullOneFile()` (đường pull, tự phục hồi dù
note không dirty, vì Changes API báo hành động trash là `"changed"` chứ không phải `"removed"`) — coi
trashed tương đương "không dùng được nữa", tái sử dụng đúng cơ chế hồi phục ở Phần 1/2.

Phần 4 — Drive Changes API có thể báo cả thay đổi của **chính app-folder "NotedApp"**
(vì app có quyền thấy mọi file/folder chính nó tạo ra, kể cả folder). Nếu không lọc, `pullOneFile()` cố
gọi `downloadFileContent()` lên 1 folder → Drive trả `403 fileNotDownloadable` (folder/Google Docs không
có nội dung nhị phân để tải qua `alt=media`). Lọc `mimeType='text/plain'` ở cả tầng query
(`listFilesInFolder()`) lẫn 1 guard đầu `pullOneFile()` (chặn luôn item lạc vào từ Changes API, vốn không
lọc được theo mimeType ở tầng query như `files.list()`).

Có test riêng cho cả 4 phần (`DriveSyncServiceImplTest`, 7 test) — lần đầu tiên `DriveSyncServiceImpl` có
unit test (trước đây là gap đã biết, cần mock Google Drive API).

**Mặt trận B — conflict Drive-vs-local-dirty (đã implement).** Trước đây `pullOneFile()` chỉ đơn giản
bỏ qua pull khi note đang `dirty` (an toàn nhưng "mù" — không biết Drive có thật sự đổi độc lập hay
không). Giờ dùng cột `drive_synced_content_hash` (baseline MD5 tại lần sync thành công gần nhất,
`V6__add_drive_synced_content_hash.sql`) để so 3 chiều: nếu MD5 hiện tại của Drive vẫn khớp baseline,
không có gì mới để mất — cứ để push bình thường ở lần debounce kế tiếp; nếu Drive đã khác baseline
(nghĩa là Drive đổi độc lập trong lúc local cũng đang dirty) → **conflict thật** → giữ local (đang
active) làm bản chính tiếp tục push, tách bản Drive thành 1 note riêng qua `NoteService.createConflictCopy()`
(tên có hậu tố `"(xung đột dd/MM HH:mm)"`, cờ `is_conflict_copy=TRUE`). Đây là **nơi duy nhất** còn
tạo bản xung đột: sửa trực tiếp trên Drive là việc người dùng không tự biết nếu không được báo,
khác với trường hợp nhiều trình duyệt của chính họ (mục 8).

## 7b. Chọn nhiều để xoá — CÓ; Chọn nhiều để sync — KHÔNG (quyết định nghiệp vụ)

- **Bulk delete** (`POST /api/notes/bulk-delete`): xử lý trong 1 transaction duy nhất, chỉ xoá note
  thực sự thuộc sở hữu `userId` (id lạ bị loại âm thầm, không làm hỏng cả thao tác). Trả về
  `BulkDeleteResponse` gồm `requestedCount`/`deletedCount` để FE báo chính xác cho người dùng.
- **Không có** "chọn nhiều để sync": đồng bộ Drive là cơ chế tự động cho mọi note, không phải thao
  tác người dùng phải chủ động chọn — tránh rủi ro *quên chọn* → mất dữ liệu.

## 8. Offline-first reconciliation (`/api/sync/batch`)

Khi client mất kết nối tới server, dữ liệu vẫn giữ trong **IndexedDB** ở trình duyệt (Local Mode).
Khi có mạng lại (hoặc sau khi đăng nhập lần đầu, xem `migrateLocalNotesToServer` ở frontend), client
gọi 1 lần `POST /api/sync/batch` gửi toàn bộ note đang chờ.

**Phát hiện conflict bằng `version` (số nguyên tăng dần), không còn dùng timestamp** — đổi từ
`localUpdatedAtEpochMs`/`updatedAt` (dễ lệch do đồng hồ hệ thống khác nhau giữa các lần request) sang
Optimistic Concurrency Control kiểu `version`: cột `notes.version` (`V7__add_note_version.sql`) tăng 1 ở
**mọi** lần ghi nội dung/tên thành công (`updateContent`, `rename`, nhánh ghi đè của `createNote`/`rename`-merge).
Client giữ `version` mình biết được (`baseVersion`), gửi kèm mỗi item trong `SyncBatchItem`. Server so
`item.baseVersion()` với `existing.getVersion()` — lệch nhau nghĩa là ai đó đã ghi trước, kể cả cùng
millisecond.

**Lưu ý quan trọng: `PATCH /api/notes/{id}/content` (autosave đang gõ sống) KHÔNG áp dụng OCC này** —
route đó **luôn ghi đè**, chỉ tăng `version` để phục vụ so sánh ở nơi khác, không tự kiểm tra/từ chối gì
cả ("phương án 2" đã chọn: ưu tiên phiên đang gõ, tránh UX phức tạp cho case hiếm gặp 2 tab cùng
account autosave trùng lúc). OCC **chỉ thực sự có hiệu lực** ở `/api/sync/batch` — đúng nơi cần bảo vệ
nhất (client offline quay lại, reconcile 1 lần).

**Chiến lược conflict — GHI ĐÈ, chỉ giữ 1 file gốc (đổi từ 2026-09-15):** nếu `baseVersion` client
cầm khác `version` hiện tại của server, server vẫn **ghi đè** bằng bản trong hàng đợi và trả status
`synced_overwritten` (thay vì `synced`) để client biết mà kéo lại nội dung. Các status có thể trả về:
`created` (kèm `serverId`), `synced`, `synced_overwritten`, `not_found_on_server`, `error` (kèm `message`).

Trước đây trường hợp này tách bản local thành note `"(xung đột ...)"` và trả `conflict_kept_both`.
Đã bỏ vì người dùng mở cùng 1 note ở 3-4 trình duyệt bị đẻ ra nhiều file xung đột, trong khi đường
lưu bình thường (`updateContent`) vốn đã là "ai lưu sau thắng".

⚠️ Việc ghi đè này **chỉ an toàn nhờ** cơ chế làm mới ở frontend (`App.tsx`): quay lại tab thì so
`contentVersion` với server, kéo bản mới về (hoặc hỏi nếu đang có chữ chưa lưu) trước khi người dùng
gõ tiếp. Bỏ cơ chế đó thì phải xem lại chỗ này.

## 9. Danh sách API Endpoints (đầy đủ, đối chiếu trực tiếp với controller)

```
# Auth
GET    /api/auth/me
POST   /api/auth/refresh                    (rotate refresh token, đọc cookie httpOnly hoặc body)
POST   /api/auth/logout                     (thu hồi phiên hiện tại)
POST   /api/auth/logout-all                 (thu hồi mọi thiết bị)
GET    /oauth2/authorization/google         (Spring Security tự cung cấp - bước Login)

# Notes
GET    /api/notes
GET    /api/notes/{id}
POST   /api/notes
PATCH  /api/notes/{id}/content              (rate-limit theo user, xem RateLimitInterceptor)
PATCH  /api/notes/{id}/rename
POST   /api/notes/{id}/duplicate
DELETE /api/notes/{id}
POST   /api/notes/bulk-delete
GET    /api/notes/check-name?name=xxx
GET    /api/notes/{id}/download

# Drive
GET    /api/drive/status
GET    /api/drive/connect                   (bước Connect Drive - trả authUrl)
GET    /api/drive/callback                  (Google redirect về đây - permitAll, không cần JWT)
POST   /api/drive/sync-all                  (flush thủ công + khi đóng tab)
DELETE /api/drive/disconnect

# Offline sync
POST   /api/sync/batch

# Soan thao cong tac (WebSocket, xem muc 12)
WS     /ws/notes/{noteId}?generation={yjsGeneration}
       (token qua Sec-WebSocket-Protocol: "noted.collab.v1", "auth.<jwt>")
```

`NoteSummaryResponse`/`NoteDetailResponse` có thêm `yjsGeneration`.

## 10. Chạy local

```bash
# MySQL KHÔNG do compose file này tạo/quản lý - giả định container infra-mysql
# đã chạy sẵn và đã nối vào shared-db-network:
docker network create shared-db-network              # nếu chưa có
docker network connect shared-db-network infra-mysql # nếu chưa nối vào
# (hoặc đặt DB_HOST=<IP host MySQL> trong .env để đi thẳng qua IP)

cp .env.example .env
# Điền GOOGLE_CLIENT_ID/SECRET, JWT_SECRET, CRYPTO_SECRET_KEY, DB_NAME/DB_USER/DB_PASSWORD
openssl rand -base64 32   # dùng để sinh JWT_SECRET và CRYPTO_SECRET_KEY

docker compose up -d --build
```

Dự án theo quy chuẩn deploy mã `82`: container `noted-api` (port `127.0.0.1:8210`) và
`noted-web` (port `127.0.0.1:8200`), mạng nội bộ `noted-net` (compose của api tạo, nên chạy api
trước web). `docker-compose.yml` tự ghép `DB_URL` từ `DB_HOST` (mặc định `infra-mysql`, phân giải
qua DNS Docker trên network ngoài `shared-db-network`) và `DB_NAME` (mặc định `note_text`) —
`application.yml` chỉ đọc `DB_URL`/`DB_USER`/`DB_PASSWORD`. Frontend build ra `dist/` và serve qua
nginx ở port `8200` (xem [`web`](../web/README.md)). Thư mục `/data/noted/notes` trên host phải cho
uid 1001 ghi được (`chown -R 1001:1001 /data/noted/notes`) vì container chạy non-root.

Chạy không qua Docker (IDE/Maven): đặt các biến trong `.env` vào môi trường rồi
`mvn spring-boot:run`. `SPRING_PROFILES_ACTIVE=dev` nạp thêm `application-dev.yml` (log SQL,
log security DEBUG); `prod` nạp `application-prod.yml` (log gọn).

⚠️ Nếu thiếu biến DB, `application.yml` và `docker-compose.yml` rơi về giá trị mặc định ghi cứng
(IP host và mật khẩu DB) thay vì báo lỗi — luôn kiểm tra `.env` đủ biến.

Production qua Cloudflare Tunnel (ingress của `cloudflared`):

```yaml
  - hostname: noted.thongtinchinhhieu.site
    service: http://localhost:8200      # noted-web
  - hostname: noted-api.thongtinchinhhieu.site
    service: http://localhost:8210      # noted-api
```

Từ 2026-10-18 frontend **gọi thẳng** backend qua `https://noted-api.thongtinchinhhieu.site` (build web
với `VITE_API_BASE_URL` đó), không còn đi qua nginx proxy. Hai domain cùng site nên cookie
`refresh_token` vẫn gửi được, nhưng là **cross-origin** nên cần CORS có credentials. `.env` production:

```
FRONTEND_REDIRECT_URL=https://noted.thongtinchinhhieu.site/oauth/callback
ALLOWED_ORIGINS=https://noted.thongtinchinhhieu.site
DRIVE_FRONTEND_CALLBACK_URL=https://noted.thongtinchinhhieu.site/drive/callback
DRIVE_REDIRECT_URI=https://noted-api.thongtinchinhhieu.site/api/drive/callback
REFRESH_COOKIE_SECURE=true
SPRING_PROFILES_ACTIVE=prod
```

"Authorized redirect URIs" trong Google Cloud Console phải có **cả 2** callback trên domain api:
- `https://noted-api.thongtinchinhhieu.site/login/oauth2/code/google`
- `https://noted-api.thongtinchinhhieu.site/api/drive/callback`

Đăng nhập thất bại: `SecurityConfig` chuyển về `FRONTEND_REDIRECT_URL?error=login_failed` (backend
không có trang `/login`) và chỉ log mã lỗi OAuth đã biết + tên class nguyên nhân, không log message
có thể chứa token/secret.

Chạy frontend bằng `pnpm dev` (cổng 8445): `.env` của api dùng `ALLOWED_ORIGINS=http://localhost:8445`,
`FRONTEND_REDIRECT_URL=http://localhost:8445/oauth/callback`,
`DRIVE_FRONTEND_CALLBACK_URL=http://localhost:8445/drive/callback`,
`DRIVE_REDIRECT_URI=http://localhost:8210/api/drive/callback`.

## 11. Trạng thái thật hiện tại (đối chiếu code, không phải checklist dự đoán)

Soạn thảo cộng tác (mục 12) — có test:
- ✅ Giao thức lib0/Yjs bằng Java — `YjsProtocolTest`. Byte của bản khởi tạo được đối chiếu với
  `Y.encodeStateAsUpdate()` của yjs 13.6.33 thật.
- ✅ Handler relay: xác thực, gửi log, nén log, relay, awareness, snapshot, ghép mảnh, lỗi →
  `NoteCollabWebSocketHandlerTest`.
- ⚠️ **Chưa chạy đầu-cuối với MySQL + Spring Boot thật** (máy phát triển không có Docker/MySQL
  riêng, migration V10 chưa áp lên DB nào). Thuật toán relay đã được kiểm bằng mô phỏng Node với
  client `y-websocket` thật (10 kịch bản: hội tụ sau offline, undo chọn lọc, nén log, seed trùng,
  giữ kết nối khi ngồi 1 mình). Cần thử trên môi trường staging trước khi lên production.
- ⚠️ Redis Pub/Sub (`RedisRoomBroadcaster`) mới chỉ compile, chưa chạy với `infra-redis` thật.

Phần còn lại:

Đã implement đầy đủ và có test:
- ✅ `GoogleTokenExchangeService` (đổi `code` → `access_token`/`refresh_token`) — `GoogleTokenExchangeServiceImplTest`
- ✅ Refresh token + rotation cho JWT nội bộ — `RefreshTokenServiceImplTest`
- ✅ Rate limiting cho `PATCH /content` — `RateLimitInterceptor` + `TokenBucket`
- ✅ Trùng tên → ghi đè ở `create`/`rename` (giữ nguyên `duplicate` tự tránh trùng) — `NoteServiceImplTest`
- ✅ Ghi file atomic — `LocalFileStorageServiceImplTest`
- ✅ Offline-batch lệch `version` → ghi đè + `synced_overwritten` (mục 8); conflict Drive-vs-local-dirty vẫn "giữ cả 2 bản" (mặt trận B, mục 7)
- ✅ Đồng bộ Drive: file bị xoá/trashed, folder lẫn vào, đổi tên 2 chiều, chặn Drive của tài khoản khác, khoá chống pull chồng — `DriveSyncServiceImplTest`, `GoogleDriveServiceImplTest`, `GoogleDriveServiceImplDownloadTest`
- ✅ Fix self-invocation `syncNote()` (`@Lazy self` reference) — không còn rác file trùng trên Drive
- ✅ Conflict detection bằng `version` (OCC) thay `updatedAt`/`localUpdatedAtEpochMs` cho `/api/sync/batch` — `NoteServiceImplTest`
- ✅ `NotePurgeServiceImpl.purgeExpiredNotes()` — xóa vĩnh viễn thật (file vật lý best-effort, gọi lại
  `deleteFromDrive()` làm lưới an toàn dự phòng, rồi mới xóa DB record) — `NotePurgeServiceImplTest`
- ✅ `NoteSyncEventListener.onNoteDeleted` giờ `@Async` — xoá/bulk-delete không còn chờ round-trip Drive API trước khi trả response
- ✅ `SyncBatchItem` có validation (`@NotBlank`/`@NotNull` + `@Valid` ở `SyncController`) — item malformed bị từ chối 400 rõ ràng thay vì lọt qua
- ⚠️ Incremental sync (Drive Changes API) — implement xong, compile-check qua thư viện thật, **chưa test được với Drive thật** trong môi trường này (xem mục 7)

Còn thiếu / dở dang thật sự (đã đọc trực tiếp code để xác nhận, không suy đoán):
- 🧹 `NoteContentChangedEvent`/kiến trúc sync-theo-sự-kiện cũ là dead code, có thể xóa hẳn (xem mục 7).
- 🧪 Chưa có unit test cho `RateLimitInterceptor`/`TokenBucket`/`JwtTokenProvider`.
- 🔐 HTTPS hiện do Cloudflare Tunnel đảm nhận (app chạy HTTP sau tunnel). Chưa có CI/CD,
  integration test (Testcontainers + MockMvc).
- 🗃️ Bảng `login_histories` (entity `LoginHistory`): file migration tên
  `V4_20260524_create_login_histories.sql` **thiếu dấu `__`** theo quy ước Flyway
  (`V<version>__<mô tả>.sql`) — cần kiểm tra Flyway có thực sự chạy file này không.
- 🔑 Giá trị mặc định ghi cứng IP và mật khẩu DB trong `application.yml`/`docker-compose.yml` (xem mục 10).

## 12. Soạn thảo cộng tác thời gian thực (Yjs CRDT qua WebSocket)

Ở Server Mode, mỗi phím gõ được gửi dưới dạng **Yjs update bytes nhị phân** qua WebSocket
`/ws/notes/{noteId}`, **không** qua REST. Phạm vi hiện tại: **một người dùng, nhiều thiết bị/tab**.
Chỉ chủ note mới vào được phòng; chưa có chia sẻ note cho người khác.

### Vai trò của backend: trạm phát sóng, không giải mã CRDT

`NoteCollabWebSocketHandler` (`BinaryWebSocketHandler`) chỉ đọc "phong bì" của message
(loại + bước sync) theo giao thức y-websocket. Nó lưu nguyên văn update vào bảng `note_yjs_updates`
(log append-only) rồi phát nguyên văn cho các kết nối khác trong cùng phòng (1 phòng = 1 note).

| Loại | Hướng | Xử lý |
|---|---|---|
| `0/1` sync step2 | client → server | Lần đầu sau khi kết nối: **nén log**. Các lần sau: ghi thêm. Relay dưới dạng update |
| `0/2` sync update | client → server | Ghi thêm vào log, relay cho người khác (không gửi lại người gửi) |
| `0/0` sync step1 | client → server | Bỏ qua — server đã gửi toàn bộ log lúc kết nối |
| `1` awareness | hai chiều | Relay cho **cả người gửi** (nhịp tim chống watchdog 30s của y-websocket). Nhớ clientID để báo "rời phòng" khi đóng |
| `3` query awareness | server → client | Gửi cho người đang trong phòng khi có người mới vào |
| `100` text snapshot | client → server | `NoteService.saveCollabSnapshot()` ghi file `.txt`, đánh dấu dirty → Debounce Sync lên Drive như cũ |
| `101` snapshot ack | server → client | Trạng thái + `version` mới của note |

### Trình tự khi một client kết nối

1. Xác thực JWT (trong `Sec-WebSocket-Protocol`, không đặt trong URL để không lọt vào access log),
   kiểm tra note thuộc user, đúng `generation`. Origin được kiểm theo `ALLOWED_ORIGINS`.
2. **Vào phòng trước**, rồi mới tải log. Nhờ vậy update của người khác đến trong lúc đang tải vẫn
   tới được client này.
3. Gửi toàn bộ log: dòng đầu dưới dạng step2 (để y-websocket bật cờ `synced`), các dòng sau là update.
4. Gửi step1 với **state vector rỗng**. Client trả lời bằng step2 chứa **toàn bộ** trạng thái của nó,
   gồm cả những gì gõ lúc offline. Server xóa **đúng danh sách id** đã gửi ở bước 3 rồi thay bằng
   1 dòng duy nhất. Không xóa theo khoảng `id <= max`, vì với auto-increment, dòng id nhỏ có thể
   commit sau dòng id lớn.

Log rỗng (note cũ có từ trước khi có tính năng này, hoặc vừa đổi đời tài liệu): server tự mã hóa
**một** update Yjs chèn nội dung file `.txt` vào `Y.Text("content")`. Đây là nội dung CRDT duy nhất
backend tự tạo (`YjsProtocol.encodeTextSeedUpdate`). `clientId` tất định theo (note, đời), nên 2 kết
nối khởi tạo cùng lúc sinh ra 2 update giống hệt nhau và Yjs tự loại trùng.

### "Đời" tài liệu (`notes.yjs_generation`)

Khi nội dung bị ghi đè **từ ngoài** luồng cộng tác, `YjsDocumentService.resetDocument()` tăng đời,
xóa log và phát `NoteContentReplacedEvent`. Sau commit, phòng bị đóng với mã `4409`, client tải lại
note rồi mở phòng theo đời mới. Những đường ghi đè từ ngoài gồm:
- `PATCH /api/notes/{id}/content` (REST cũ);
- tạo note hoặc đổi tên trùng tên (ghi đè);
- kéo nội dung mới từ Google Drive (`pullOneFile`).

Snapshot từ chính luồng Yjs **không** tăng đời. Nếu đời cũ còn phần gõ chưa được server xác nhận,
frontend giữ lại thành note riêng `"... (bản chưa lưu dd/MM HH:mm)"`.

Mã đóng kết nối (`CollabCloseStatus`): `4400` URL sai, `4401` token hết hạn (frontend refresh rồi
kết nối lại), `4404` không có note, `4409` đời tài liệu đổi, `4410` note bị xóa. Lỗi lưu trữ →
`1011`: client tự kết nối lại và đồng bộ bù, không nuốt lỗi.

### Nhiều instance: Redis Pub/Sub

`app.collab.redis.enabled=true` (`COLLAB_REDIS_ENABLED`) bật `RedisRoomBroadcaster`. Mỗi message
được gửi cho kết nối cục bộ trước, rồi publish lên kênh `noted.collab.room` của `infra-redis`.
Instance khác bỏ qua message của chính mình (so `instanceId`) và gửi tiếp cho kết nối của nó. Redis
lỗi thì kết nối ở instance khác chỉ bị trễ: mọi update đã được lưu MySQL trước khi phát, nên lần
kết nối lại sẽ bù đủ. Mặc định `false` (1 instance, `LocalRoomBroadcaster`).

### Giới hạn đã biết

- Drive → app vẫn là **ghi đè cả tài liệu** (đổi đời), chưa hợp nhất từng phần. Thiết bị đang offline
  ở đời cũ mất khả năng merge, phần chưa lưu được giữ thành note riêng như trên. Phần này sẽ được
  thay ở Module 3 (Drive push notification → Yjs update qua WebSocket).
- Mỗi update gõ phím là 1 lần `INSERT`. Log được nén mỗi khi có thiết bị kết nối, nhưng một phiên
  gõ rất dài trên 1 thiết bị sẽ tích nhiều dòng cho tới lần kết nối kế tiếp.
- Khóa `syncingNoteIds`/`pullingUserIds` của Drive sync vẫn là khóa trong bộ nhớ (1 instance).
