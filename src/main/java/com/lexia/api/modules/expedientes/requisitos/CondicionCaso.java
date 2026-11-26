package com.lexia.api.modules.expedientes.requisitos;

import java.util.Locale;
import java.util.Optional;

/**
 * Condiciones del caso que cambian los documentos sugeridos (códigos fijos, mismos que el CHECK de
 * V120). Solo las confirmadas por el abogado cambian los requisitos.
 */
public enum CondicionCaso {
  COMPANIA_VENDEDORA("Vendedor es una compañía"),
  COMPANIA_COMPRADORA("Comprador es una compañía"),
  HEREDEROS("Vendedores herederos"),
  PROPIEDAD_HORIZONTAL("Bien en propiedad horizontal"),
  PREDIO_RUSTICO("Predio rústico"),
  ENTREGA_OBRA("Entrega de obra"),
  CAPITULACIONES("Capitulaciones matrimoniales"),
  DISOLUCION_SOCIEDAD_CONYUGAL("Disolución de la sociedad conyugal"),
  COMPARECE_CON_PODER("Comparecencia con poder"),
  VENDEDOR_MENOR_EDAD("Vendedor menor de edad"),
  EXTRANJERO("Compareciente extranjero"),
  COLINDA_PLAYA_RIO("Colinda con playa o río"),
  CREDITO_PREFERENCIAL("Crédito preferencial"),
  FINANCIAMIENTO_GASTOS("Financiamiento de gastos por el estudio");

  private final String descripcion;

  CondicionCaso(String descripcion) {
    this.descripcion = descripcion;
  }

  public String descripcion() {
    return descripcion;
  }

  public static Optional<CondicionCaso> de(String codigo) {
    if (codigo == null || codigo.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(valueOf(codigo.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
