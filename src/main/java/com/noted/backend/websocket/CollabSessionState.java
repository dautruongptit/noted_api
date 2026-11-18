package com.noted.backend.websocket;

import lombok.Getter;
import org.springframework.web.socket.WebSocketSession;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trang thai cua 1 ket noi WebSocket vao phong soan thao.
 *
 * Cac phuong thuc chi duoc goi tu luong nhan message cua CHINH ket noi nay
 * (container goi tuan tu cho tung session), tru awarenessClocks duoc doc them
 * luc dong ket noi - nen dung ConcurrentHashMap.
 */
@Getter
public class CollabSessionState {

    private final Long userId;
    private final Long noteId;
    private final int generation;
    /** Session da boc ConcurrentWebSocketSessionDecorator - dung de GUI. */
    private final WebSocketSession session;

    /**
     * Id cac dong log da gui cho client luc ket noi. Step2 DAU TIEN client gui ve
     * (tra loi step1 rong cua server) chua toan bo nhung dong nay + thay doi
     * offline cua client -> thay dung danh sach nay bang 1 dong duy nhat.
     */
    private List<Long> pendingCompactionIds;

    /** clientID Yjs -> clock awareness gan nhat ma ket noi nay da bao. */
    private final Map<Long, Long> awarenessClocks = new ConcurrentHashMap<>();

    private ByteArrayOutputStream fragmentBuffer;

    public CollabSessionState(Long userId, Long noteId, int generation, WebSocketSession session) {
        this.userId = userId;
        this.noteId = noteId;
        this.generation = generation;
        this.session = session;
    }

    public void setPendingCompactionIds(List<Long> ids) {
        this.pendingCompactionIds = new ArrayList<>(ids);
    }

    /** Lay va xoa danh sach cho nen - chi step2 dau tien duoc dung no. */
    public List<Long> takePendingCompactionIds() {
        List<Long> ids = pendingCompactionIds;
        pendingCompactionIds = null;
        return ids;
    }

    public void trackAwareness(List<YjsProtocol.AwarenessEntry> entries) {
        for (YjsProtocol.AwarenessEntry entry : entries) {
            if (entry.removed()) {
                awarenessClocks.remove(entry.clientId());
            } else {
                awarenessClocks.put(entry.clientId(), entry.clock());
            }
        }
    }

    public List<YjsProtocol.AwarenessEntry> trackedAwarenessEntries() {
        List<YjsProtocol.AwarenessEntry> entries = new ArrayList<>();
        awarenessClocks.forEach((clientId, clock) -> entries.add(new YjsProtocol.AwarenessEntry(clientId, clock, false)));
        return entries;
    }

    /**
     * Ghep cac manh cua 1 message lon (handler bat supportsPartialMessages de
     * Tomcat khong phai cap buffer bang kich thuoc toi da cho MOI ket noi).
     *
     * @return message hoan chinh khi {@code last}, nguoc lai null
     * @throws MessageTooLargeException tong kich thuoc vuot {@code maxBytes}
     */
    public byte[] appendFragment(ByteBuffer payload, boolean last, int maxBytes) {
        if (last && fragmentBuffer == null) {
            byte[] whole = new byte[payload.remaining()];
            payload.get(whole);
            if (whole.length > maxBytes) {
                throw new MessageTooLargeException(whole.length, maxBytes);
            }
            return whole;
        }
        if (fragmentBuffer == null) {
            fragmentBuffer = new ByteArrayOutputStream(Math.max(payload.remaining() * 2, 1024));
        }
        if (fragmentBuffer.size() + payload.remaining() > maxBytes) {
            int size = fragmentBuffer.size() + payload.remaining();
            fragmentBuffer = null;
            throw new MessageTooLargeException(size, maxBytes);
        }
        byte[] chunk = new byte[payload.remaining()];
        payload.get(chunk);
        fragmentBuffer.writeBytes(chunk);
        if (!last) {
            return null;
        }
        byte[] whole = fragmentBuffer.toByteArray();
        fragmentBuffer = null;
        return whole;
    }

    public static class MessageTooLargeException extends RuntimeException {
        public MessageTooLargeException(int size, int maxBytes) {
            super("Message " + size + " byte vuot gioi han " + maxBytes + " byte");
        }
    }
}
