package com.noted.backend.service;

import com.noted.backend.domain.entity.Note;
import com.noted.backend.domain.entity.NotePendingMerge;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
     * Noi dung moi den tu NGOAI luong soan thao cong tac (Google Drive, REST
     * PATCH /content, ghi de khi trung ten). KHONG ghi de tai lieu Yjs, KHONG tach
     * ban sao: luu (ban goc, ban moi) cho 1 trinh duyet hop nhat 3 chieu vao
     * Y.Text dang soan - chi 1 file duy nhat, doan bi sua o ca 2 noi duoc giu ca
     * 2 ban co dau danh dau. Da co lan cho -> giu ban goc CU NHAT, ban moi MOI NHAT.
     *
     * Bo qua khi khong co gi de hop nhat: ban goc = ban moi, hoac note chua tung
     * mo o che do cong tac (chua co log) va file da la ban moi (ban khoi tao se
     * lay thang tu file).
     *
     * @param baseContent noi dung tai lieu TRUOC khi bi thay (null = rong)
     */
    void submitExternalContent(Note note, String baseContent, String incomingContent, NotePendingMerge.Source source);

    Optional<NotePendingMerge> findPendingMerge(Long noteId);

    /** Trinh duyet da hop nhat xong lan {@code mergeId} - xoa neu chua co lan moi hon. */
    void acknowledgeMerge(Long noteId, long mergeId);
}
