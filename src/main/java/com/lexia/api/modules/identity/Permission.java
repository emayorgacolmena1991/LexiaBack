package com.lexia.api.modules.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "permission")
public class Permission {

  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 96)
  private String code;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(name = "module_code", nullable = false, length = 64)
  private String moduleCode;

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }
}
