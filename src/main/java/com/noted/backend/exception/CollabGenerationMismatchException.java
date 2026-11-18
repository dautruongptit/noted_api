package com.noted.backend.exception;

/**
 * Client dang giu 1 "doi" tai lieu Yjs da cu (noi dung note da bi ghi de tu
 * ngoai luong cong tac sau khi client mo phong). WebSocket handler doi loi nay
 * thanh ma dong 4409 de client tai lai note va mo phong theo doi moi.
 */
public class CollabGenerationMismatchException extends RuntimeException {
    public CollabGenerationMismatchException(Long noteId, int clientGeneration, int currentGeneration) {
        super("Doi tai lieu Yjs da cu: note id=" + noteId
                + ", client=" + clientGeneration + ", hien tai=" + currentGeneration);
    }
}
