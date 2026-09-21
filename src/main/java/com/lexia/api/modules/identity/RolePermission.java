package com.lexia.api.modules.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "role_permission")
@IdClass(RolePermission.Pk.class)
public class RolePermission {

  @Id
  @Column(name = "role_id", nullable = false)
  private UUID roleId;

  @Id
  @Column(name = "permission_id", nullable = false)
  private UUID permissionId;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  public UUID getRoleId() {
    return roleId;
  }

  public UUID getPermissionId() {
    return permissionId;
  }

  public static RolePermission assign(UUID roleId, UUID permissionId, UUID tenantId) {
    RolePermission row = new RolePermission();
    row.roleId = roleId;
    row.permissionId = permissionId;
    row.tenantId = tenantId;
    return row;
  }

  public static class Pk implements Serializable {
    private UUID roleId;
    private UUID permissionId;

    public Pk() {}

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Pk pk)) {
        return false;
      }
      return Objects.equals(roleId, pk.roleId) && Objects.equals(permissionId, pk.permissionId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(roleId, permissionId);
    }
  }
}
