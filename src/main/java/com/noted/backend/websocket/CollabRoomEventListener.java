package com.noted.backend.websocket;

import com.noted.backend.event.NoteContentReplacedEvent;
import com.noted.backend.event.NoteDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Dong phong soan thao khi tai lieu khong con hop le. Chay SAU KHI commit: neu
 * dong truoc, client ket noi lai ngay co the doc phai doi tai lieu cu chua commit.
 */
@Component
@RequiredArgsConstructor
public class CollabRoomEventListener {

    private final RoomBroadcaster roomBroadcaster;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onContentReplaced(NoteContentReplacedEvent event) {
        roomBroadcaster.closeRoom(event.noteId(), CollabCloseStatus.DOCUMENT_REPLACED);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNoteDeleted(NoteDeletedEvent event) {
        roomBroadcaster.closeRoom(event.noteId(), CollabCloseStatus.NOTE_DELETED);
    }
}
