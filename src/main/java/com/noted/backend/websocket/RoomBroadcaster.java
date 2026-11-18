package com.noted.backend.websocket;

import org.springframework.web.socket.CloseStatus;

/**
 * Phat message toi TAT CA ket noi cua 1 phong, ke ca ket noi nam tren instance
 * backend khac.
 *
 * 2 ban cai dat, chon qua app.collab.redis.enabled:
 *  - LocalRoomBroadcaster: 1 instance duy nhat, chi gui cuc bo (dev).
 *  - RedisRoomBroadcaster: gui cuc bo + publish len Redis (infra-redis) de cac
 *    instance khac gui tiep cho ket noi cua chung.
 */
public interface RoomBroadcaster {

    /** @param excludeSessionId ket noi cuc bo khong nhan lai message (thuong la nguoi gui); null = gui het */
    void broadcast(Long noteId, byte[] message, String excludeSessionId);

    /** Dong moi ket noi cua phong tren moi instance. */
    void closeRoom(Long noteId, CloseStatus status);
}
