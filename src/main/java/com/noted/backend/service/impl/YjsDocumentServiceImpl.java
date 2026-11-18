package com.noted.backend.service.impl;

import com.noted.backend.domain.entity.Note;
import com.noted.backend.domain.entity.NoteYjsUpdate;
import com.noted.backend.event.NoteContentReplacedEvent;
import com.noted.backend.exception.CollabGenerationMismatchException;
import com.noted.backend.exception.NoteNotFoundException;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class YjsDocumentServiceImpl implements YjsDocumentService {

    private final NoteRepository noteRepository;
    private final NoteYjsUpdateRepository updateRepository;
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
    public void resetDocument(Note note) {
        note.setYjsGeneration(note.getYjsGeneration() + 1);
        if (note.getId() != null) {
            updateRepository.deleteAllOfNote(note.getId());
            eventPublisher.publishEvent(new NoteContentReplacedEvent(note.getId()));
        }
    }

    private static void ensureGeneration(Note note, int generation) {
        if (note.getYjsGeneration() != generation) {
            throw new CollabGenerationMismatchException(note.getId(), generation, note.getYjsGeneration());
        }
    }
}
