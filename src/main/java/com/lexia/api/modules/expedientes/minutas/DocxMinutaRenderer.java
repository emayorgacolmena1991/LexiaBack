package com.lexia.api.modules.expedientes.minutas;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.template.ElementTemplate;
import com.deepoove.poi.template.MetaTemplate;
import com.lexia.api.common.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Renderiza plantillas .docx con poi-tl (tags Mustache {@code {{campo}}}). */
@Service
public class DocxMinutaRenderer {

  private static final Logger LOG = LoggerFactory.getLogger(DocxMinutaRenderer.class);
  private static final Pattern PARTE_TEXTO =
      Pattern.compile("word/(document|header\\d*|footer\\d*|footnotes|endnotes)\\.xml");
  private static final Pattern HIPERVINCULO =
      Pattern.compile("<w:hyperlink\\b[^>]*>(.*?)</w:hyperlink>", Pattern.DOTALL);

  private final Map<String, List<String>> tagsPorPlantilla = new ConcurrentHashMap<>();

  public byte[] render(MinutaTemplateDescriptor descriptor, Map<String, Object> data) {
    if (descriptor == null) {
      throw ApiException.badRequest("Descriptor de plantilla requerido.");
    }
    byte[] bytes = renderInterno(descriptor.classpathResource(), data, true);
    LOG.info(
        "DOCX renderizado product={} kind={} bytes={}",
        descriptor.productCode(),
        descriptor.templateKind(),
        bytes.length);
    return bytes;
  }

  /**
   * Render genérico: cada tag {@code {{...}}} de la plantilla debe existir tal cual en {@code data}
   * (sin los alias de Escrituración).
   */
  public byte[] renderPlantilla(String classpathResource, Map<String, Object> data) {
    byte[] bytes = renderInterno(classpathResource, data, false);
    LOG.info("DOCX renderizado plantilla={} bytes={}", classpathResource, bytes.length);
    return bytes;
  }

  private byte[] renderInterno(String classpathResource, Map<String, Object> data, boolean aliases) {
    Map<String, Object> safe = data == null ? Map.of() : data;
    try (InputStream in = abrir(classpathResource);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      XWPFTemplate template = XWPFTemplate.compile(in);
      List<String> tags = tagNames(template);
      List<String> sinDato =
          aliases
              ? MinutaTagAliases.sinResolver(tags, safe.keySet())
              : tags.stream().filter(t -> !safe.containsKey(t)).toList();
      if (!sinDato.isEmpty()) {
        template.close();
        throw ApiException.badRequest(
            "La plantilla "
                + classpathResource
                + " tiene placeholders sin dato asociado: "
                + String.join(", ", sinDato.stream().map(t -> "{{" + t + "}}").toList()));
      }
      template.render(aliases ? MinutaTagAliases.valoresPorTag(tags, safe) : safe);
      template.write(out);
      template.close();
      byte[] bytes = out.toByteArray();
      verificarSinPlaceholders(classpathResource, bytes);
      return bytes;
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      LOG.error("Error renderizando DOCX {}: {}", classpathResource, e.getMessage(), e);
      String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      throw ApiException.badRequest("Error al generar el documento .docx: " + detail);
    }
  }

  public byte[] renderVivienda(MinutaTemplateDescriptor descriptor, MinutaViviendaData data) {
    MinutaViviendaData safe = data == null ? new MinutaViviendaData() : data;
    return render(descriptor, safe.toTemplateMap());
  }

  /** Bytes de la plantilla sin renderizar (con sus tags {@code {{...}}}). */
  public byte[] plantilla(MinutaTemplateDescriptor descriptor) {
    try (InputStream in = resource(descriptor.classpathResource()).getInputStream()) {
      return in.readAllBytes();
    } catch (java.io.IOException e) {
      throw ApiException.badRequest(
          "No se pudo leer la plantilla " + descriptor.classpathResource() + ": " + e.getMessage());
    }
  }

  /** Tags {@code {{...}}} presentes en la plantilla (cuerpo, encabezados y pies). */
  public List<String> tags(MinutaTemplateDescriptor descriptor) {
    return tags(descriptor.classpathResource());
  }

  public List<String> tags(String classpathResource) {
    return tagsPorPlantilla.computeIfAbsent(
        classpathResource,
        key -> {
          try (InputStream in = abrir(key);
              XWPFTemplate template = XWPFTemplate.compile(in)) {
            return List.copyOf(tagNames(template));
          } catch (ApiException e) {
            throw e;
          } catch (Exception e) {
            throw ApiException.badRequest(
                "No se pudo leer la plantilla " + key + ": " + e.getMessage());
          }
        });
  }

  /**
   * Campos canónicos usados por la plantilla (directo o vía alias) que quedarían sin dato real (se
   * renderizan como {@code nodata}).
   */
  public List<String> camposPendientes(
      MinutaTemplateDescriptor descriptor, MinutaViviendaData data) {
    Map<String, Object> map = (data == null ? new MinutaViviendaData() : data).toTemplateMap();
    Set<String> pendientes = new LinkedHashSet<>();
    for (String tag : tags(descriptor)) {
      String campo = MinutaTagAliases.canonico(tag);
      if (MinutaViviendaData.isMissing(map.get(campo))) {
        pendientes.add(campo);
      }
    }
    return List.copyOf(pendientes);
  }

  /** Valores por tag de la plantilla (canónicos y alias), tal como se renderizarían. */
  public Map<String, Object> valoresPorTag(
      MinutaTemplateDescriptor descriptor, MinutaViviendaData data) {
    Map<String, Object> map = (data == null ? new MinutaViviendaData() : data).toTemplateMap();
    return MinutaTagAliases.valoresPorTag(tags(descriptor), map);
  }

  /** Tags de la plantilla cuyo campo canónico está en {@code campos}. */
  public List<String> tagsDeCampos(MinutaTemplateDescriptor descriptor, Collection<String> campos) {
    return tags(descriptor).stream()
        .filter(tag -> campos.contains(MinutaTagAliases.canonico(tag)))
        .toList();
  }

  /** Tags de la plantilla sin campo canónico ni alias: impiden generarla. */
  public List<String> tagsSinResolver(MinutaTemplateDescriptor descriptor) {
    return MinutaTagAliases.sinResolver(
        tags(descriptor), new MinutaViviendaData().toTemplateMap().keySet());
  }

  /** Plantilla lista para poi-tl: tags dentro de hipervínculos quedan como runs normales. */
  private static InputStream abrir(String classpathResource) throws IOException {
    try (InputStream in = resource(classpathResource).getInputStream()) {
      return new ByteArrayInputStream(sinHipervinculosEnTags(in.readAllBytes()));
    }
  }

  /**
   * poi-tl no resuelve tags dentro de {@code <w:hyperlink>} (Word convierte en enlace los correos
   * escritos como {@code {{correo}}}). Esos enlaces se desenvuelven: el texto y su formato se
   * conservan y el destino, que apuntaba al placeholder, se descarta.
   */
  static byte[] sinHipervinculosEnTags(byte[] docx) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(docx.length);
    boolean cambio = false;
    try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(docx));
        ZipOutputStream zout = new ZipOutputStream(out)) {
      ZipEntry entry;
      while ((entry = zin.getNextEntry()) != null) {
        byte[] contenido = zin.readAllBytes();
        if (PARTE_TEXTO.matcher(entry.getName()).matches()) {
          String xml = new String(contenido, StandardCharsets.UTF_8);
          String limpio = desenvolverHipervinculos(xml);
          if (!limpio.equals(xml)) {
            contenido = limpio.getBytes(StandardCharsets.UTF_8);
            cambio = true;
          }
        }
        zout.putNextEntry(new ZipEntry(entry.getName()));
        zout.write(contenido);
        zout.closeEntry();
      }
    }
    return cambio ? out.toByteArray() : docx;
  }

  private static String desenvolverHipervinculos(String xml) {
    Matcher m = HIPERVINCULO.matcher(xml);
    StringBuilder sb = new StringBuilder(xml.length());
    while (m.find()) {
      String interior = m.group(1);
      String texto = interior.replaceAll("<[^>]+>", "");
      m.appendReplacement(sb, Matcher.quoteReplacement(texto.contains("{{") ? interior : m.group()));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static ClassPathResource resource(String classpathResource) {
    if (classpathResource == null || classpathResource.isBlank()) {
      throw ApiException.badRequest("Ruta de plantilla requerida.");
    }
    ClassPathResource resource = new ClassPathResource(classpathResource);
    if (!resource.exists()) {
      throw ApiException.badRequest("Plantilla no encontrada en classpath: " + classpathResource);
    }
    return resource;
  }

  private static List<String> tagNames(XWPFTemplate template) {
    Set<String> names = new LinkedHashSet<>();
    for (MetaTemplate meta : template.getElementTemplates()) {
      if (meta instanceof ElementTemplate element) {
        names.add(element.getTagName());
      }
    }
    return new ArrayList<>(names);
  }

  private static void verificarSinPlaceholders(String classpathResource, byte[] bytes)
      throws Exception {
    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
        XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
      String text = extractor.getText();
      if (text.contains("{{") || text.contains("}}")) {
        throw ApiException.badRequest(
            "El documento generado conserva placeholders sin resolver ("
                + classpathResource
                + ").");
      }
    }
  }
}
