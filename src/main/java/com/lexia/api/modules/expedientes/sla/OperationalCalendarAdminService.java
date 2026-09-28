package com.lexia.api.modules.expedientes.sla;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.expedientes.sla.OperationalCalendarAdminDtos.AddHolidayRequest;
import com.lexia.api.modules.expedientes.sla.OperationalCalendarAdminDtos.HolidayItem;
import com.lexia.api.modules.expedientes.sla.OperationalCalendarAdminDtos.OperationalCalendarView;
import com.lexia.api.modules.expedientes.sla.OperationalCalendarAdminDtos.SlaPolicyItem;
import com.lexia.api.modules.expedientes.sla.OperationalCalendarAdminDtos.UpdateOperationalCalendarRequest;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.tenancy.Tenant;
import com.lexia.api.modules.tenancy.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.lexia.api.modules.expedientes.proceso.ChangeSetDomain;
import com.lexia.api.modules.expedientes.proceso.ProcessConfigChangeService;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class OperationalCalendarAdminService {

  private final OperationalCalendarRepository calendars;
  private final OperationalCalendarHolidayRepository holidays;
  private final SlaPolicyRepository slaPolicies;
  private final TenantRepository tenants;
  private final AuthorizationService authorization;
  private final AuditEventRepository auditEvents;
  private final ProcessConfigChangeService configChanges;

  public OperationalCalendarAdminService(
      OperationalCalendarRepository calendars,
      OperationalCalendarHolidayRepository holidays,
      SlaPolicyRepository slaPolicies,
      TenantRepository tenants,
      AuthorizationService authorization,
      AuditEventRepository auditEvents,
      ProcessConfigChangeService configChanges) {
    this.calendars = calendars;
    this.holidays = holidays;
    this.slaPolicies = slaPolicies;
    this.tenants = tenants;
    this.authorization = authorization;
    this.auditEvents = auditEvents;
    this.configChanges = configChanges;
  }

  @Transactional(readOnly = true)
  public OperationalCalendarView getForAdmin() {
    authorization.requirePermission("admin:tenant:leer");
    UUID tenantId = AuthContext.require().tenantId();
    OperationalCalendar calendar =
        calendars.findById(tenantId).orElseGet(() -> OperationalCalendar.defaults(tenantId));
    String timezone =
        tenants.findById(tenantId).map(Tenant::getTimezone).orElse("America/Bogota");
    List<HolidayItem> holidayItems =
        holidays.findByTenantIdOrderByHolidayDateAsc(tenantId).stream()
            .map(row -> new HolidayItem(row.getId(), row.getHolidayDate(), row.getLabel()))
            .toList();
    List<SlaPolicyItem> policies =
        slaPolicies.findByTenantIdOrderByCodeAsc(tenantId).stream()
            .map(
                row ->
                    new SlaPolicyItem(
                        row.getId(), row.getCode(), row.getName(), row.getHoursLimit()))
            .toList();
    return new OperationalCalendarView(
        calendar.getSlaMode(),
        calendar.getBusinessDayStart(),
        calendar.getBusinessDayEnd(),
        timezone,
        holidayItems,
        policies);
  }

  @Transactional
  public OperationalCalendarView updateCalendar(UpdateOperationalCalendarRequest request) {
    authorization.requirePermission("admin:tenant:escribir");
    if (request.businessDayEnd() <= request.businessDayStart()) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST,
          "INVALID_BUSINESS_HOURS",
          "La hora de cierre debe ser posterior al inicio.");
    }
    UUID tenantId = AuthContext.require().tenantId();
    OperationalCalendar calendar =
        calendars
            .findById(tenantId)
            .orElseGet(() -> OperationalCalendar.defaults(tenantId));
    calendar.apply(
        request.slaMode(), request.businessDayStart(), request.businessDayEnd());
    calendars.save(calendar);
    markProcessDrafts(tenantId);
    audit("admin.calendar.updated", tenantId);
    return getForAdmin();
  }

  @Transactional
  public OperationalCalendarView addHoliday(AddHolidayRequest request) {
    authorization.requirePermission("admin:tenant:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    if (holidays.existsByTenantIdAndHolidayDate(tenantId, request.holidayDate())) {
      throw new AuthException(
          HttpStatus.CONFLICT, "HOLIDAY_EXISTS", "Ya existe un feriado en esa fecha.");
    }
    holidays.save(
        OperationalCalendarHoliday.create(
            tenantId, request.holidayDate(), request.label()));
    markProcessDrafts(tenantId);
    audit("admin.calendar.holiday.added", tenantId);
    return getForAdmin();
  }

  @Transactional
  public OperationalCalendarView removeHoliday(UUID holidayId) {
    authorization.requirePermission("admin:tenant:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    OperationalCalendarHoliday row =
        holidays
            .findById(holidayId)
            .filter(item -> item.getTenantId().equals(tenantId))
            .orElseThrow(
                () ->
                    new AuthException(
                        HttpStatus.NOT_FOUND, "HOLIDAY_NOT_FOUND", "Feriado no encontrado."));
    holidays.delete(row);
    markProcessDrafts(tenantId);
    audit("admin.calendar.holiday.removed", tenantId);
    return getForAdmin();
  }

  private void markProcessDrafts(UUID tenantId) {
    configChanges.markDraftByCaseType(
        tenantId,
        "EJD",
        ChangeSetDomain.CALENDAR,
        "Calendario operacional o feriados (afecta SLA)",
        "operational_calendar");
    configChanges.markDraftByCaseType(
        tenantId,
        "ECD",
        ChangeSetDomain.CALENDAR,
        "Calendario operacional o feriados (afecta SLA)",
        "operational_calendar");
  }

  private void audit(String action, UUID tenantId) {
    UUID userId = AuthContext.require().userId();
    HttpServletRequest http = currentRequest();
    auditEvents.save(
        AuditEvent.of(
            tenantId,
            userId,
            action,
            "operational_calendar",
            tenantId,
            "OK",
            http != null ? http.getRemoteAddr() : null,
            http != null ? http.getHeader("User-Agent") : null));
  }

  private static HttpServletRequest currentRequest() {
    var attrs = RequestContextHolder.getRequestAttributes();
    if (attrs instanceof ServletRequestAttributes servlet) {
      return servlet.getRequest();
    }
    return null;
  }
}
