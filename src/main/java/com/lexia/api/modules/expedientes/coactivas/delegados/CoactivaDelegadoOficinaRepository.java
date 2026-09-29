package com.lexia.api.modules.expedientes.coactivas.delegados;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoactivaDelegadoOficinaRepository
    extends JpaRepository<CoactivaDelegadoOficina, CoactivaDelegadoOficina.Key> {

  List<CoactivaDelegadoOficina> findByTenantId(UUID tenantId);

  @Query("select r from CoactivaDelegadoOficina r where r.tenantId = :tenantId and r.id.oficinaCodigo = :oficina")
  List<CoactivaDelegadoOficina> findByOficina(
      @Param("tenantId") UUID tenantId, @Param("oficina") String oficinaCodigo);

  @Modifying
  @Query("delete from CoactivaDelegadoOficina r where r.tenantId = :tenantId and r.id.delegadoId = :delegadoId")
  void deleteByDelegado(@Param("tenantId") UUID tenantId, @Param("delegadoId") UUID delegadoId);
}
