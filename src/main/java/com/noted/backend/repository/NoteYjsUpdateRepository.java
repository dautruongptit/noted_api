package com.noted.backend.repository;

import com.noted.backend.domain.entity.NoteYjsUpdate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface NoteYjsUpdateRepository extends JpaRepository<NoteYjsUpdate, Long> {

    List<NoteYjsUpdate> findByNoteIdAndGenerationOrderByIdAsc(Long noteId, int generation);

    @Modifying
    @Query("delete from NoteYjsUpdate u where u.noteId = :noteId")
    int deleteAllOfNote(@Param("noteId") Long noteId);

    @Modifying
    @Query("delete from NoteYjsUpdate u where u.noteId = :noteId and u.generation <> :generation")
    int deleteStaleGenerations(@Param("noteId") Long noteId, @Param("generation") int generation);

    // Xoa DUNG danh sach id (khong xoa theo "id <= max"): voi auto-increment,
    // 1 dong id nho hon co the commit SAU dong id lon hon, xoa theo khoang se
    // xoa nham update client chua tung nhan.
    @Modifying
    @Query("delete from NoteYjsUpdate u where u.noteId = :noteId and u.id in :ids")
    int deleteByNoteIdAndIdIn(@Param("noteId") Long noteId, @Param("ids") Collection<Long> ids);
}
