package com.lexia.api.modules.actos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lexia.api.common.api.ApiExceptionHandler;
import com.lexia.api.config.SecurityConfig;
import com.lexia.api.modules.actos.ActosDtos.ActoNotarialListadoDTO;
import com.lexia.api.modules.actos.ActosDtos.ActoNotarialRespuestaDTO;
import com.lexia.api.modules.actos.ActosDtos.DocumentoRequeridoDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ActoNotarialController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, ApiExceptionHandler.class})
@ActiveProfiles("test")
class ActoNotarialControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ActoNotarialService actoNotarialService;

  @Test
  void listaActosConConteo() throws Exception {
    Mockito.when(actoNotarialService.listarActos())
        .thenReturn(List.of(new ActoNotarialListadoDTO("COMPRAVENTA", "Compraventa de Inmueble", 4)));

    mockMvc
        .perform(get("/api/v1/actos-notariales"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].idActo").value("COMPRAVENTA"))
        .andExpect(jsonPath("$[0].totalRequisitos").value(4));
  }

  @Test
  void requisitosPorActo() throws Exception {
    Mockito.when(actoNotarialService.obtenerRequisitosPorActo("COMPRAVENTA"))
        .thenReturn(
            new ActoNotarialRespuestaDTO(
                "COMPRAVENTA",
                "Compraventa de Inmueble",
                List.of(
                    new DocumentoRequeridoDTO(
                        "CEDULA", "Cédula de Identidad / Ciudadanía", true, "Identidad"))));

    mockMvc
        .perform(get("/api/v1/actos-notariales/COMPRAVENTA/requisitos"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.idActo").value("COMPRAVENTA"))
        .andExpect(jsonPath("$.totalRequisitos").value(1))
        .andExpect(jsonPath("$.documentos[0].codigoDocumento").value("CEDULA"))
        .andExpect(jsonPath("$.documentos[0].obligatorio").value(true));
  }
}
