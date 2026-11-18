package com.noted.backend.websocket;

import com.noted.backend.domain.entity.NotePendingMerge;
import com.noted.backend.service.YjsDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giao 1 lan hop nhat dang cho (noi dung tu Drive/REST) cho DUNG 1 trinh duyet
 * dang mo note.
 *
 * Vi sao chi 1: hop nhat la 1 thao tac sua Y.Text. 2 trinh duyet cung ap 1 lan
 * hop nhat dong thoi (truoc khi thay ban cua nhau) se chen doan text 2 lan - Yjs
 * giu ca hai. Trinh duyet duoc giao danh dau mergeId da ap vao chinh tai lieu
 * (Y.Map), nen giao lai cho cung trinh duyet hoac trinh duyet khac SAU khi
 * dong bo deu vo hai.
 *
 * Nguoi duoc giao roi phong truoc khi bao xong -> giao lai cho nguoi con lai.
 * Khong ai mo note -> lan hop nhat nam cho trong DB, giao cho nguoi mo dau tien.
 *
 * Gioi han: chi chon trong ket noi cua INSTANCE nay. Chay nhieu instance (Redis
 * bat) can khoa phan tan de chon 1 nguoi tren toan he thong.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingMergeDispatcher {

    private static final String SOURCE_LABEL_DRIVE = "Google Drive";
    private static final String SOURCE_LABEL_EXTERNAL = "Nơi khác";

    private final YjsDocumentService yjsDocumentService;
    private final NoteRoomRegistry roomRegistry;

    /** noteId -> id ket noi dang duoc giao hop nhat */
    private final Map<Long, String> assignees = new ConcurrentHashMap<>();

    /** Co lan hop nhat moi (hoac vua bao xong 1 lan): giao (lai) cho nguoi dang duoc giao / nguoi bat ky. */
    public synchronized void deliver(Long noteId) {
        Optional<NotePendingMerge> pending = yjsDocumentService.findPendingMerge(noteId);
        if (pending.isEmpty()) {
            assignees.remove(noteId);
            return;
        }
        WebSocketSession target = roomRegistry.findOpenSession(noteId, assignees.get(noteId));
        if (target == null) {
            assignees.remove(noteId);
            return; // cho nguoi mo note tiep theo
        }
        assignees.put(noteId, target.getId());
        NotePendingMerge merge = pending.get();
        NoteRoomRegistry.send(target, YjsProtocol.encodePendingMerge(merge.getId(),
                merge.getBaseContent(), merge.getIncomingContent(),
                merge.getSource() == NotePendingMerge.Source.DRIVE ? SOURCE_LABEL_DRIVE : SOURCE_LABEL_EXTERNAL));
        log.debug("Giao hop nhat {} cua note {} cho ket noi {}", merge.getId(), noteId, target.getId());
    }

    /** Ket noi moi vao phong: chi giao neu chua ai dang giu lan hop nhat cua note nay. */
    public synchronized void onSessionJoined(Long noteId) {
        String assignee = assignees.get(noteId);
        WebSocketSession current = assignee == null ? null : roomRegistry.findOpenSession(noteId, assignee);
        if (current == null || !current.getId().equals(assignee)) {
            deliver(noteId);
        }
    }

    /** Ket noi roi phong: neu no dang giu lan hop nhat chua xong, giao cho nguoi khac. */
    public synchronized void onSessionLeft(Long noteId, String sessionId) {
        if (assignees.remove(noteId, sessionId)) {
            deliver(noteId);
        }
    }

    /** Trinh duyet bao da hop nhat xong. Con lan moi hon (den trong luc hop nhat) thi giao tiep. */
    public void onMergeApplied(Long noteId, long mergeId) {
        yjsDocumentService.acknowledgeMerge(noteId, mergeId);
        deliver(noteId);
    }
}
