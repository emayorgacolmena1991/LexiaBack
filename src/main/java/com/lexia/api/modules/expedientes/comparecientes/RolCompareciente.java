package com.lexia.api.modules.expedientes.comparecientes;

import java.util.Locale;
import java.util.Optional;

/** Rol notarial del compareciente (mismos códigos que el CHECK de V121). */
public enum RolCompareciente {
  COMPRADOR(Parte.COMPRADORA, "Comprador"),
  CONYUGE_COMPRADOR(Parte.COMPRADORA, "Cónyuge del comprador"),
  VENDEDOR(Parte.VENDEDORA, "Vendedor"),
  CONYUGE_VENDEDOR(Parte.VENDEDORA, "Cónyuge del vendedor"),
  HEREDERO(Parte.VENDEDORA, "Heredero"),
  REPRESENTANTE(null, "Representante legal"),
  APODERADO(null, "Apoderado");

  public enum Parte {
    COMPRADORA,
    VENDEDORA
  }

  /** null: la parte la indica la fila (representante y apoderado). */
  private final Parte parteFija;

  private final String etiqueta;

  RolCompareciente(Parte parteFija, String etiqueta) {
    this.parteFija = parteFija;
    this.etiqueta = etiqueta;
  }

  public Parte parteFija() {
    return parteFija;
  }

  public String etiqueta() {
    return etiqueta;
  }

  /** Otorga el acto (titular, cónyuge o heredero): cuenta para minutas, cédulas y alertas. */
  public boolean otorgante() {
    return this != REPRESENTANTE && this != APODERADO;
  }

  public static Optional<RolCompareciente> de(String codigo) {
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
