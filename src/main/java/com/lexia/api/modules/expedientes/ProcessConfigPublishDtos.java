package com.lexia.api.modules.expedientes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ProcessConfigPublishDtos {

  private ProcessConfigPublishDtos() {}

  public record ChangeSetItemView(
      String domain, String summary, String entityRef, Instant lastUpdatedAt) {}

  public record ChangeSetState(
      UUID id,
      String status,
      String submitComment,
      UUID submittedBy,
      Instant submittedAt,
      String reviewComment,
      UUID reviewedBy,
      Instant reviewedAt,
      List<ChangeSetItemView> items) {}

  public record ProcessPublicationState(
      int configVersion,
      boolean hasUnpublishedChanges,
      Instant lastPublishedAt,
      UUID lastPublishedBy,
      Instant lastModifiedAt,
      UUID lastModifiedBy,
      String lastModifiedByName,
      ChangeSetState changeSet) {}

  public record ChangeSetCommentRequest(@NotBlank @Size(min = 10, max = 2000) String comment) {}

  public record PublishProcessConfigRequest(
      @NotBlank @Size(min = 10, max = 2000) String comment) {}

  public record ProcessPublicationHistoryItem(
      UUID id,
      int configVersion,
      String comment,
      UUID publishedBy,
      Instant publishedAt,
      boolean hasSnapshot) {}

  public record ProcessPublicationHistoryView(
      String caseType, int activeVersion, List<ProcessPublicationHistoryItem> publications) {}

  public record ProcessConfigDiffLine(
      String path, String changeType, String before, String after) {}

  public record ProcessConfigDiffView(
      int fromVersion,
      int toVersion,
      boolean complete,
      String notice,
      List<ProcessConfigDiffLine> changes) {}

  public record ImpactFinding(String severity, String code, String message) {}

  public record ProcessConfigImpactView(
      boolean ready,
      int changeCount,
      long openCasesOnPriorConfig,
      int activeConfigVersion,
      int nextConfigVersion,
      List<ImpactFinding> findings) {}

  public record ProcessConfigExportPackage(
      int lexiaExportVersion,
      String caseType,
      Instant exportedAt,
      int baseConfigVersion,
      String snapshotJson) {}

  public record ProcessConfigImportPreviewRequest(@NotBlank String snapshotJson) {}

  public record ProcessConfigImportPreviewView(
      ProcessConfigDiffView diff,
      ProcessConfigImpactView impact,
      boolean packageValid,
      String notice) {}
}
