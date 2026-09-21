package com.lexia.api.modules.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

  @Query(
      """
      SELECT e FROM AuditEvent e
      WHERE e.tenantId = :tenantId
      AND e.result = 'DENIED'
      AND e.createdAt >= :since
      ORDER BY e.createdAt DESC
      """)
  List<AuditEvent> findRecentDeniedByTenant(
      @Param("tenantId") UUID tenantId, @Param("since") Instant since, Pageable pageable);

  long countByTenantIdAndResultAndCreatedAtAfter(UUID tenantId, String result, Instant since);

  @Query(
      """
      SELECT e FROM AuditEvent e
      WHERE e.tenantId = :tenantId
      AND (:q IS NULL OR :q = '' OR LOWER(e.event) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(e.objectType) LIKE LOWER(CONCAT('%', :q, '%')))
      AND (:result IS NULL OR :result = '' OR e.result = :result)
      ORDER BY e.createdAt DESC
      """)
  Page<AuditEvent> searchTenantEvents(
      @Param("tenantId") UUID tenantId,
      @Param("q") String q,
      @Param("result") String result,
      Pageable pageable);
}
