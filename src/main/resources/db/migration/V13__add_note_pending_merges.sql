-- ============================================================
-- V13__add_note_pending_merges.sql
--
-- Noi dung den tu NGOAI luong soan thao cong tac (Google Drive, REST PATCH
-- /content, ghi de khi trung ten) KHONG con dat lai tai lieu Yjs hay tach ban
-- sao "(xung dot ...)" nua. Server giu lai 2 ban o day:
--   base_content     = noi dung tai lieu truoc khi bi thay (ban goc chung)
--   incoming_content = noi dung moi tu nguon ngoai
-- va gui cho DUNG 1 trinh duyet dang mo note. Trinh duyet hop nhat 3 chieu
-- (diff3 theo dong) vao Y.Text hien tai roi bao lai -> dong nay bi xoa.
-- Doan bi sua o ca 2 noi duoc giu ca 2 ban, kep giua dau <<<<<<< ======= >>>>>>>.
--
-- Moi note toi da 1 dong: nhieu thay doi ngoai lien tiep -> giu base CU NHAT,
-- incoming MOI NHAT (diff3 tu loai phan 2 ben cung sua giong nhau).
-- ============================================================
CREATE TABLE note_pending_merges (
    id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    note_id          BIGINT      NOT NULL,
    base_content     MEDIUMTEXT  NOT NULL,
    incoming_content MEDIUMTEXT  NOT NULL,
    -- DRIVE | EXTERNAL - chi de hien nhan cho doan xung dot (">>>>>>> Google Drive")
    source           VARCHAR(20) NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    CONSTRAINT uk_note_pending_merges_note UNIQUE (note_id),
    CONSTRAINT fk_note_pending_merges_note FOREIGN KEY (note_id) REFERENCES notes (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
