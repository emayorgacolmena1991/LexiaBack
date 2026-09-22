package com.lexia.api.modules.notifications;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserNotificationRepository extends JpaRepository<UserNotification, UUID> {

  List<UserNotification> findTop50ByTenantIdAndUserIdAndCategoryOrderByCreatedAtDesc(
      UUID tenantId, UUID userId, String category);

  long countByTenantIdAndUserIdAndCategoryAndReadAtIsNull(
      UUID tenantId, UUID userId, String category);

  Optional<UserNotification> findByTenantIdAndUserIdAndDedupeKey(
      UUID tenantId, UUID userId, String dedupeKey);

  @Modifying
  @Query(
      """
      UPDATE UserNotification n
      SET n.readAt = CURRENT_TIMESTAMP
      WHERE n.tenantId = :tenantId AND n.userId = :userId AND n.category = :category AND n.readAt IS NULL
      """)
  int markAllRead(
      @Param("tenantId") UUID tenantId,
      @Param("userId") UUID userId,
      @Param("category") String category);
}
