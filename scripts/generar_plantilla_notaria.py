#!/usr/bin/env python3
"""
generar_plantilla_notaria.py

Convierte el borrador COMPRAVENTA_HIPOTECA_BIESS.docx (bloques XXXX) en
COMPRAVENTA_HIPOTECA_BIESS_TEMPLATE.docx con tags poi-tl alineados a
MinutaViviendaData.toTemplateMap().

Uso:
  python scripts/generar_plantilla_notaria.py
  python scripts/generar_plantilla_notaria.py --entrada RUTA --salida RUTA
"""
import argparse
import logging
import re
import shutil
import sys
from datetime import datetime
from pathlib import Path

from docx import Document

ROOT = Path(__file__).resolve().parents[1]
RUTA_ENTRADA = ROOT / "src/main/resources/templates/notaria/COMPRAVENTA_HIPOTECA_BIESS.docx"
NOMBRE_SALIDA = "COMPRAVENTA_HIPOTECA_BIESS_TEMPLATE.docx"

PATRON_X = re.compile(r"[Xx]{4,}")

# Tags alineados a MinutaViviendaData.toTemplateMap() (poi-tl).
PRECIO = (
    "{{precio_compraventa_numero}} "
    "({{precio_compraventa_letras}} DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA)"
)

# ----------------------------------------------------------------------------------------
# 1) Bloques XXXX -> {{variable}}  (clave = "<ubicación>#<n-ésimo XXXX del párrafo>")
# ----------------------------------------------------------------------------------------
MAPPING = {
    # --- P1: encabezado de la escritura ---
    "P1#1": "{{nombre_vendedor}}",
    "P1#2": "{{nombre_conyuge_1}}",
    "P1#3": "{{precio_compraventa_numero}}",
    "P1#4": "{{nombre_conyuge_1}}",
    "P1#5": "{{monto_prestamo}}",
    # --- P2: comparecencia ante notaria ---
    "P2#1": "{{nombre_vendedor}}",
    "P2#2": "{{representante_vendedor}}",  # SIN ORIGEN (BUFETE_JSON)
    "P2#3": "{{cargo_representante_vendedor}}",  # SIN ORIGEN (BUFETE_JSON)
    "P2#4": "{{nombre_conyuge_1}}",
    # --- P2: compraventa, cláusula primera ---
    "P2#5": "{{nombre_vendedor}}",
    "P2#6": "{{representante_vendedor}}",  # SIN ORIGEN (BUFETE_JSON)
    "P2#7": "{{nombre_conyuge_1}}",
    # --- P2: compraventa, cláusula segunda (antecedentes) ---
    "P2#8": "{{nombre_vendedor}}",
    "P2#9": "{{descripcion_inmueble_general}}",
    "P2#10": "{{linderos_generales_norte}}",
    "P2#11": "{{linderos_generales_sur}}",
    "P2#12": "{{linderos_generales_este}}",
    "P2#13": "{{linderos_generales_oeste}}",
    "P2#14": "{{superficie_general}}",
    "P2#15": "{{clave_catastral}}",
    "P2#16": "{{area_construccion}}",
    "P2#17": "{{nombre_vendedor}}",
    "P2#18": "{{nombre_adquirente_anterior}}",
    "P2#19": "{{notaria_antecedente}}",
    "P2#20": "{{numero_notaria_adquisicion}}",  # SIN ORIGEN (BUFETE_JSON)
    "P2#21": "{{fecha_inscripcion_antecedente}}",
    # --- P2: compraventa, cláusulas tercera y cuarta ---
    "P2#22": "{{nombre_vendedor}}",
    "P2#23": "{{nombre_conyuge_1}}",
    "P2#24": PRECIO,
    "P2#25": "{{valor_entrada_numero}}",
    "P2#26": "{{monto_prestamo}}",
    "P2#27": "{{institucion_financiera_original}}",
    # --- P2: compraventa, cláusula undécima (notificaciones) ---
    "P2#28": "{{direccion_vendedor}}",
    "P2#29": "{{ciudad_notificacion_vendedor}}",  # SIN ORIGEN (BUFETE_JSON)
    "P2#30": "{{telefono_vendedor}}",
    "P2#31": "{{correo_vendedor}}",
    "P2#32": "{{direccion_deudor}}",
    "P2#33": "{{telefono_deudor}}",
    "P2#34": "{{correo_deudor}}",
    # --- P2: hipoteca, cláusula primera (comparecientes) ---
    "P2#35": "{{nombre_conyuge_1}}",
    "P2#36": "{{cedula_conyuge_1}}",
    "P2#37": "{{nombre_conyuge_2}}",
    "P2#38": "{{cedula_conyuge_2}}",
    # --- P2: hipoteca, cláusula segunda (antecedentes) ---
    "P2#39": "{{nombre_conyuge_1}}",
    "P2#40": "{{nombre_conyuge_2}}",
    # --- P2: hipoteca, cláusula décima quinta (declaración juramentada) ---
    "P2#41": "{{nombre_conyuge_1}}",
    "P2#42": "{{cedula_conyuge_1}}",
    "P2#43": "{{nombre_conyuge_2}}",
    "P2#44": "{{cedula_conyuge_2}}",
    # --- Bloque de firmas ---
    "P4#1": "{{nombre_vendedor}}",
    "P5#1": "{{ruc_vendedor}}",  # SIN ORIGEN (BUFETE_JSON)
    "P11#1": "{{representante_vendedor}}",  # SIN ORIGEN (BUFETE_JSON)
    "P13#1": "{{cedula_representante_vendedor}}",  # SIN ORIGEN (BUFETE_JSON)
    "P19#1": "{{nombre_conyuge_1}}",
    "P20#1": "{{cedula_conyuge_1}}",
    "P27#1": "{{nombre_conyuge_2}}",
    "P28#1": "{{cedula_conyuge_2}}",
}

# ----------------------------------------------------------------------------------------
# 2) Texto variable escrito a mano: (ubicación, texto_literal, reemplazo)
# ----------------------------------------------------------------------------------------
TEXTOS_VARIABLES = [
    # Comparecencia ante notaria
    ("P2", "veinte días del mes de agosto de dos mil veintiséis", "{{fecha_firma}}"),
    ("P2", "CRISTEL PAMELA QUIROLA LEMA", "{{notaria_titular_nombre}}"),
    ("P2", "SEXAGÉSIMA PRIMERA", "{{notaria_numero_literal}}"),
    ("P2", "de estado civil casado, mayor de edad, licenciado",
     "de estado civil {{estado_civil_vendedor}}, mayor de edad, {{profesion_vendedor}}"),
    ("P2", "domiciliado en esta ciudad de Guayaquil",
     "domiciliado en esta ciudad de {{ciudad_firma}}"),
    ("P2", "domiciliados en esta ciudad de Guayaquil",
     "domiciliados en esta ciudad de {{ciudad_firma}}"),
    ("P2", "casados, tecnólogo superior en seguridad ciudadana y licenciada en psicología",
     "{{estado_civil}}, {{profesion_conyuge_1}}"),
    # Compraventa
    ("P2", "Gerente General, el señor licenciado",
     "{{cargo_representante_vendedor}}, el señor {{profesion_vendedor}}"),
    ("P2", "el treinta y uno de marzo de dos mil veintiséis",
     "el {{fecha_escritura_antecedente}}"),
    ("P2", "Registro de la Propiedad de Guayaquil el",
     "Registro de la Propiedad de {{canton_notario_adquisicion}} el"),
    ("P2", "Abogada Verónica Valdivieso Rodríguez",
     "{{abogado_patrocinador_compraventa}}"),  # SIN ORIGEN (NOTARIA_MANUAL)
    ("P2", "cero nueve – dos mil diecisiete – treinta y seis (09 – 2017 - 36)",
     "{{matricula_abogado_compraventa}}"),  # SIN ORIGEN (NOTARIA_MANUAL)
    # Hipoteca: comparecientes
    ("P2", "de estado civil casados, de profesión ejecutivos",
     "de estado civil {{estado_civil}}, de profesión {{profesion_conyuge_1}}"),
    ("P2", "domiciliados en la Cantón Guayaquil",
     "domiciliados en la Cantón {{canton_domicilio}}"),
    # Hipoteca: inmueble
    ("P2", "SOLAR Y EDIFICACIÓN TRECE (13) DE LA MANZANA DOS MIL TRESCIENTOS OCHENTA Y SIETE (2387) "
           "UBICADO EN LA URBANIZACIÓN BELÉN DE NORTE, ETAPA TRES (3), PARROQUIA TARQUI",
     "{{descripcion_inmueble_hipoteca}}"),
    ("P2", "solar doce (12), con doce metros (12,00 m)", "{{linderos_generales_norte}}"),
    ("P2", "solar catorce (14), con doce metros (12,00 m)", "{{linderos_generales_sur}}"),
    ("P2", "calle pública, con siete metros (7,00 m)", "{{linderos_generales_este}}"),
    ("P2", "solar seis (6), con siete metros (7,00 m)", "{{linderos_generales_oeste}}"),
    ("P2", "ochenta y cuatro metros cuadrados (84,00m²)", "{{superficie_general}}"),
    ("P2", "sesenta – dos mil trescientos ochenta y siete – cero trece – cero – cero – cero "
           "(60 – 2387 – 013 – 0 – 0 - 0)", "{{clave_catastral}}"),
    ("P2", "ochenta metros cuadrados con ochenta decímetros cuadrados (80,80m²)",
     "{{area_construccion}}"),
    # Hipoteca: firma de minuta BIESS
    ("P2", "Abogada Mishell Guillen Triana",
     "{{abogado_patrocinador_biess}}"),  # SIN ORIGEN (NOTARIA_MANUAL)
    ("P2", "cero nueve – dos mil dieciocho – cero setecientos noventa y nueve (09- 2018- 0799)",
     "{{matricula_abogado_biess}}"),  # SIN ORIGEN (NOTARIA_MANUAL)
    # Firmas
    ("P12", "GERENTE GENERAL", "{{cargo_representante_vendedor}}"),  # SIN ORIGEN (BUFETE_JSON)
    ("P46", "CRISTEL PAMELA QUIROLA LEMA", "{{notaria_titular_nombre}}"),
    ("P47", "SEXAGÉSIMA PRIMERA", "{{notaria_numero_literal}}"),
]

# ----------------------------------------------------------------------------------------
# 3) Ajustes posteriores al reemplazo de XXXX
# ----------------------------------------------------------------------------------------
AJUSTES = [
    ("P1", "{{nombre_vendedor}} FAVOR", "{{nombre_vendedor}} A FAVOR"),
    ("P2", "número cero {{cedula_conyuge_1}}", "número {{cedula_conyuge_1}}"),
    ("P2", "{{cedula_conyuge_2}}, de estado civil casados,",
     "{{cedula_conyuge_2}}, de estado civil {{estado_civil}},"),
]

log = logging.getLogger("plantilla_notaria")
stats = {"xxxx_reemplazados": 0, "xxxx_huerfanos": 0, "textos_reemplazados": 0, "ajustes": 0}


def _texto_runs(p):
    return "".join(r.text for r in p.runs)


def _reemplazar_rango(p, ini, fin, nuevo):
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
    texto = _texto_runs(p)
    matches = list(PATRON_X.finditer(texto))
    for n, m in reversed(list(enumerate(matches, 1))):
        clave = f"{loc}#{n}"
        var = MAPPING.get(clave)
        if var is None:
            ctx = texto[max(0, m.start() - 40):m.start()].replace("\n", " ")
            log.warning(
                "XXXX huérfano en %s (línea %s, %d X) tras: '...%s'",
                clave, loc, len(m.group()), ctx,
            )
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


def _verificar_textos_presentes(doc):
    textos = {f"P{i}": p.text for i, p in enumerate(doc.paragraphs, 1)}
    for loc, viejo, _ in TEXTOS_VARIABLES:
        if viejo not in textos.get(loc, ""):
            log.warning("Texto variable no encontrado en %s: '%s'", loc, viejo[:60])


def _verificar_salida(ruta):
    doc = Document(ruta)
    restantes, tags_partidos = 0, 0
    parrafos = list(doc.paragraphs)
    for t in doc.tables:
        for fila in t.rows:
            for c in fila.cells:
                parrafos.extend(c.paragraphs)
    for p in parrafos:
        restantes += len(PATRON_X.findall(p.text))
        for r in p.runs:
            if r.text.count("{{") != r.text.count("}}"):
                tags_partidos += 1
    return restantes, tags_partidos


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--entrada", type=Path, default=RUTA_ENTRADA)
    ap.add_argument("--salida", type=Path, default=None)
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")

    entrada = args.entrada.resolve()
    salida = (args.salida or entrada.with_name(NOMBRE_SALIDA)).resolve()
    if not entrada.is_file():
        sys.exit(f"ERROR: no existe {entrada}")
    if salida == entrada:
        sys.exit("ERROR: la salida no puede sobrescribir el original")
    if "TEMPLATE" in entrada.name.upper():
        sys.exit("ERROR: no ejecutar contra una TEMPLATE; usa el ORIGINAL .docx")

    backup = entrada.with_name(f"{entrada.stem}.backup_{datetime.now():%Y%m%d_%H%M%S}{entrada.suffix}")
    shutil.copy2(entrada, backup)
    log.info("Backup: %s", backup)

    doc = Document(entrada)
    _verificar_textos_presentes(doc)

    for i, p in enumerate(doc.paragraphs, 1):
        reemplazar_en_paragraph(p, f"P{i}")
    for ti, t in enumerate(doc.tables):
        reemplazar_en_tabla(t, ti)

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


if __name__ == "__main__":
    main()
