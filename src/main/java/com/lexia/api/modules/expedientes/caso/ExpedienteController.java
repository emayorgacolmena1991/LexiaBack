package com.lexia.api.modules.expedientes.caso;

import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CaseDetailItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CaseSummaryItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CreateCaseRequest;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ResolveGateRequest;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.StageAdvanceRequest;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.StageRevertRequest;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ExpedienteExtraidoDTO;
import com.lexia.api.modules.expedientes.caso.WorkspaceDtos.WorkspaceResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.lexia.api.modules.expedientes.documentos.DocumentExtractorService;
import com.lexia.api.modules.expedientes.ejd.EjdConnectorDispatchService;

@RestController
@RequestMapping("/api/v1/expedientes")
public class ExpedienteController {

  private final DocumentExtractorService extractorService;
  private final CaseService caseService;
  private final WorkspaceService workspaceService;
  private final CaseStageTransitionService stageTransitionService;
  private final CaseGateResolutionService gateResolutionService;
  private final EjdConnectorDispatchService connectorDispatch;
  private final CaseExceptionResolutionService exceptionResolutionService;

  public ExpedienteController(
      DocumentExtractorService extractorService,
      @org.springframework.beans.factory.annotation.Autowired(required = false) CaseService caseService,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          WorkspaceService workspaceService,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          CaseStageTransitionService stageTransitionService,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          CaseGateResolutionService gateResolutionService,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          EjdConnectorDispatchService connectorDispatch,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          CaseExceptionResolutionService exceptionResolutionService) {
    this.extractorService = extractorService;
    this.caseService = caseService;
    this.workspaceService = workspaceService;
    this.stageTransitionService = stageTransitionService;
    this.gateResolutionService = gateResolutionService;
    this.connectorDispatch = connectorDispatch;
    this.exceptionResolutionService = exceptionResolutionService;
  }

  @PostMapping("/{id}/exceptions/{exceptionId}/resolve")
  public ResponseEntity<WorkspaceResponse> resolveException(
      @PathVariable UUID id,
      @PathVariable UUID exceptionId,
      @Valid @RequestBody ExpedienteDtos.ResolveExceptionRequest request) {
    if (exceptionResolutionService == null || workspaceService == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    exceptionResolutionService.resolve(id, exceptionId, request);
    return ResponseEntity.ok(workspaceService.getWorkspace(id));
  }

  @PostMapping("/{id}/integrations/{connectorCode}/invoke")
  public ResponseEntity<WorkspaceResponse> invokeConnector(
      @PathVariable UUID id, @PathVariable String connectorCode) {
    if (connectorDispatch == null || workspaceService == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    connectorDispatch.invokeManually(id, connectorCode);
    return ResponseEntity.ok(workspaceService.getWorkspace(id));
  }

  @PostMapping(value = "/procesar-documentos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ExpedienteExtraidoDTO> procesarDocumentos(
      @RequestParam("archivos") List<MultipartFile> archivos) {
    if (archivos == null || archivos.isEmpty()) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(extractorService.extraerInformacion(archivos));
  }

  @GetMapping
  public List<CaseSummaryItem> listCases() {
    if (caseService == null) {
      return List.of();
    }
    return caseService.listCases();
  }

  @GetMapping("/{id}")
  public ResponseEntity<CaseDetailItem> getCase(@PathVariable UUID id) {
    if (caseService == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(caseService.getCase(id));
  }

  @GetMapping("/{id}/workspace")
  public ResponseEntity<WorkspaceResponse> getWorkspace(@PathVariable UUID id) {
    if (workspaceService == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(workspaceService.getWorkspace(id));
  }

  @PostMapping("/{id}/gates/{gateId}/resolve")
  public ResponseEntity<WorkspaceResponse> resolveGate(
      @PathVariable UUID id,
      @PathVariable UUID gateId,
      @Valid @RequestBody ResolveGateRequest request) {
    if (gateResolutionService == null || workspaceService == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    gateResolutionService.resolve(id, gateId, request);
    return ResponseEntity.ok(workspaceService.getWorkspace(id));
  }

  @PostMapping("/{id}/stages/advance")
  public ResponseEntity<WorkspaceResponse> advanceStage(
      @PathVariable UUID id, @Valid @RequestBody(required = false) StageAdvanceRequest request) {
    if (stageTransitionService == null || workspaceService == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    stageTransitionService.advance(id, request == null ? new StageAdvanceRequest(null, null) : request);
    return ResponseEntity.ok(workspaceService.getWorkspace(id));
  }

  @PostMapping("/{id}/stages/revert")
  public ResponseEntity<WorkspaceResponse> revertStage(
      @PathVariable UUID id, @Valid @RequestBody StageRevertRequest request) {
    if (stageTransitionService == null || workspaceService == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    stageTransitionService.revert(id, request);
    return ResponseEntity.ok(workspaceService.getWorkspace(id));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CaseDetailItem createCase(@Valid @RequestBody CreateCaseRequest request) {
    if (caseService == null) {
      throw new IllegalStateException("Persistencia de expedientes no disponible.");
    }
    return caseService.createCase(request);
  }
}
