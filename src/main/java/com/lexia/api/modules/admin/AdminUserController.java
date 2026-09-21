package com.lexia.api.modules.admin;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
  public AdminDtos.PageResponse<AdminDtos.InvitationItem> invitations(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String role,
      @RequestParam(required = false) String status) {
    return userAdminService.listInvitations(page, size, q, role, status);
  }

  @GetMapping("/members")
  public AdminDtos.PageResponse<AdminDtos.TenantMemberItem> members(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String role,
      @RequestParam(required = false) String access,
      @RequestParam(required = false) String mfa) {
    return userAdminService.listMembers(page, size, q, role, access, mfa);
  }

  @PostMapping("/invitations/{invitationId}/revoke")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeInvitation(@PathVariable UUID invitationId) {
    userAdminService.revokeInvitation(invitationId);
  }

  @PostMapping("/invitations/{invitationId}/resend")
  @ResponseStatus(HttpStatus.CREATED)
  public AdminDtos.InviteUserResponse resendInvitation(@PathVariable UUID invitationId) {
    return userAdminService.resendInvitation(invitationId);
  }

  @PatchMapping("/members/{membershipId}/role")
  public AdminDtos.TenantMemberItem updateMemberRole(
      @PathVariable UUID membershipId, @Valid @RequestBody AdminDtos.UpdateMemberRoleRequest request) {
    return userAdminService.updateMemberRole(membershipId, request.roleCode());
  }

  @PostMapping("/members/{membershipId}/suspend")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void suspendMember(@PathVariable UUID membershipId) {
    userAdminService.suspendMember(membershipId);
  }

  @PostMapping("/members/{membershipId}/reactivate")
  public AdminDtos.TenantMemberItem reactivateMember(@PathVariable UUID membershipId) {
    return userAdminService.reactivateMember(membershipId);
  }

  @PostMapping("/members/{membershipId}/unlock")
  public AdminDtos.TenantMemberItem unlockMember(@PathVariable UUID membershipId) {
    return userAdminService.unlockMember(membershipId);
  }

  @PostMapping("/members/{membershipId}/mfa/reset")
  public AdminDtos.TenantMemberItem resetMemberMfa(@PathVariable UUID membershipId) {
    return userAdminService.resetMemberMfa(membershipId);
  }

  @PostMapping("/invite")
  @ResponseStatus(HttpStatus.CREATED)
  public AdminDtos.InviteUserResponse invite(@Valid @RequestBody AdminDtos.InviteUserRequest request) {
    return userAdminService.invite(request);
  }
}
