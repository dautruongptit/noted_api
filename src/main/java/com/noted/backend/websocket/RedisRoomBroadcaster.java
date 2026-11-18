package com.noted.backend.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Phat cheo message giua nhieu instance backend qua Redis Pub/Sub (container
 * infra-redis tren shared-db-network).
 *
 * Moi message: gui ngay cho ket noi cuc bo, roi publish len 1 kenh chung. Moi
 * instance nghe kenh do, bo qua message cua chinh minh (so instanceId), gui tiep
 * cho ket noi cuc bo cua phong tuong ung.
 *
 * Redis loi/mat ket noi: ket noi cuc bo VAN nhan duoc (gui truoc khi publish),
 * chi ket noi o instance khac bi tre - chung tu bu lai khi ket noi lai (state
 * vector) vi moi update deu da duoc luu vao MySQL truoc khi phat.
 *
 * Dinh dang phong bi (lib0): varString(instanceId) varUint(loai) varUint(noteId)
 *   loai 0 RELAY: varUint8Array(message)
 *   loai 1 CLOSE: varUint(ma dong) varString(ly do)
 */
@Component
@ConditionalOnProperty(name = "app.collab.redis.enabled", havingValue = "true")
@Slf4j
public class RedisRoomBroadcaster implements RoomBroadcaster, MessageListener {

    private static final int KIND_RELAY = 0;
    private static final int KIND_CLOSE = 1;

    private final NoteRoomRegistry roomRegistry;
    private final RedisConnectionFactory connectionFactory;
    private final byte[] channel;
    private final String instanceId = UUID.randomUUID().toString();

    public RedisRoomBroadcaster(NoteRoomRegistry roomRegistry,
                                RedisConnectionFactory connectionFactory,
                                @Value("${app.collab.redis.channel}") String channel) {
        this.roomRegistry = roomRegistry;
        this.connectionFactory = connectionFactory;
        this.channel = channel.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void broadcast(Long noteId, byte[] message, String excludeSessionId) {
        roomRegistry.sendToRoom(noteId, message, excludeSessionId);
        publish(new Lib0Encoder(message.length + 64)
                .writeVarString(instanceId).writeVarUint(KIND_RELAY).writeVarUint(noteId)
                .writeVarUint8Array(message).toByteArray());
    }

    @Override
    public void closeRoom(Long noteId, CloseStatus status) {
        roomRegistry.closeRoom(noteId, status);
        publish(new Lib0Encoder()
                .writeVarString(instanceId).writeVarUint(KIND_CLOSE).writeVarUint(noteId)
                .writeVarUint(status.getCode()).writeVarString(status.getReason() == null ? "" : status.getReason())
                .toByteArray());
    }

    /** Nhan tu Redis (luong cua RedisMessageListenerContainer). */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Lib0Decoder decoder = new Lib0Decoder(message.getBody());
            if (instanceId.equals(decoder.readVarString())) {
                return; // chinh instance nay da gui cuc bo roi
            }
            int kind = decoder.readVarUintAsInt();
            long noteId = decoder.readVarUint();
            if (roomRegistry.localSessionCount(noteId) == 0) {
                return;
            }
            if (kind == KIND_RELAY) {
                roomRegistry.sendToRoom(noteId, decoder.readVarUint8Array(), null);
            } else if (kind == KIND_CLOSE) {
                int code = decoder.readVarUintAsInt();
                roomRegistry.closeRoom(noteId, new CloseStatus(code, decoder.readVarString()));
            }
        } catch (YjsProtocolException e) {
            log.warn("Bo qua message Redis sai dinh dang: {}", e.getMessage());
        }
    }

    private void publish(byte[] envelope) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.publish(channel, envelope);
        } catch (Exception e) {
            log.warn("Publish len Redis that bai (ket noi o instance khac se dong bo bu khi ket noi lai): {}",
                    e.getMessage());
        }
    }
}
