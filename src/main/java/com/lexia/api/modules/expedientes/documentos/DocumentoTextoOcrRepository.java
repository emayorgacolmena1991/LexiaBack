package com.lexia.api.modules.expedientes.documentos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentoTextoOcrRepository extends JpaRepository<DocumentoTextoOcr, UUID> {

  Optional<DocumentoTextoOcr> findByIdExpedienteAndIdDocumento(
      String idExpediente, String idDocumento);

  List<DocumentoTextoOcr> findByIdExpedienteOrderByCreatedAtAsc(String idExpediente);
}
