package com.noted.backend.websocket;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Ban Java toi thieu cua lib0/decoding. Moi loi dinh dang (het du lieu giua
 * chung, varUint qua dai, do dai vuot phan con lai) deu nem
 * YjsProtocolException thay vi IndexOutOfBounds - handler doi no thanh ma dong
 * "du lieu khong hop le".
 */
public final class Lib0Decoder {

    // 2^53 - 1: gioi han so nguyen an toan cua JavaScript, cung la tran cua lib0.
    private static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    private final byte[] data;
    private int position;

    public Lib0Decoder(byte[] data) {
        this.data = data;
        this.position = 0;
    }

    public boolean hasContent() {
        return position < data.length;
    }

    public int readUint8() {
        if (position >= data.length) {
            throw new YjsProtocolException("Het du lieu khi doc uint8");
        }
        return data[position++] & 0xFF;
    }

    public long readVarUint() {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = readUint8();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 49) {
                throw new YjsProtocolException("varUint vuot qua 53 bit");
            }
        }
        if (result > MAX_SAFE_INTEGER) {
            throw new YjsProtocolException("varUint vuot qua 53 bit");
        }
        return result;
    }

    public int readVarUintAsInt() {
        long value = readVarUint();
        if (value > Integer.MAX_VALUE) {
            throw new YjsProtocolException("Gia tri qua lon: " + value);
        }
        return (int) value;
    }

    public byte[] readVarUint8Array() {
        int length = readVarUintAsInt();
        if (length > data.length - position) {
            throw new YjsProtocolException("Do dai mang (" + length + ") vuot phan du lieu con lai");
        }
        byte[] result = Arrays.copyOfRange(data, position, position + length);
        position += length;
        return result;
    }

    public String readVarString() {
        return new String(readVarUint8Array(), StandardCharsets.UTF_8);
    }
}
