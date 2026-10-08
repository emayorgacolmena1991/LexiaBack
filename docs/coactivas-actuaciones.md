# Actuaciones del expediente coactivo

Contrato en [`openapi/coactivas-actuaciones.yaml`](openapi/coactivas-actuaciones.yaml). Todos los POST autenticados escriben un evento de timeline con el usuario de la sesión. No hay Springdoc en el proyecto: el YAML es el contrato.

## Implementado

| Acción | Ruta | Comportamiento |
|---|---|---|
| Plantillas | `GET /expedientes/{id}/plantillas?etapa=` | Catálogo persistido por tenant |
| Generar PDF | `POST /expedientes/{id}/actuaciones` | PDF con datos del expediente, estado `GENERADO`, `Idempotency-Key` |
| Medidas | `GET/POST /expedientes/{id}/medidas-cautelares` | El alta exige etapa verificada `EMBARGO` |
| Honorarios | `GET /expedientes/{id}/honorarios` | Solo lectura. `disponible: false` porque no hay regla de cálculo |
| Solicitar carga | `POST /expedientes/{id}/solicitudes-carga` | Permiso `coactivas:financiero:gestionar` |
| Correr traslado | `POST /expedientes/{id}/traslado` | Permiso `coactivas:diagnostico:confirmar` |
| Firma | `POST /actuaciones/{id}/firma` | **422** `FIRMA_PROVEEDOR_NO_CONFIGURADO`. No marca la actuación como firmada |
| DataDoc | `POST /expedientes/{id}/datadoc/sync` | **502** `DATADOC_NO_CONFIGURADO`. No llama a un sistema externo |

Transición de etapa inválida: **409** `COA_TRANSICION_INVALIDA` con `{ code, message, allowedNext }`. Escrito y Honorarios están en el catálogo y en `process_stage_transition`.

## Firma (spike)

No hay certificado, proveedor ni token en el repositorio. El README del API deja la firma fuera de alcance. La operación es síncrona y rechaza: no hay `202` ni polling porque no existe un job que consultar. El estado sigue en `GENERADO`. El intento queda en el timeline.

## DataDoc

No hay URL, credencial ni cliente. No se simula éxito, timeout ni reintento externo. La respuesta es 502 de inmediato. La misma `Idempotency-Key` no duplica el evento.

## Honorarios

No hay tabla de porcentajes ni monto retenido. El endpoint devuelve el `montoOriginal` guardado en el expediente y `honorarios: null`. El cliente no calcula.

## Roles (supuesto hasta confirmarlo)

- Generar: `coactivas:documento:generar`
- Firmar: `coactivas:documento:firmar`
- Traslado: `coactivas:diagnostico:confirmar` (abogado senior y admin; el analista no)
- Carga de honorarios: `coactivas:financiero:gestionar`
- DataDoc y medidas: `coactivas:expediente:escribir`
- Lectura de plantillas, medidas y honorarios: `coactivas:expediente:leer`

Laura Gómez (`laura.gomez@lexia.demo`) tiene el rol abogado senior y cubre estas acciones.

## Auditoría

Cada POST persiste `usuario_id` en `coactiva_evento`. El timeline lo expone como `usuarioId` y, si hay documento, `archivoId`.
