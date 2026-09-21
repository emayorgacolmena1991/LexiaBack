package com.lexia.api.modules.expedientes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class CargaDocumentoDtos {

  private CargaDocumentoDtos() {}

  public record CrearBorradorRequest(@NotBlank @Size(max = 64) String idActo) {}

  public record BorradorResponse(String idExpediente, String idActo, String estado) {}

  public record TipoPermitidoDTO(String codigo, String nombre) {}

  public record DocumentoCargadoDTO(
      String idDocumento, String nombreOriginal, String tamano, String codigoTipoDocumento) {}

  public record ActualizarTipoRequest(@NotBlank @Size(max = 64) String codigoTipoDocumento) {}

  public record TipoActualizadoDTO(String idDocumento, String codigoTipoDocumento, String estado) {}

  public record IniciarProcesamientoResponse(
      String idExpediente, String estado, int siguientePaso, String mensaje) {}

  public record ListaDocumentosResponse(List<DocumentoCargadoDTO> documentos) {}
}
