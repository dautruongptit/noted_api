package com.noted.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 1 lan hop nhat dang cho trinh duyet thuc hien: noi dung ngoai (Drive/REST)
 * can duoc hop nhat 3 chieu vao tai lieu Yjs dang soan (xem
 * YjsDocumentService.submitExternalContent va V13__add_note_pending_merges.sql).
 */
@Entity
@Table(name = "note_pending_merges")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotePendingMerge {

    public enum Source { DRIVE, EXTERNAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false, unique = true)
    private Long noteId;

    @Column(name = "base_content", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String baseContent;

    @Column(name = "incoming_content", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String incomingContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private Source source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
