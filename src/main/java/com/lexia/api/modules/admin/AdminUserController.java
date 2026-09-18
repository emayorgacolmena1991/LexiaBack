package com.lexia.api.modules.admin;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class AdminUserController {

  private final UserAdminService userAdminService;

  public AdminUserController(UserAdminService userAdminService) {
    this.userAdminService = userAdminService;
  }

  @GetMapping("/roles")
  public List<AdminDtos.RoleOption> roles() {
    return userAdminService.listRoles();
  }

  @GetMapping("/invitations")
  public List<AdminDtos.InvitationItem> invitations() {
    return userAdminService.listInvitations();
  }

  @PostMapping("/invite")
  @ResponseStatus(HttpStatus.CREATED)
  public AdminDtos.InviteUserResponse invite(@Valid @RequestBody AdminDtos.InviteUserRequest request) {
    return userAdminService.invite(request);
  }
}
