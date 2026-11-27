package com.lexia.api.common.storage.migracion;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("migrar-storage")
public class StorageMigracionOpciones {

  private final boolean dryRun;
  private final int lote;
  private final Set<String> tablas;

  public StorageMigracionOpciones(
      @Value("${lexia.storage.migracion.dry-run:true}") boolean dryRunDefault,
      @Value("${lexia.storage.migracion.lote:200}") int loteDefault,
      ApplicationArguments args) {
    this.dryRun = leerDryRun(args, dryRunDefault);
    this.lote = leerLote(args, loteDefault);
    this.tablas = leerTablas(args);
  }

  public boolean dryRun() {
    return dryRun;
  }

  public int lote() {
    return lote;
  }

  public Set<String> tablas() {
    return tablas;
  }

  private static boolean leerDryRun(ApplicationArguments args, boolean defecto) {
    if (args.containsOption("dry-run")) {
      for (String valor : args.getOptionValues("dry-run")) {
        if (valor == null || valor.isBlank()) {
          return true;
        }
        return !"false".equalsIgnoreCase(valor.trim());
      }
      return true;
    }
    return defecto;
  }

  private static int leerLote(ApplicationArguments args, int defecto) {
    if (!args.containsOption("lote")) {
      return Math.max(1, defecto);
    }
    for (String valor : args.getOptionValues("lote")) {
      if (valor != null && !valor.isBlank()) {
        return Math.max(1, Integer.parseInt(valor.trim()));
      }
    }
    return Math.max(1, defecto);
  }

  private static Set<String> leerTablas(ApplicationArguments args) {
    if (!args.containsOption("tablas")) {
      return Set.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String valor : args.getOptionValues("tablas")) {
      if (valor == null || valor.isBlank()) {
        continue;
      }
      Arrays.stream(valor.split(","))
          .map(s -> s.trim().toLowerCase(Locale.ROOT))
          .filter(s -> !s.isEmpty())
          .forEach(out::add);
    }
    return Set.copyOf(out);
  }
}