package com.lexia.api.modules.expedientes.proceso;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.ejd.EjdWorkflowAdminDtos.StageGatesAdminView;
import com.lexia.api.modules.expedientes.ejd.EjdWorkflowAdminDtos.StageTransitionsAdminView;
import com.lexia.api.modules.expedientes.tenant.TenantConfigDtos.ProcessConfig;
import com.lexia.api.modules.expedientes.ejd.EjdIntegrationDtos.StageIntegrationsAdminView;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.lexia.api.modules.expedientes.ecd.EcdDocumentRequirementAdminService;
import com.lexia.api.modules.expedientes.ejd.EjdDocumentRequirementAdminService;
import com.lexia.api.modules.expedientes.ejd.EjdIntegrationService;
import com.lexia.api.modules.expedientes.ejd.EjdWorkflowAdminService;
import com.lexia.api.modules.expedientes.sla.OperationalCalendar;
import com.lexia.api.modules.expedientes.sla.OperationalCalendarHolidayRepository;
import com.lexia.api.modules.expedientes.sla.SlaCalendarService;
import com.lexia.api.modules.expedientes.tenant.TenantConfigService;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class ProcessConfigSnapshotService {

  private final TenantConfigService tenantConfigService;
  private final EjdWorkflowAdminService workflowAdmin;
  private final EjdDocumentRequirementAdminService ejdDocumentAdmin;
  private final EcdDocumentRequirementAdminService ecdDocumentAdmin;
  private final EjdIntegrationService ejdIntegrations;
  private final SlaCalendarService slaCalendar;
  private final OperationalCalendarHolidayRepository calendarHolidays;
  private final ObjectMapper objectMapper;

  public ProcessConfigSnapshotService(
      @Lazy TenantConfigService tenantConfigService,
      EjdWorkflowAdminService workflowAdmin,
      EjdDocumentRequirementAdminService ejdDocumentAdmin,
      EcdDocumentRequirementAdminService ecdDocumentAdmin,
      @Lazy EjdIntegrationService ejdIntegrations,
      SlaCalendarService slaCalendar,
      OperationalCalendarHolidayRepository calendarHolidays,
      ObjectMapper objectMapper) {
    this.tenantConfigService = tenantConfigService;
    this.workflowAdmin = workflowAdmin;
    this.ejdDocumentAdmin = ejdDocumentAdmin;
    this.ecdDocumentAdmin = ecdDocumentAdmin;
    this.ejdIntegrations = ejdIntegrations;
    this.slaCalendar = slaCalendar;
    this.calendarHolidays = calendarHolidays;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public String captureSnapshotJson(String caseTypeParam) {
    String caseType = caseTypeParam.trim().toUpperCase(Locale.ROOT);
    ProcessConfig process = tenantConfigService.getProcessConfigForAdmin(caseType);
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("caseType", caseType);
    root.put("configVersion", process.publication().configVersion());
    root.put("stages", process.stages());
    root.put("gates", process.gates());
    root.put("validations", process.validations());

    StageTransitionsAdminView transitions = workflowAdmin.getStageTransitionsForAdmin(caseType);
    StageGatesAdminView stageGates = workflowAdmin.getStageGatesForAdmin(caseType);
    root.put("transitions", transitions.transitions());
    root.put("stageGateRequirements", stageGates.links());
    UUID tenantId = AuthContext.require().tenantId();
    OperationalCalendar calendar = slaCalendar.resolveCalendar(tenantId);
    root.put(
        "operationalCalendar",
        Map.of(
            "slaMode", calendar.getSlaMode(),
            "businessDayStart", calendar.getBusinessDayStart(),
            "businessDayEnd", calendar.getBusinessDayEnd(),
            "holidays",
            calendarHolidays.findByTenantIdOrderByHolidayDateAsc(tenantId).stream()
                .map(h -> Map.of("date", h.getHolidayDate().toString(), "label", h.getLabel()))
                .toList()));

    if ("EJD".equals(caseType)) {
      var docs = ejdDocumentAdmin.list();
      StageIntegrationsAdminView integrations =
          ejdIntegrations.getStageIntegrationsForAdmin("EJD");
      root.put("documentRequirements", docs.requirements());
      root.put("stageIntegrations", integrations.links());
    } else if ("ECD".equals(caseType)) {
      var ecdDocs = ecdDocumentAdmin.list();
      StageIntegrationsAdminView integrations =
          ejdIntegrations.getStageIntegrationsForAdmin("ECD");
      root.put("documentRequirements", ecdDocs.requiredDocuments());
      root.put("stageIntegrations", integrations.links());
    }

    try {
      return objectMapper.writeValueAsString(root);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("No se pudo serializar snapshot de proceso", ex);
    }
  }
}
