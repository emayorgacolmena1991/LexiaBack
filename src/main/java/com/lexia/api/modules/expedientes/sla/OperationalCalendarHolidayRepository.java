package com.lexia.api.modules.expedientes.sla;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationalCalendarHolidayRepository
    extends JpaRepository<OperationalCalendarHoliday, UUID> {

  List<OperationalCalendarHoliday> findByTenantIdOrderByHolidayDateAsc(UUID tenantId);

  boolean existsByTenantIdAndHolidayDate(UUID tenantId, LocalDate holidayDate);

  void deleteByTenantIdAndHolidayDate(UUID tenantId, LocalDate holidayDate);
}
