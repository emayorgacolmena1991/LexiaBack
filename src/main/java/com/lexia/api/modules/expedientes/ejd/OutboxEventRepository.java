package com.lexia.api.modules.expedientes.ejd;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  /** Vacío si no existe o si otro nodo lo está procesando: el lock dura la transacción. */
  @Query(
      value = "SELECT * FROM app.outbox_event WHERE id = :id FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  Optional<OutboxEvent> bloquearSiLibre(@Param("id") UUID id);
}
