package com.lexia.api.modules.admin;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/e2e")
@ConditionalOnProperty(name = "lexia.e2e.enabled", havingValue = "true")
public class AdminE2eController {

  private final UserAdminService userAdminService;

  public AdminE2eController(UserAdminService userAdminService) {
    this.userAdminService = userAdminService;
  }

  @PostMapping("/invite")
  @ResponseStatus(HttpStatus.CREATED)
  public AdminDtos.E2eInviteResponse invite(@Valid @RequestBody AdminDtos.InviteUserRequest request) {
    return userAdminService.inviteForE2e(request);
  }
}
