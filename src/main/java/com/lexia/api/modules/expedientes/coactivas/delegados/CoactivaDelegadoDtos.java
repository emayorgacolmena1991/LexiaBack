package com.lexia.api.modules.expedientes.coactivas.delegados;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class CoactivaDelegadoDtos {

  private CoactivaDelegadoDtos() {}

  public record OficinaItem(
      UUID id, String codigo, String nombre, String provincia, boolean activo, int sortOrder) {}

  public record OficinaRequest(
      @NotBlank @Size(max = 32) String codigo,
      @NotBlank @Size(max = 120) String nombre,
      @Size(max = 80) String provincia,
      Boolean activo,
      Integer sortOrder) {}

  public record DelegadoItem(
      UUID id,
      String nombre,
      String identificacion,
      String cargo,
      String resolucionNumero,
      LocalDate resolucionFecha,
      LocalDate vigenteDesde,
      LocalDate vigenteHasta,
      String email,
      boolean activo,
      boolean vigente,
      List<String> oficinas) {}

  public record DelegadoRequest(
      @NotBlank @Size(max = 200) String nombre,
      @Size(max = 20) String identificacion,
      @Size(max = 160) String cargo,
      @Size(max = 120) String resolucionNumero,
      LocalDate resolucionFecha,
      LocalDate vigenteDesde,
      LocalDate vigenteHasta,
      @Size(max = 200) String email,
      Boolean activo,
      List<String> oficinas) {}

  public record ResolucionDelegadoRequest(
      @Size(max = 120) String resolucionNumero, LocalDate resolucionFecha) {}

  public record CatalogosResponse(List<OficinaItem> oficinas, List<DelegadoItem> delegados) {}
}
