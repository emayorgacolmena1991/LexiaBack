package com.lexia.api.modules.admin;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/roles")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class AdminRoleController {

  private final RoleAdminService roleAdminService;

  public AdminRoleController(RoleAdminService roleAdminService) {
    this.roleAdminService = roleAdminService;
  }

  @GetMapping("/permissions")
  public List<AdminDtos.PermissionOption> permissions() {
    return roleAdminService.listPermissions();
  }

  @GetMapping
  public List<AdminDtos.RoleDetail> roles() {
    return roleAdminService.listRoles();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AdminDtos.RoleDetail create(@Valid @RequestBody AdminDtos.CreateRoleRequest request) {
    return roleAdminService.createRole(request);
  }

  @PutMapping("/{roleId}")
  public AdminDtos.RoleDetail update(
      @PathVariable UUID roleId, @Valid @RequestBody AdminDtos.UpdateRoleRequest request) {
    return roleAdminService.updateRole(roleId, request);
  }

  @DeleteMapping("/{roleId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID roleId) {
    roleAdminService.deleteRole(roleId);
  }
}
