package com.lexia.api.modules.expedientes.coactivas.ia;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoactivaPromptCatalogRepository extends JpaRepository<CoactivaPromptCatalog, Long> {

  Optional<CoactivaPromptCatalog> findFirstByTipoDocumentoAndEtapaAndActivoTrue(
      String tipoDocumento, String etapa);

  List<CoactivaPromptCatalog> findByTipoDocumentoAndEtapaAndActivoTrue(String tipoDocumento, String etapa);

  /**
   * Resuelve el prompt más específico: (tipo, etapa) → (tipo, '*') → ('*', etapa) → ('*', '*').
   */
  default Optional<CoactivaPromptCatalog> resolver(String tipoDocumento, String etapa) {
    String tipo = tipoDocumento == null ? CoactivaPromptCatalog.COMODIN : tipoDocumento;
    String et = etapa == null ? CoactivaPromptCatalog.COMODIN : etapa;
    return findFirstByTipoDocumentoAndEtapaAndActivoTrue(tipo, et)
        .or(() -> findFirstByTipoDocumentoAndEtapaAndActivoTrue(tipo, CoactivaPromptCatalog.COMODIN))
        .or(() -> findFirstByTipoDocumentoAndEtapaAndActivoTrue(CoactivaPromptCatalog.COMODIN, et))
        .or(
            () ->
                findFirstByTipoDocumentoAndEtapaAndActivoTrue(
                    CoactivaPromptCatalog.COMODIN, CoactivaPromptCatalog.COMODIN));
  }
}
