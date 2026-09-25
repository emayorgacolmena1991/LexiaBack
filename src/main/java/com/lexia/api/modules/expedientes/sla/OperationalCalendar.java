package com.lexia.api.modules.expedientes.sla;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "operational_calendar")
public class OperationalCalendar {

  @Id
  @Column(name = "tenant_id")
  private UUID tenantId;

  @Column(name = "sla_mode", nullable = false, length = 24)
  private String slaMode;

  @Column(name = "business_day_start", nullable = false, columnDefinition = "SMALLINT")
  private short businessDayStart;

  @Column(name = "business_day_end", nullable = false, columnDefinition = "SMALLINT")
  private short businessDayEnd;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public static OperationalCalendar defaults(UUID tenantId) {
    OperationalCalendar row = new OperationalCalendar();
    row.tenantId = tenantId;
    row.slaMode = "CONTINUOUS";
    row.businessDayStart = (short) 8;
    row.businessDayEnd = (short) 18;
    row.updatedAt = Instant.now();
    return row;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getSlaMode() {
    return slaMode;
  }

  public int getBusinessDayStart() {
    return businessDayStart;
  }

  public int getBusinessDayEnd() {
    return businessDayEnd;
  }

  public void apply(String slaMode, int businessDayStart, int businessDayEnd) {
    this.slaMode = slaMode;
    this.businessDayStart = (short) businessDayStart;
    this.businessDayEnd = (short) businessDayEnd;
    this.updatedAt = Instant.now();
  }

  public boolean isBusinessHoursMode() {
    return "BUSINESS_HOURS".equals(slaMode);
  }
}
