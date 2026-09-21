package com.lexia.api.modules.actos;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class ActosDtos {

  private ActosDtos() {}

  public record ActoNotarialListadoDTO(String idActo, String nombreActo, int totalRequisitos) {}

  public record DocumentoRequeridoDTO(
      String codigoDocumento, String nombre, boolean obligatorio, String descripcion) {}

  public record ActoNotarialRespuestaDTO(
      String idActo,
      String nombreActo,
      int totalRequisitos,
      @JsonProperty("documentos") @JsonAlias("documentosRequeridos")
          List<DocumentoRequeridoDTO> documentos) {

    public ActoNotarialRespuestaDTO(
        String idActo, String nombreActo, List<DocumentoRequeridoDTO> documentos) {
      this(idActo, nombreActo, documentos == null ? 0 : documentos.size(), documentos);
    }
  }
}
