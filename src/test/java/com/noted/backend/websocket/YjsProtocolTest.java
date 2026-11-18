package com.noted.backend.websocket;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Byte ky vong duoc sinh bang yjs 13.6.33 THAT (khong tu tinh tay):
 * <pre>
 *   const d = new Y.Doc(); d.clientID = clientId
 *   d.getText('content').insert(0, text)
 *   Y.encodeStateAsUpdate(d)
 * </pre>
 * Va da kiem tra nguoc: Y.applyUpdate vao 1 Y.Doc moi cho ra dung text ban dau.
 * Sai 1 byte o day = moi note cu bi hong khi mo lan dau sau nang cap.
 */
class YjsProtocolTest {

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    @Test
    void seedUpdate_matchesYjsEncodingForVietnameseMultilineText() {
        byte[] expected = bytes(1, 1, 149, 154, 239, 58, 0, 4, 1, 7, 99, 111, 110, 116, 101, 110, 116,
                16, 88, 105, 110, 32, 99, 104, 195, 160, 111, 10, 108, 105, 110, 101, 32, 50, 0);

        assertThat(YjsProtocol.encodeTextSeedUpdate(123456789L, "content", "Xin chào\nline 2"))
                .isEqualTo(expected);
    }

    @Test
    void seedUpdate_matchesYjsEncodingForSurrogatePairs() {
        byte[] expected = bytes(1, 1, 7, 0, 4, 1, 7, 99, 111, 110, 116, 101, 110, 116,
                6, 97, 240, 159, 152, 128, 98, 0);

        assertThat(YjsProtocol.encodeTextSeedUpdate(7L, "content", "a😀b")).isEqualTo(expected);
    }

    @Test
    void seedUpdate_emptyTextIsEmptyYjsUpdate() {
        assertThat(YjsProtocol.encodeTextSeedUpdate(5L, "content", "")).isEqualTo(bytes(0, 0));
        assertThat(YjsProtocol.encodeTextSeedUpdate(5L, "content", null)).isEqualTo(bytes(0, 0));
    }

    @Test
    void seedClientId_isStableUnsigned32BitAndChangesWithGeneration() {
        long first = YjsProtocol.seedClientId(42L, 0);
        assertThat(first).isEqualTo(YjsProtocol.seedClientId(42L, 0));
        assertThat(first).isBetween(1L, 0xFFFFFFFFL);
        assertThat(YjsProtocol.seedClientId(42L, 1)).isNotEqualTo(first);
        assertThat(YjsProtocol.seedClientId(43L, 0)).isNotEqualTo(first);
    }

    @Test
    void emptySyncStep1_isEmptyStateVector() {
        // [messageSync, step1, varUint8Array([0])] - Y.encodeStateVector(new Y.Doc()) = [0]
        assertThat(YjsProtocol.encodeEmptySyncStep1()).isEqualTo(bytes(0, 0, 1, 0));
    }

    @Test
    void parse_syncUpdateExtractsPayload() {
        byte[] update = bytes(1, 2, 3);
        YjsProtocol.IncomingMessage message = YjsProtocol.parse(YjsProtocol.encodeSyncUpdate(update));

        assertThat(message.messageType()).isEqualTo(YjsProtocol.MESSAGE_SYNC);
        assertThat(message.syncStep()).isEqualTo(YjsProtocol.SYNC_UPDATE);
        assertThat(message.payload()).isEqualTo(update);
    }

    @Test
    void parse_textSnapshotReadsUtf8() {
        byte[] message = new Lib0Encoder()
                .writeVarUint(YjsProtocol.MESSAGE_TEXT_SNAPSHOT).writeVarString("Ghi chú 😀").toByteArray();

        assertThat(YjsProtocol.parse(message).text()).isEqualTo("Ghi chú 😀");
    }

    @Test
    void parse_truncatedMessageThrowsProtocolException() {
        // Khai bao mang 10 byte nhung chi co 2
        assertThatThrownBy(() -> YjsProtocol.parse(bytes(0, 2, 10, 1, 2)))
                .isInstanceOf(YjsProtocolException.class);
        assertThatThrownBy(() -> YjsProtocol.parse(bytes(0, 7, 0)))
                .isInstanceOf(YjsProtocolException.class);
    }

    @Test
    void varUint_roundTripsLargeValues() {
        long[] values = {0, 1, 127, 128, 300, 0xFFFFFFFFL, (1L << 53) - 1};
        for (long value : values) {
            byte[] encoded = new Lib0Encoder().writeVarUint(value).toByteArray();
            assertThat(new Lib0Decoder(encoded).readVarUint()).isEqualTo(value);
        }
    }

    @Test
    void awareness_decodeAndRemovalRoundTrip() {
        byte[] update = new Lib0Encoder()
                .writeVarUint(2)
                .writeVarUint(111).writeVarUint(3).writeVarString("{\"user\":{\"name\":\"A\"}}")
                .writeVarUint(222).writeVarUint(9).writeVarString("null")
                .toByteArray();

        List<YjsProtocol.AwarenessEntry> entries = YjsProtocol.decodeAwarenessUpdate(update);
        assertThat(entries).containsExactly(
                new YjsProtocol.AwarenessEntry(111, 3, false),
                new YjsProtocol.AwarenessEntry(222, 9, true));

        List<YjsProtocol.AwarenessEntry> removal = YjsProtocol.decodeAwarenessUpdate(
                YjsProtocol.encodeAwarenessRemoval(List.of(entries.get(0))));
        // Dong ho phai TANG thi client khac moi chap nhan trang thai "null"
        assertThat(removal).containsExactly(new YjsProtocol.AwarenessEntry(111, 4, true));
    }
}
