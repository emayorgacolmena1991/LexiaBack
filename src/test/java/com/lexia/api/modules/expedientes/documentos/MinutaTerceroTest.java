package com.lexia.api.modules.expedientes.documentos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.common.storage.ArchivoStorageLocal;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.documentos.CaseDocumentoStore.CaseFileContext;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.DocumentoRequisitoItem;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ProductoDetalle;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

/** EJ-2: casilla de minuta de compraventa de tercero (vendedor persona jurídica). */
class MinutaTerceroTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();
  private static final UUID USUARIO = UUID.randomUUID();
  private static final String TIPO = LegalDocument.TIPO_MINUTA_TERCERO;
  private static final String COMPANIA = "VIV_TERMINADA_COMPANIA";
  private static final String INDIVIDUAL = "VIV_TERMINADA_IND";
  private static final byte[] PDF = {'%', 'P', 'D', 'F', 1};

  @TempDir Path almacenamiento;

  private final CaseDocumentoStore caseDocs = mock(CaseDocumentoStore.class);
  private final ProductoBiessService productos = mock(ProductoBiessService.class);
  private final MockMultipartFile archivo =
      new MockMultipartFile("file", "minuta.pdf", "application/pdf", PDF);
  private CargaDocumentoService carga;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(USUARIO, UUID.randomUUID(), TENANT, UUID.randomUUID()));
    carga =
        new CargaDocumentoService(
            null,
            null,
            mock(DocumentoTextoOcrRepository.class),
            productos,
            null,
            caseDocs,
            mock(ObjectProvider.class));
    when(productos.detalle(eq(COMPANIA), any()))
        .thenReturn(
            detalle(COMPANIA, "RUC", "NOMBRAMIENTO_RL", TIPO, LegalDocument.TIPO_SOPORTE_INGRESOS));
    when(productos.detalle(eq(INDIVIDUAL), any()))
        .thenReturn(detalle(INDIVIDUAL, "CEDULA", "PAPELETA", LegalDocument.TIPO_SOPORTE_INGRESOS));
    when(productos.detalle(eq("SUSTITUCION_HIPOTECA"), any()))
        .thenReturn(detalle("SUSTITUCION_HIPOTECA", "CEDULA", "PAPELETA"));
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  private static ProductoDetalle detalle(String producto, String... tipos) {
    List<DocumentoRequisitoItem> requisitos =
        java.util.Arrays.stream(tipos)
            .map(t -> new DocumentoRequisitoItem(t, t, false, 60, "ALL", 1))
            .toList();
    return new ProductoDetalle(producto, producto, requisitos, List.of());
  }

  private void expediente(String producto) {
    when(caseDocs.context(CASO.toString()))
        .thenReturn(
            new CaseFileContext(CASO, TENANT, producto, null, IngestionMode.DIGITAL_SEPARADO));
  }

  @Test
  void conVendedorPersonaJuridicaLaCasillaExisteYSeCarga() {
    expediente(COMPANIA);

    assertThat(carga.tiposPermitidos(CASO.toString()))
        .extracting(CargaDocumentoDtos.TipoPermitidoDTO::codigo)
        .contains(TIPO);
    carga.subir(CASO.toString(), archivo, TIPO, " De la inmobiliaria ");

    verify(caseDocs)
        .subir(any(), eq("minuta.pdf"), eq("application/pdf"), eq(PDF), eq(TIPO), eq(" De la inmobiliaria "));
  }

  @Test
  void conVendedorPersonaNaturalLaCasillaNoExisteYSeRechaza() {
    expediente(INDIVIDUAL);

    assertThat(carga.tiposPermitidos(CASO.toString()))
        .extracting(CargaDocumentoDtos.TipoPermitidoDTO::codigo)
        .doesNotContain(TIPO);
    ApiException error =
        assertThrows(ApiException.class, () -> carga.subir(CASO.toString(), archivo, TIPO, null));

    assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    verify(caseDocs, never()).subir(any(), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void sinProductoNiCatalogoTampocoSeAdmite() {
    expediente(null);

    assertThrows(ApiException.class, () -> carga.subir(CASO.toString(), archivo, TIPO, null));
  }

  @Test
  void elSoporteDeIngresosSeAdmiteEnProductosConCompraventa() {
    String soporte = LegalDocument.TIPO_SOPORTE_INGRESOS;
    assertThat(productos.detalle(INDIVIDUAL, null).requisitos())
        .filteredOn(r -> soporte.equals(r.documentTypeCode()))
        .isNotEmpty()
        .allMatch(r -> !r.mandatory());
    for (String producto : new String[] {INDIVIDUAL, COMPANIA}) {
      expediente(producto);
      assertThat(carga.tiposPermitidos(CASO.toString()))
          .extracting(CargaDocumentoDtos.TipoPermitidoDTO::codigo)
          .contains(soporte);
      carga.subir(CASO.toString(), archivo, soporte, "Rol de pago");
    }

    verify(caseDocs, org.mockito.Mockito.times(2))
        .subir(any(), eq("minuta.pdf"), eq("application/pdf"), eq(PDF), eq(soporte), eq("Rol de pago"));
  }

  @Test
  void elSoporteDeIngresosNoBloqueaNiSeExigeEnSustitucion() {
    String soporte = LegalDocument.TIPO_SOPORTE_INGRESOS;
    expediente("SUSTITUCION_HIPOTECA");

    assertThat(carga.tiposPermitidos(CASO.toString()))
        .extracting(CargaDocumentoDtos.TipoPermitidoDTO::codigo)
        .doesNotContain(soporte);
    assertThrows(ApiException.class, () -> carga.subir(CASO.toString(), archivo, soporte, null));
    verify(caseDocs, never()).subir(any(), anyString(), anyString(), any(), eq(soporte), any());
  }

  @Test
  void observacionDeMasDe500CaracteresSeRechaza() {
    expediente(COMPANIA);

    assertThrows(
        ApiException.class, () -> carga.subir(CASO.toString(), archivo, TIPO, "x".repeat(501)));
    verify(caseDocs, never()).subir(any(), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void quedaAsociadaAlExpedienteConUsuarioTipoManualYObservacion() {
    LegalDocumentRepository documents = mock(LegalDocumentRepository.class);
    DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    ExpedienteDocumentoStorage archivos =
        new ExpedienteDocumentoStorage(new ArchivoStorageLocal(almacenamiento));
    @SuppressWarnings("unchecked")
    CaseDocumentoStore store =
        new CaseDocumentoStore(
            null,
            null,
            documents,
            versions,
            mock(DocumentoTextoOcrRepository.class),
            archivos,
            mock(ObjectProvider.class));
    CaseFileContext ctx =
        new CaseFileContext(CASO, TENANT, COMPANIA, null, IngestionMode.DIGITAL_SEPARADO);

    var dto = store.subir(ctx, "minuta.pdf", "application/pdf", PDF, TIPO, "  De la inmobiliaria ");

    ArgumentCaptor<LegalDocument> doc = ArgumentCaptor.forClass(LegalDocument.class);
    verify(documents).save(doc.capture());
    ArgumentCaptor<DocumentVersion> version = ArgumentCaptor.forClass(DocumentVersion.class);
    verify(versions).save(version.capture());
    LegalDocument guardado = doc.getValue();
    assertEquals(CASO, guardado.getCaseId());
    assertEquals(TIPO, guardado.getDocType());
    assertEquals(LegalDocument.ORIGEN_MANUAL, guardado.getClasificacionOrigen());
    assertEquals(USUARIO, guardado.getUploadedBy());
    assertEquals("De la inmobiliaria", guardado.getObservacion());
    assertThat(guardado.getCreatedAt()).isNotNull();
    assertEquals(TIPO, dto.codigoTipoDocumento());
    assertArrayEquals(PDF, archivos.read(version.getValue().getStorageKey()));
  }
}
