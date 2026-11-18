package com.noted.backend.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cac "phong tai lieu" tren INSTANCE hien tai: noteId -> cac ket noi dang mo note do.
 *
 * Chi biet ket noi cuc bo. Phat cheo sang instance khac la viec cua
 * RoomBroadcaster (Redis Pub/Sub) - lop do goi lai sendToRoom() o day.
 *
 * Moi session luu o day PHAI la ConcurrentWebSocketSessionDecorator: nhieu luong
 * (luong cua cac ket noi khac, luong nhan Redis) cung gui vao 1 session, ma
 * WebSocketSession goc khong an toan da luong.
 */
@Component
@Slf4j
public class NoteRoomRegistry {

    private final Map<Long, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    public void join(Long noteId, WebSocketSession session) {
        rooms.computeIfAbsent(noteId, id -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void leave(Long noteId, WebSocketSession session) {
        rooms.computeIfPresent(noteId, (id, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    /**
     * Gui nguyen van {@code message} toi moi ket noi trong phong, tru
     * {@code excludeSessionId} (null = gui ca nguoi gui).
     */
    public void sendToRoom(Long noteId, byte[] message, String excludeSessionId) {
        Set<WebSocketSession> sessions = rooms.get(noteId);
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            if (session.getId().equals(excludeSessionId)) {
                continue;
            }
            send(session, message);
        }
    }

    public void closeRoom(Long noteId, CloseStatus status) {
        Set<WebSocketSession> sessions = rooms.remove(noteId);
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            try {
                session.close(status);
            } catch (IOException e) {
                log.debug("Dong ket noi {} cua note {} that bai: {}", session.getId(), noteId, e.getMessage());
            }
        }
    }

    /**
     * 1 ket noi dang mo cua phong tren instance nay: uu tien {@code preferredSessionId}
     * neu no con mo, khong thi lay bat ky. null = phong trong.
     */
    public WebSocketSession findOpenSession(Long noteId, String preferredSessionId) {
        Set<WebSocketSession> sessions = rooms.get(noteId);
        if (sessions == null) {
            return null;
        }
        WebSocketSession fallback = null;
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                continue;
            }
            if (session.getId().equals(preferredSessionId)) {
                return session;
            }
            if (fallback == null) {
                fallback = session;
            }
        }
        return fallback;
    }

    public int localSessionCount(Long noteId) {
        Set<WebSocketSession> sessions = rooms.get(noteId);
        return sessions == null ? 0 : sessions.size();
    }

    static void send(WebSocketSession session, byte[] message) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new BinaryMessage(message));
        } catch (Exception e) {
            // IOException (mang dut) hoac SessionLimitExceededException (client qua cham,
            // hang doi gui vuot gioi han - decorator da tu dong ket noi). Client se tu
            // ket noi lai va dong bo bu bang state vector, khong mat du lieu.
            log.debug("Gui toi ket noi {} that bai: {}", session.getId(), e.getMessage());
        }
    }
}
