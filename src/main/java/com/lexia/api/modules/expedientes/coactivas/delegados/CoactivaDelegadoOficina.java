package com.lexia.api.modules.expedientes.coactivas.delegados;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(schema = "app", name = "coactiva_delegado_oficina")
public class CoactivaDelegadoOficina {

  @EmbeddedId private Key id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  public static CoactivaDelegadoOficina create(UUID tenantId, UUID delegadoId, String oficinaCodigo) {
    CoactivaDelegadoOficina row = new CoactivaDelegadoOficina();
    row.id = new Key(delegadoId, oficinaCodigo);
    row.tenantId = tenantId;
    return row;
  }

  public UUID getDelegadoId() {
    return id.delegadoId;
  }

  public String getOficinaCodigo() {
    return id.oficinaCodigo;
  }

  @Embeddable
  public static class Key implements Serializable {

    @Column(name = "delegado_id", nullable = false)
    private UUID delegadoId;

    @Column(name = "oficina_codigo", nullable = false, length = 32)
    private String oficinaCodigo;

    protected Key() {}

    public Key(UUID delegadoId, String oficinaCodigo) {
      this.delegadoId = delegadoId;
      this.oficinaCodigo = oficinaCodigo;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Key key)) {
        return false;
      }
      return Objects.equals(delegadoId, key.delegadoId)
          && Objects.equals(oficinaCodigo, key.oficinaCodigo);
    }

    @Override
    public int hashCode() {
      return Objects.hash(delegadoId, oficinaCodigo);
    }
  }
}
