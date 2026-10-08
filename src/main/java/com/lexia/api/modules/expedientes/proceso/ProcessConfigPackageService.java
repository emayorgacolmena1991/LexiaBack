package com.lexia.api.modules.expedientes.proceso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ProcessConfigExportPackage;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ProcessConfigImportPreviewView;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigPublishDtos.ProcessConfigImpactView;
import com.lexia.api.modules.identity.AuthorizationService;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class ProcessConfigPackageService {

  private static final int EXPORT_VERSION = 1;

  private final ProcessDefinitionRepository processDefinitions;
  private final ProcessConfigSnapshotService snapshotService;
  private final ProcessConfigDiffService diffService;
  private final ProcessConfigImpactService impactService;
  private final ProcessConfigPublicationRepository publications;
  private final AuthorizationService authorization;
  private final ObjectMapper objectMapper;

  public ProcessConfigPackageService(
      ProcessDefinitionRepository processDefinitions,
      ProcessConfigSnapshotService snapshotService,
      ProcessConfigDiffService diffService,
      ProcessConfigImpactService impactService,
      ProcessConfigPublicationRepository publications,
      AuthorizationService authorization,
      ObjectMapper objectMapper) {
    this.processDefinitions = processDefinitions;
    this.snapshotService = snapshotService;
    this.diffService = diffService;
    this.impactService = impactService;
    this.publications = publications;
    this.authorization = authorization;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public ProcessConfigExportPackage export(String caseTypeParam) {
    authorization.requirePermission("admin:proceso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    ProcessDefinition process = requireProcess(tenantId, caseTypeParam);
    String snapshotJson = snapshotService.captureSnapshotJson(process.getCaseType());
    return new ProcessConfigExportPackage(
        EXPORT_VERSION,
        process.getCaseType(),
        Instant.now(),
        process.getConfigVersion(),
        snapshotJson);
  }

  @Transactional(readOnly = true)
  public ProcessConfigImportPreviewView previewImport(String caseTypeParam, String payloadJson) {
    authorization.requirePermission("admin:proceso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    ProcessDefinition process = requireProcess(tenantId, caseTypeParam);
    String snapshotJson = extractSnapshot(payloadJson, process.getCaseType());
    int activeVersion = process.getConfigVersion();
    String activeJson =
        publications
            .findByTenantIdAndProcessDefinitionIdAndConfigVersion(
                tenantId, process.getId(), activeVersion)
            .map(ProcessConfigPublication::getSnapshotJson)
            .orElse(null);
    var diff = diffService.diff(activeVersion, activeVersion, activeJson, snapshotJson);
    ProcessConfigImpactView impact =
        impactService.analyzeImportedSnapshot(process.getCaseType(), snapshotJson);
    return new ProcessConfigImportPreviewView(
        diff,
        impact,
        true,
        "Vista previa sin aplicar cambios. Use la parametrización admin para incorporar ajustes.");
  }

  private String extractSnapshot(String payloadJson, String expectedCaseType) {
    if (!StringUtils.hasText(payloadJson)) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "IMPORT_EMPTY", "Paquete de importación vacío.");
    }
    try {
      JsonNode root = objectMapper.readTree(payloadJson);
      if (root.has("snapshot")) {
        JsonNode snapshot = root.get("snapshot");
        if (root.has("caseType")) {
          String packType = root.get("caseType").asText("").trim().toUpperCase(Locale.ROOT);
          if (!expectedCaseType.equals(packType)) {
            throw new AuthException(
                HttpStatus.BAD_REQUEST,
                "IMPORT_CASE_TYPE_MISMATCH",
                "El paquete es para " + packType + " pero la ruta es " + expectedCaseType + ".");
          }
        }
        return objectMapper.writeValueAsString(snapshot);
      }
      if (root.has("stages")) {
        return payloadJson;
      }
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "IMPORT_FORMAT", "Formato de paquete no reconocido.");
    } catch (AuthException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST, "IMPORT_INVALID", "JSON inválido: " + ex.getMessage());
    }
  }

  private ProcessDefinition requireProcess(UUID tenantId, String caseTypeParam) {
    String caseType = caseTypeParam.trim().toUpperCase(Locale.ROOT);
    return processDefinitions
        .findByTenantIdAndCaseTypeAndDeletedAtIsNull(tenantId, caseType)
        .orElseThrow(
            () ->
                new AuthException(
                    HttpStatus.NOT_FOUND,
                    "PROCESS_NOT_FOUND",
                    "No hay definición de proceso para " + caseType + "."));
  }
}
