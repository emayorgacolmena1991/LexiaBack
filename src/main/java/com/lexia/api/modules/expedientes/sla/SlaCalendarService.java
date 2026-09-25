package com.lexia.api.modules.expedientes.sla;

import com.lexia.api.modules.tenancy.Tenant;
import com.lexia.api.modules.tenancy.TenantParameterService;
import com.lexia.api.modules.tenancy.TenantRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class SlaCalendarService {

  private final OperationalCalendarRepository calendars;
  private final OperationalCalendarHolidayRepository holidays;
  private final TenantRepository tenants;
  private final TenantParameterService tenantParameters;

  public SlaCalendarService(
      OperationalCalendarRepository calendars,
      OperationalCalendarHolidayRepository holidays,
      TenantRepository tenants,
      TenantParameterService tenantParameters) {
    this.calendars = calendars;
    this.holidays = holidays;
    this.tenants = tenants;
    this.tenantParameters = tenantParameters;
  }

  @Transactional(readOnly = true)
  public Instant computeStageDueAt(UUID tenantId, Instant start, int slaHours) {
    OperationalCalendar calendar = resolveCalendar(tenantId);
    if (!calendar.isBusinessHoursMode()) {
      return start.plus(slaHours, ChronoUnit.HOURS);
    }
    ZoneId zone = resolveZone(tenantId);
    Set<LocalDate> holidayDates = loadHolidayDates(tenantId);
    return addBusinessHours(
        start, slaHours, zone, calendar.getBusinessDayStart(), calendar.getBusinessDayEnd(), holidayDates);
  }

  @Transactional(readOnly = true)
  public OperationalCalendar resolveCalendar(UUID tenantId) {
    return calendars.findById(tenantId).orElseGet(() -> OperationalCalendar.defaults(tenantId));
  }

  static Instant addBusinessHours(
      Instant start,
      int slaHours,
      ZoneId zone,
      int dayStartHour,
      int dayEndHour,
      Set<LocalDate> holidayDates) {
    if (slaHours <= 0) {
      return start;
    }
    LocalDateTime cursor = LocalDateTime.ofInstant(start, zone);
    int remaining = slaHours;
    int guard = 0;
    while (remaining > 0 && guard++ < 8760) {
      LocalDate date = cursor.toLocalDate();
      if (isNonWorkingDay(date, holidayDates)) {
        cursor = date.plusDays(1).atTime(dayStartHour, 0);
        continue;
      }
      if (cursor.getHour() < dayStartHour) {
        cursor = cursor.withHour(dayStartHour).withMinute(0).withSecond(0).withNano(0);
      } else if (cursor.getHour() >= dayEndHour) {
        cursor = date.plusDays(1).atTime(dayStartHour, 0);
        continue;
      }
      int available = dayEndHour - cursor.getHour();
      int chunk = Math.min(remaining, available);
      cursor = cursor.plusHours(chunk);
      remaining -= chunk;
    }
    return cursor.atZone(zone).toInstant();
  }

  private Set<LocalDate> loadHolidayDates(UUID tenantId) {
    Set<LocalDate> dates = new HashSet<>();
    for (OperationalCalendarHoliday row : holidays.findByTenantIdOrderByHolidayDateAsc(tenantId)) {
      dates.add(row.getHolidayDate());
    }
    return dates;
  }

  private ZoneId resolveZone(UUID tenantId) {
    return tenants
        .findById(tenantId)
        .map(Tenant::getTimezone)
        .filter(tz -> tz != null && !tz.isBlank())
        .map(ZoneId::of)
        .orElse(ZoneId.of("America/Bogota"));
  }

  private static boolean isNonWorkingDay(LocalDate date, Set<LocalDate> holidayDates) {
    DayOfWeek dow = date.getDayOfWeek();
    if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
      return true;
    }
    return holidayDates.contains(date);
  }

  @Transactional(readOnly = true)
  public int resolveSlaHours(UUID tenantId, Integer stageSlaHours) {
    return stageSlaHours != null ? stageSlaHours : tenantParameters.getSlaDefaultHours(tenantId);
  }
}
