package com.lexia.api.modules.expedientes.caso;

import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CreateCaseRequest;
import com.lexia.api.modules.tenancy.TenantParameterService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import com.lexia.api.modules.expedientes.escrituracion.CollectionFile;
import com.lexia.api.modules.expedientes.escrituracion.CollectionFileRepository;
import com.lexia.api.modules.expedientes.proceso.GateDef;
import com.lexia.api.modules.expedientes.proceso.GateDefRepository;
import com.lexia.api.modules.expedientes.proceso.ProcessDefinition;
import com.lexia.api.modules.expedientes.proceso.ProcessDefinitionRepository;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDef;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDefRepository;
import com.lexia.api.modules.expedientes.sla.SlaCalendarService;
import com.lexia.api.modules.expedientes.proceso.ValidationDef;
import com.lexia.api.modules.expedientes.proceso.ValidationDefRepository;
import com.lexia.api.modules.expedientes.escrituracion.WritingFile;
import com.lexia.api.modules.expedientes.escrituracion.WritingFileRepository;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CaseBootstrapService {

  private final ProcessDefinitionRepository processDefinitions;
  private final ProcessStageDefRepository stageDefs;
  private final CaseStageRepository caseStages;
  private final GateDefRepository gateDefs;
  private final CaseGateRepository caseGates;
  private final ValidationDefRepository validationDefs;
  private final CaseValidationRepository caseValidations;
  private final CasePartyRepository caseParties;
  private final CaseNoteRepository caseNotes;
  private final CaseActionRepository caseActions;
  private final TenantParameterService tenantParameters;
  private final SlaCalendarService slaCalendar;
  private final CollectionFileRepository collectionFiles;
  private final WritingFileRepository writingFiles;

  public CaseBootstrapService(
      ProcessDefinitionRepository processDefinitions,
      ProcessStageDefRepository stageDefs,
      CaseStageRepository caseStages,
      GateDefRepository gateDefs,
      CaseGateRepository caseGates,
      ValidationDefRepository validationDefs,
      CaseValidationRepository caseValidations,
      CasePartyRepository caseParties,
      CaseNoteRepository caseNotes,
      CaseActionRepository caseActions,
      TenantParameterService tenantParameters,
      SlaCalendarService slaCalendar,
      CollectionFileRepository collectionFiles,
      WritingFileRepository writingFiles) {
    this.processDefinitions = processDefinitions;
    this.stageDefs = stageDefs;
    this.caseStages = caseStages;
    this.gateDefs = gateDefs;
    this.caseGates = caseGates;
    this.validationDefs = validationDefs;
    this.caseValidations = caseValidations;
    this.caseParties = caseParties;
    this.caseNotes = caseNotes;
    this.caseActions = caseActions;
    this.tenantParameters = tenantParameters;
    this.slaCalendar = slaCalendar;
    this.collectionFiles = collectionFiles;
    this.writingFiles = writingFiles;
  }

  public void bootstrap(LegalCase legalCase, CreateCaseRequest request, UUID tenantId, UUID userId) {
    ProcessDefinition process =
        processDefinitions
            .findByTenantIdAndCaseTypeAndDeletedAtIsNull(tenantId, legalCase.getCaseType())
            .orElse(null);
    if (process == null) {
      persistParties(legalCase.getId(), tenantId, request);
      persistCreationEvents(legalCase.getId(), tenantId, userId, request);
      return;
    }

    List<ProcessStageDef> definitions =
        stageDefs.findByProcessDefinitionIdAndTenantIdAndActiveTrueOrderBySortOrderAsc(
            process.getId(), tenantId);
    if (definitions.isEmpty()) {
      definitions =
          stageDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(
              process.getId(), tenantId);
    }
    int currentIndex = resolveStageIndex(request.stage(), definitions);
    Instant now = Instant.now();
    UUID currentStageId = null;

    for (int index = 0; index < definitions.size(); index++) {
      ProcessStageDef definition = definitions.get(index);
      String status =
          index < currentIndex ? "completed" : (index == currentIndex ? "current" : "pending");
      CaseStage saved =
          caseStages.save(
              CaseStage.create(
                  tenantId,
                  legalCase.getId(),
                  definition.getId(),
                  status,
                  index <= currentIndex ? now : null));
      if (index == currentIndex) {
        currentStageId = saved.getId();
        applyStageSla(legalCase, tenantId, definition);
      }
    }

    legalCase.attachProcess(process.getId(), currentStageId, process.getConfigVersion());
    if (request.operationTypeCode() != null && !request.operationTypeCode().isBlank()) {
      legalCase.setOperationTypeCode(request.operationTypeCode().trim().toUpperCase(Locale.ROOT));
    }
    if (request.productCode() != null && !request.productCode().isBlank()) {
      legalCase.setProductCode(request.productCode().trim().toUpperCase(Locale.ROOT));
    }
    if (request.ingestionMode() != null && !request.ingestionMode().isBlank()) {
      String mode = request.ingestionMode().trim().toUpperCase(Locale.ROOT);
      if ("FISICO_ESCANEDO".equals(mode)) {
        mode = "FISICO_ESCANEADO";
      }
      legalCase.setIngestionMode(mode);
    }

    if ("EJD".equals(legalCase.getCaseType())) {
      WritingFile writing = WritingFile.create(tenantId, legalCase.getId());
      if (legalCase.getProductCode() != null) {
        String canton =
            request.canton() == null || request.canton().isBlank()
                ? "ALL"
                : request.canton().trim().toUpperCase(Locale.ROOT);
        writing.applyProduct(legalCase.getProductCode(), canton, legalCase.getIngestionMode());
      }
      writingFiles.save(writing);
    }

    for (GateDef gateDef :
        gateDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(process.getId(), tenantId)) {
      caseGates.save(CaseGate.create(tenantId, legalCase.getId(), gateDef.getId(), "NOT_EVALUATED"));
    }

    for (ValidationDef validationDef :
        validationDefs.findByProcessDefinitionIdAndTenantIdOrderBySortOrderAsc(
            process.getId(), tenantId)) {
      caseValidations.save(
          CaseValidation.create(
              tenantId,
              legalCase.getId(),
              validationDef.getId(),
              validationDef.getCode() + " " + validationDef.getLabel(),
              "Regla determinística",
              "NOT_RUN",
              "v1.0"));
    }

    persistParties(legalCase.getId(), tenantId, request);
    persistCreationEvents(legalCase.getId(), tenantId, userId, request);
    if ("ECD".equals(legalCase.getCaseType())) {
      collectionFiles.save(
          CollectionFile.create(tenantId, legalCase.getId(), request.clientName()));
    }
  }

  private void applyStageSla(LegalCase legalCase, UUID tenantId, ProcessStageDef definition) {
    int hours = slaCalendar.resolveSlaHours(tenantId, definition.getSlaHours());
    legalCase.setSlaDueAt(slaCalendar.computeStageDueAt(tenantId, Instant.now(), hours));
  }

  static int resolveStageIndex(String requestedStage, List<ProcessStageDef> definitions) {
    if (definitions.isEmpty()) {
      return 0;
    }
    if (requestedStage == null || requestedStage.isBlank()) {
      return 0;
    }
    String normalized = requestedStage.trim().toLowerCase(Locale.ROOT);
    for (int index = 0; index < definitions.size(); index++) {
      ProcessStageDef definition = definitions.get(index);
      String label = definition.getLabel().toLowerCase(Locale.ROOT);
      String shortLabel = definition.getShortLabel().toLowerCase(Locale.ROOT);
      if (label.contains(normalized)
          || normalized.contains(label)
          || shortLabel.equals(normalized)
          || normalized.contains(shortLabel)) {
        return index;
      }
    }
    return 0;
  }

  private void persistParties(UUID caseId, UUID tenantId, CreateCaseRequest request) {
    if (request.clientName() != null && !request.clientName().isBlank()) {
      caseParties.save(
          CaseParty.create(
              tenantId,
              caseId,
              "CLIENT",
              request.clientName().trim(),
              "Cliente / referencia",
              blankToNull(request.identification())));
    }
    if (request.participants() == null || request.participants().isBlank()) {
      return;
    }
    for (String raw : request.participants().split("[,;]")) {
      String name = raw.trim();
      if (name.isEmpty()) {
        continue;
      }
      caseParties.save(
          CaseParty.create(tenantId, caseId, "PARTY", name, "Participante", null));
    }
  }

  private void persistCreationEvents(
      UUID caseId, UUID tenantId, UUID userId, CreateCaseRequest request) {
    caseNotes.save(
        CaseNote.create(tenantId, caseId, userId, "Expediente creado en LEXIA."));
    if (request.operationTypeCode() != null && !request.operationTypeCode().isBlank()) {
      caseNotes.save(
          CaseNote.create(
              tenantId,
              caseId,
              userId,
              "Tipo de operación: " + request.operationTypeCode().trim()));
    }
    caseActions.save(
        CaseAction.create(
            tenantId,
            caseId,
            "Expediente creado",
            "CASE_CREATED",
            "COMPLETED",
            userId,
            Instant.now()));
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
