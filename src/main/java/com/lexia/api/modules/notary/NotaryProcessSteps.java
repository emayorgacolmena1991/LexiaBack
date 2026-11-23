package com.lexia.api.modules.notary;

import java.util.List;

/**
 * Pasos operativos alineados al flujo LEXIA (UI) y al acta de levantamiento notarial (recepción →
 * matriz/registro → tributos/municipal → cierre/devolución al bufete).
 */
public final class NotaryProcessSteps {

  public static final List<String> LABELS =
      List.of(
          "Revisar paquete (expediente + acta)",
          "Trámite notarial interno",
          "Registrar acto / fe notarial",
          "Devolver al bufete de abogados");

  public static final int COUNT = LABELS.size();

  private NotaryProcessSteps() {}
}
