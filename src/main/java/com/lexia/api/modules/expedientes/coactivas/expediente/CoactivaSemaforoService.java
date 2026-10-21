package com.lexia.api.modules.expedientes.coactivas.expediente;

import com.lexia.api.modules.expedientes.coactivas.CoactivaEtapa;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegado;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Semáforo determinístico del expediente (F1). ROJO: OPI sin notificar o notificación incompleta;
 * AMARILLO: pendientes subsanables (RPV, delegado, identificaciones); VERDE: al día; GRIS: sin
 * etapa verificada.
 */
@Component
public class CoactivaSemaforoService {

  public static final String ROJO = "ROJO";
  public static final String AMARILLO = "AMARILLO";
  public static final String VERDE = "VERDE";
  public static final String GRIS = "GRIS";

  public Evaluacion evaluar(
      CoactivaExpediente expediente,
      List<CoactivaParticipante> participantes,
      List<CoactivaNotificacion> notificaciones,
      CoactivaDelegado delegado) {
    CoactivaEtapa etapa = CoactivaEtapa.parse(expediente.getEtapaVerificada()).orElse(null);
    boolean delegadoVigente = delegado != null && delegado.vigenteEn(LocalDate.now());
    Set<UUID> sinNotificar = participantesSinNotificarOpi(participantes, notificaciones);
    boolean hayNotificacionesOpi =
        notificaciones.stream().anyMatch(n -> "OPI".equals(n.getActo()));

    String semaforo;
    List<String> motivos = new ArrayList<>();
    if (etapa == null) {
      semaforo = GRIS;
      motivos.add("Etapa procesal sin verificar");
    } else if (etapa == CoactivaEtapa.OPI_EMITIDA) {
      semaforo = ROJO;
      motivos.add("OPI emitida sin notificar");
    } else if (hayNotificacionesOpi && !sinNotificar.isEmpty() && requiereOpiNotificada(etapa)) {
      semaforo = ROJO;
      motivos.add(sinNotificar.size() + " participante(s) sin notificación válida de OPI");
    } else {
      if (etapa == CoactivaEtapa.RPV) {
        motivos.add("En requerimiento de pago voluntario");
      }
      if (expediente.getDelegadoId() == null) {
        motivos.add("Sin delegado asignado");
      } else if (!delegadoVigente) {
        motivos.add("Delegado sin vigencia: avocar conocimiento");
      }
      long sinId = participantes.stream().filter(p -> p.getIdentificacion() == null).count();
      if (sinId > 0) {
        motivos.add(sinId + " participante(s) sin identificación");
      }
      semaforo = motivos.isEmpty() || etapa == CoactivaEtapa.ARCHIVADO ? VERDE : AMARILLO;
      if (etapa == CoactivaEtapa.ARCHIVADO) {
        motivos.clear();
      }
    }
    String motivo = motivos.isEmpty() ? null : String.join("; ", motivos);
    if (motivo != null && motivo.length() > 400) {
      motivo = motivo.substring(0, 400);
    }
    return new Evaluacion(
        semaforo, motivo, siguienteAccion(expediente, etapa, semaforo, delegadoVigente), sinNotificar);
  }

  public void aplicar(
      CoactivaExpediente expediente,
      List<CoactivaParticipante> participantes,
      List<CoactivaNotificacion> notificaciones,
      CoactivaDelegado delegado) {
    Evaluacion evaluacion = evaluar(expediente, participantes, notificaciones, delegado);
    expediente.setSemaforo(evaluacion.semaforo(), evaluacion.motivo());
  }

  /** Siguiente acción usando solo los datos persistidos (para la bandeja, sin cargar notificaciones). */
  public String siguienteAccion(CoactivaExpediente expediente, boolean delegadoVigente) {
    CoactivaEtapa etapa = CoactivaEtapa.parse(expediente.getEtapaVerificada()).orElse(null);
    return siguienteAccion(expediente, etapa, expediente.getSemaforo(), delegadoVigente);
  }

  /**
   * Un participante queda notificado de la OPI con 1 notificación válida PERSONAL o CORREO, o con 2
   * boletas válidas.
   */
  public Set<UUID> participantesSinNotificarOpi(
      List<CoactivaParticipante> participantes, List<CoactivaNotificacion> notificaciones) {
    Set<UUID> notificados = new HashSet<>();
    var porParticipante =
        notificaciones.stream()
            .filter(n -> "OPI".equals(n.getActo()) && n.isValida() && n.getParticipanteId() != null)
            .collect(Collectors.groupingBy(CoactivaNotificacion::getParticipanteId));
    porParticipante.forEach(
        (participanteId, lista) -> {
          boolean directa =
              lista.stream().anyMatch(n -> "PERSONAL".equals(n.getMedio()) || "CORREO".equals(n.getMedio()));
          long boletas = lista.stream().filter(n -> "BOLETA".equals(n.getMedio())).count();
          if (directa || boletas >= 2) {
            notificados.add(participanteId);
          }
        });
    Set<UUID> pendientes = new HashSet<>();
    for (CoactivaParticipante participante : participantes) {
      if (!notificados.contains(participante.getId())) {
        pendientes.add(participante.getId());
      }
    }
    return pendientes;
  }

  private static boolean requiereOpiNotificada(CoactivaEtapa etapa) {
    return switch (etapa) {
      case PREVIA, RPV, OPI_EMITIDA, ARCHIVADO -> false;
      default -> true;
    };
  }

  private static String siguienteAccion(
      CoactivaExpediente expediente, CoactivaEtapa etapa, String semaforo, boolean delegadoVigente) {
    String estado = expediente.getEstadoOperativo();
    if (CoactivaExpediente.RECIBIDO.equals(estado)) {
      return "Cargar expediente escaneado";
    }
    if (CoactivaExpediente.DIGITALIZADO.equals(estado) || CoactivaExpediente.EN_REVISION.equals(estado)) {
      return "Revisar y confirmar etapa";
    }
    if (etapa == CoactivaEtapa.ARCHIVADO) {
      return "—";
    }
    if (ROJO.equals(semaforo)) {
      return "Notificar OPI";
    }
    if (expediente.getDelegadoId() == null) {
      return "Asignar delegado";
    }
    if (!delegadoVigente) {
      return "Avocar conocimiento";
    }
    if (etapa == null) {
      return "Revisar y confirmar etapa";
    }
    return switch (etapa) {
      case PREVIA -> "Emitir requerimiento de pago voluntario";
      case RPV -> "Emitir orden de pago inmediato";
      case OPI_EMITIDA -> "Notificar OPI";
      case NOTIFICACION_COA -> "Imponer medidas cautelares";
      case MEDIDAS_CAUTELARES -> "Ratificar medidas";
      case ESCRITO -> "Atender el escrito";
      case EMBARGO -> "Solicitar avalúo";
      case HONORARIOS -> "Revisar honorarios";
      case AVALUO -> "Preparar remate";
      case REMATE -> "Liquidar remate";
      case CONVENIO -> "Controlar cuotas";
      case ARCHIVADO -> "—";
    };
  }

  public record Evaluacion(String semaforo, String motivo, String siguienteAccion, Set<UUID> sinNotificarOpi) {}
}
