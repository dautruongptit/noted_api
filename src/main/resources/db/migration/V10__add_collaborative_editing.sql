-- ============================================================
-- V10__add_collaborative_editing.sql
--
-- Soan thao cong tac thoi gian thuc (Yjs CRDT qua WebSocket).
--
-- Backend KHONG giai ma CRDT: chi luu nguyen van cac "Yjs update bytes" client
-- gui len (append-only), phat lai cho client ket noi sau. Log duoc "nen" moi
-- khi 1 client ket noi: client gui lai toan bo trang thai da hop nhat, server
-- xoa dung nhung dong da gui cho client do va thay bang 1 dong duy nhat.
-- ============================================================

-- "Doi" tai lieu Yjs, tang khi noi dung bi ghi de tu ngoai luong cong tac
-- (xem Note.yjsGeneration / YjsDocumentService.resetDocument).
ALTER TABLE notes ADD COLUMN yjs_generation INT NOT NULL DEFAULT 0;

CREATE TABLE note_yjs_updates (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    note_id     BIGINT      NOT NULL,
    generation  INT         NOT NULL,
    -- 1 Yjs update (encodeStateAsUpdate/V1). MEDIUMBLOB = toi da 16MB, du cho
    -- trang thai day du cua 1 note (gioi han noi dung 5MB + metadata CRDT).
    update_data MEDIUMBLOB  NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    CONSTRAINT fk_note_yjs_updates_note FOREIGN KEY (note_id) REFERENCES notes (id) ON DELETE CASCADE,
    INDEX idx_note_yjs_updates_note_generation (note_id, generation, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
