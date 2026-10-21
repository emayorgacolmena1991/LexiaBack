package com.lexia.api.modules.expedientes.coactivas.ingesta;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.coactivas.CoactivaPermisos;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpediente;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ArchivoItem;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteDtos.ExpedienteDetalle;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteService;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.CargaMasivaResponse;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Carga masiva de expedientes escaneados: el n.º de juicio se detecta en el nombre del archivo
 * ({@code 025-2024-00026.pdf}); lo que no coincide queda SIN_ASIGNAR para vinculación manual.
 */
@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CargaMasivaService {

  private final CoactivaArchivoRepository archivos;
  private final CoactivaExpedienteRepository expedientes;
  private final CoactivaExpedienteService expedienteService;
  private final AuthorizationService authorization;

  public CargaMasivaService(
      CoactivaArchivoRepository archivos,
      CoactivaExpedienteRepository expedientes,
      CoactivaExpedienteService expedienteService,
      AuthorizationService authorization) {
    this.archivos = archivos;
    this.expedientes = expedientes;
    this.expedienteService = expedienteService;
    this.authorization = authorization;
  }

  @Transactional
  public CargaMasivaResponse subir(List<MultipartFile> files) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    if (files == null || files.isEmpty()) {
      throw ApiException.badRequest("Selecciona al menos un archivo.");
    }
    List<ArchivoItem> result = new ArrayList<>();
    int vinculados = 0;
    for (MultipartFile file : files) {
      if (file == null || file.isEmpty()) {
        continue;
      }
      CoactivaArchivo archivo =
          expedienteService.guardarArchivo(principal, file, CoactivaArchivo.EXPEDIENTE_ESCANEADO);
      if (archivo.getNroJuicioDetectado() != null) {
        CoactivaExpediente expediente =
            expedientes
                .findByTenantIdAndNroJuicioAndDeletedAtIsNull(principal.tenantId(), archivo.getNroJuicioDetectado())
                .orElse(null);
        if (expediente != null) {
          expedienteService.vincular(principal, archivo, expediente);
          expedienteService.encolarAnalisis(archivo, expediente.getEtapaVerificada());
          vinculados++;
        }
      }
      result.add(CoactivaExpedienteService.toItem(archivo));
    }
    return new CargaMasivaResponse(result.size(), vinculados, result.size() - vinculados, result);
  }

  @Transactional(readOnly = true)
  public List<ArchivoItem> sinAsignar() {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    return archivos
        .findByTenantIdAndEstadoVinculoAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId, CoactivaArchivo.SIN_ASIGNAR)
        .stream()
        .map(CoactivaExpedienteService::toItem)
        .toList();
  }

  @Transactional
  public ArchivoItem vincular(UUID archivoId, UUID expedienteId) {
    CoactivaArchivo archivo = vincularInterno(archivoId, expedienteId);
    return CoactivaExpedienteService.toItem(archivo);
  }

  @Transactional
  public ExpedienteDetalle vincularManual(UUID archivoId, UUID expedienteId) {
    vincularInterno(archivoId, expedienteId);
    return expedienteService.detalle(expedienteId);
  }

  private CoactivaArchivo vincularInterno(UUID archivoId, UUID expedienteId) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    CoactivaArchivo archivo = expedienteService.requireArchivo(principal.tenantId(), archivoId);
    if (!CoactivaArchivo.SIN_ASIGNAR.equals(archivo.getEstadoVinculo())) {
      throw ApiException.conflict("El archivo ya está vinculado.");
    }
    CoactivaExpediente expediente = expedienteService.require(principal.tenantId(), expedienteId);
    archivo.marcarVinculoManual();
    expedienteService.vincular(principal, archivo, expediente);
    expedienteService.encolarAnalisis(archivo, expediente.getEtapaVerificada());
    return archivo;
  }

  @Transactional
  public void descartar(UUID archivoId) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    UUID tenantId = AuthContext.require().tenantId();
    CoactivaArchivo archivo = expedienteService.requireArchivo(tenantId, archivoId);
    if (!CoactivaArchivo.SIN_ASIGNAR.equals(archivo.getEstadoVinculo())) {
      throw ApiException.conflict("Solo se pueden descartar archivos sin asignar.");
    }
    archivo.softDelete();
  }
}
