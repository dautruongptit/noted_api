package com.noted.backend.event;

/**
 * Note vua co noi dung ngoai can hop nhat vao tai lieu Yjs (xem
 * YjsDocumentService.submitExternalContent) - sau commit, gui cho 1 trinh duyet
 * dang mo note (PendingMergeDispatcher).
 */
public record NotePendingMergeEvent(Long noteId) {}
