# Etapas propuestas y matriz de la entidad

Propuesta para validar con la abogada. No cambia el catálogo de `EstatusProcesoService.ETAPAS` ni agrega migración. No copia valores de la matriz ni texto de los documentos.

## 1. Dos secuencias de origen

No son la misma lista. La columna S trae el nombre que ella escribe. El Control trae el orden de las columnas de fecha, y ese orden no sigue el de la columna S.

### Columna S (9 etiquetas, ambas matrices)

1. Confirmación cita con el cliente
2. Regularizar documentación legal
3. Firma BIESS, matriz y mutuo
4. Entrega Escritura/Mutuo firmados a Notaría
5. Retiro Liquidación Impuestos, Escritura y Mutuo
6. Regularización Documentos para Registro de la Prop
7. Ingreso de escrituras Registro de la Propiedad
8. Validar Fecha Entrega Revision Reg. Propiedad
9. Resultado Registro de la Propiedad

El orden de arriba es solo el de aparición al agrupar. No es un flujo.

### Columnas de fecha del Control

Guayas (hay columnas vacías D, G, H y M; el título de asignación está escrito «asiganción»):

1. Fecha de asignación
2. Fecha Firma Notaría
3. Fecha Entrega de Liquidación
4. Fecha Pago de Liquidación e Impuestos
5. Fecha Firma BIESS
6. Fecha de Cierre
7. Fecha Ingreso al RP
8. Fecha Pago de Ingreso

Manabí y Pichincha, en este orden, sin huecos:

1. Fecha Firma Notaría
2. Fecha Entrega de Liquidación
3. Fecha Pago de Liquidación e Impuestos
4. Fecha Firma BIESS
5. Fecha Entrega / Retiro
6. Fecha de Cierre
7. Fecha Ingreso al RP
8. Fecha Pago de Ingreso

Guayas antepone la asignación y no tiene la columna Entrega / Retiro. Manabí y Pichincha no tienen asignación. El NIP del Control no es la columna B de la matriz.

Conflicto a resolver: en el Control, Firma Notaría va antes que Firma BIESS. En la columna S conviven las dos etiquetas sin orden. Cierre va antes que el ingreso al Registro.

## 2. Lista propuesta

Las 9 etiquetas de la columna S, más las fechas del Control que no tienen etiqueta en S. El orden sigue las columnas de fecha cuando hay pareja; donde no la hay, la etapa queda marcada como solo Control o solo columna S.

| Orden | Etapa | Origen | Texto sugerido |
| --- | --- | --- | --- |
| 1 | Asignación | Solo Control (Guayas) | El expediente quedó asignado. |
| 2 | Confirmación cita con el cliente | Columna S | Pendiente confirmar la cita de firma con el cliente. |
| 3 | Regularizar documentación legal | Columna S | Pendiente regularizar la minuta y la documentación legal. |
| 4 | Firma BIESS, matriz y mutuo | Columna S y Fecha Firma BIESS | Pendiente la firma de la matriz y del mutuo. |
| 5 | Entrega Escritura/Mutuo firmados a Notaría | Columna S y Fecha Firma Notaría | Escritura y mutuo enviados a la notaría. Pendiente la liquidación de impuestos. |
| 6 | Retiro Liquidación Impuestos, Escritura y Mutuo | Columna S y Fecha Entrega de Liquidación | Pendiente el retiro de la liquidación, la escritura y el mutuo para la firma. |
| 7 | Pago de liquidación e impuestos | Solo Control | Pendiente el pago de la liquidación y de los impuestos. |
| 8 | Entrega / retiro | Solo Control (Manabí y Pichincha) | Pendiente la entrega o el retiro de la escritura. |
| 9 | Regularización Documentos para Registro de la Propiedad | Columna S | Pendiente la firma y el envío de los documentos para el Registro de la Propiedad. |
| 10 | Ingreso de escrituras Registro de la Propiedad | Columna S y Fecha Ingreso al RP | Las escrituras ingresaron al Registro de la Propiedad. |
| 11 | Pago de ingreso al Registro | Solo Control | Pendiente el pago de ingreso al Registro de la Propiedad. |
| 12 | Validar Fecha Entrega Revision Reg. Propiedad | Columna S | Pendiente validar la fecha de entrega de la revisión en el Registro de la Propiedad. |
| 13 | Resultado Registro de la Propiedad | Columna S | Pendiente el resultado de la inscripción en el Registro de la Propiedad. |
| 14 | Cierre | Solo Control | El trámite quedó cerrado. |

Los textos salen del tipo de observación de la columna U: cita y firma, minuta y documento, envío a notaría con liquidación de impuestos, retiro para firma, firma y envío al Registro, y texto de resultado. Las etapas que solo están en el Control no tienen observación en la columna U; el texto describe la fecha.

«Regularizar documentación legal» y «Regularización Documentos para Registro de la Propiedad» se dejan separadas. Pueden unirse si la abogada dice que son la misma etapa.

El catálogo actual del código (recepción, estudio, regularización, minuta, notaría, liquidación, pago, firma, registro, cierre) no usa estas etiquetas. No se toca hasta que ella confirme esta lista.

## 3. Matriz de la entidad

Una sola hoja. Encabezados de la fila 1, sin valores:

| Col | Encabezado | Uso |
| --- | --- | --- |
| A | PRODUCTO | Dato de la entidad |
| B | NUMERO INSTANCIA PROCESO | Número de instancia. Llave contra `legal_case.numero_instancia` |
| C | FECHA ULTIMA TRANSACCION | Dato de la entidad |
| D | FECHA ACTUAL | Dato de la entidad |
| E | DIAS EN ACTIVIDAD | Dato de la entidad |
| F | ID REFERENCIA | Dato de la entidad |
| G | DESCRIPCION REFERENCIA | Dato de la entidad |
| H | Provincia | Dato de la entidad |
| I | Cantón | Dato de la entidad |
| J | Datos Generales Estudio - Centro de Costo - C.Costos | Dato de la entidad |
| K | Datos aprobados para desembolso - Monto Aprobado | Dato de la entidad |
| L | Datos de la solicitud - Monto Original Solicitado | Dato de la entidad |
| M | Proyecto Precalificado - Es un proyecto calificado? * | Dato de la entidad |
| N | Datos de la construcción - Nombre del Constructor en caso de Vivienda Nueva | Dato de la entidad |
| O | Datos de la solicitud - Producto solicitado | Dato de la entidad |
| P | Proyecto Precalificado - Proyecto | Dato de la entidad |
| Q | Datos del vendedor - Nombres y Apellidos | Dato personal. No se loguea |
| R | Datos del vendedor - Identificación | Dato personal. No se loguea |
| S | ETAPA 11-11-2026 | Etapa. El sufijo de fecha cambia |
| T | ORDEN GRUPO 11-11-2026 | Agrupación de la entidad. No es el estatus |
| U | OBSERVACION | Texto del estatus |

Hay dos archivos con estos encabezados. En uno la columna U está vacía en todas las filas. En el otro, todas las filas traen observación. La muestra no tiene validaciones ni listas desplegables.

## 4. Viabilidad de llenar S y U

Viable, y no hace falta otra librería. `BandejaExcel` ya escribe xlsx con `XSSFWorkbook`. `poi-tl` 1.12.2 y `poi-ooxml-full` 5.2.2 están en el classpath.

La función recibiría el xlsx, resolvería las columnas por el texto del encabezado (B por «NUMERO INSTANCIA», S por el prefijo «ETAPA», U por «OBSERVACION»), buscaría cada número en los expedientes del tenant y escribiría en S la etiqueta de la última etapa y en U el último texto. Devolvería los mismos bytes. Solo se escriben esas celdas; el resto del libro se conserva. Como la muestra no tiene validaciones, escribir texto no rompe una lista.

Hace falta, y no está hecho:

- Un endpoint o una herramienta de un solo uso, con permiso `expedientes:caso:leer` y filtro por tenant además de RLS.
- Resolver columnas por encabezado, no por la letra. El sufijo de S y T («11-11-2026») va a cambiar.
- Un mapa del código de etapa de Lexia a la etiqueta de la columna S. Hoy no coinciden. Esta propuesta es ese mapa, pendiente de la abogada.
- Regla de sobrescritura. Llenar U en el archivo que ya trae observaciones borra el texto de la abogada. La regla segura es llenar solo celdas vacías, o escribir en una columna nueva.
- Informe de números no encontrados, vacíos o repetidos. El formato de la columna B puede no coincidir con `numero_instancia` (ceros, espacios).
- No registrar en logs nombres, identificaciones, montos ni el texto de U. No guardar el archivo lleno en una ruta pública.

El libro de Control queda fuera. Tiene otra forma: NIP en distinta columna según la hoja, fechas en vez de una etapa, y tres hojas provinciales.

`XSSFWorkbook` carga el libro entero en memoria, igual que la exportación de la bandeja. La muestra tiene 14 filas. Un archivo mucho mayor habría que medirlo antes de subirlo por el mismo camino.
