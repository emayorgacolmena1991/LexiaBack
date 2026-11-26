package com.lexia.api.modules.expedientes.requisitos;

import java.util.Locale;
import java.util.Optional;

/**
 * Condiciones del caso que cambian los documentos sugeridos (códigos fijos, mismos que el CHECK de
 * V120). Solo las confirmadas por el abogado cambian los requisitos. {@code pista}: qué debe buscar
 * la IA en los documentos; null = la IA no la propone (la define el producto o el abogado).
 */
public enum CondicionCaso {
  COMPANIA_VENDEDORA(
      "Vendedor es una compañía",
      "El vendedor es persona jurídica: RUC de sociedad, nombramiento de representante legal o razón social en la historia de dominio."),
  COMPANIA_COMPRADORA(
      "Comprador es una compañía",
      "El comprador es persona jurídica: RUC de sociedad o nombramiento de representante legal del comprador."),
  HEREDEROS(
      "Vendedores herederos",
      "Los vendedores adquirieron por herencia: posesión efectiva, partición o sucesión en la historia de dominio."),
  PROPIEDAD_HORIZONTAL(
      "Bien en propiedad horizontal",
      "El inmueble está sometido al régimen de propiedad horizontal (alícuota, declaratoria de PH, condominio) según la historia de dominio o el avalúo."),
  PREDIO_RUSTICO(
      "Predio rústico",
      "El inmueble es rural o rústico (predio rústico, zona rural, hectáreas) según el avalúo o la historia de dominio."),
  ENTREGA_OBRA(
      "Entrega de obra",
      "El avalúo muestra edificación o construcción que no consta en la historia de dominio (solo terreno o solar), o se menciona construcción nueva."),
  CAPITULACIONES(
      "Capitulaciones matrimoniales",
      "Algún compareciente tiene capitulaciones matrimoniales (marginación en el acta de matrimonio o mención en la escritura)."),
  DISOLUCION_SOCIEDAD_CONYUGAL(
      "Disolución de la sociedad conyugal",
      "Algún compareciente tiene la sociedad conyugal disuelta o liquidada."),
  COMPARECE_CON_PODER(
      "Comparecencia con poder",
      "Algún compareciente actúa mediante apoderado o hay un poder entre los documentos."),
  VENDEDOR_MENOR_EDAD(
      "Vendedor menor de edad",
      "Algún vendedor es menor de edad según su fecha de nacimiento o se menciona curador o representación de menores."),
  EXTRANJERO(
      "Compareciente extranjero",
      "Algún compareciente es extranjero: cédula de extranjería, pasaporte o nacionalidad distinta de la ecuatoriana."),
  COLINDA_PLAYA_RIO(
      "Colinda con playa o río",
      "Los linderos del inmueble colindan con playa, bahía, mar, estero o río."),
  CREDITO_PREFERENCIAL("Crédito preferencial", null),
  FINANCIAMIENTO_GASTOS("Financiamiento de gastos por el estudio", null);

  private final String descripcion;
  private final String pista;

  CondicionCaso(String descripcion, String pista) {
    this.descripcion = descripcion;
    this.pista = pista;
  }

  public String descripcion() {
    return descripcion;
  }

  /** Qué buscar en los documentos; null si la IA no la propone. */
  public String pista() {
    return pista;
  }

  public boolean detectablePorIa() {
    return pista != null;
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
