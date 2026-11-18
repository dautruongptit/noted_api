package com.noted.backend.websocket;

import org.springframework.web.socket.CloseStatus;

/**
 * Ma dong ket noi WebSocket cua phong soan thao.
 *
 * y-websocket coi 4400-4499 la loi "dut khoat" (khong tu ket noi lai) va phat
 * su kien 'closed' de frontend tu xu ly; cac ma khac (1011...) thi tu ket noi lai
 * voi backoff. Frontend (useCollabDocument.ts) xu ly tung ma ben duoi.
 */
public final class CollabCloseStatus {

    /** Thieu/sai/het han access token -> frontend refresh token roi ket noi lai. */
    public static final CloseStatus UNAUTHORIZED = new CloseStatus(4401, "unauthorized");
    /** Note khong ton tai hoac khong thuoc nguoi dung. */
    public static final CloseStatus NOTE_NOT_FOUND = new CloseStatus(4404, "note not found");
    /** Doi tai lieu Yjs da doi (noi dung bi ghi de tu ngoai) -> frontend tai lai note. */
    public static final CloseStatus DOCUMENT_REPLACED = new CloseStatus(4409, "document replaced");
    /** Note vua bi xoa. */
    public static final CloseStatus NOTE_DELETED = new CloseStatus(4410, "note deleted");
    /** URL ket noi sai dinh dang (thieu noteId/generation). */
    public static final CloseStatus BAD_REQUEST = new CloseStatus(4400, "bad request");

    private CollabCloseStatus() {
    }
}
