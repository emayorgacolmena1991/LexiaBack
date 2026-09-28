package com.lexia.api.modules.expedientes.minutas;

import com.deepoove.poi.XWPFTemplate;
import com.lexia.api.common.api.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Renderiza plantillas .docx con poi-tl (tags Mustache {@code {{campo}}}). */
@Service
public class DocxMinutaRenderer {

  private static final Logger LOG = LoggerFactory.getLogger(DocxMinutaRenderer.class);

  public byte[] render(MinutaTemplateDescriptor descriptor, Map<String, Object> data) {
    if (descriptor == null) {
      throw ApiException.badRequest("Descriptor de plantilla requerido.");
    }
    Map<String, Object> safe = data == null ? Map.of() : data;
    ClassPathResource resource = new ClassPathResource(descriptor.classpathResource());
    if (!resource.exists()) {
      throw ApiException.badRequest(
          "Plantilla no encontrada en classpath: " + descriptor.classpathResource());
    }
    try (InputStream in = resource.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      XWPFTemplate template = XWPFTemplate.compile(in).render(safe);
      template.write(out);
      template.close();
      byte[] bytes = out.toByteArray();
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
}
