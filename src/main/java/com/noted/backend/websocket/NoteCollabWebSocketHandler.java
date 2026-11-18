package com.noted.backend.websocket;

import com.noted.backend.exception.CollabGenerationMismatchException;
import com.noted.backend.exception.NoteNotFoundException;
import com.noted.backend.security.JwtTokenProvider;
import com.noted.backend.service.NoteService;
import com.noted.backend.service.YjsDocumentService;
import com.noted.backend.service.YjsDocumentService.StoredUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * "Tram phat song" cua soan thao cong tac: nhan Yjs update bytes tu 1 trinh
 * duyet, luu nguyen van vao log (MySQL) roi phat nguyen van cho cac trinh duyet
 * khac trong cung phong (1 phong = 1 note). KHONG giai ma noi dung CRDT.
 *
 * Ket noi: {@code wss://<api>/ws/notes/{noteId}?generation={yjsGeneration}}, access
 * token gui qua header Sec-WebSocket-Protocol ("noted.collab.v1", "auth.<jwt>") thay
 * vi query string - URL hay bi ghi vao access log cua proxy/Cloudflare.
 *
 * Trinh tu khi ket noi (xem them YjsDocumentService):
 * <ol>
 *   <li>Xac thuc JWT, kiem tra note thuoc nguoi dung va dung doi tai lieu.</li>
 *   <li>Vao phong TRUOC, roi moi tai log - update cua nguoi khac den trong luc dang
 *       tai van toi duoc ket noi nay (Yjs tu xu ly update den khong theo thu tu).</li>
 *   <li>Gui toan bo log: dong dau la sync step2 (de y-websocket bat co "synced"),
 *       cac dong sau la update thuong.</li>
 *   <li>Gui sync step1 voi state vector rong -> client tra loi bang step2 chua TOAN BO
 *       trang thai cua no (gom ca thay doi luc offline). Server dung no de nen log:
 *       xoa dung cac dong da gui o buoc 3, thay bang 1 dong duy nhat.</li>
 * </ol>
 * Step1 client gui len khong can tra loi: server da gui het log o buoc 3.
 *
 * Moi loi trong luc xu ly 1 message -> dong ket noi (ma 1011, client tu ket noi
 * lai). An toan vi client van giu du lieu (IndexedDB) va lan ket noi lai se dong
 * bo bu toan bo; nuot loi roi tiep tuc moi la nguy hiem (update da mat ma khong ai biet).
 */
@Component
@Slf4j
public class NoteCollabWebSocketHandler extends BinaryWebSocketHandler implements SubProtocolCapable {

    public static final String SUB_PROTOCOL = "noted.collab.v1";
    private static final String AUTH_PROTOCOL_PREFIX = "auth.";
    private static final String PATH_PREFIX = "/ws/notes/";
    private static final String STATE_ATTRIBUTE = "noted.collab.state";

    private final JwtTokenProvider jwtTokenProvider;
    private final YjsDocumentService yjsDocumentService;
    private final NoteService noteService;
    private final NoteRoomRegistry roomRegistry;
    private final RoomBroadcaster roomBroadcaster;
    private final PendingMergeDispatcher pendingMergeDispatcher;
    private final int maxMessageBytes;
    private final int maxSnapshotBytes;
    private final int sendTimeLimitMs;
    private final int sendBufferLimitBytes;

    public NoteCollabWebSocketHandler(JwtTokenProvider jwtTokenProvider,
                                      YjsDocumentService yjsDocumentService,
                                      NoteService noteService,
                                      NoteRoomRegistry roomRegistry,
                                      RoomBroadcaster roomBroadcaster,
                                      PendingMergeDispatcher pendingMergeDispatcher,
                                      @Value("${app.collab.max-message-bytes}") int maxMessageBytes,
                                      @Value("${app.storage.max-file-size-bytes}") int maxSnapshotBytes,
                                      @Value("${app.collab.send-time-limit-ms}") int sendTimeLimitMs,
                                      @Value("${app.collab.send-buffer-limit-bytes}") int sendBufferLimitBytes) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.yjsDocumentService = yjsDocumentService;
        this.noteService = noteService;
        this.roomRegistry = roomRegistry;
        this.roomBroadcaster = roomBroadcaster;
        this.pendingMergeDispatcher = pendingMergeDispatcher;
        this.maxMessageBytes = maxMessageBytes;
        this.maxSnapshotBytes = maxSnapshotBytes;
        this.sendTimeLimitMs = sendTimeLimitMs;
        this.sendBufferLimitBytes = sendBufferLimitBytes;
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(SUB_PROTOCOL);
    }

    @Override
    public boolean supportsPartialMessages() {
        return true;
    }

    // ---------------------------------------------------------------- ket noi

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws IOException {
        ConnectionRequest request;
        try {
            request = ConnectionRequest.from(rawSession.getUri());
        } catch (IllegalArgumentException e) {
            rawSession.close(CollabCloseStatus.BAD_REQUEST);
            return;
        }

        String token = extractToken(rawSession);
        if (token == null || !jwtTokenProvider.validateToken(token)) {
            rawSession.close(CollabCloseStatus.UNAUTHORIZED);
            return;
        }
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        CollabSessionState state = null;
        try {
            yjsDocumentService.authorize(userId, request.noteId(), request.generation());

            WebSocketSession session = new ConcurrentWebSocketSessionDecorator(
                    rawSession, sendTimeLimitMs, sendBufferLimitBytes);
            state = new CollabSessionState(userId, request.noteId(), request.generation(), session);
            rawSession.getAttributes().put(STATE_ATTRIBUTE, state);
            roomRegistry.join(request.noteId(), session);

            List<StoredUpdate> updates = yjsDocumentService.loadDocument(request.noteId(), request.generation());
            for (int i = 0; i < updates.size(); i++) {
                byte[] data = updates.get(i).data();
                NoteRoomRegistry.send(session, i == 0 ? YjsProtocol.encodeSyncStep2(data) : YjsProtocol.encodeSyncUpdate(data));
            }
            state.setPendingCompactionIds(updates.stream().map(StoredUpdate::id).toList());
            NoteRoomRegistry.send(session, YjsProtocol.encodeEmptySyncStep1());
            // Nguoi dang o trong phong (ke ca o instance khac) bao lai con tro/ten
            // de nguoi moi thay ngay - cau tra loi di qua handleAwareness() nhu thuong.
            roomBroadcaster.broadcast(request.noteId(), YjsProtocol.encodeQueryAwareness(), session.getId());
            // Noi dung ngoai dang cho hop nhat (Drive/REST luc khong ai mo note): gui SAU
            // toan bo log + step1, de client hop nhat tren tai lieu da nap day du.
            pendingMergeDispatcher.onSessionJoined(request.noteId());

            log.debug("Ket noi {} vao phong note {} (doi {}), {} dong log",
                    rawSession.getId(), request.noteId(), request.generation(), updates.size());
        } catch (Exception e) {
            closeWithError(rawSession, state, e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession rawSession, CloseStatus status) {
        CollabSessionState state = stateOf(rawSession);
        if (state == null) {
            return;
        }
        roomRegistry.leave(state.getNoteId(), state.getSession());
        pendingMergeDispatcher.onSessionLeft(state.getNoteId(), state.getSession().getId());

        // Bao cho nguoi con lai: con tro/ten cua ket noi nay da roi phong. Khong co
        // buoc nay, con tro "ma" con hien den khi awareness tu het han (30s).
        List<YjsProtocol.AwarenessEntry> entries = state.trackedAwarenessEntries();
        if (!entries.isEmpty()) {
            roomBroadcaster.broadcast(state.getNoteId(),
                    YjsProtocol.encodeAwarenessMessage(YjsProtocol.encodeAwarenessRemoval(entries)), null);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession rawSession, Throwable exception) {
        log.debug("Loi truyen tai tren ket noi {}: {}", rawSession.getId(), exception.getMessage());
    }

    // ---------------------------------------------------------------- message

    @Override
    protected void handleBinaryMessage(WebSocketSession rawSession, BinaryMessage message) {
        CollabSessionState state = stateOf(rawSession);
        if (state == null) {
            return; // ket noi bi tu choi o buoc xac thuc, dang dong
        }
        try {
            byte[] data = state.appendFragment(message.getPayload(), message.isLast(), maxMessageBytes);
            if (data != null) {
                dispatch(state, data);
            }
        } catch (Exception e) {
            closeWithError(rawSession, state, e);
        }
    }

    private void dispatch(CollabSessionState state, byte[] data) {
        YjsProtocol.IncomingMessage message = YjsProtocol.parse(data);
        switch (message.messageType()) {
            case YjsProtocol.MESSAGE_SYNC -> handleSync(state, message, data);
            case YjsProtocol.MESSAGE_AWARENESS -> handleAwareness(state, message, data);
            case YjsProtocol.MESSAGE_TEXT_SNAPSHOT -> handleTextSnapshot(state, message.text());
            case YjsProtocol.MESSAGE_MERGE_APPLIED -> pendingMergeDispatcher.onMergeApplied(state.getNoteId(), message.number());
            case YjsProtocol.MESSAGE_QUERY_AWARENESS, YjsProtocol.MESSAGE_AUTH -> {
                // Chi server gui query awareness (khi co nguoi moi vao); xac thuc lam o buoc ket noi
            }
            default -> log.debug("Bo qua loai message khong biet: {}", message.messageType());
        }
    }

    private void handleSync(CollabSessionState state, YjsProtocol.IncomingMessage message, byte[] rawMessage) {
        Long noteId = state.getNoteId();
        String senderId = state.getSession().getId();
        switch (message.syncStep()) {
            case YjsProtocol.SYNC_STEP1 -> {
                // Server da gui toan bo log ngay khi ket noi - khong can tra loi.
            }
            case YjsProtocol.SYNC_STEP2 -> {
                List<Long> replacedIds = state.takePendingCompactionIds();
                if (replacedIds != null) {
                    yjsDocumentService.compactDocument(noteId, state.getGeneration(), replacedIds, message.payload());
                } else {
                    yjsDocumentService.appendUpdate(noteId, state.getGeneration(), message.payload());
                }
                // Gom ca thay doi offline cua client nay -> nguoi khac phai nhan duoc.
                roomBroadcaster.broadcast(noteId, YjsProtocol.encodeSyncUpdate(message.payload()), senderId);
            }
            case YjsProtocol.SYNC_UPDATE -> {
                if (Arrays.equals(message.payload(), YjsProtocol.EMPTY_UPDATE)) {
                    return;
                }
                yjsDocumentService.appendUpdate(noteId, state.getGeneration(), message.payload());
                roomBroadcaster.broadcast(noteId, rawMessage, senderId);
            }
            default -> throw new YjsProtocolException("Buoc sync khong hop le: " + message.syncStep());
        }
    }

    private void handleAwareness(CollabSessionState state, YjsProtocol.IncomingMessage message, byte[] rawMessage) {
        try {
            state.trackAwareness(YjsProtocol.decodeAwarenessUpdate(message.payload()));
        } catch (YjsProtocolException e) {
            // Chi mat kha nang "don con tro khi roi phong" - khong dang dong ket noi.
            log.debug("Khong doc duoc awareness update: {}", e.getMessage());
        }
        // Gui ca CHINH nguoi gui (excludeSessionId = null), giong server y-websocket chuan:
        // client y-websocket tu ngat neu 30s khong nhan duoc message nao, ma awareness
        // duoc gia han moi 15s - khi ngoi 1 minh trong phong, day la "nhip tim" duy nhat.
        roomBroadcaster.broadcast(state.getNoteId(), rawMessage, null);
    }

    private void handleTextSnapshot(CollabSessionState state, String text) {
        WebSocketSession session = state.getSession();
        if (text.getBytes(StandardCharsets.UTF_8).length > maxSnapshotBytes) {
            log.warn("Tu choi snapshot note {}: vuot {} byte", state.getNoteId(), maxSnapshotBytes);
            NoteRoomRegistry.send(session, YjsProtocol.encodeSnapshotAck(YjsProtocol.SNAPSHOT_ACK_REJECTED, 0));
            return;
        }
        int version;
        try {
            version = noteService.saveCollabSnapshot(state.getUserId(), state.getNoteId(), state.getGeneration(), text);
        } catch (CollabGenerationMismatchException | NoteNotFoundException e) {
            throw e; // dong ket noi voi ma tuong ung
        } catch (RuntimeException e) {
            // Loi ghi file/DB: noi dung van an toan trong log Yjs, chi file .txt chua
            // cap nhat. Bao cho client hien "Luu that bai", lan go tiep se thu lai.
            // In ca loi GOC (VD AccessDeniedException + duong dan): "Ghi file that bai"
            // khong thoi khong du de biet la sai quyen thu muc, het dia hay gi khac.
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            log.warn("Ghi snapshot note {} that bai: {} | goc: {}: {}", state.getNoteId(), e.getMessage(),
                    root.getClass().getSimpleName(), root.getMessage());
            NoteRoomRegistry.send(session, YjsProtocol.encodeSnapshotAck(YjsProtocol.SNAPSHOT_ACK_REJECTED, 0));
            return;
        }
        NoteRoomRegistry.send(session, YjsProtocol.encodeSnapshotAck(YjsProtocol.SNAPSHOT_ACK_OK, version));
    }

    // ---------------------------------------------------------------- tien ich

    private void closeWithError(WebSocketSession rawSession, CollabSessionState state, Exception e) {
        CloseStatus status;
        if (e instanceof CollabGenerationMismatchException) {
            status = CollabCloseStatus.DOCUMENT_REPLACED;
        } else if (e instanceof NoteNotFoundException) {
            status = CollabCloseStatus.NOTE_NOT_FOUND;
        } else if (e instanceof CollabSessionState.MessageTooLargeException) {
            log.warn("Ket noi {}: {}", rawSession.getId(), e.getMessage());
            status = CloseStatus.TOO_BIG_TO_PROCESS;
        } else if (e instanceof YjsProtocolException) {
            log.warn("Ket noi {} gui message sai giao thuc: {}", rawSession.getId(), e.getMessage());
            status = CloseStatus.BAD_DATA;
        } else {
            log.error("Loi xu ly phong soan thao (ket noi {})", rawSession.getId(), e);
            status = CloseStatus.SERVER_ERROR;
        }
        try {
            if (state != null) {
                roomRegistry.leave(state.getNoteId(), state.getSession());
            }
            rawSession.close(status);
        } catch (IOException closeError) {
            log.debug("Dong ket noi {} that bai: {}", rawSession.getId(), closeError.getMessage());
        }
    }

    private static CollabSessionState stateOf(WebSocketSession rawSession) {
        return (CollabSessionState) rawSession.getAttributes().get(STATE_ATTRIBUTE);
    }

    /** Token nam trong Sec-WebSocket-Protocol duoi dang "auth.<jwt>" (JWT chi gom ky tu hop le cua header nay). */
    private static String extractToken(WebSocketSession session) {
        List<String> headerValues = session.getHandshakeHeaders().get("Sec-WebSocket-Protocol");
        if (headerValues == null) {
            return null;
        }
        for (String headerValue : headerValues) {
            for (String protocol : headerValue.split(",")) {
                String trimmed = protocol.trim();
                if (trimmed.startsWith(AUTH_PROTOCOL_PREFIX)) {
                    return trimmed.substring(AUTH_PROTOCOL_PREFIX.length());
                }
            }
        }
        return null;
    }

    /** Thong tin lay tu URL ket noi: /ws/notes/{noteId}?generation={n}. */
    record ConnectionRequest(Long noteId, int generation) {

        static ConnectionRequest from(URI uri) {
            if (uri == null || uri.getPath() == null || !uri.getPath().startsWith(PATH_PREFIX)) {
                throw new IllegalArgumentException("Duong dan khong hop le");
            }
            long noteId = Long.parseLong(uri.getPath().substring(PATH_PREFIX.length()));
            Integer generation = null;
            if (uri.getRawQuery() != null) {
                for (String pair : uri.getRawQuery().split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && "generation".equals(pair.substring(0, eq))) {
                        generation = Integer.parseInt(pair.substring(eq + 1));
                    }
                }
            }
            if (generation == null || generation < 0 || noteId <= 0) {
                throw new IllegalArgumentException("Thieu hoac sai noteId/generation");
            }
            return new ConnectionRequest(noteId, generation);
        }
    }
}
