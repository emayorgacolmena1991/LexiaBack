package com.lexia.api.modules.admin;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class AuditAdminService {

  private final AuditEventRepository auditEvents;
  private final AppUserRepository appUsers;
  private final AuthorizationService authorization;

  public AuditAdminService(
      AuditEventRepository auditEvents,
      AppUserRepository appUsers,
      AuthorizationService authorization) {
    this.auditEvents = auditEvents;
    this.appUsers = appUsers;
    this.authorization = authorization;
  }

  @Transactional(readOnly = true)
  public AdminDtos.AuditSummary summary() {
    authorization.requirePermission("auditoria:evento:leer");
    UUID tenantId = AuthContext.require().tenantId();
    Instant since = Instant.now().minus(Duration.ofHours(24));
    long denied = auditEvents.countByTenantIdAndResultAndCreatedAtAfter(tenantId, "DENIED", since);
    return new AdminDtos.AuditSummary(denied);
  }

  @Transactional(readOnly = true)
  public AdminDtos.PageResponse<AdminDtos.AuditEventItem> listEvents(
      int page, int size, String q, String result) {
    authorization.requirePermission("auditoria:evento:leer");
    UUID tenantId = AuthContext.require().tenantId();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(Math.max(size, 1), 100);
    String query = q == null ? "" : q.trim();
    String resultFilter = normalizeResultFilter(result);

    Page<AuditEvent> rows =
        auditEvents.searchTenantEvents(
            tenantId, query, resultFilter, PageRequest.of(safePage, safeSize));

    Set<UUID> actorIds =
        rows.getContent().stream()
            .map(AuditEvent::getActorUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<UUID, AppUser> actors =
        appUsers.findAllById(actorIds).stream()
            .collect(Collectors.toMap(AppUser::getId, user -> user));

    List<AdminDtos.AuditEventItem> items =
        rows.getContent().stream().map(event -> toItem(event, actors)).toList();
    return new AdminDtos.PageResponse<>(items, rows.getTotalElements(), safePage, safeSize);
  }

  private static String normalizeResultFilter(String result) {
    if (result == null || result.isBlank()) {
      return "";
    }
    String normalized = result.trim().toUpperCase();
    if ("OK".equals(normalized) || "DENIED".equals(normalized) || "ERROR".equals(normalized)) {
      return normalized;
    }
    return "";
  }

  private static AdminDtos.AuditEventItem toItem(AuditEvent event, Map<UUID, AppUser> actors) {
    AppUser actor = event.getActorUserId() == null ? null : actors.get(event.getActorUserId());
    String actorEmail = actor != null ? actor.getEmail() : null;
    String actorDisplayName = actor != null ? actor.getDisplayName() : null;
    if (actorDisplayName == null && "SYSTEM".equals(event.getActorType())) {
      actorDisplayName = "Sistema";
    }
    return new AdminDtos.AuditEventItem(
        event.getId(),
        event.getCreatedAt(),
        event.getActorType(),
        event.getActorUserId(),
        actorEmail,
        actorDisplayName,
        event.getEvent(),
        event.getObjectType(),
        event.getObjectId(),
        event.getResult(),
        event.getIpAddress());
  }
}
