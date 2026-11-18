package com.noted.backend.websocket;

import com.noted.backend.domain.entity.Note;
import com.noted.backend.domain.entity.NotePendingMerge;
import com.noted.backend.exception.CollabGenerationMismatchException;
import com.noted.backend.security.JwtTokenProvider;
import com.noted.backend.service.NoteService;
import com.noted.backend.service.YjsDocumentService;
import com.noted.backend.service.YjsDocumentService.StoredUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoteCollabWebSocketHandlerTest {

    private static final long NOTE_ID = 42L;
    private static final long USER_ID = 7L;
    private static final byte[] SEED = YjsProtocol.encodeTextSeedUpdate(99L, "content", "hello");

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private YjsDocumentService yjsDocumentService;
    @Mock private NoteService noteService;

    private NoteRoomRegistry registry;
    private NoteCollabWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        registry = new NoteRoomRegistry();
        handler = new NoteCollabWebSocketHandler(jwtTokenProvider, yjsDocumentService, noteService,
                registry, new LocalRoomBroadcaster(registry), new PendingMergeDispatcher(yjsDocumentService, registry),
                1024 * 1024, 1024, 10_000, 1024 * 1024);
        lenient().when(jwtTokenProvider.validateToken("good")).thenReturn(true);
        lenient().when(jwtTokenProvider.getUserIdFromToken("good")).thenReturn(USER_ID);
        lenient().when(yjsDocumentService.authorize(USER_ID, NOTE_ID, 0)).thenReturn(new Note());
        lenient().when(yjsDocumentService.loadDocument(NOTE_ID, 0))
                .thenReturn(List.of(new StoredUpdate(1L, SEED), new StoredUpdate(2L, new byte[]{9, 9})));
    }

    private FakeSession connect(String id, String token) throws Exception {
        FakeSession session = new FakeSession(id, URI.create("wss://api/ws/notes/" + NOTE_ID + "?generation=0"), token);
        handler.afterConnectionEstablished(session);
        return session;
    }

    private void send(FakeSession session, byte[] data) throws Exception {
        handler.handleMessage(session, new BinaryMessage(data));
    }

    @Test
    void rejectsMissingOrInvalidToken() throws Exception {
        FakeSession noToken = connect("a", null);
        FakeSession badToken = connect("b", "bad");

        assertThat(noToken.closeStatus).isEqualTo(CollabCloseStatus.UNAUTHORIZED);
        assertThat(badToken.closeStatus).isEqualTo(CollabCloseStatus.UNAUTHORIZED);
        assertThat(registry.localSessionCount(NOTE_ID)).isZero();
    }

    @Test
    void rejectsStaleGeneration() throws Exception {
        when(yjsDocumentService.authorize(USER_ID, NOTE_ID, 0))
                .thenThrow(new CollabGenerationMismatchException(NOTE_ID, 0, 1));

        FakeSession session = connect("a", "good");

        assertThat(session.closeStatus).isEqualTo(CollabCloseStatus.DOCUMENT_REPLACED);
    }

    @Test
    void sendsStoredLogAsStep2ThenUpdatesThenEmptyStep1() throws Exception {
        FakeSession session = connect("a", "good");

        assertThat(session.closeStatus).isNull();
        assertThat(session.sent).containsExactly(
                YjsProtocol.encodeSyncStep2(SEED),            // dong dau: step2 -> y-websocket bat "synced"
                YjsProtocol.encodeSyncUpdate(new byte[]{9, 9}),
                YjsProtocol.encodeEmptySyncStep1());          // xin toan bo trang thai cua client
    }

    @Test
    void newcomerTriggersAwarenessQueryToPeopleAlreadyInRoom() throws Exception {
        FakeSession a = connect("a", "good");
        a.sent.clear();

        FakeSession b = connect("b", "good");

        // Server khong giu awareness: phai hoi nguoi dang o day, ho tra loi cho ca phong
        assertThat(a.sent).containsExactly(YjsProtocol.encodeQueryAwareness());
        assertThat(b.sent).doesNotContain(YjsProtocol.encodeQueryAwareness());
    }

    private static NotePendingMerge pendingMerge(long id) {
        return NotePendingMerge.builder().id(id).noteId(NOTE_ID)
                .baseContent("a\n").incomingContent("a\nb\n").source(NotePendingMerge.Source.DRIVE).build();
    }

    private static long countPendingMergeMessages(FakeSession session) {
        return session.sent.stream().filter(m -> m[0] == (byte) YjsProtocol.MESSAGE_PENDING_MERGE).count();
    }

    @Test
    void pendingMergeIsSentToExactlyOneSessionAfterTheLog() throws Exception {
        when(yjsDocumentService.findPendingMerge(NOTE_ID)).thenReturn(Optional.of(pendingMerge(5)));

        FakeSession a = connect("a", "good");
        // Gui SAU log + step1 rong (client hop nhat tren tai lieu da nap day du)
        assertThat(a.sent).containsExactly(
                YjsProtocol.encodeSyncStep2(SEED),
                YjsProtocol.encodeSyncUpdate(new byte[]{9, 9}),
                YjsProtocol.encodeEmptySyncStep1(),
                YjsProtocol.encodePendingMerge(5, "a\n", "a\nb\n", "Google Drive"));

        FakeSession b = connect("b", "good");

        // Chi 1 nguoi hop nhat - 2 nguoi cung ap se chen doan text 2 lan
        assertThat(countPendingMergeMessages(a)).isEqualTo(1);
        assertThat(countPendingMergeMessages(b)).isZero();
    }

    @Test
    void pendingMergeIsReassignedWhenAssigneeLeavesWithoutAcknowledging() throws Exception {
        when(yjsDocumentService.findPendingMerge(NOTE_ID)).thenReturn(Optional.of(pendingMerge(5)));
        FakeSession a = connect("a", "good");
        FakeSession b = connect("b", "good");

        handler.afterConnectionClosed(a, CloseStatus.GOING_AWAY);

        assertThat(countPendingMergeMessages(b)).isEqualTo(1);
    }

    @Test
    void mergeAppliedAcknowledgesAndHandsOutNewerMergeIfAny() throws Exception {
        when(yjsDocumentService.findPendingMerge(NOTE_ID))
                .thenReturn(Optional.of(pendingMerge(5)))
                .thenReturn(Optional.empty());
        FakeSession a = connect("a", "good");
        a.sent.clear();

        send(a, new Lib0Encoder().writeVarUint(YjsProtocol.MESSAGE_MERGE_APPLIED).writeVarUint(5).toByteArray());

        verify(yjsDocumentService).acknowledgeMerge(NOTE_ID, 5L);
        assertThat(countPendingMergeMessages(a)).isZero();
    }

    @Test
    void firstStep2CompactsExactlyTheRowsSentAndIsRelayedAsUpdate() throws Exception {
        FakeSession a = connect("a", "good");
        FakeSession b = connect("b", "good");
        a.sent.clear();
        b.sent.clear();
        byte[] fullState = {1, 2, 3};

        send(a, YjsProtocol.encodeSyncStep2(fullState));
        send(a, YjsProtocol.encodeSyncStep2(new byte[]{4}));

        verify(yjsDocumentService).compactDocument(NOTE_ID, 0, List.of(1L, 2L), fullState);
        verify(yjsDocumentService).appendUpdate(NOTE_ID, 0, new byte[]{4}); // step2 thu 2: chi ghi them
        assertThat(b.sent).containsExactly(YjsProtocol.encodeSyncUpdate(fullState), YjsProtocol.encodeSyncUpdate(new byte[]{4}));
        assertThat(a.sent).isEmpty();
    }

    @Test
    void updateIsStoredAndRelayedToOthersButNotEchoed() throws Exception {
        FakeSession a = connect("a", "good");
        FakeSession b = connect("b", "good");
        a.sent.clear();
        b.sent.clear();
        byte[] message = YjsProtocol.encodeSyncUpdate(new byte[]{5, 6});

        send(a, message);

        verify(yjsDocumentService).appendUpdate(NOTE_ID, 0, new byte[]{5, 6});
        assertThat(b.sent).containsExactly(message);
        assertThat(a.sent).isEmpty();
    }

    @Test
    void emptyUpdateIsIgnored() throws Exception {
        FakeSession a = connect("a", "good");

        send(a, YjsProtocol.encodeSyncUpdate(YjsProtocol.EMPTY_UPDATE));

        verify(yjsDocumentService, never()).appendUpdate(anyLong(), anyInt(), any());
    }

    @Test
    void awarenessIsEchoedToEveryoneAndRemovedOnClose() throws Exception {
        FakeSession a = connect("a", "good");
        FakeSession b = connect("b", "good");
        a.sent.clear();
        b.sent.clear();
        byte[] awareness = YjsProtocol.encodeAwarenessMessage(new Lib0Encoder()
                .writeVarUint(1).writeVarUint(555).writeVarUint(3).writeVarString("{\"user\":{}}").toByteArray());

        send(a, awareness);
        // Ca nguoi gui cung nhan: day la "nhip tim" giu ket noi khi ngoi 1 minh trong phong
        assertThat(a.sent).containsExactly(awareness);
        assertThat(b.sent).containsExactly(awareness);

        b.sent.clear();
        handler.afterConnectionClosed(a, CloseStatus.NORMAL);

        assertThat(b.sent).hasSize(1);
        YjsProtocol.IncomingMessage removal = YjsProtocol.parse(b.sent.get(0));
        assertThat(YjsProtocol.decodeAwarenessUpdate(removal.payload()))
                .containsExactly(new YjsProtocol.AwarenessEntry(555, 4, true));
        assertThat(registry.localSessionCount(NOTE_ID)).isEqualTo(1);
    }

    @Test
    void snapshotIsSavedAndAcknowledgedOnlyToSender() throws Exception {
        when(noteService.saveCollabSnapshot(USER_ID, NOTE_ID, 0, "Xin chào")).thenReturn(12);
        FakeSession a = connect("a", "good");
        FakeSession b = connect("b", "good");
        a.sent.clear();
        b.sent.clear();

        send(a, new Lib0Encoder().writeVarUint(YjsProtocol.MESSAGE_TEXT_SNAPSHOT).writeVarString("Xin chào").toByteArray());

        assertThat(a.sent).containsExactly(YjsProtocol.encodeSnapshotAck(YjsProtocol.SNAPSHOT_ACK_OK, 12));
        assertThat(b.sent).isEmpty();
    }

    @Test
    void oversizedSnapshotIsRejectedWithoutSaving() throws Exception {
        FakeSession a = connect("a", "good");
        a.sent.clear();

        send(a, new Lib0Encoder().writeVarUint(YjsProtocol.MESSAGE_TEXT_SNAPSHOT).writeVarString("x".repeat(2000)).toByteArray());

        verify(noteService, never()).saveCollabSnapshot(anyLong(), anyLong(), anyInt(), any());
        assertThat(a.sent).containsExactly(YjsProtocol.encodeSnapshotAck(YjsProtocol.SNAPSHOT_ACK_REJECTED, 0));
    }

    @Test
    void storageFailureClosesWithServerErrorSoClientResyncs() throws Exception {
        FakeSession a = connect("a", "good");
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(yjsDocumentService).appendUpdate(eq(NOTE_ID), eq(0), any());

        send(a, YjsProtocol.encodeSyncUpdate(new byte[]{1, 1}));

        assertThat(a.closeStatus).isEqualTo(CloseStatus.SERVER_ERROR);
    }

    @Test
    void fragmentedMessageIsReassembled() throws Exception {
        FakeSession a = connect("a", "good");
        FakeSession b = connect("b", "good");
        b.sent.clear();
        byte[] message = YjsProtocol.encodeSyncUpdate(new byte[]{7, 7, 7, 7});

        handler.handleMessage(a, new BinaryMessage(ByteBuffer.wrap(Arrays.copyOfRange(message, 0, 3)), false));
        handler.handleMessage(a, new BinaryMessage(ByteBuffer.wrap(Arrays.copyOfRange(message, 3, message.length)), true));

        assertThat(b.sent).containsExactly(message);
    }

    @Test
    void malformedMessageClosesWithBadData() throws Exception {
        FakeSession a = connect("a", "good");

        send(a, new byte[]{0, 2, 50, 1});

        assertThat(a.closeStatus).isEqualTo(CloseStatus.BAD_DATA);
    }

    /** WebSocketSession toi thieu: ghi lai message da gui va ma dong. */
    private static final class FakeSession implements WebSocketSession {
        final String id;
        final URI uri;
        final HttpHeaders headers = new HttpHeaders();
        final Map<String, Object> attributes = new HashMap<>();
        final List<byte[]> sent = new ArrayList<>();
        CloseStatus closeStatus;

        FakeSession(String id, URI uri, String token) {
            this.id = id;
            this.uri = uri;
            if (token != null) {
                headers.add("Sec-WebSocket-Protocol", NoteCollabWebSocketHandler.SUB_PROTOCOL + ", auth." + token);
            }
        }

        @Override public String getId() { return id; }
        @Override public URI getUri() { return uri; }
        @Override public HttpHeaders getHandshakeHeaders() { return headers; }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return NoteCollabWebSocketHandler.SUB_PROTOCOL; }
        @Override public void setTextMessageSizeLimit(int messageSizeLimit) { }
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) { }
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public List<WebSocketExtension> getExtensions() { return List.of(); }
        @Override public boolean isOpen() { return closeStatus == null; }
        @Override public void close() { closeStatus = CloseStatus.NORMAL; }
        @Override public void close(CloseStatus status) { closeStatus = status; }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            ByteBuffer payload = ((BinaryMessage) message).getPayload();
            byte[] bytes = new byte[payload.remaining()];
            payload.get(bytes);
            sent.add(bytes);
        }
    }
}
