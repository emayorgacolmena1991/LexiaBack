package com.lexia.api.modules.expedientes;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class OperationalCalendarAdminDtos {

  private OperationalCalendarAdminDtos() {}

  public record HolidayItem(UUID id, LocalDate holidayDate, String label) {}

  public record SlaPolicyItem(UUID id, String code, String name, int hoursLimit) {}

  public record OperationalCalendarView(
      String slaMode,
      int businessDayStart,
      int businessDayEnd,
      String timezone,
      List<HolidayItem> holidays,
      List<SlaPolicyItem> slaPolicies) {}

  public record UpdateOperationalCalendarRequest(
      @NotBlank @Pattern(regexp = "CONTINUOUS|BUSINESS_HOURS") String slaMode,
      @Min(0) @Max(23) int businessDayStart,
      @Min(1) @Max(24) int businessDayEnd) {}

  public record AddHolidayRequest(
      @NotNull LocalDate holidayDate, @NotBlank @Size(max = 160) String label) {}
}
