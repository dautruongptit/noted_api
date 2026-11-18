package com.noted.backend.websocket;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Ban Java toi thieu cua lib0/encoding (thu vien ma hoa Yjs dung).
 * Chi gom cac kieu du lieu ma giao thuc y-websocket can: varUint, mang byte
 * co do dai, chuoi UTF-8 co do dai.
 */
public final class Lib0Encoder {

    private final ByteArrayOutputStream out;

    public Lib0Encoder() {
        this(64);
    }

    public Lib0Encoder(int initialCapacity) {
        this.out = new ByteArrayOutputStream(initialCapacity);
    }

    public Lib0Encoder writeUint8(int value) {
        out.write(value & 0xFF);
        return this;
    }

    /** Unsigned varint: 7 bit/byte, little-endian, bit cao nhat = "con byte tiep". */
    public Lib0Encoder writeVarUint(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("varUint khong nhan so am: " + value);
        }
        while (value > 0x7F) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
        return this;
    }

    public Lib0Encoder writeVarUint8Array(byte[] data) {
        writeVarUint(data.length);
        out.writeBytes(data);
        return this;
    }

    public Lib0Encoder writeVarString(String value) {
        return writeVarUint8Array(value.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }
}
