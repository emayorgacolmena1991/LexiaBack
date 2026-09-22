package com.lexia.api.modules.expedientes;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.PrevalidacionDtos.PrevalidacionDocumentoDto;
import com.lexia.api.modules.expedientes.PrevalidacionDtos.PrevalidacionDto;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class PrevalidacionService {

  private static final String MOTIVO_JOB =
      "No se pudo completar la prevalidación.";

  private final PrevalidacionWorker worker;
  private final ConcurrentMap<String, Snapshot> snapshots = new ConcurrentHashMap<>();

  public PrevalidacionService(PrevalidacionWorker worker) {
    this.worker = worker;
  }

  public void iniciar(String idExpediente, List<CargaDocumentoService.StoredDoc> documentos) {
    List<CargaDocumentoService.StoredDoc> copia = List.copyOf(documentos);
    Snapshot snapshot = Snapshot.enProceso(idExpediente, copia);
    if (snapshots.putIfAbsent(idExpediente, snapshot) != null) {
      return;
    }
    AuthPrincipal principal = AuthContext.get();
    worker.procesar(idExpediente, copia, principal);
  }

  public PrevalidacionDto obtener(String idExpediente) {
    Snapshot snapshot = snapshots.get(idExpediente);
    if (snapshot == null) {
      throw ApiException.notFound("Prevalidación no iniciada.");
    }
    return snapshot.toDto();
  }

  public void marcarDocumento(String idExpediente, String idDocumento, String estado, String motivo) {
    Snapshot snapshot = snapshots.get(idExpediente);
    if (snapshot != null) {
      snapshot.marcar(idDocumento, estado, motivo);
    }
  }

  public void completar(String idExpediente) {
    Snapshot snapshot = snapshots.get(idExpediente);
    if (snapshot != null) {
      snapshot.completar();
    }
  }

  public void fallar(String idExpediente) {
    Snapshot snapshot = snapshots.get(idExpediente);
    if (snapshot != null) {
      snapshot.fallar();
    }
  }

  private static final class Snapshot {
    private final String idExpediente;
    private final List<DocEstado> documentos;
    private String estado;

    private Snapshot(String idExpediente, List<DocEstado> documentos, String estado) {
      this.idExpediente = idExpediente;
      this.documentos = documentos;
      this.estado = estado;
    }

    static Snapshot enProceso(String idExpediente, List<CargaDocumentoService.StoredDoc> documentos) {
      List<DocEstado> docs = new ArrayList<>();
      for (CargaDocumentoService.StoredDoc doc : documentos) {
        docs.add(
            new DocEstado(
                doc.idDocumento(),
                doc.nombreOriginal(),
                doc.codigoTipoDocumento(),
                PrevalidacionDtos.EN_PROCESO,
                null));
      }
      return new Snapshot(idExpediente, docs, PrevalidacionDtos.EN_PROCESO);
    }

    synchronized void marcar(String idDocumento, String estadoDoc, String motivo) {
      if (PrevalidacionDtos.ERROR.equals(estado)) {
        return;
      }
      for (DocEstado doc : documentos) {
        if (doc.idDocumento.equals(idDocumento)) {
          doc.estado = estadoDoc;
          doc.motivo = motivo;
          return;
        }
      }
    }

    synchronized void completar() {
      if (!PrevalidacionDtos.ERROR.equals(estado)) {
        estado = PrevalidacionDtos.COMPLETADA;
      }
    }

    synchronized void fallar() {
      estado = PrevalidacionDtos.ERROR;
      for (DocEstado doc : documentos) {
        if (PrevalidacionDtos.EN_PROCESO.equals(doc.estado)) {
          doc.estado = PrevalidacionDtos.ERROR;
          doc.motivo = MOTIVO_JOB;
        }
      }
    }

    synchronized PrevalidacionDto toDto() {
      int legibles = 0;
      List<PrevalidacionDocumentoDto> docs = new ArrayList<>();
      for (DocEstado doc : documentos) {
        if (PrevalidacionDtos.LEGIBLE.equals(doc.estado)) {
          legibles++;
        }
        docs.add(
            new PrevalidacionDocumentoDto(
                doc.idDocumento, doc.nombreOriginal, doc.tipoDocumento, doc.estado, doc.motivo));
      }
      int total = documentos.size();
      double confianza = total == 0 ? 0d : (double) legibles / total;
      return new PrevalidacionDto(
          idExpediente, estado, confianza, legibles, total, List.copyOf(docs));
    }
  }

  private static final class DocEstado {
    private final String idDocumento;
    private final String nombreOriginal;
    private final String tipoDocumento;
    private String estado;
    private String motivo;

    private DocEstado(
        String idDocumento,
        String nombreOriginal,
        String tipoDocumento,
        String estado,
        String motivo) {
      this.idDocumento = idDocumento;
      this.nombreOriginal = nombreOriginal;
      this.tipoDocumento = tipoDocumento;
      this.estado = estado;
      this.motivo = motivo;
    }
  }
}
