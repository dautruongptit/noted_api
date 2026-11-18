package com.noted.backend.repository;

import com.noted.backend.domain.entity.NotePendingMerge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NotePendingMergeRepository extends JpaRepository<NotePendingMerge, Long> {

    Optional<NotePendingMerge> findByNoteId(Long noteId);

    // JPQL delete chay NGAY (khong doi flush) - can thiet vi xoa roi insert lai
    // cung note_id (UNIQUE) trong 1 transaction; Hibernate mac dinh xep INSERT
    // truoc DELETE khi flush.
    @Modifying
    @Query("delete from NotePendingMerge m where m.noteId = :noteId")
    int deleteByNoteIdNow(@Param("noteId") Long noteId);

    /** Chi xoa neu dung lan hop nhat client vua bao xong - lan moi hon den sau thi giu lai. */
    @Modifying
    @Query("delete from NotePendingMerge m where m.noteId = :noteId and m.id = :id")
    int deleteByNoteIdAndId(@Param("noteId") Long noteId, @Param("id") Long id);
}
