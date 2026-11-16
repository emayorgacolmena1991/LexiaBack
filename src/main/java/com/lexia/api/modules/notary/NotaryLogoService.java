package com.lexia.api.modules.notary;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.documentos.ExpedienteDocumentoStorage;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Logo de la notaría de la sesión. Sin parámetro de tenant: nadie opera sobre el logo de otra. */
@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class NotaryLogoService {

  static final long MAX_BYTES = 1024 * 1024;
  static final int MAX_LADO = 4000;

  private static final String PERMISO = "notaria:partnership:gestionar";
  private static final String CARPETA = "perfil-notaria";
  private static final String PNG = "image/png";
  private static final String JPEG = "image/jpeg";
  private static final byte[] FIRMA_PNG = {
    (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
  };
  private static final byte[] FIRMA_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  /** Con este pixel el tag de imagen de la plantilla siempre tiene un valor válido. */
  private static final byte[] PIXEL_TRANSPARENTE =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNgYGBgAAAABQABeqhXUAAAAABJRU5ErkJggg==");

  private final AuthorizationService authorization;
  private final NotaryTenantProfileRepository profiles;
  private final ExpedienteDocumentoStorage storage;
  private final AuditEventRepository auditEvents;

  public NotaryLogoService(
      AuthorizationService authorization,
      NotaryTenantProfileRepository profiles,
      ExpedienteDocumentoStorage storage,
      AuditEventRepository auditEvents) {
    this.authorization = authorization;
    this.profiles = profiles;
    this.storage = storage;
    this.auditEvents = auditEvents;
  }

  @Transactional
  public void subir(MultipartFile archivo) {
    authorization.requirePermission(PERMISO);
    NotaryTenantProfile perfil = perfilDeSesion();
    byte[] bytes = leerValidado(archivo);
    String tipo = tipoPorFirma(bytes);
    exigirImagenLegible(bytes, tipo);
    String nombre = PNG.equals(tipo) ? "logo.png" : "logo.jpg";
    String anterior = perfil.getLogoStorageRef();
    String nuevo =
        storage
            .guardar(perfil.getTenantId() + "/" + CARPETA + "/" + UUID.randomUUID() + "_" + nombre, tipo, bytes)
            .clave();
    storage.sustituirAlTerminar(nuevo, anterior);
    perfil.asignarLogo(nuevo, tipo);
    profiles.save(perfil);
    audit(perfil, "NOTARY_LOGO_UPDATED");
  }

  @Transactional
  public void quitar() {
    authorization.requirePermission(PERMISO);
    NotaryTenantProfile perfil = perfilConLogo();
    storage.sustituirAlTerminar(null, perfil.getLogoStorageRef());
    perfil.quitarLogo();
    profiles.save(perfil);
    audit(perfil, "NOTARY_LOGO_REMOVED");
  }

  @Transactional(readOnly = true)
  public NotaryDtos.RemisionDocumentContent leer() {
    authorization.requirePermission(PERMISO);
    NotaryTenantProfile perfil = perfilConLogo();
    String nombre = PNG.equals(perfil.getLogoContentType()) ? "logo.png" : "logo.jpg";
    return new NotaryDtos.RemisionDocumentContent(
        nombre, perfil.getLogoContentType(), storage.read(perfil.getLogoStorageRef()));
  }

  /**
   * Para el render de la escritura, que corre en la sesión del bufete: por eso recibe el tenant y
   * no exige permisos de la notaría. No exponer en un endpoint.
   */
  @Transactional(readOnly = true)
  public byte[] logoParaRender(UUID notaryTenantId) {
    String ref =
        profiles.findById(notaryTenantId).map(NotaryTenantProfile::getLogoStorageRef).orElse(null);
    return ref == null ? PIXEL_TRANSPARENTE.clone() : storage.read(ref);
  }

  private NotaryTenantProfile perfilDeSesion() {
    return profiles
        .findById(AuthContext.require().tenantId())
        .orElseThrow(() -> NotaryBusinessException.notFound("Perfil de notaría no encontrado."));
  }

  private NotaryTenantProfile perfilConLogo() {
    NotaryTenantProfile perfil = perfilDeSesion();
    if (perfil.getLogoStorageRef() == null) {
      throw NotaryBusinessException.notFound("La notaría no tiene logo.");
    }
    return perfil;
  }

  private static byte[] leerValidado(MultipartFile archivo) {
    if (archivo == null || archivo.isEmpty()) {
      throw NotaryBusinessException.badRequest("Selecciona un archivo PNG o JPEG.");
    }
    if (archivo.getSize() > MAX_BYTES) {
      throw NotaryBusinessException.badRequest("El logo no puede superar 1 MB.");
    }
    try {
      return archivo.getBytes();
    } catch (IOException ex) {
      throw NotaryBusinessException.badRequest("No se pudo leer el archivo.");
    }
  }

  private static String tipoPorFirma(byte[] bytes) {
    if (empiezaCon(bytes, FIRMA_PNG)) {
      return PNG;
    }
    if (empiezaCon(bytes, FIRMA_JPEG)) {
      return JPEG;
    }
    throw NotaryBusinessException.badRequest("El logo debe ser una imagen PNG o JPEG.");
  }

  /**
   * La firma solo mira los primeros bytes: un archivo corrupto la pasa y rompería el render. Las
   * dimensiones se leen de la cabecera antes de decodificar: 1 MB de PNG puede descomprimir a GB.
   */
  private static void exigirImagenLegible(byte[] bytes, String tipo) {
    String ilegible =
        JPEG.equals(tipo)
            ? "Use PNG o JPEG en RGB"
            : "El logo está dañado o no se puede leer como imagen.";
    ImageReader lector = ImageIO.getImageReadersByMIMEType(tipo).next();
    boolean excede;
    BufferedImage imagen = null;
    try (ImageInputStream entrada =
        ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      lector.setInput(entrada, true, true);
      excede = lector.getWidth(0) > MAX_LADO || lector.getHeight(0) > MAX_LADO;
      if (!excede) {
        imagen = lector.read(0);
      }
    } catch (IOException | RuntimeException ex) {
      throw NotaryBusinessException.badRequest(ilegible);
    } finally {
      lector.dispose();
    }
    if (excede) {
      throw NotaryBusinessException.badRequest("El logo no puede superar 4000×4000 px");
    }
    if (imagen == null) {
      throw NotaryBusinessException.badRequest(ilegible);
    }
  }

  private static boolean empiezaCon(byte[] bytes, byte[] firma) {
    return bytes.length >= firma.length
        && Arrays.equals(Arrays.copyOf(bytes, firma.length), firma);
  }

  private void audit(NotaryTenantProfile perfil, String evento) {
    AuthPrincipal principal = AuthContext.require();
    auditEvents.save(
        AuditEvent.of(
            perfil.getTenantId(),
            principal.userId(),
            evento,
            "notary_tenant_profile",
            perfil.getTenantId(),
            "OK",
            null,
            null));
  }
}
