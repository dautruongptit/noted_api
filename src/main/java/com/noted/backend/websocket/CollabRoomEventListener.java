package com.noted.backend.websocket;

import com.noted.backend.event.NotePendingMergeEvent;
import com.noted.backend.event.NoteDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phan ung voi thay doi cua note sau khi commit (truoc commit, client co the doc
 * phai du lieu cu): note bi xoa -> dong phong; co noi dung ngoai -> giao hop nhat.
 */
@Component
@RequiredArgsConstructor
public class CollabRoomEventListener {

    private final RoomBroadcaster roomBroadcaster;
    private final PendingMergeDispatcher pendingMergeDispatcher;

    /** Co noi dung ngoai can hop nhat -> giao cho 1 trinh duyet dang mo note (neu co). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPendingMerge(NotePendingMergeEvent event) {
        pendingMergeDispatcher.deliver(event.noteId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNoteDeleted(NoteDeletedEvent event) {
        roomBroadcaster.closeRoom(event.noteId(), CollabCloseStatus.NOTE_DELETED);
    }
}
