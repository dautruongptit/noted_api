package com.noted.backend.event;

/**
 * Noi dung note vua bi ghi de TU NGOAI luong soan thao cong tac (xem
 * YjsDocumentService.resetDocument) - cac phong WebSocket dang mo cho note nay
 * phai bi dong (4409) de client mo lai dung "doi" tai lieu moi.
 */
public record NoteContentReplacedEvent(Long noteId) {}
