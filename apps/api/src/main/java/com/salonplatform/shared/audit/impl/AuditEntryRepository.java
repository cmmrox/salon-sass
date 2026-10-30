package com.salonplatform.shared.audit.impl;

import com.salonplatform.shared.persistence.NewEntities;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The current salon's audit trail: Hibernate's tenant filter and row-level security see to that. */
interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID>, NewEntities<AuditEntry> {

    List<AuditEntry> findBySubjectKindAndSubjectIdOrderByCreatedAtAscIdAsc(String subjectKind, String subjectId);

    /** Identity's entries and the support-session entries (F03-S04), newest first. */
    @Query("""
            select e from AuditEntry e
             where (e.action like 'identity.%' or e.action like 'support.%')
             order by e.createdAt desc, e.id
            """)
    Page<AuditEntry> findIdentityEntries(Pageable page);

    /** As {@link #findIdentityEntries}, about one subject. */
    @Query("""
            select e from AuditEntry e
             where (e.action like 'identity.%' or e.action like 'support.%') and e.subjectId = :subject
             order by e.createdAt desc, e.id
            """)
    Page<AuditEntry> findIdentityEntriesAbout(@Param("subject") String subjectId, Pageable page);
}
