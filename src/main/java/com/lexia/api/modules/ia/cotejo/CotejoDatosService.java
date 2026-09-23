package com.lexia.api.modules.ia.cotejo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.expedientes.CargaDocumentoDtos.DocumentoOcrResultadoDTO;
import com.lexia.api.modules.expedientes.CargaDocumentoService;
import com.lexia.api.modules.expedientes.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.ia.cotejo.CotejoDtos.CotejoComparacion;
import com.lexia.api.modules.ia.cotejo.CotejoDtos.CotejoResponse;
import com.lexia.api.modules.ia.cotejo.CotejoDtos.CotejoResumen;
import com.lexia.api.modules.ia.cotejo.CotejoMotor.Bloque;
import com.lexia.api.modules.ia.cotejo.CotejoMotor.DocCampos;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionDocumento;
import com.lexia.api.modules.ia.ocr.OcrAzureBatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Paso 4. Compara los archivos que el paso 3 ya extrajo (análisis por documento).
 * Si esa extracción no está, lee el texto consolidado de E04 y lo estructura con el LLM
 * ya configurado. No vuelve a ejecutar Azure OCR y no modifica el paso 3.
 */
@Service
public class CotejoDatosService {

  private static final Logger LOG = LoggerFactory.getLogger(CotejoDatosService.class);

  @FunctionalInterface
  interface TextoConsolidado {
    String leer(String sessionId);
  }

  @FunctionalInterface
  interface ExtraccionesGuardadas {
    List<DocumentoOcrResultadoDTO> leer(String sessionId);
  }

  private final TextoConsolidado lectura;
  private final AnalisisDocumentoService analisisDocumentoService;
  private final ExtraccionesGuardadas extracciones;
  private final ObjectMapper objectMapper;
  private final ConcurrentHashMap<String, CotejoResponse> resultados = new ConcurrentHashMap<>();

  @Autowired
  public CotejoDatosService(
      OcrAzureBatchService batchService,
      AnalisisDocumentoService analisisDocumentoService,
      CargaDocumentoService cargaDocumentoService,
      ObjectMapper objectMapper) {
    this(
        id -> leerCache(batchService, id),
        analisisDocumentoService,
        id -> leerArchivos(cargaDocumentoService, id),
        objectMapper);
  }

  CotejoDatosService(TextoConsolidado lectura, AnalisisDocumentoService analisisDocumentoService) {
    this(lectura, analisisDocumentoService, id -> List.of(), new ObjectMapper());
  }

  CotejoDatosService(
      TextoConsolidado lectura,
      AnalisisDocumentoService analisisDocumentoService,
      ExtraccionesGuardadas extracciones,
      ObjectMapper objectMapper) {
    this.lectura = lectura;
    this.analisisDocumentoService = analisisDocumentoService;
    this.extracciones = extracciones;
    this.objectMapper = objectMapper;
  }

  public CotejoResponse cotejar(String sessionId) {
    if (!StringUtils.hasText(sessionId)) {
      throw ApiException.badRequest("sessionId requerido.");
    }
    String id = sessionId.trim();
    List<DocCampos> archivos = camposDeArchivos(id);
    if (archivos.size() >= 2) {
      String cacheKey = id + ":archivos:" + Integer.toHexString(archivos.hashCode());
      CotejoResponse previo = resultados.get(cacheKey);
      if (previo != null) {
        return previo;
      }
      CotejoResponse fresco = responder(id, archivos);
      resultados.put(cacheKey, fresco);
      return fresco;
    }

    String contenido = lectura.leer(id);
    if (!StringUtils.hasText(contenido)) {
      return vacio(id);
    }
    String cacheKey = id + ":" + contenido.length() + ":" + Integer.toHexString(contenido.hashCode());
    CotejoResponse previo = resultados.get(cacheKey);
    if (previo != null) {
      return previo;
    }
    CotejoResponse fresco = construir(id, contenido);
    resultados.put(cacheKey, fresco);
    return fresco;
  }

  private CotejoResponse construir(String sessionId, String contenido) {
    List<Bloque> bloques = CotejoMotor.parse(contenido);
    if (bloques.size() < 2) {
      LOG.info("Cotejo session={} sin bloques comparables ({})", sessionId, bloques.size());
      return vacio(sessionId);
    }

    List<DocCampos> docs = new ArrayList<>();
    int errores = 0;
    for (Bloque bloque : bloques) {
      try {
        ExtraccionDocumento extraccion =
            analisisDocumentoService.extraerDatosClave(bloque.texto(), bloque.tipo());
        if (extraccion == null || "ERROR".equals(extraccion.estado())) {
          errores++;
          LOG.warn(
              "Cotejo sin extracción tipo={} motivo={}",
              bloque.tipo(),
              extraccion == null ? "null" : extraccion.motivo());
          continue;
        }
        docs.add(CotejoMotor.desdeExtraccion(bloque.tipo(), extraccion.datos()));
      } catch (RuntimeException ex) {
        errores++;
        LOG.warn("Cotejo falló al estructurar tipo={}: {}", bloque.tipo(), ex.getMessage());
      }
    }

    if (docs.size() < 2 && errores > 0) {
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          "COTEJO_IA",
          "No se pudo estructurar el texto consolidado con el servicio de IA.");
    }
    if (docs.size() < 2) {
      return vacio(sessionId);
    }
    return responder(sessionId, docs);
  }

  /** Análisis que el paso 3 ya guardó por archivo. No vuelve a llamar al OCR ni al LLM. */
  private List<DocCampos> camposDeArchivos(String sessionId) {
    List<DocumentoOcrResultadoDTO> rows;
    try {
      rows = extracciones.leer(sessionId);
    } catch (RuntimeException ex) {
      LOG.warn("Cotejo sin archivos extraídos session={}: {}", sessionId, ex.getMessage());
      return List.of();
    }
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<DocCampos> docs = new ArrayList<>();
    for (DocumentoOcrResultadoDTO row : rows) {
      if (row == null || !StringUtils.hasText(row.analisisJson())) {
        continue;
      }
      try {
        DatosExtraidosDTO datos =
            objectMapper.readValue(row.analisisJson(), DatosExtraidosDTO.class);
        String nombre =
            StringUtils.hasText(row.nombreOriginal())
                ? row.nombreOriginal()
                : row.tipoDocumento();
        DocCampos campos = CotejoMotor.desdeExtraccion(nombre, datos);
        if (!campos.valores().isEmpty()) {
          docs.add(campos);
        }
      } catch (Exception ex) {
        LOG.warn(
            "Cotejo no pudo leer el análisis de {}: {}",
            row.nombreOriginal(),
            ex.getMessage());
      }
    }
    if (!docs.isEmpty()) {
      LOG.info("Cotejo session={} archivos con datos={}", sessionId, docs.size());
    }
    return docs;
  }

  private CotejoResponse responder(String sessionId, List<DocCampos> docs) {
    List<CotejoComparacion> filas = CotejoMotor.comparar(docs);
    int coinciden = contar(filas, CotejoMotor.COINCIDE);
    int diferencias = contar(filas, CotejoMotor.DIFERENCIA);
    int noEncontrados = contar(filas, CotejoMotor.NO_ENCONTRADO);
    LOG.info(
        "Cotejo session={} docs={} reglas={} coinciden={} diferencias={} noEncontrados={}",
        sessionId,
        docs.size(),
        filas.size(),
        coinciden,
        diferencias,
        noEncontrados);
    return new CotejoResponse(
        sessionId,
        new CotejoResumen(
            filas.size(),
            coinciden,
            diferencias,
            noEncontrados,
            filas.size(),
            diferencias + noEncontrados > 0),
        filas);
  }

  private static String leerCache(OcrAzureBatchService batchService, String sessionId) {
    try {
      return batchService.getConsolidated(sessionId).consolidatedContent();
    } catch (RuntimeException ex) {
      return "";
    }
  }

  private static List<DocumentoOcrResultadoDTO> leerArchivos(
      CargaDocumentoService cargaDocumentoService, String sessionId) {
    try {
      return cargaDocumentoService.listarOcrResultados(sessionId);
    } catch (RuntimeException ex) {
      return List.of();
    }
  }

  private static int contar(List<CotejoComparacion> filas, String estado) {
    int n = 0;
    for (CotejoComparacion fila : filas) {
      if (estado.equals(fila.estado())) {
        n++;
      }
    }
    return n;
  }

  private static CotejoResponse vacio(String sessionId) {
    return new CotejoResponse(
        sessionId, new CotejoResumen(0, 0, 0, 0, 0, false), List.of());
  }
}
