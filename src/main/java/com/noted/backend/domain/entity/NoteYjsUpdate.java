package com.noted.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 1 Yjs update (dang nhi phan, nguyen van client gui len) thuoc 1 note.
 *
 * Backend khong hieu noi dung ben trong - chi luu de phat lai cho client ket
 * noi sau va "nen" lai khi client gui trang thai day du (xem
 * YjsDocumentService.compactDocument). Cot "generation" khop Note.yjsGeneration
 * tai thoi diem ghi; dong thuoc doi cu bi bo qua khi tai va bi xoa dan.
 */
@Entity
@Table(name = "note_yjs_updates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NoteYjsUpdate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    @Column(name = "generation", nullable = false)
    private int generation;

    @Lob
    @Column(name = "update_data", nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] updateData;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
