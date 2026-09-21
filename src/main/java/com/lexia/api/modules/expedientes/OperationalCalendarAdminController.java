package com.lexia.api.modules.expedientes;

import com.lexia.api.modules.expedientes.OperationalCalendarAdminDtos.AddHolidayRequest;
import com.lexia.api.modules.expedientes.OperationalCalendarAdminDtos.OperationalCalendarView;
import com.lexia.api.modules.expedientes.OperationalCalendarAdminDtos.UpdateOperationalCalendarRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/operational-calendar")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class OperationalCalendarAdminController {

  private final OperationalCalendarAdminService calendarAdmin;

  public OperationalCalendarAdminController(OperationalCalendarAdminService calendarAdmin) {
    this.calendarAdmin = calendarAdmin;
  }

  @GetMapping
  public OperationalCalendarView get() {
    return calendarAdmin.getForAdmin();
  }

  @PutMapping
  public OperationalCalendarView update(@Valid @RequestBody UpdateOperationalCalendarRequest request) {
    return calendarAdmin.updateCalendar(request);
  }

  @PostMapping("/holidays")
  public OperationalCalendarView addHoliday(@Valid @RequestBody AddHolidayRequest request) {
    return calendarAdmin.addHoliday(request);
  }

  @DeleteMapping("/holidays/{holidayId}")
  public OperationalCalendarView removeHoliday(@PathVariable UUID holidayId) {
    return calendarAdmin.removeHoliday(holidayId);
  }
}
