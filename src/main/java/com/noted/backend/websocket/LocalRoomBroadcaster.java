package com.noted.backend.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

/** Chi phat trong instance hien tai - dung khi chua bat Redis (dev, 1 instance). */
@Component
@ConditionalOnProperty(name = "app.collab.redis.enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class LocalRoomBroadcaster implements RoomBroadcaster {

    private final NoteRoomRegistry roomRegistry;

    @Override
    public void broadcast(Long noteId, byte[] message, String excludeSessionId) {
        roomRegistry.sendToRoom(noteId, message, excludeSessionId);
    }

    @Override
    public void closeRoom(Long noteId, CloseStatus status) {
        roomRegistry.closeRoom(noteId, status);
    }
}
