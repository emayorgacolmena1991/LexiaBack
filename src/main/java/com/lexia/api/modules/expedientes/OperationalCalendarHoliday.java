package com.lexia.api.modules.expedientes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "operational_calendar_holiday")
public class OperationalCalendarHoliday {

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "holiday_date", nullable = false)
  private LocalDate holidayDate;

  @Column(nullable = false, length = 160)
  private String label;

  public static OperationalCalendarHoliday create(
      UUID tenantId, LocalDate holidayDate, String label) {
    OperationalCalendarHoliday row = new OperationalCalendarHoliday();
    row.id = UUID.randomUUID();
    row.tenantId = tenantId;
    row.holidayDate = holidayDate;
    row.label = label.trim();
    return row;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public LocalDate getHolidayDate() {
    return holidayDate;
  }

  public String getLabel() {
    return label;
  }
}
