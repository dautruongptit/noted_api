package com.noted.backend.service;

import com.noted.backend.domain.entity.Note;

import java.util.Collection;
import java.util.List;

/**
 * Luu tru tai lieu Yjs cua note duoi dang log cac update nhi phan.
 *
 * Nguyen tac: backend KHONG giai ma CRDT. No chi:
 *  - luu nguyen van update client gui len (append-only),
 *  - phat lai toan bo log cho client vua ket noi,
 *  - "nen" log khi client gui lai trang thai day du da hop nhat,
 *  - tu ma hoa DUY NHAT 1 loai update: ban khoi tao tu file .txt co san khi
 *    tai lieu chua co log (xem YjsProtocol.encodeTextSeedUpdate).
 */
public interface YjsDocumentService {

    /** Ten kieu goc (root type) cua Y.Text trong Y.Doc - frontend dung dung ten nay. */
    String TEXT_ROOT_NAME = "content";

    record StoredUpdate(Long id, byte[] data) {}

    /**
     * Kiem tra quyen mo phong: note thuoc user, chua bi xoa, va client dang giu
     * dung doi tai lieu hien tai.
     *
     * @throws com.noted.backend.exception.NoteNotFoundException note khong ton tai/khong thuoc user
     * @throws com.noted.backend.exception.CollabGenerationMismatchException doi tai lieu da cu
     */
    Note authorize(Long userId, Long noteId, int generation);

    /**
     * Tai toan bo log cua doi hien tai. Neu log rong, tao ban khoi tao tu noi
     * dung file dang co tren dia va luu lai nhu dong dau tien.
     */
    List<StoredUpdate> loadDocument(Long noteId, int generation);

    /** Ghi them 1 update client gui len. */
    void appendUpdate(Long noteId, int generation, byte[] update);

    /**
     * Thay cac dong da gui cho client (replacedIds) bang trang thai day du ma
     * client vua gui lai. Cac dong khac (update den sau, tu client khac) giu nguyen.
     */
    void compactDocument(Long noteId, int generation, Collection<Long> replacedIds, byte[] fullState);

    /**
     * Noi dung note vua bi ghi de tu ngoai luong cong tac: tang doi tai lieu,
     * xoa log cu va phat NoteContentReplacedEvent (dong phong sau commit).
     * Nguoi goi chiu trach nhiem save() note sau do, trong CUNG transaction.
     */
    void resetDocument(Note note);
}
