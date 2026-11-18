package com.noted.backend.service.impl;

import com.noted.backend.domain.entity.Note;
import com.noted.backend.domain.entity.NotePendingMerge;
import com.noted.backend.domain.entity.NoteYjsUpdate;
import com.noted.backend.event.NotePendingMergeEvent;
import com.noted.backend.repository.NotePendingMergeRepository;
import com.noted.backend.repository.NoteRepository;
import com.noted.backend.repository.NoteYjsUpdateRepository;
import com.noted.backend.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YjsDocumentServiceImplTest {

    @Mock private NoteRepository noteRepository;
    @Mock private NoteYjsUpdateRepository updateRepository;
    @Mock private NotePendingMergeRepository pendingMergeRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private YjsDocumentServiceImpl service;

    private Note note;

    @BeforeEach
    void setUp() {
        note = Note.builder().id(9L).userId(1L).displayName("a.txt").filePath("1/u.txt").build();
        lenient().when(updateRepository.findByNoteIdAndGenerationOrderByIdAsc(9L, 0))
                .thenReturn(List.of(new NoteYjsUpdate(1L, 9L, 0, new byte[]{0, 0}, null)));
        lenient().when(pendingMergeRepository.findByNoteId(9L)).thenReturn(Optional.empty());
    }

    @Test
    void storesBaseAndIncomingAndNotifiesInsteadOfResettingDocument() {
        service.submitExternalContent(note, "old\n", "new\n", NotePendingMerge.Source.DRIVE);

        ArgumentCaptor<NotePendingMerge> saved = ArgumentCaptor.forClass(NotePendingMerge.class);
        verify(pendingMergeRepository).save(saved.capture());
        assertThat(saved.getValue().getBaseContent()).isEqualTo("old\n");
        assertThat(saved.getValue().getIncomingContent()).isEqualTo("new\n");
        assertThat(saved.getValue().getSource()).isEqualTo(NotePendingMerge.Source.DRIVE);
        verify(eventPublisher).publishEvent(new NotePendingMergeEvent(9L));
        // Khong con dat lai tai lieu: doi tai lieu giu nguyen
        assertThat(note.getYjsGeneration()).isZero();
    }

    @Test
    void consecutiveExternalChangesKeepOldestBaseAndNewestIncoming() {
        when(pendingMergeRepository.findByNoteId(9L)).thenReturn(Optional.of(NotePendingMerge.builder()
                .id(3L).noteId(9L).baseContent("v0\n").incomingContent("v1\n").source(NotePendingMerge.Source.DRIVE).build()));

        service.submitExternalContent(note, "v1\n", "v2\n", NotePendingMerge.Source.DRIVE);

        ArgumentCaptor<NotePendingMerge> saved = ArgumentCaptor.forClass(NotePendingMerge.class);
        verify(pendingMergeRepository).deleteByNoteIdNow(9L);
        verify(pendingMergeRepository).save(saved.capture());
        assertThat(saved.getValue().getBaseContent()).isEqualTo("v0\n");
        assertThat(saved.getValue().getIncomingContent()).isEqualTo("v2\n");
    }

    @Test
    void identicalContentIsIgnored() {
        service.submitExternalContent(note, "same", "same", NotePendingMerge.Source.EXTERNAL);

        verify(pendingMergeRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void noteNeverOpenedCollaborativelyWithFileAlreadyNewIsIgnored() {
        when(updateRepository.findByNoteIdAndGenerationOrderByIdAsc(9L, 0)).thenReturn(List.of());
        when(fileStorageService.read("1/u.txt")).thenReturn("new\n");

        service.submitExternalContent(note, "old\n", "new\n", NotePendingMerge.Source.EXTERNAL);

        // Lan mo dau se khoi tao tai lieu tu file - khong co gi de hop nhat
        verify(pendingMergeRepository, never()).save(any());
    }

    @Test
    void noteNeverOpenedButFileDiffersStillQueuesMerge() {
        // Mat tran B voi note chua mo: file = ban local, Drive = ban khac -> van phai hop nhat
        when(updateRepository.findByNoteIdAndGenerationOrderByIdAsc(9L, 0)).thenReturn(List.of());
        when(fileStorageService.read("1/u.txt")).thenReturn("local\n");

        service.submitExternalContent(note, "base\n", "drive\n", NotePendingMerge.Source.DRIVE);

        verify(pendingMergeRepository).save(any());
    }
}
