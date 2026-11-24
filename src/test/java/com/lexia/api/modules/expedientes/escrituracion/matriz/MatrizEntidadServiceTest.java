package com.lexia.api.modules.expedientes.escrituracion.matriz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthException;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.CaseReadScope;
import com.lexia.api.modules.expedientes.caso.EstatusEtapa;
import com.lexia.api.modules.expedientes.caso.EstatusEtapaRepository;
import com.lexia.api.modules.expedientes.caso.EstatusProceso;
import com.lexia.api.modules.expedientes.caso.EstatusProcesoRepository;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.escrituracion.matriz.MatrizEntidadService.Clasificacion;
import com.lexia.api.modules.expedientes.escrituracion.matriz.MatrizEntidadService.FilaResumen;
import com.lexia.api.modules.identity.AuditEvent;
import com.lexia.api.modules.identity.AuditEventRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import com.lexia.api.modules.tenancy.Tenant;
import com.lexia.api.modules.tenancy.TenantRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

/** Todo con Excel sintéticos armados aquí: ningún archivo real de la entidad. */
class MatrizEntidadServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID MIA = UUID.randomUUID();
  // Encabezado en otro orden que la matriz real y con un título arriba (fila 1).
  private static final int COL_OBS = 0;
  private static final int COL_PRODUCTO = 1;
  private static final int COL_INSTANCIA = 2;
  private static final int COL_ETAPA = 3;
  private static final int COL_FORMULA = 4;

  private final AuthorizationService authorization = mock(AuthorizationService.class);
  private final TenantRepository tenants = mock(TenantRepository.class);
  private final LegalCaseRepository legalCases = mock(LegalCaseRepository.class);
  private final EstatusProcesoRepository estatus = mock(EstatusProcesoRepository.class);
  private final EstatusEtapaRepository etapas = mock(EstatusEtapaRepository.class);
  private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
  private final List<LegalCase> casos = new ArrayList<>();
  private final List<EstatusProceso> ultimos = new ArrayList<>();
  private MatrizEntidadService service;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, MIA));
    Tenant bufete = mock(Tenant.class);
    when(bufete.getTenantKind()).thenReturn("LEGAL_FIRM");
    when(tenants.findById(TENANT)).thenReturn(Optional.of(bufete));
    when(authorization.hasPermission(CaseReadScope.READ_ALL)).thenReturn(true);
    when(legalCases.findEscrituracionPorNumerosInstancia(eq(TENANT), any()))
        .thenAnswer(
            call -> {
              Collection<String> numeros = call.getArgument(1);
              return casos.stream()
                  .filter(c -> numeros.contains(MatrizEntidad.sinEspacios(c.getNumeroInstancia())))
                  .toList();
            });
    when(estatus.findRecientes(eq(TENANT), any()))
        .thenAnswer(
            call -> {
              Collection<UUID> ids = call.getArgument(1);
              return ultimos.stream().filter(e -> ids.contains(e.getCaseId())).toList();
            });
    when(etapas.findByTenantId(TENANT))
        .thenReturn(
            List.of(
                EstatusEtapa.of(TENANT, "FIRMA_BIESS", "Firma BIESS", "Firma BIESS, matriz y mutuo", 4, ""),
                EstatusEtapa.of(TENANT, "ASIGNACION", "Asignación", null, 1, "")));
    service = new MatrizEntidadService(authorization, tenants, legalCases, estatus, etapas, auditEvents);
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  // --- lectura ---------------------------------------------------------------------------------

  @Test
  void ubicaLasColumnasPorEncabezadoEnCualquierOrden() throws Exception {
    try (XSSFWorkbook book = MatrizEntidad.abrir(matriz(List.of()))) {
      MatrizEntidad.Columnas c = MatrizEntidad.ubicar(book);
      assertEquals(1, c.filaEncabezado());
      assertEquals(COL_INSTANCIA, c.instancia());
      assertEquals(COL_ETAPA, c.etapa());
      assertEquals(COL_OBS, c.observacion());
    }
  }

  @Test
  void numeroDeInstanciaNumericoOTextoSeNormaliza() throws Exception {
    byte[] xlsx =
        matriz(
            List.of(
                fila(12345678d, ""), fila(" INS 001 ", ""), fila(1.2345678901E10, ""), fila("", "sin número")));
    try (XSSFWorkbook book = MatrizEntidad.abrir(xlsx)) {
      List<String> numeros =
          MatrizEntidad.filas(book, MatrizEntidad.ubicar(book)).stream().map(MatrizEntidad.FilaMatriz::numero).toList();
      assertEquals(List.of("12345678", "INS001", "12345678901"), numeros);
    }
  }

  @Test
  void faltanColumnasDanUn400Claro() throws Exception {
    ApiException sinInstancia =
        assertThrows(ApiException.class, () -> service.previsualizar(archivo(libro("PRODUCTO", "ETAPA", "OBSERVACION")), false));
    assertTrue(sinInstancia.getMessage().contains("NUMERO INSTANCIA PROCESO"));
    ApiException sinObs =
        assertThrows(
            ApiException.class, () -> service.previsualizar(archivo(libro("NUMERO INSTANCIA PROCESO", "ETAPA 01-12-2026")), false));
    assertTrue(sinObs.getMessage().contains("OBSERVACION"));
    ApiException sinEtapa =
        assertThrows(
            ApiException.class, () -> service.previsualizar(archivo(libro("NUMERO INSTANCIA PROCESO", "OBSERVACION")), false));
    assertTrue(sinEtapa.getMessage().contains("ETAPA"));
  }

  @Test
  void xlsYXlsxConMacrosSeRechazan() throws Exception {
    byte[] xls;
    try (HSSFWorkbook viejo = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      viejo.createSheet("Hoja1").createRow(0).createCell(0).setCellValue("NUMERO INSTANCIA PROCESO");
      viejo.write(out);
      xls = out.toByteArray();
    }
    ApiException ex = assertThrows(ApiException.class, () -> service.previsualizar(archivo(xls), false));
    assertTrue(ex.getMessage().contains(".xls"));

    byte[] conMacros = agregarEntrada(matriz(List.of()), "xl/vbaProject.bin", new byte[] {1, 2, 3});
    assertTrue(assertThrows(ApiException.class, () -> service.previsualizar(archivo(conMacros), false))
        .getMessage().contains("macros"));
    assertThrows(ApiException.class, () -> service.previsualizar(archivo("no es excel".getBytes()), false));
    verify(legalCases, never()).findEscrituracionPorNumerosInstancia(any(), any());
  }

  // --- cruce y clasificación -------------------------------------------------------------------

  @Test
  void clasificaCadaFilaSinSobrescribirPorDefecto() throws Exception {
    escenario();

    List<FilaResumen> filas = service.previsualizar(archivo(matrizDelEscenario()), false).filas();

    assertEquals(
        List.of(
            Clasificacion.ACTUALIZADA,
            Clasificacion.SIN_ESTATUS,
            Clasificacion.NO_ENCONTRADA,
            Clasificacion.DUPLICADA,
            Clasificacion.OMITIDA_YA_TIENE_TEXTO),
        filas.stream().map(FilaResumen::clasificacion).toList());
    assertEquals("Firma BIESS, matriz y mutuo", filas.get(0).etapa());
    assertEquals(3, filas.get(0).fila(), "fila como en Excel: título, encabezado, primera fila de datos");
    // Sin etiqueta de matriz se usa el nombre visible.
    assertEquals("Asignación", filas.get(4).etapa());
  }

  @Test
  void conSobrescribirTambienActualizaLaQueYaTeniaTexto() throws Exception {
    escenario();
    Map<Clasificacion, Integer> totales = service.previsualizar(archivo(matrizDelEscenario()), true).totales();
    assertEquals(2, totales.get(Clasificacion.ACTUALIZADA));
    assertEquals(0, totales.get(Clasificacion.OMITIDA_YA_TIENE_TEXTO));
  }

  @Test
  void expedienteDeOtroResponsableNoAparece() throws Exception {
    // Solo el de la fila 1 es de MIA; los demás, de otro abogado.
    responsable = UUID.randomUUID();
    escenario();
    casos.set(0, caso("A-1", MIA));
    ultimos.set(0, EstatusProceso.crear(TENANT, casos.get(0).getId(), null, "FIRMA_BIESS", "Texto del estatus A"));
    when(authorization.hasPermission(CaseReadScope.READ_ALL)).thenReturn(false);

    List<FilaResumen> filas = service.previsualizar(archivo(matrizDelEscenario()), true).filas();

    assertEquals(Clasificacion.ACTUALIZADA, filas.get(0).clasificacion());
    assertEquals(Clasificacion.NO_ENCONTRADA, filas.get(1).clasificacion());
    assertEquals(Clasificacion.NO_ENCONTRADA, filas.get(4).clasificacion());
  }

  @Test
  void soloBufeteYSiempreConElTenantDeLaSesion() throws Exception {
    escenario();
    service.previsualizar(archivo(matrizDelEscenario()), false);
    verify(legalCases).findEscrituracionPorNumerosInstancia(eq(TENANT), any());
    verify(authorization).requirePermission("expedientes:caso:leer");

    Tenant notaria = mock(Tenant.class);
    when(notaria.getTenantKind()).thenReturn("NOTARY");
    when(tenants.findById(TENANT)).thenReturn(Optional.of(notaria));
    AuthException ex =
        assertThrows(AuthException.class, () -> service.previsualizar(archivo(matrizDelEscenario()), false));
    assertEquals(403, ex.getStatus().value());
  }

  // --- escritura -------------------------------------------------------------------------------

  @Test
  void llenarEscribeSoloEtapaYObservacionDeLasActualizadasYNoTocaNadaMas() throws Exception {
    escenario();
    byte[] entrada = matrizDelEscenario();

    MatrizEntidadService.Llenado llenado =
        service.llenar(archivo(entrada, "Matriz noviembre.xlsx"), false);

    assertEquals("Matriz noviembre_LEXIA.xlsx", llenado.nombre());
    try (XSSFWorkbook antes = new XSSFWorkbook(new ByteArrayInputStream(entrada));
        XSSFWorkbook despues = new XSSFWorkbook(new ByteArrayInputStream(llenado.archivo()))) {
      XSSFSheet hoja = despues.getSheetAt(0);
      // Fila 3 (índice 2): ACTUALIZADA.
      assertEquals("Firma BIESS, matriz y mutuo", hoja.getRow(2).getCell(COL_ETAPA).getStringCellValue());
      assertEquals("Texto del estatus A", hoja.getRow(2).getCell(COL_OBS).getStringCellValue());
      // OMITIDA_YA_TIENE_TEXTO: la observación de la abogada sigue igual.
      assertEquals("Observación previa", hoja.getRow(6).getCell(COL_OBS).getStringCellValue());

      for (int h = 0; h < antes.getNumberOfSheets(); h++) {
        XSSFSheet a = antes.getSheetAt(h);
        XSSFSheet d = despues.getSheetAt(h);
        assertEquals(a.getSheetName(), d.getSheetName());
        for (int col = 0; col <= COL_FORMULA; col++) {
          assertEquals(a.getColumnWidth(col), d.getColumnWidth(col), "ancho de columna " + col);
        }
        for (Row ra : a) {
          for (Cell ca : ra) {
            boolean escrita = h == 0 && ra.getRowNum() == 2 && (ca.getColumnIndex() == COL_ETAPA || ca.getColumnIndex() == COL_OBS);
            Cell cd = d.getRow(ra.getRowNum()).getCell(ca.getColumnIndex());
            assertEquals(ca.getCellStyle().getIndex(), cd.getCellStyle().getIndex(), "formato " + ca.getAddress());
            if (!escrita) {
              assertEquals(ca.toString(), cd.toString(), "valor " + ca.getAddress());
            }
          }
        }
      }
      assertEquals("SUM(B3:B7)", hoja.getRow(2).getCell(COL_FORMULA).getCellFormula());
      assertTrue(hoja.getCTWorksheet().isSetAutoFilter(), "el filtro de la hoja se conserva");
      assertEquals("ETAPA 11-11-2026", hoja.getRow(1).getCell(COL_ETAPA).getStringCellValue());
    }
    ArgumentCaptor<AuditEvent> evento = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents).save(evento.capture());
    assertEquals("MATRIZ_LLENADA:A=1,S=1,N=1,D=1,O=1", evento.getValue().getEvent());
    assertNull(evento.getValue().getObjectId());
    assertFalse(evento.getValue().getEvent().contains("A-1"), "sin números de instancia");
  }

  // --- datos sintéticos ------------------------------------------------------------------------

  /** A-1 con estatus; B-2 sin estatus; C-3 no existe; D-4 repetido; E-5 con estatus y observación previa. */
  private void escenario() {
    LegalCase a = caso("A-1");
    caso("B-2");
    caso("D-4");
    caso("D-4");
    LegalCase e = caso("E 5");
    ultimos.add(EstatusProceso.crear(TENANT, a.getId(), null, "FIRMA_BIESS", "Texto del estatus A"));
    ultimos.add(EstatusProceso.crear(TENANT, e.getId(), null, "ASIGNACION", "Texto del estatus E"));
  }

  private byte[] matrizDelEscenario() throws Exception {
    return matriz(
        List.of(fila("A-1", ""), fila("B-2", ""), fila("C-3", ""), fila("D-4", ""), fila("E5", "Observación previa")));
  }

  /** Responsable de los expedientes que crea {@link #escenario()}. */
  private UUID responsable = MIA;

  private LegalCase caso(String numero) {
    LegalCase c = caso(numero, responsable);
    casos.add(c);
    return c;
  }

  private static LegalCase caso(String numero, UUID membership) {
    LegalCase c = LegalCase.create(TENANT, "LEX-" + numero, "EJD", "Escrituración", "S", "ALTA", membership, null, null);
    c.setNumeroInstancia(numero);
    return c;
  }

  private record Fila(Object numero, String observacion) {}

  private static Fila fila(Object numero, String observacion) {
    return new Fila(numero, observacion);
  }

  /** Título en la fila 1, encabezado en la 2, datos desde la 3; segunda hoja, fórmula, formato, filtro. */
  private static byte[] matriz(List<Fila> filas) throws Exception {
    try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      XSSFSheet sheet = book.createSheet("Matriz");
      sheet.createRow(0).createCell(0).setCellValue("REPORTE SINTÉTICO");
      CellStyle negrita = book.createCellStyle();
      Font font = book.createFont();
      font.setBold(true);
      negrita.setFont(font);
      Row head = sheet.createRow(1);
      String[] encabezados = {"OBSERVACION", "PRODUCTO", "Número Instancia  Proceso", "ETAPA 11-11-2026", "TOTAL"};
      for (int i = 0; i < encabezados.length; i++) {
        Cell c = head.createCell(i);
        c.setCellValue(encabezados[i]);
        c.setCellStyle(negrita);
        sheet.setColumnWidth(i, (10 + i) * 256);
      }
      int r = 2;
      for (Fila f : filas) {
        Row row = sheet.createRow(r++);
        if (!f.observacion().isEmpty()) {
          row.createCell(COL_OBS).setCellValue(f.observacion());
        }
        row.createCell(COL_PRODUCTO).setCellValue(r);
        Cell num = row.createCell(COL_INSTANCIA);
        if (f.numero() instanceof Double d) {
          num.setCellValue(d);
        } else {
          num.setCellValue((String) f.numero());
        }
        row.createCell(COL_FORMULA).setCellFormula("SUM(B3:B7)");
      }
      sheet.setAutoFilter(new CellRangeAddress(1, Math.max(1, r - 1), 0, encabezados.length - 1));
      book.createSheet("Otra hoja").createRow(0).createCell(0).setCellValue("no se toca");
      book.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] libro(String... encabezados) throws Exception {
    try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Row head = book.createSheet("Hoja1").createRow(0);
      for (int i = 0; i < encabezados.length; i++) {
        head.createCell(i).setCellValue(encabezados[i]);
      }
      book.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] agregarEntrada(byte[] xlsx, String nombre, byte[] contenido) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(xlsx));
        ZipOutputStream zip = new ZipOutputStream(out)) {
      ZipEntry e;
      while ((e = in.getNextEntry()) != null) {
        zip.putNextEntry(new ZipEntry(e.getName()));
        zip.write(in.readAllBytes());
        zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry(nombre));
      zip.write(contenido);
      zip.closeEntry();
    }
    return out.toByteArray();
  }

  private static MockMultipartFile archivo(byte[] bytes) {
    return archivo(bytes, "matriz.xlsx");
  }

  private static MockMultipartFile archivo(byte[] bytes, String nombre) {
    return new MockMultipartFile("file", nombre, "application/octet-stream", bytes);
  }
}
