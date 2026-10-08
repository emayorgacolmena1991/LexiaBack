package com.lexia.api.modules.expedientes.sla;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationalCalendarRepository extends JpaRepository<OperationalCalendar, UUID> {}
