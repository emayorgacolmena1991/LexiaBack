package com.lexia.api.modules.expedientes.minutas;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.template.ElementTemplate;
import com.deepoove.poi.template.MetaTemplate;
import com.lexia.api.common.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

  private final Map<String, List<String>> tagsPorPlantilla = new ConcurrentHashMap<>();

  public byte[] render(MinutaTemplateDescriptor descriptor, Map<String, Object> data) {
    if (descriptor == null) {
      throw ApiException.badRequest("Descriptor de plantilla requerido.");
    }
    Map<String, Object> safe = data == null ? Map.of() : data;
    ClassPathResource resource = resource(descriptor);
    try (InputStream in = resource.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      XWPFTemplate template = XWPFTemplate.compile(in);
      List<String> sinDato =
          tagNames(template).stream().filter(tag -> !safe.containsKey(tag)).toList();
      if (!sinDato.isEmpty()) {
        template.close();
        throw ApiException.badRequest(
            "La plantilla "
                + descriptor.classpathResource()
                + " tiene placeholders sin dato asociado: "
                + String.join(", ", sinDato.stream().map(t -> "{{" + t + "}}").toList()));
      }
      template.render(safe);
      template.write(out);
      template.close();
      byte[] bytes = out.toByteArray();
      verificarSinPlaceholders(descriptor, bytes);
      LOG.info(
          "DOCX renderizado product={} kind={} bytes={}",
          descriptor.productCode(),
          descriptor.templateKind(),
          bytes.length);
      return bytes;
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      LOG.error(
          "Error renderizando DOCX {}: {}",
          descriptor.classpathResource(),
          e.getMessage(),
          e);
      String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      throw ApiException.badRequest(
          "Error al generar el documento .docx de la minuta: " + detail);
    }
  }

  public byte[] renderVivienda(MinutaTemplateDescriptor descriptor, MinutaViviendaData data) {
    MinutaViviendaData safe = data == null ? new MinutaViviendaData() : data;
    return render(descriptor, safe.toTemplateMap());
  }

  /** Tags {@code {{...}}} presentes en la plantilla (cuerpo, encabezados y pies). */
  public List<String> tags(MinutaTemplateDescriptor descriptor) {
    return tagsPorPlantilla.computeIfAbsent(
        descriptor.classpathResource(),
        key -> {
          try (InputStream in = resource(descriptor).getInputStream();
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

  /** Tags de la plantilla que quedarían sin dato real (se renderizan como {@code nodata}). */
  public List<String> camposPendientes(
      MinutaTemplateDescriptor descriptor, MinutaViviendaData data) {
    Map<String, Object> map = (data == null ? new MinutaViviendaData() : data).toTemplateMap();
    List<String> pendientes = new ArrayList<>();
    for (String tag : tags(descriptor)) {
      if (MinutaViviendaData.isMissing(map.get(tag))) {
        pendientes.add(tag);
      }
    }
    return pendientes;
  }

  private static ClassPathResource resource(MinutaTemplateDescriptor descriptor) {
    ClassPathResource resource = new ClassPathResource(descriptor.classpathResource());
    if (!resource.exists()) {
      throw ApiException.badRequest(
          "Plantilla no encontrada en classpath: " + descriptor.classpathResource());
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

  private static void verificarSinPlaceholders(MinutaTemplateDescriptor descriptor, byte[] bytes)
      throws Exception {
    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
        XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
      String text = extractor.getText();
      if (text.contains("{{") || text.contains("}}")) {
        throw ApiException.badRequest(
            "El documento generado conserva placeholders sin resolver ("
                + descriptor.classpathResource()
                + ").");
      }
    }
  }
}
