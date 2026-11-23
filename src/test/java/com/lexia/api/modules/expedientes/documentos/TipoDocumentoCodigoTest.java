package com.lexia.api.modules.expedientes.documentos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.actos.ActoNotarialService;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoCargadoDTO;
import com.lexia.api.modules.expedientes.documentos.CaseDocumentoStore.CaseFileContext;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.DocumentoRequisitoItem;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ProductoDetalle;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

/** El tipo que manda el cliente se guarda como código del catálogo, nunca como etiqueta. */
class TipoDocumentoCodigoTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID DRAFT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();
  private static final String PRODUCTO = "VIV_HIPOTECADA_BIESS";

  private final CaseDocumentoStore caseDocs = mock(CaseDocumentoStore.class);
  private final CaseFileContext expediente =
      new CaseFileContext(CASO, TENANT, PRODUCTO, "GUAYAQUIL", IngestionMode.DIGITAL_SEPARADO);
  private CargaDocumentoService carga;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, UUID.randomUUID()));
    ExpedienteBorradorStore borradores = mock(ExpedienteBorradorStore.class);
    when(borradores.find(DRAFT, TENANT))
        .thenReturn(
            Optional.of(
                ExpedienteBorrador.loaded(DRAFT, TENANT, null, PRODUCTO, "GUAYAQUIL", "DIGITAL_SEPARADO")));
    ProductoBiessService productos = mock(ProductoBiessService.class);
    when(productos.detalle(PRODUCTO, "GUAYAQUIL"))
        .thenReturn(
            new ProductoDetalle(
                PRODUCTO,
                "BIESS",
                List.of(
                    new DocumentoRequisitoItem("CEDULA", "Cédula de identidad", true, 0, null, 1),
                    new DocumentoRequisitoItem("AVALUO", "Avalúo del inmueble", true, 0, null, 2)),
                List.of()));
    when(caseDocs.context(CASO.toString())).thenReturn(expediente);
    carga =
        new CargaDocumentoService(
            mock(ActoNotarialService.class),
            null,
            mock(DocumentoTextoOcrRepository.class),
            productos,
            borradores,
            caseDocs,
            mock(ObjectProvider.class));
  }

  @AfterEach
  void limpiar() {
    AuthContext.clear();
  }

  private String subirAlBorrador() {
    return carga
        .subir(
            DRAFT.toString(),
            new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", "%PDF-1.4 x".getBytes(StandardCharsets.UTF_8)))
        .idDocumento();
  }

  private DocumentoCargadoDTO enElBorrador(String docId) {
    return carga.listarDocumentos(DRAFT.toString()).stream()
        .filter(d -> d.idDocumento().equals(docId))
        .findFirst()
        .orElseThrow();
  }

  private static MockMultipartFile pdf() {
    return new MockMultipartFile("file", "nuevo.pdf", "application/pdf", "%PDF-1.4 y".getBytes(StandardCharsets.UTF_8));
  }

  // ---------- borrador (analyze-single / batch)

  @Test
  void etiquetaEnBorradorSeGuardaComoCodigo() {
    String docId = subirAlBorrador();

    carga.actualizarTipoDocumentoLibre(DRAFT.toString(), docId, "Cédula de identidad");

    assertEquals("CEDULA", enElBorrador(docId).codigoTipoDocumento());
    assertEquals(LegalDocument.ORIGEN_MANUAL, enElBorrador(docId).clasificacionOrigen());
  }

  @Test
  void etiquetaSinTildesNiMayusculasTambienSeReconoce() {
    String docId = subirAlBorrador();

    carga.actualizarTipoDocumentoLibre(DRAFT.toString(), docId, "  avaluo   DEL inmueble ");

    assertEquals("AVALUO", enElBorrador(docId).codigoTipoDocumento());
  }

  @Test
  void textoDesconocidoEnBorradorNoAsignaNiMarcaManual() {
    String docId = subirAlBorrador();

    carga.actualizarTipoDocumentoLibre(DRAFT.toString(), docId, "Pasaporte");

    assertNull(enElBorrador(docId).codigoTipoDocumento());
    assertNull(enElBorrador(docId).clasificacionOrigen());
  }

  @Test
  void codigoValidoEnBorradorIgualQueHoy() {
    String docId = subirAlBorrador();

    carga.actualizarTipoDocumentoLibre(DRAFT.toString(), docId, "AVALUO");

    assertEquals("AVALUO", enElBorrador(docId).codigoTipoDocumento());
    assertEquals(LegalDocument.ORIGEN_MANUAL, enElBorrador(docId).clasificacionOrigen());
  }

  // ---------- reupload en borrador

  @Test
  void reemplazoEnBorradorConEtiquetaGuardaElCodigo() {
    String docId = subirAlBorrador();

    assertEquals(
        "AVALUO",
        carga.reemplazarArchivoOcr(DRAFT.toString(), docId, pdf(), "Avalúo del inmueble").codigoTipoDocumento());
    assertEquals(LegalDocument.ORIGEN_MANUAL, enElBorrador(docId).clasificacionOrigen());
  }

  @Test
  void reemplazoEnBorradorConTextoDesconocidoConservaElTipoSinMarcarManual() {
    String docId = subirAlBorrador();

    assertNull(carga.reemplazarArchivoOcr(DRAFT.toString(), docId, pdf(), "Pasaporte").codigoTipoDocumento());
    assertNull(enElBorrador(docId).clasificacionOrigen());
  }

  // ---------- expediente persistido

  @Test
  void etiquetaEnExpedienteSeGuardaComoCodigo() {
    carga.actualizarTipoDocumentoLibre(CASO.toString(), "doc-1", "Avalúo del inmueble");

    verify(caseDocs).actualizarTipo(expediente, "doc-1", "AVALUO");
  }

  @Test
  void textoDesconocidoEnExpedienteNoAsigna() {
    carga.actualizarTipoDocumentoLibre(CASO.toString(), "doc-1", "Pasaporte");

    verify(caseDocs, never()).actualizarTipo(any(), anyString(), anyString());
  }

  @Test
  void codigoValidoEnExpedienteIgualQueHoy() {
    carga.actualizarTipoDocumentoLibre(CASO.toString(), "doc-1", "CEDULA");

    verify(caseDocs).actualizarTipo(expediente, "doc-1", "CEDULA");
  }

  @Test
  void reemplazoEnExpedienteMandaElCodigoONadaAlStore() {
    carga.reemplazarArchivoOcr(CASO.toString(), "doc-1", pdf(), "Cédula de identidad");
    carga.reemplazarArchivoOcr(CASO.toString(), "doc-2", pdf(), "Pasaporte");

    verify(caseDocs).reemplazar(eq(expediente), eq("doc-1"), anyString(), any(), any(), eq("CEDULA"));
    verify(caseDocs).reemplazar(eq(expediente), eq("doc-2"), anyString(), any(), any(), isNull());
  }
}
