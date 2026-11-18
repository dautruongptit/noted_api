package com.noted.backend.service.impl;

import com.noted.backend.domain.entity.Note;
import com.noted.backend.domain.entity.NotePendingMerge;
import com.noted.backend.domain.entity.NoteYjsUpdate;
import com.noted.backend.event.NotePendingMergeEvent;
import com.noted.backend.exception.CollabGenerationMismatchException;
import com.noted.backend.exception.NoteNotFoundException;
import com.noted.backend.repository.NotePendingMergeRepository;
import com.noted.backend.repository.NoteRepository;
import com.noted.backend.repository.NoteYjsUpdateRepository;
import com.noted.backend.service.FileStorageService;
import com.noted.backend.service.YjsDocumentService;
import com.noted.backend.websocket.YjsProtocol;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class YjsDocumentServiceImpl implements YjsDocumentService {

    private final NoteRepository noteRepository;
    private final NoteYjsUpdateRepository updateRepository;
    private final NotePendingMergeRepository pendingMergeRepository;
    private final FileStorageService fileStorageService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public Note authorize(Long userId, Long noteId, int generation) {
        Note note = noteRepository.findByIdAndUserIdAndDeletedFalse(noteId, userId)
                .orElseThrow(() -> new NoteNotFoundException(noteId));
        ensureGeneration(note, generation);
        return note;
    }

    @Override
    @Transactional
    public List<StoredUpdate> loadDocument(Long noteId, int generation) {
        Note note = noteRepository.findById(noteId).orElseThrow(() -> new NoteNotFoundException(noteId));
        ensureGeneration(note, generation);

        // Dong cua doi cu co the con sot lai neu 1 update den dung luc doi tai lieu
        // (ket noi cu chua kip bi dong) - don luon o day, khong de log phinh ra.
        updateRepository.deleteStaleGenerations(noteId, generation);

        List<NoteYjsUpdate> rows = updateRepository.findByNoteIdAndGenerationOrderByIdAsc(noteId, generation);
        if (rows.isEmpty()) {
            // Tai lieu chua tung co log (note cu truoc khi co tinh nang nay, hoac vua
            // doi tai lieu): khoi tao tu file .txt dang co. 2 ket noi dong thoi cung
            // vao day se ghi 2 dong GIONG HET nhau (clientId tat dinh) - vo hai,
            // Yjs tu loai trung va lan nen log ke tiep se gop lai.
            String content = fileStorageService.read(note.getFilePath());
            byte[] seed = YjsProtocol.encodeTextSeedUpdate(
                    YjsProtocol.seedClientId(noteId, generation), TEXT_ROOT_NAME, content);
            NoteYjsUpdate saved = updateRepository.save(NoteYjsUpdate.builder()
                    .noteId(noteId).generation(generation).updateData(seed).build());
            rows = List.of(saved);
            log.debug("Khoi tao tai lieu Yjs cho note {} (doi {}) tu file, {} ky tu", noteId, generation, content.length());
        }
        return rows.stream().map(r -> new StoredUpdate(r.getId(), r.getUpdateData())).toList();
    }

    @Override
    @Transactional
    public void appendUpdate(Long noteId, int generation, byte[] update) {
        // Khong doc lai note de kiem tra doi tai lieu o moi phim go (ton 1 truy van
        // moi ky tu). Doi tai lieu thay doi -> ket noi bi dong qua event; dong lot
        // vao giua chung mang doi cu, bi loai khi tai va bi don o loadDocument().
        updateRepository.save(NoteYjsUpdate.builder()
                .noteId(noteId).generation(generation).updateData(update).build());
    }

    @Override
    @Transactional
    public void compactDocument(Long noteId, int generation, Collection<Long> replacedIds, byte[] fullState) {
        Note note = noteRepository.findById(noteId).orElseThrow(() -> new NoteNotFoundException(noteId));
        ensureGeneration(note, generation);

        if (!replacedIds.isEmpty()) {
            updateRepository.deleteByNoteIdAndIdIn(noteId, replacedIds);
        }
        updateRepository.save(NoteYjsUpdate.builder()
                .noteId(noteId).generation(generation).updateData(fullState).build());
    }

    @Override
    @Transactional
    public void submitExternalContent(Note note, String baseContent, String incomingContent,
                                      NotePendingMerge.Source source) {
        if (note.getId() == null) {
            return; // note vua tao, chua co tai lieu nao de hop nhat
        }
        String base = baseContent == null ? "" : baseContent;
        String incoming = incomingContent == null ? "" : incomingContent;
        if (base.equals(incoming)) {
            return;
        }
        boolean hasDocument = !updateRepository
                .findByNoteIdAndGenerationOrderByIdAsc(note.getId(), note.getYjsGeneration()).isEmpty();
        if (!hasDocument && Objects.equals(fileStorageService.read(note.getFilePath()), incoming)) {
            return; // lan mo dau se khoi tao tai lieu tu chinh file nay
        }

        Optional<NotePendingMerge> existing = pendingMergeRepository.findByNoteId(note.getId());
        // Nhieu thay doi ngoai lien tiep truoc khi trinh duyet kip hop nhat: GIU ban
        // goc cu nhat (diem tai lieu Yjs con dong bo), lay ban moi nhat. diff3 tu
        // loai phan ban moi va tai lieu cung chua giong nhau.
        String mergeBase = existing.map(NotePendingMerge::getBaseContent).orElse(base);
        pendingMergeRepository.deleteByNoteIdNow(note.getId());
        pendingMergeRepository.save(NotePendingMerge.builder()
                .noteId(note.getId())
                .baseContent(mergeBase)
                .incomingContent(incoming)
                .source(source)
                .build());
        eventPublisher.publishEvent(new NotePendingMergeEvent(note.getId()));
        log.debug("Note {}: cho hop nhat noi dung tu {}", note.getId(), source);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotePendingMerge> findPendingMerge(Long noteId) {
        return pendingMergeRepository.findByNoteId(noteId);
    }

    @Override
    @Transactional
    public void acknowledgeMerge(Long noteId, long mergeId) {
        pendingMergeRepository.deleteByNoteIdAndId(noteId, mergeId);
    }

    private static void ensureGeneration(Note note, int generation) {
        if (note.getYjsGeneration() != generation) {
            throw new CollabGenerationMismatchException(note.getId(), generation, note.getYjsGeneration());
        }
    }
}
