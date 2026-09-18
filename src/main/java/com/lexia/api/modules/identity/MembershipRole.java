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
@Table(schema = "app", name = "membership_role")
@IdClass(MembershipRole.Pk.class)
public class MembershipRole {

  @Id
  @Column(name = "membership_id", nullable = false)
  private UUID membershipId;

  @Id
  @Column(name = "role_id", nullable = false)
  private UUID roleId;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  public UUID getMembershipId() {
    return membershipId;
  }

  public UUID getRoleId() {
    return roleId;
  }

  public static MembershipRole assign(UUID membershipId, UUID roleId, UUID tenantId) {
    MembershipRole link = new MembershipRole();
    link.membershipId = membershipId;
    link.roleId = roleId;
    link.tenantId = tenantId;
    return link;
  }

  public static class Pk implements Serializable {
    private UUID membershipId;
    private UUID roleId;

    public Pk() {}

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Pk pk)) {
        return false;
      }
      return Objects.equals(membershipId, pk.membershipId) && Objects.equals(roleId, pk.roleId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(membershipId, roleId);
    }
  }
}
