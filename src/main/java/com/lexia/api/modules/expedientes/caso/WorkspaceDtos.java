package com.lexia.api.modules.expedientes.caso;

import java.util.List;
import java.util.UUID;

public final class WorkspaceDtos {

  private WorkspaceDtos() {}

  public record WorkspaceResponse(
      UUID id,
      boolean demo,
      String code,
      String caseType,
      String vertical,
      String subject,
      String status,
      String currentStage,
      String responsible,
      String lastActivity,
      String clientRef,
      AttentionItem attention,
      List<StageItem> stages,
      List<ParticipantItem> participants,
      List<DocumentItem> keyDocuments,
      List<DocumentItem> documents,
      List<DataItem> dataItems,
      List<ValidationItem> validations,
      ValidationSummary validationSummary,
      List<TaskItem> tasks,
      List<ExceptionItem> exceptions,
      List<ActionItem> actions,
      List<AuditItem> auditEvents,
      LegalReviewItem legalReview,
      NextActionItem nextAction,
      SituationItem situation,
      List<TimelineItem> recentActivity,
      List<NoteItem> notes,
      List<GateItem> gates,
      List<StageConnectorItem> stageConnectors,
      StageTransitionOptions stageTransition) {}

  public record StageConnectorItem(
      String code, String name, boolean enabled, String lastCallStatus) {}

  public record StageTransitionOptions(
      boolean canAdvance,
      String blockReason,
      String nextStageCode,
      String nextStageLabel,
      boolean canRevert,
      String revertBlockReason,
      String previousStageCode,
      String previousStageLabel) {}

  public record AttentionItem(String headline, String detail, String nextAction, String responsible) {}

  public record StageItem(
      String id, String label, String shortLabel, String title, String status) {}

  public record ParticipantItem(String name, String role, String kind, boolean demo) {}

  public record DocumentItem(
      UUID id,
      String name,
      String type,
      String version,
      String status,
      String origin,
      String processing,
      String updated,
      boolean demo) {}

  public record DataItem(
      UUID id,
      String label,
      String value,
      String group,
      DataProvenanceItem provenance,
      boolean demo) {}

  public record DataProvenanceItem(
      String source,
      String document,
      String page,
      String method,
      String confidence,
      String validation) {}

  public record ValidationItem(
      UUID id,
      String label,
      String kind,
      String result,
      String evidence,
      String ruleVersion,
      String review,
      boolean demo) {}

  public record ValidationSummary(int completed, int pending, int observation) {}

  public record TaskItem(
      UUID id,
      String title,
      String status,
      String owner,
      String due,
      String priority,
      String blocked,
      boolean demo) {}

  public record ExceptionItem(
      UUID id,
      String title,
      String type,
      String severity,
      String owner,
      String age,
      String status,
      String detail,
      String why,
      String evidence,
      String resolution,
      boolean demo) {}

  public record ActionItem(
      UUID id,
      String title,
      String type,
      String status,
      String date,
      String actor,
      String evidence,
      boolean demo) {}

  public record AuditItem(
      UUID id,
      String datetime,
      String actor,
      String event,
      String object,
      String result,
      String correlation,
      String evidence,
      boolean demo) {}

  public record LegalReviewItem(
      String status, String owner, String findings, String decisions, String evidence, boolean demo) {}

  public record NextActionItem(String action, String owner, String due) {}

  public record SituationItem(String status, String stage, String attention) {}

  public record TimelineItem(
      String title, String detail, String date, String actor, String evidence, String kind) {}

  public record NoteItem(
      UUID id, String author, String initials, String datetime, String content, boolean demo) {}

  public record GateItem(UUID id, String code, String question, String result, String resultCode) {}
}
