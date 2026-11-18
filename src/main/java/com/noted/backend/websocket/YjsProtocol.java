package com.noted.backend.websocket;

import java.util.ArrayList;
import java.util.List;

/**
 * Giao thuc nhi phan giua frontend (y-websocket) va backend.
 *
 * Moi message = varUint(loai message) + phan than tuy loai:
 * <pre>
 *  0 SYNC             varUint(buoc) + varUint8Array(du lieu Yjs)
 *                       buoc 0 = step1 (state vector), 1 = step2 (update tra loi), 2 = update thuong
 *  1 AWARENESS        varUint8Array(awareness update: con tro, ten nguoi dung)
 *  100 TEXT_SNAPSHOT  (client -> server) varString(toan bo text hien tai) - de ghi file .txt
 *                       va day len Google Drive; KHONG tham gia dong bo CRDT
 *  101 SNAPSHOT_ACK   (server -> client) varUint(trang thai) + varUint(version note)
 *  102 PENDING_MERGE  (server -> 1 client) varUint(mergeId) varString(ban goc) varString(ban moi)
 *                       varString(nhan nguon) - noi dung ngoai can hop nhat 3 chieu vao Y.Text
 *  103 MERGE_APPLIED  (client -> server) varUint(mergeId) - da hop nhat xong
 * </pre>
 * Loai 0-3 la cua y-websocket chuan; 100-103 la mo rong rieng cua Noted (y-websocket
 * cho phep dang ky them handler qua provider.messageHandlers).
 *
 * Backend chi doc "phong bi" (loai, buoc) - khong giai ma noi dung CRDT ben trong.
 */
public final class YjsProtocol {

    public static final int MESSAGE_SYNC = 0;
    public static final int MESSAGE_AWARENESS = 1;
    public static final int MESSAGE_AUTH = 2;
    public static final int MESSAGE_QUERY_AWARENESS = 3;
    public static final int MESSAGE_TEXT_SNAPSHOT = 100;
    public static final int MESSAGE_SNAPSHOT_ACK = 101;
    public static final int MESSAGE_PENDING_MERGE = 102;
    public static final int MESSAGE_MERGE_APPLIED = 103;

    public static final int SYNC_STEP1 = 0;
    public static final int SYNC_STEP2 = 1;
    public static final int SYNC_UPDATE = 2;

    public static final int SNAPSHOT_ACK_OK = 0;
    public static final int SNAPSHOT_ACK_REJECTED = 1;

    /** Update rong cua Yjs (0 client co struct, 0 delete set) - Y.encodeStateAsUpdate(new Y.Doc()). */
    public static final byte[] EMPTY_UPDATE = {0, 0};

    // Ma "content ref" cua ContentString trong Yjs (Item.content.getRef()).
    private static final int CONTENT_STRING_REF = 4;

    private YjsProtocol() {
    }

    /** Message da tach phong bi. Chi 1 trong cac truong du lieu co gia tri tuy loai. */
    public record IncomingMessage(int messageType, int syncStep, byte[] payload, String text, long number) {}

    /** 1 muc trong awareness update: client nao, dong ho bao nhieu, trang thai co bi xoa (null) khong. */
    public record AwarenessEntry(long clientId, long clock, boolean removed) {}

    public static IncomingMessage parse(byte[] message) {
        Lib0Decoder decoder = new Lib0Decoder(message);
        int type = decoder.readVarUintAsInt();
        return switch (type) {
            case MESSAGE_SYNC -> {
                int step = decoder.readVarUintAsInt();
                if (step != SYNC_STEP1 && step != SYNC_STEP2 && step != SYNC_UPDATE) {
                    throw new YjsProtocolException("Buoc sync khong hop le: " + step);
                }
                yield new IncomingMessage(type, step, decoder.readVarUint8Array(), null, 0);
            }
            case MESSAGE_AWARENESS -> new IncomingMessage(type, -1, decoder.readVarUint8Array(), null, 0);
            case MESSAGE_TEXT_SNAPSHOT -> new IncomingMessage(type, -1, null, decoder.readVarString(), 0);
            case MESSAGE_MERGE_APPLIED -> new IncomingMessage(type, -1, null, null, decoder.readVarUint());
            default -> new IncomingMessage(type, -1, null, null, 0);
        };
    }

    public static byte[] encodeSyncStep2(byte[] update) {
        return new Lib0Encoder(update.length + 8)
                .writeVarUint(MESSAGE_SYNC).writeVarUint(SYNC_STEP2).writeVarUint8Array(update).toByteArray();
    }

    public static byte[] encodeSyncUpdate(byte[] update) {
        return new Lib0Encoder(update.length + 8)
                .writeVarUint(MESSAGE_SYNC).writeVarUint(SYNC_UPDATE).writeVarUint8Array(update).toByteArray();
    }

    /**
     * Sync step1 voi state vector RONG ([0]): nghia la "server khong biet gi ca",
     * client se tra loi bang step2 chua TOAN BO trang thai cua no - dung de nen log
     * va de nhan cac thay doi offline client chua gui.
     */
    public static byte[] encodeEmptySyncStep1() {
        return new Lib0Encoder(4)
                .writeVarUint(MESSAGE_SYNC).writeVarUint(SYNC_STEP1).writeVarUint8Array(new byte[]{0}).toByteArray();
    }

    /**
     * "Moi nguoi bao lai trang thai awareness" - client y-websocket tra loi bang
     * awareness update cua no. Server khong giu awareness (chi relay), nen khi co
     * nguoi moi vao phong phai hoi nhung nguoi dang o day, neu khong nguoi moi
     * khong thay con tro ai cho toi lan gia han ke tiep (toi 15s).
     */
    public static byte[] encodeQueryAwareness() {
        return new Lib0Encoder(1).writeVarUint(MESSAGE_QUERY_AWARENESS).toByteArray();
    }

    public static byte[] encodePendingMerge(long mergeId, String baseContent, String incomingContent, String sourceLabel) {
        return new Lib0Encoder(baseContent.length() + incomingContent.length() + 64)
                .writeVarUint(MESSAGE_PENDING_MERGE).writeVarUint(mergeId)
                .writeVarString(baseContent).writeVarString(incomingContent).writeVarString(sourceLabel)
                .toByteArray();
    }

    public static byte[] encodeSnapshotAck(int status, long noteVersion) {
        return new Lib0Encoder(8)
                .writeVarUint(MESSAGE_SNAPSHOT_ACK).writeVarUint(status).writeVarUint(noteVersion).toByteArray();
    }

    public static byte[] encodeAwarenessMessage(byte[] awarenessUpdate) {
        return new Lib0Encoder(awarenessUpdate.length + 4)
                .writeVarUint(MESSAGE_AWARENESS).writeVarUint8Array(awarenessUpdate).toByteArray();
    }

    /**
     * Doc cac muc trong 1 awareness update (dinh dang y-protocols/awareness:
     * varUint(so muc) + [varUint(clientID), varUint(clock), varString(JSON trang thai)]).
     * Dung de nho client nao thuoc ket noi nao, de bao "da roi phong" khi ket noi dong.
     */
    public static List<AwarenessEntry> decodeAwarenessUpdate(byte[] awarenessUpdate) {
        Lib0Decoder decoder = new Lib0Decoder(awarenessUpdate);
        int count = decoder.readVarUintAsInt();
        List<AwarenessEntry> entries = new ArrayList<>(Math.min(count, 64));
        for (int i = 0; i < count; i++) {
            long clientId = decoder.readVarUint();
            long clock = decoder.readVarUint();
            String state = decoder.readVarString();
            entries.add(new AwarenessEntry(clientId, clock, "null".equals(state)));
        }
        return entries;
    }

    /** Awareness update danh dau cac client da roi di (trang thai "null", dong ho + 1). */
    public static byte[] encodeAwarenessRemoval(List<AwarenessEntry> entries) {
        Lib0Encoder encoder = new Lib0Encoder();
        encoder.writeVarUint(entries.size());
        for (AwarenessEntry entry : entries) {
            encoder.writeVarUint(entry.clientId()).writeVarUint(entry.clock() + 1).writeVarString("null");
        }
        return encoder.toByteArray();
    }

    /**
     * Ma hoa 1 Yjs update (dinh dang V1) chen nguyen doan text vao Y.Text goc
     * {@code rootName} tai vi tri 0, do client {@code clientId} tao o clock 0.
     *
     * Day la DUY NHAT cho backend tu tao noi dung CRDT: khoi tao tai lieu tu file
     * .txt co san. Bo cuc byte da doi chieu voi Y.encodeStateAsUpdate() cua yjs
     * 13.6 (xem YjsProtocolTest):
     * <pre>
     *  varUint(1 client) varUint(1 struct) varUint(clientId) varUint(clock 0)
     *  uint8(info = ContentString) varUint(1 = parent la kieu goc) varString(rootName)
     *  varString(text) varUint(0 = delete set rong)
     * </pre>
     * clientId PHAI on dinh theo (note, doi tai lieu): 2 ket noi cung luc cung tao
     * ban khoi tao se sinh ra 2 update GIONG HET nhau ve id, Yjs tu loai trung.
     */
    public static byte[] encodeTextSeedUpdate(long clientId, String rootName, String text) {
        if (text == null || text.isEmpty()) {
            return EMPTY_UPDATE.clone();
        }
        return new Lib0Encoder(text.length() + rootName.length() + 16)
                .writeVarUint(1)
                .writeVarUint(1)
                .writeVarUint(clientId)
                .writeVarUint(0)
                .writeUint8(CONTENT_STRING_REF)
                .writeVarUint(1)
                .writeVarString(rootName)
                .writeVarString(text)
                .writeVarUint(0)
                .toByteArray();
    }

    /**
     * clientId cho ban khoi tao: so nguyen khong dau 32 bit (gioi han clientID cua
     * Yjs), suy ra tat dinh tu (noteId, doi tai lieu) va khong bao gio bang 0.
     */
    public static long seedClientId(long noteId, int generation) {
        long mixed = (noteId * 0x9E3779B1L + generation * 0x85EBCA6BL + 0x27D4EB2FL) & 0xFFFFFFFFL;
        return mixed == 0 ? 1 : mixed;
    }
}
