#!/usr/bin/env python3
"""
generar_plantilla_notaria.py

Convierte el borrador de la Notaría COMPRAVENTA_HIPOTECA_BIESS.docx (con bloques XXXX)
en una plantilla poi-tl (COMPRAVENTA_HIPOTECA_BIESS_TEMPLATE.docx) con {{variables}}.

NOTA SOBRE MAPPING:
  El diccionario original estaba indexado por la longitud del bloque XXXX, pero en el
  borrador las longitudes no son únicas (16 X -> 4 variables distintas) y casi ninguna
  coincide con el documento real. Por eso MAPPING se indexa por posición:
      "<ubicación>#<n>"  ->  ubicación = P<párrafo> (1-based) o T<t>R<r>C<c>P<p> en tablas
                              n         = n-ésimo bloque XXXX dentro de ese párrafo.
  TB<p> = párrafo p de un cuadro de texto: Word guarda cada cuadro dos veces (Choice y Fallback)
  y ambas copias usan la misma clave.
  TEXTOS_VARIABLES cubre datos que en el borrador están escritos a mano (no con XXXX).
  AJUSTES corrige el texto pegado a un XXXX (ej. "cero XXXX" en una cédula).

TAGS:
  Con el mismo dato en MinutaViviendaData.toTemplateMap() se usa esa clave. Los demás quedan sin
  clave a propósito (ver los comentarios "SIN ORIGEN" en cada fila): DocxMinutaRenderer rechaza
  la plantilla hasta que el dato exista.

Uso:
  python generar_plantilla_notaria.py [--entrada RUTA] [--salida RUTA]
"""
import argparse
import logging
import re
import shutil
import sys
from datetime import datetime
from pathlib import Path

from docx import Document
from docx.oxml.ns import qn
from docx.text.paragraph import Paragraph

RAIZ_BACKEND = Path(__file__).resolve().parents[2]
RUTA_ENTRADA = Path(__file__).resolve().with_name("COMPRAVENTA_HIPOTECA_BIESS.docx")
RUTA_SALIDA = RAIZ_BACKEND / "src/main/resources/templates/notaria/COMPRAVENTA_HIPOTECA_BIESS_TEMPLATE.docx"

PATRON_X = re.compile(r"[Xx]{4,}")

# Cifras del cuerpo como «letras (número)»: NotaryEscrituraService arma cada x_completo
# (LetrasNotariales), también la cuantía del encabezado. Las firmas (C.C., RUC) quedan solo en número.
# x_completo de un monto ya trae «USD» y «DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA».
PRECIO = "{{precio_compraventa_numero_completo}}"
# Un solo bloque XXXX nombra a los dos cónyuges: toTemplateMap() los tiene por separado.
CONYUGES = "{{nombre_conyuge_1}} y {{nombre_conyuge_2}}"
CONYUGES_MAYUS = "{{nombre_conyuge_1}} Y {{nombre_conyuge_2}}"
PROFESIONES = "{{profesion_conyuge_1}} y {{profesion_conyuge_2}}"

# ----------------------------------------------------------------------------------------
# 1) Bloques XXXX -> {{variable}}  (clave = "<ubicación>#<n-ésimo XXXX del párrafo>")
# ----------------------------------------------------------------------------------------
MAPPING = {
    # --- P1: encabezado de la escritura ---
    "P1#1": "{{nombre_vendedor}}",
    "P1#2": CONYUGES_MAYUS,
    "P1#3": "{{precio_compraventa_numero_completo}}",  # CUANTÍA
    "P1#4": CONYUGES_MAYUS,
    "P1#5": "{{monto_prestamo_completo}}",  # CUANTÍA
    # --- P2: comparecencia ante notaria ---
    "P2#1": "{{nombre_vendedor}}",
    "P2#2": "{{representante_vendedor}}",  # SIN ORIGEN: BUFETE_JSON
    "P2#3": "{{cargo_representante_vendedor}}",  # SIN ORIGEN: BUFETE_JSON
    "P2#4": CONYUGES,
    # --- P2: compraventa, cláusula primera ---
    "P2#5": "{{nombre_vendedor}}",
    "P2#6": "{{representante_vendedor}}",
    "P2#7": CONYUGES,
    # --- P2: compraventa, cláusula segunda (antecedentes) ---
    "P2#8": "{{nombre_vendedor}}",
    "P2#9": "{{descripcion_inmueble_general}}",
    "P2#10": "{{linderos_generales_norte}}",
    "P2#11": "{{linderos_generales_sur}}",
    "P2#12": "{{linderos_generales_este}}",
    "P2#13": "{{linderos_generales_oeste}}",
    "P2#14": "{{superficie_general_completo}}",
    "P2#15": "{{clave_catastral_completo}}",
    "P2#16": "{{area_construccion_completo}}",
    "P2#17": "{{nombre_vendedor}}",
    "P2#18": "{{nombre_adquirente_anterior}}",
    "P2#19": "{{notaria_antecedente}}",
    "P2#20": "",  # "Notario XXXX": notaria_antecedente ya nombra la notaría (ver AJUSTES)
    "P2#21": "{{fecha_inscripcion_antecedente_completo}}",
    # --- P2: compraventa, cláusulas tercera y cuarta ---
    "P2#22": "{{nombre_vendedor}}",
    "P2#23": CONYUGES,
    "P2#24": PRECIO,
    "P2#25": "{{valor_entrada_numero_completo}}",
    "P2#26": "{{monto_prestamo_completo}}",
    "P2#27": "{{nombre_entidad_crediticia}}",  # SIN ORIGEN: DERIVADO (producto BIESS)
    # --- P2: compraventa, cláusula undécima (notificaciones) ---
    "P2#28": "{{direccion_vendedor}}",
    "P2#29": "{{ciudad_notificacion_vendedor}}",  # SIN ORIGEN: BUFETE_JSON
    "P2#30": "{{telefono_vendedor_completo}}",
    "P2#31": "{{correo_vendedor}}",
    "P2#32": "{{direccion_deudor}}",
    "P2#33": "{{telefono_deudor_completo}}",
    "P2#34": "{{correo_deudor}}",
    # --- P2: hipoteca, cláusula primera (comparecientes) ---
    "P2#35": "{{nombre_conyuge_1}}",
    "P2#36": "{{cedula_conyuge_1_completo}}",
    "P2#37": "{{nombre_conyuge_2}}",
    "P2#38": "{{cedula_conyuge_2_completo}}",
    # --- P2: hipoteca, cláusula segunda (antecedentes) ---
    "P2#39": "{{nombre_conyuge_1}}",
    "P2#40": "{{nombre_conyuge_2}}",
    # --- P2: hipoteca, cláusula décima quinta (declaración juramentada) ---
    "P2#41": "{{nombre_conyuge_1}}",
    "P2#42": "{{cedula_conyuge_1_completo}}",
    "P2#43": "{{nombre_conyuge_2}}",
    "P2#44": "{{cedula_conyuge_2_completo}}",
    # --- Bloque de firmas ---
    "P4#1": "{{nombre_vendedor}}",
    "P5#1": "{{ruc_vendedor}}",  # SIN ORIGEN: BUFETE_JSON (cedula_vendedor es otro campo)
    "P11#1": "{{representante_vendedor}}",
    "P13#1": "{{cedula_representante_vendedor}}",  # SIN ORIGEN: BUFETE_JSON
    "P19#1": "{{nombre_conyuge_1}}",
    "P20#1": "{{cedula_conyuge_1}}",
    "P27#1": "{{nombre_conyuge_2}}",
    "P28#1": "{{cedula_conyuge_2}}",
    # --- Cuadro de texto "ÚLTIMA FOJA" ---
    "TB1#1": "{{nombre_vendedor}}",
    "TB1#2": "{{representante_vendedor}}",
    "TB1#3": "{{nombre_conyuge_1}}",
    "TB1#4": "{{nombre_conyuge_2}}",
    "TB1#5": CONYUGES_MAYUS,
}

# ----------------------------------------------------------------------------------------
# 2) Texto variable escrito a mano en el borrador: (ubicación, texto_literal, reemplazo)
#    Se reemplazan TODAS las ocurrencias dentro de esa ubicación.
# ----------------------------------------------------------------------------------------
TEXTOS_VARIABLES = [
    # Datos de la notaría (formulario)
    ("P2", "CRISTEL PAMELA QUIROLA LEMA", "{{notaria_titular_nombre}}"),
    ("P46", "CRISTEL PAMELA QUIROLA LEMA", "{{notaria_titular_nombre}}"),
    ("P2", "SEXAGÉSIMA PRIMERA", "{{notaria_numero_literal}}"),
    ("P47", "SEXAGÉSIMA PRIMERA", "{{notaria_numero_literal}}"),
    ("P2", "Santiago de Guayaquil, Capital de la Provincia del Guayas",
     "{{canton_notaria}}, Provincia de {{provincia_notaria}}"),  # SIN ORIGEN: NOTARIA_FORMULARIO
    ("P2", "jueces competentes de la ciudad de Guayaquil", "jueces competentes de la ciudad de {{canton_notaria}}"),
    ("P47", "DE GUAYAQUIL", "DE {{canton_notaria}}"),
    # Apoderado del BIESS: dos grafías en el cuerpo y la cédula en letras + número
    ("P2", "ANDRE DANIEL LEON NAVEDA", "{{apoderado_biess}}"),
    ("P2", "ANDRÉ DANIEL LEÓN NAVEDA", "{{apoderado_biess}}"),
    ("P38", "ANDRE DANIEL LEON NAVEDA", "{{apoderado_biess}}"),
    ("P2", "cero nueve dos dos cinco cinco dos cuatro ocho – cuatro (092255248 - 4)", "{{cedula_apoderado_biess_completo}}"),
    ("P40", "0922552484", "{{cedula_apoderado_biess}}"),
    # Comparecencia ante notaria
    # El derivado trae la preposición: «a los veinte días…» / «al primer día…».
    ("P2", "a los veinte días del mes de agosto de dos mil veintiséis", "{{fecha_otorgamiento_completo}}"),  # SIN ORIGEN: NOTARIA_FORMULARIO
    # La frase describe al representante, no a la compañía: estado_civil_vendedor es otro dato.
    ("P2", "de estado civil casado, mayor de edad, licenciado",
     "de estado civil {{estado_civil_representante_vendedor}}, mayor de edad, {{profesion_representante_vendedor}}"),  # SIN ORIGEN: BUFETE_JSON
    ("P2", "domiciliado en esta ciudad de Guayaquil", "domiciliado en esta ciudad de {{ciudad_representante_vendedor}}"),  # SIN ORIGEN: BUFETE_JSON
    ("P2", "domiciliados en esta ciudad de Guayaquil", "domiciliados en esta ciudad de {{canton_domicilio}}"),
    ("P2", "casados, tecnólogo superior en seguridad ciudadana y licenciada en psicología",
     "{{estado_civil}}, " + PROFESIONES),
    # Compraventa
    ("P2", "cantón Guayaquil", "cantón {{canton_inmueble}}"),
    ("P2", "Gerente General, el señor licenciado",
     "{{cargo_representante_vendedor}}, el señor {{profesion_representante_vendedor}}"),
    ("P2", "el treinta y uno de marzo de dos mil veintiséis", "el {{fecha_escritura_antecedente_completo}}"),
    ("P2", "ante el abogado ", "ante "),
    ("P2", "Registro de la Propiedad de Guayaquil el", "Registro de la Propiedad de {{canton_inmueble}} el"),
    ("P2", "Abogada Verónica Valdivieso Rodríguez", "{{abogado_patrocinador_compraventa}}"),  # SIN ORIGEN: BUFETE_JSON
    ("P2", "cero nueve – dos mil diecisiete – treinta y seis (09 – 2017 - 36)", "{{matricula_abogado_compraventa}}"),  # SIN ORIGEN: BUFETE_JSON
    # Hipoteca: comparecientes
    ("P2", "de estado civil casados, de profesión ejecutivos",
     "de estado civil {{estado_civil}}, de profesión " + PROFESIONES),
    ("P2", "domiciliados en la Cantón Guayaquil", "domiciliados en la Cantón {{canton_domicilio}}"),
    # Hipoteca: inmueble (aparece 2 veces: antecedentes y cláusula tercera)
    ("P2", "SOLAR Y EDIFICACIÓN TRECE (13) DE LA MANZANA DOS MIL TRESCIENTOS OCHENTA Y SIETE (2387) "
           "UBICADO EN LA URBANIZACIÓN BELÉN DE NORTE, ETAPA TRES (3), PARROQUIA TARQUI",
     "{{descripcion_inmuebles_antecedentes}}"),
    ("P2", "solar doce (12), con doce metros (12,00 m)", "{{lindero_norte}}"),
    ("P2", "solar catorce (14), con doce metros (12,00 m)", "{{lindero_sur}}"),
    ("P2", "calle pública, con siete metros (7,00 m)", "{{lindero_este}}"),
    ("P2", "solar seis (6), con siete metros (7,00 m)", "{{lindero_oeste}}"),
    ("P2", "ochenta y cuatro metros cuadrados (84,00m²)", "{{superficie_m2_completo}}"),
    ("P2", "sesenta – dos mil trescientos ochenta y siete – cero trece – cero – cero – cero "
           "(60 – 2387 – 013 – 0 – 0 - 0)", "{{clave_catastral_completo}}"),
    ("P2", "ochenta metros cuadrados con ochenta decímetros cuadrados (80,80m²)", "{{area_construccion_completo}}"),
    # Hipoteca: firma de minuta BIESS
    ("P2", "Abogada Mishell Guillen Triana", "{{abogado_patrocinador_biess}}"),  # SIN ORIGEN: BUFETE_JSON
    ("P2", "cero nueve – dos mil dieciocho – cero setecientos noventa y nueve (09- 2018- 0799)",
     "{{matricula_abogado_biess}}"),  # SIN ORIGEN: BUFETE_JSON
    # Firmas y cuadro de texto
    ("P12", "GERENTE GENERAL", "{{cargo_representante_vendedor}}"),
    ("TB1", "GERENTE GENERAL", "{{cargo_representante_vendedor}}"),
]

# ----------------------------------------------------------------------------------------
# 3) Ajustes posteriores al reemplazo de XXXX (texto pegado al bloque en el borrador)
# ----------------------------------------------------------------------------------------
AJUSTES = [
    ("P1", "{{nombre_vendedor}} FAVOR", "{{nombre_vendedor}} A FAVOR"),     # el XXXX se comió la "A"
    ("P2", "número cero {{cedula_conyuge_1_completo}}", "número {{cedula_conyuge_1_completo}}"),  # "cero" fijo antes de la cédula
    ("P2", "{{cedula_conyuge_2_completo}}, de estado civil casados,",                    # declaración juramentada
     "{{cedula_conyuge_2_completo}}, de estado civil {{estado_civil}},"),
    ("P2", ", Notario , e inscrita", ", e inscrita"),                          # queda sin P2#20
]

log = logging.getLogger("plantilla_notaria")
stats = {"xxxx_reemplazados": 0, "xxxx_huerfanos": 0, "textos_reemplazados": 0, "ajustes": 0}


# ----------------------------------------------------------------------------------------
# Motor de reemplazo a nivel de run (conserva negrita/fuente del run donde empieza el match)
# ----------------------------------------------------------------------------------------
def _texto_runs(p):
    return "".join(r.text for r in p.runs)


def _reemplazar_rango(p, ini, fin, nuevo):
    """Sustituye el rango [ini, fin) del texto de runs por `nuevo`.
    El texto nuevo queda completo en el primer run tocado (requisito de poi-tl:
    un tag no puede quedar partido entre runs) y conserva su formato."""
    pos, primero = 0, True
    for r in p.runs:
        a, b = pos, pos + len(r.text)
        pos = b
        if b <= ini or a >= fin:
            continue
        s, e = max(ini, a) - a, min(fin, b) - a
        if primero:
            r.text = r.text[:s] + nuevo + r.text[e:]
            primero = False
        else:
            r.text = r.text[:s] + r.text[e:]


def _reemplazar_literal(p, loc, viejo, nuevo):
    texto = _texto_runs(p)
    posiciones = [m.start() for m in re.finditer(re.escape(viejo), texto)]
    for ini in reversed(posiciones):
        _reemplazar_rango(p, ini, ini + len(viejo), nuevo)
    return len(posiciones)


def reemplazar_en_paragraph(p, loc):
    """Reemplaza bloques XXXX según MAPPING y luego textos variables / ajustes de `loc`."""
    texto = _texto_runs(p)
    matches = list(PATRON_X.finditer(texto))
    for n, m in reversed(list(enumerate(matches, 1))):
        clave = f"{loc}#{n}"
        var = MAPPING.get(clave)
        if var is None:
            ctx = texto[max(0, m.start() - 40):m.start()].replace("\n", " ")
            log.warning("XXXX huérfano en %s (línea %s, %d X) tras: '...%s'", clave, loc, len(m.group()), ctx)
            stats["xxxx_huerfanos"] += 1
            continue
        _reemplazar_rango(p, m.start(), m.end(), var)
        stats["xxxx_reemplazados"] += 1

    for loc_t, viejo, nuevo in TEXTOS_VARIABLES:
        if loc_t == loc:
            stats["textos_reemplazados"] += _reemplazar_literal(p, loc, viejo, nuevo)
    for loc_a, viejo, nuevo in AJUSTES:
        if loc_a == loc:
            stats["ajustes"] += _reemplazar_literal(p, loc, viejo, nuevo)


def reemplazar_en_tabla(t, ti):
    for ri, fila in enumerate(t.rows):
        for ci, celda in enumerate(fila.cells):
            for pi, p in enumerate(celda.paragraphs, 1):
                reemplazar_en_paragraph(p, f"T{ti}R{ri}C{ci}P{pi}")
            for tj, sub in enumerate(celda.tables):
                reemplazar_en_tabla(sub, f"{ti}.{ri}.{ci}.{tj}")


def parrafos_cuadros_texto(doc):
    """Párrafos de cuadros de texto: doc.paragraphs no los incluye."""
    for cuadro in doc.element.body.iter(qn("w:txbxContent")):
        for pi, p in enumerate(cuadro.findall(qn("w:p")), 1):
            yield f"TB{pi}", Paragraph(p, doc._body)


def reemplazar_logo_header(doc):
    """Cambia el logo del encabezado por defecto por {{@logo_notaria}} en el mismo párrafo.
    El párrafo conserva su pPr (alineación incluida). Devuelve cuántos dibujos quitó."""
    header = doc.sections[0].header
    quitados = 0
    for p in header.paragraphs:
        for dibujo in list(p._p.iter(qn("w:drawing"))):
            for blip in dibujo.iter(qn("a:blip")):
                rid = blip.get(qn("r:embed"))
                dibujo.getparent().remove(dibujo)
                # Sin esto el logo de la notaría anterior seguiría dentro del .docx.
                header.part.drop_rel(rid)
                quitados += 1
                break
        if quitados:
            p.runs[0].text = "{{@logo_notaria}}"
            break
    return quitados


def _verificar_textos_presentes(doc):
    """Avisa si algún texto variable/ajuste configurado no aparece en el borrador."""
    textos = {f"P{i}": p.text for i, p in enumerate(doc.paragraphs, 1)}
    textos.update({loc: p.text for loc, p in parrafos_cuadros_texto(doc)})
    for loc, viejo, _ in TEXTOS_VARIABLES:
        if viejo not in textos.get(loc, ""):
            log.warning("Texto variable no encontrado en %s: '%s'", loc, viejo[:60])


def _verificar_salida(ruta):
    """Cuenta en cuerpo, cuadros de texto y encabezados."""
    doc = Document(ruta)
    raices = [doc.element.body] + [
        parte._element
        for s in doc.sections
        for parte in (s.header, s.first_page_header, s.even_page_header)
    ]
    restantes, tags_partidos = 0, 0
    for raiz in raices:
        for p in raiz.iter(qn("w:p")):
            texto = "".join(t.text or "" for t in p.iter(qn("w:t")))
            restantes += len(PATRON_X.findall(texto))
        for t in raiz.iter(qn("w:t")):
            if (t.text or "").count("{{") != (t.text or "").count("}}"):
                tags_partidos += 1
    return restantes, tags_partidos


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--entrada", type=Path, default=RUTA_ENTRADA)
    ap.add_argument("--salida", type=Path, default=RUTA_SALIDA)
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")

    entrada = args.entrada.resolve()
    salida = args.salida.resolve()
    if not entrada.is_file():
        sys.exit(f"ERROR: no existe {entrada}")
    if salida == entrada:
        sys.exit("ERROR: la salida no puede sobrescribir el original")

    # Backup del original (nunca se escribe sobre él)
    backup = entrada.with_name(f"{entrada.stem}.backup_{datetime.now():%Y%m%d_%H%M%S}{entrada.suffix}")
    shutil.copy2(entrada, backup)
    log.info("Backup: %s", backup)

    doc = Document(entrada)
    _verificar_textos_presentes(doc)

    for i, p in enumerate(doc.paragraphs, 1):
        reemplazar_en_paragraph(p, f"P{i}")
    for ti, t in enumerate(doc.tables):
        reemplazar_en_tabla(t, ti)
    for loc, p in parrafos_cuadros_texto(doc):
        reemplazar_en_paragraph(p, loc)
    logos = reemplazar_logo_header(doc)
    title_pg = doc.sections[0].different_first_page_header_footer

    doc.save(salida)
    restantes, partidos = _verificar_salida(salida)

    print("\n===== RESUMEN =====")
    print(f"Plantilla generada     : {salida}")
    print(f"XXXX reemplazados      : {stats['xxxx_reemplazados']}")
    print(f"XXXX huérfanos         : {stats['xxxx_huerfanos']}")
    print(f"Textos variables       : {stats['textos_reemplazados']}")
    print(f"Ajustes                : {stats['ajustes']}")
    print(f"XXXX restantes (verif.): {restantes}")
    print(f"Tags partidos en runs  : {partidos}")
    print(f"Logos reemplazados     : {logos}")
    print(f"titlePg activo         : {'SÍ' if title_pg else 'NO'}")


if __name__ == "__main__":
    main()
