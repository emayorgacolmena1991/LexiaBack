package com.lexia.api.modules.expedientes.coactivas.expediente;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.coactivas.CoactivaPermisos;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoRepository;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivoStorage;
import com.lexia.api.modules.expedientes.minutas.DocxPdfConverter;
import com.lexia.api.modules.identity.AuthorizationService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PageMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expediente completo en un solo PDF: el escaneo del juicio y, a continuación, todo lo que se fue
 * agregando (actuaciones generadas, providencias, escritos, resoluciones…) en orden de fecha. Así
 * la abogada no tiene que volver a escanear el proceso para tenerlo al día. Cada pieza queda como
 * marcador del PDF.
 */
@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaExpedienteCompletoService {

  private static final Logger LOG = LoggerFactory.getLogger(CoactivaExpedienteCompletoService.class);
  private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final ZoneId ZONA = ZoneId.of("America/Guayaquil");

  private static final Map<String, String> ETIQUETAS =
      Map.ofEntries(
          Map.entry(CoactivaArchivo.EXPEDIENTE_UNIFICADO, "Expediente"),
          Map.entry(CoactivaArchivo.EXPEDIENTE_ESCANEADO, "Expediente"),
          Map.entry("ACTUACION", "Actuación"),
          Map.entry("LIQUIDACION", "Liquidación"),
          Map.entry("PROVIDENCIA", "Providencia"),
          Map.entry("OFICIO", "Oficio"),
          Map.entry("RESPUESTA_ENTIDAD", "Respuesta de entidad"),
          Map.entry("ESCRITO", "Escrito"),
          Map.entry("EVIDENCIA", "Evidencia"),
          Map.entry("OTRO", "Documento"));

  public record ExpedienteCompleto(String nombreArchivo, byte[] pdf, int incluidos, List<String> omitidos) {}

  record Pieza(String titulo, byte[] pdf) {}

  private final CoactivaExpedienteService expedientes;
  private final CoactivaArchivoRepository archivos;
  private final CoactivaArchivoStorage storage;
  private final DocxPdfConverter docxAPdf;
  private final AuthorizationService authorization;

  public CoactivaExpedienteCompletoService(
      CoactivaExpedienteService expedientes,
      CoactivaArchivoRepository archivos,
      CoactivaArchivoStorage storage,
      DocxPdfConverter docxAPdf,
      AuthorizationService authorization) {
    this.expedientes = expedientes;
    this.archivos = archivos;
    this.storage = storage;
    this.docxAPdf = docxAPdf;
    this.authorization = authorization;
  }

  @Transactional(readOnly = true)
  public ExpedienteCompleto generar(UUID expedienteId) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    CoactivaExpediente expediente = expedientes.require(tenantId, expedienteId);
    List<CoactivaArchivo> orden =
        ordenar(archivos.findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId, expedienteId));
    List<Pieza> piezas = new ArrayList<>();
    List<String> omitidos = new ArrayList<>();
    for (CoactivaArchivo archivo : orden) {
      try {
        piezas.add(new Pieza(titulo(archivo), aPdf(archivo, storage.read(archivo))));
      } catch (RuntimeException ex) {
        LOG.warn("Expediente completo {}: se omite archivo={} ({})", expedienteId, archivo.getId(), ex.getMessage());
        omitidos.add(archivo.getNombreOriginal());
      }
    }
    if (piezas.isEmpty()) {
      throw ApiException.badRequest("El expediente no tiene documentos para unir.");
    }
    byte[] pdf = unir(piezas);
    LOG.info(
        "Expediente completo {} piezas={} omitidos={} bytes={}", expedienteId, piezas.size(), omitidos.size(), pdf.length);
    String juicio = expediente.getNroJuicio() == null ? expedienteId.toString() : expediente.getNroJuicio();
    return new ExpedienteCompleto("Expediente_" + juicio + "_completo.pdf", pdf, piezas.size(), List.copyOf(omitidos));
  }

  /**
   * Primero el escaneo vigente del juicio (el unificado más reciente; si no hay, el escaneado más
   * reciente); después el resto en orden de subida. Los escaneos anteriores no se repiten.
   */
  static List<CoactivaArchivo> ordenar(List<CoactivaArchivo> todos) {
    Comparator<CoactivaArchivo> porFecha = Comparator.comparing(CoactivaArchivo::getCreatedAt);
    Optional<CoactivaArchivo> base =
        ultimo(todos, CoactivaArchivo.EXPEDIENTE_UNIFICADO, porFecha)
            .or(() -> ultimo(todos, CoactivaArchivo.EXPEDIENTE_ESCANEADO, porFecha));
    List<CoactivaArchivo> resto =
        todos.stream()
            .filter(a -> !esEscaneo(a.getTipo()))
            .filter(a -> !CoactivaArchivo.ACTA.equals(a.getTipo()))
            .sorted(porFecha)
            .toList();
    List<CoactivaArchivo> orden = new ArrayList<>();
    base.ifPresent(orden::add);
    orden.addAll(resto);
    return orden;
  }

  static String titulo(CoactivaArchivo archivo) {
    String etiqueta = ETIQUETAS.getOrDefault(archivo.getTipo(), "Documento");
    String fecha = archivo.getCreatedAt() == null ? "" : " (" + FECHA.format(archivo.getCreatedAt().atZone(ZONA)) + ")";
    return etiqueta + ": " + archivo.getNombreOriginal() + fecha;
  }

  byte[] aPdf(CoactivaArchivo archivo, byte[] bytes) {
    String mime = archivo.getMimeType() == null ? "" : archivo.getMimeType().toLowerCase(Locale.ROOT);
    String nombre = archivo.getNombreOriginal() == null ? "" : archivo.getNombreOriginal().toLowerCase(Locale.ROOT);
    if (mime.contains("pdf") || nombre.endsWith(".pdf")) {
      return bytes;
    }
    if (mime.contains("wordprocessingml") || nombre.endsWith(".docx")) {
      return docxAPdf.toPdf(bytes);
    }
    if (mime.startsWith("image/") && !mime.contains("svg") || nombre.matches(".*\\.(png|jpe?g)$")) {
      return imagenAPdf(bytes, nombre);
    }
    throw new IllegalArgumentException("formato no soportado: " + (mime.isEmpty() ? nombre : mime));
  }

  /** Una imagen (foto de un oficio, por ejemplo) como página A4, escalada sin deformar. */
  static byte[] imagenAPdf(byte[] bytes, String nombre) {
    try (PDDocument doc = new PDDocument()) {
      PDImageXObject imagen = PDImageXObject.createFromByteArray(doc, bytes, nombre);
      PDRectangle a4 = PDRectangle.A4;
      PDPage pagina = new PDPage(a4);
      doc.addPage(pagina);
      float margen = 24f;
      float escala =
          Math.min((a4.getWidth() - 2 * margen) / imagen.getWidth(), (a4.getHeight() - 2 * margen) / imagen.getHeight());
      float ancho = imagen.getWidth() * escala;
      float alto = imagen.getHeight() * escala;
      try (PDPageContentStream contenido = new PDPageContentStream(doc, pagina)) {
        contenido.drawImage(imagen, (a4.getWidth() - ancho) / 2, (a4.getHeight() - alto) / 2, ancho, alto);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    } catch (IOException ex) {
      throw new IllegalArgumentException("imagen ilegible", ex);
    }
  }

  /** Une las piezas en orden y deja un marcador por pieza apuntando a su primera página. */
  static byte[] unir(List<Pieza> piezas) {
    List<PDDocument> fuentes = new ArrayList<>();
    try (PDDocument destino = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
      PDFMergerUtility merger = new PDFMergerUtility();
      List<PDOutlineItem> marcadores = new ArrayList<>();
      for (Pieza pieza : piezas) {
        PDDocument fuente = Loader.loadPDF(new RandomAccessReadBuffer(pieza.pdf()));
        // PDFBox exige que la fuente siga abierta hasta guardar el destino.
        fuentes.add(fuente);
        int inicio = destino.getNumberOfPages();
        merger.appendDocument(destino, fuente);
        if (destino.getNumberOfPages() > inicio) {
          PDOutlineItem item = new PDOutlineItem();
          item.setTitle(pieza.titulo());
          item.setDestination(destino.getPage(inicio));
          marcadores.add(item);
        }
      }
      PDDocumentOutline indice = new PDDocumentOutline();
      marcadores.forEach(indice::addLast);
      destino.getDocumentCatalog().setDocumentOutline(indice);
      destino.getDocumentCatalog().setPageMode(PageMode.USE_OUTLINES);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      destino.save(out);
      return out.toByteArray();
    } catch (IOException ex) {
      throw ApiException.badRequest("No se pudieron unir los PDF del expediente.");
    } finally {
      for (PDDocument fuente : fuentes) {
        try {
          fuente.close();
        } catch (IOException ignored) {
          // Solo libera memoria/temporales.
        }
      }
    }
  }

  private static boolean esEscaneo(String tipo) {
    return CoactivaArchivo.EXPEDIENTE_UNIFICADO.equals(tipo) || CoactivaArchivo.EXPEDIENTE_ESCANEADO.equals(tipo);
  }

  private static Optional<CoactivaArchivo> ultimo(
      List<CoactivaArchivo> todos, String tipo, Comparator<CoactivaArchivo> porFecha) {
    return todos.stream().filter(a -> tipo.equals(a.getTipo())).max(porFecha);
  }
}
