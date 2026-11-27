#!/usr/bin/env python3
"""Fase 6: carta de financiamiento (.docx) e informe legal final (.xlsx) desde los archivos de la abogada.

Carta: copia el .docx a templates/escrituracion/ADICIONALES/carta_financiamiento.docx, cambia los XXXX
por tags poi-tl (cada tag en un solo w:t), quita resaltados y autor, y al final copia los comentarios de
Word a COMENTARIOS_PLANTILLAS.md y los quita (comando `comentarios` de plantillas.py). Las firmas quedan
en {{firmas_vendedores}} / {{firmas_compradores}}: un bloque por persona (los arma el backend).

Informe: copia el .xlsx a templates/escrituracion/ADICIONALES/informe_legal.xlsx dejando solo las
etiquetas (sin XXXX, sin instrucciones ni nombre del abogado); el backend llena las celdas con POI. Las
instrucciones que traía cada celda se copian a COMENTARIOS_PLANTILLAS.md.

Idempotente: si el destino ya está preparado, no hace nada.

Uso (desde backend/):
  py -3 tools/plantillas/plantillas_adicionales.py "RUTA/CARTA DE FINANCIAMIENTO  .docx" "RUTA/INFORME LEGAL  .xlsx"
"""
from __future__ import annotations

import re
import shutil
import sys
import zipfile
from pathlib import Path

import plantillas as P
from lxml import etree

DESTINO = P.RAIZ / "src/main/resources/templates/escrituracion/ADICIONALES"
CARTA = DESTINO / "carta_financiamiento.docx"
INFORME = DESTINO / "informe_legal.xlsx"

S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
DC = "http://purl.org/dc/elements/1.1/"
CP = "http://schemas.openxmlformats.org/package/2006/metadata/core-properties"

FILAS_IMPUESTOS = {"PLUSVALIA": "plusvalia", "ALCABALAS": "alcabalas", "OBRAS (CEM)": "obras"}
COLUMNAS_PAGO = ["vendedor", "cliente", "financiado"]


def sin_autor(core: bytes) -> bytes:
    root = etree.fromstring(core)
    for tag in (f"{{{DC}}}creator", f"{{{CP}}}lastModifiedBy"):
        for el in root.iter(tag):
            el.text = ""
    return P.serializar(root)


# --- Carta --------------------------------------------------------------------------------------


def ts_de(p):
    return [t for t in p.iter(P.Q("t")) if next(t.iterancestors(P.Q("p"))) is p]


def reemplazar_regex(p, patron: str, nuevo: str) -> None:
    ts = ts_de(p)
    m = re.search(patron, P.texto(ts))
    if not m:
        raise SystemExit(f"Carta: no se encontró /{patron}/ en «{P.texto(ts)[:80]}»")
    P.reemplazar_en_parrafo(ts, m.group(), nuevo)


def poner_tag(p, tag: str, modelo_rpr=None) -> None:
    """Deja el párrafo con un único run que contiene el tag (conserva el formato del primer run)."""
    runs = [r for r in p.findall(P.Q("r"))]
    rpr = None
    if runs and runs[0].find(P.Q("rPr")) is not None:
        rpr = runs[0].find(P.Q("rPr"))
    elif modelo_rpr is not None:
        rpr = modelo_rpr
    for r in runs:
        p.remove(r)
    r = etree.SubElement(p, P.Q("r"))
    if rpr is not None:
        r.append(etree.fromstring(etree.tostring(rpr)))
    t = etree.SubElement(r, P.Q("t"))
    t.text = "{{" + tag + "}}"


def celdas(tr):
    return tr.findall(P.Q("tc"))


def texto_celda(tc) -> str:
    return "".join(t.text or "" for t in tc.iter(P.Q("t"))).strip()


def preparar_carta(origen: Path) -> None:
    if CARTA.exists():
        with zipfile.ZipFile(CARTA) as z:
            if "{{nip}}" in z.read("word/document.xml").decode("utf-8"):
                print(f"{CARTA.name}: ya está preparada")
                return
    DESTINO.mkdir(parents=True, exist_ok=True)
    shutil.copy2(origen, CARTA)
    with zipfile.ZipFile(CARTA) as z:
        doc = etree.fromstring(z.read("word/document.xml"))
        core = z.read("docProps/core.xml")

    for hl in list(doc.iter(P.Q("highlight"))):
        hl.getparent().remove(hl)

    parrafos = [p for p, _ in P.parrafos(doc)]
    por_texto = lambda frag: [p for p in parrafos if frag in P.texto(ts_de(p))]  # noqa: E731

    (comparecientes,) = por_texto("en calidad de VENDEDOR(ES)")
    reemplazar_regex(comparecientes, r"X{4,} con cedula de ciudadania No\.? X{4,}", "{{vendedores_identificacion}}")
    reemplazar_regex(comparecientes, r"X{4,} con cedula de ciudadania No\.? X{4,}", "{{compradores_identificacion}}")

    (prestamo,) = por_texto("signado con el número")
    reemplazar_regex(prestamo, r"X{4,}", "{{nip}}")

    (constancia,) = por_texto("Para constancia de lo acordado")
    reemplazar_regex(constancia, r"XX de X+ el \d{4}", "{{fecha_carta}}")

    marcadas = set()
    for tr in doc.iter(P.Q("tr")):
        cs = celdas(tr)
        if len(cs) != 4:
            continue
        rubro = P.sin_tildes(texto_celda(cs[0])).upper()
        if rubro not in FILAS_IMPUESTOS:
            continue
        modelo = next((r.find(P.Q("rPr")) for r in cs[0].iter(P.Q("r")) if r.find(P.Q("rPr")) is not None), None)
        for tc, columna in zip(cs[1:], COLUMNAS_PAGO):
            ps = tc.findall(P.Q("p"))
            for extra in ps[1:]:
                tc.remove(extra)
            poner_tag(ps[0], f"{FILAS_IMPUESTOS[rubro]}_{columna}", modelo)
        marcadas.add(rubro)
    if marcadas != set(FILAS_IMPUESTOS):
        raise SystemExit(f"Carta: filas de impuestos encontradas {sorted(marcadas)}")

    firmas = 0
    for tc in doc.iter(P.Q("tc")):
        ps = tc.findall(P.Q("p"))
        textos = [P.texto(ts_de(p)).strip() for p in ps]
        if len(textos) < 4 or not P.LINEA_FIRMA.fullmatch(textos[0] or "x"):
            continue
        etiqueta = P.sin_tildes(textos[1]).upper()
        tag = "firmas_vendedores" if "VENDEDOR" in etiqueta else "firmas_compradores" if "COMPRADOR" in etiqueta else None
        if tag is None:
            continue
        poner_tag(ps[0], tag)
        for p in ps[1:4]:
            tc.remove(p)
        firmas += 1
    if firmas != 2:
        raise SystemExit(f"Carta: se esperaban 2 bloques de firma, hay {firmas}")

    P.reescribir(CARTA, {"word/document.xml": P.serializar(doc), "docProps/core.xml": sin_autor(core)})
    print(f"{CARTA.name}: tags puestos")
    previo = P.COMENTARIOS_MD.read_text(encoding="utf-8")
    P.COMENTARIOS_MD.write_text(
        previo.rstrip("\n") + "\n\n# Comentarios de la carta de financiamiento\n\n"
        "| Plantilla | Párrafo | Comentario |\n|---|---|---|\n",
        encoding="utf-8")
    P.cmd_comentarios(None)
    inf = P.revisar(CARTA)
    print(f"   tags: {' '.join(sorted(inf.tags))}")
    if inf.partidos or inf.borrador or inf.comentarios or inf.amarillos:
        raise SystemExit(f"Carta: quedan pendientes {inf.partidos} {inf.borrador} comentarios={inf.comentarios} amarillos={inf.amarillos}")


# --- Informe ------------------------------------------------------------------------------------

# (inicio del texto original, texto que queda en la plantilla)
ETIQUETAS_INFORME = [
    (r"^\s*NIP:", "NIP: "),
    (r"^\s*Nombres y Apellidos Propietario del Bien:", "Nombres y Apellidos Propietario del Bien: "),
    (r"^\s*COMPRAVENTA\s+/\s+HIPOTECA", ""),
    (r"^X{4,}$", ""),
    (r"^Parroquia:", "Parroquia: "),
    (r"^Cant[oó]n:", "Cantón: "),
    (r"^Provincia:", "Provincia: "),
    (r"^X{4,} \(Se coloca el valor", ""),
    (r"^Notaria X{4,}", ""),
    (r"^X{4,} \(Fecha en la que se firmo", ""),
    (r"^X{4,} \(Fecha en la que se salio inscrita", ""),
    (r"^X{4,} \(Fecha del informe\)", ""),
    (r"^Elaborado por:", "Elaborado por:"),
]


def preparar_informe(origen: Path) -> None:
    if INFORME.exists():
        print(f"{INFORME.name}: ya está preparado")
        return
    DESTINO.mkdir(parents=True, exist_ok=True)
    shutil.copy2(origen, INFORME)
    with zipfile.ZipFile(INFORME) as z:
        ss = etree.fromstring(z.read("xl/sharedStrings.xml"))
        core = z.read("docProps/core.xml")
    instrucciones = []
    usados = set()
    for si in ss.findall(f"{{{S}}}si"):
        ts = list(si.iter(f"{{{S}}}t"))
        original = "".join(t.text or "" for t in ts)
        limpio = original.strip()
        for i, (patron, nuevo) in enumerate(ETIQUETAS_INFORME):
            if re.search(patron, limpio):
                for rpr in si.findall(f"{{{S}}}r"):
                    si.remove(rpr)
                for t in si.findall(f"{{{S}}}t"):
                    si.remove(t)
                t = etree.SubElement(si, f"{{{S}}}t")
                t.text = nuevo
                if nuevo.strip():
                    t.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
                m = re.search(r"\(([^)]*)\)", limpio)
                if m and "X" in limpio:
                    instrucciones.append(m.group(1).strip())
                usados.add(i)
                break
    faltan = [ETIQUETAS_INFORME[i][0] for i in range(len(ETIQUETAS_INFORME)) if i not in usados]
    if faltan:
        raise SystemExit(f"Informe: no se encontraron {faltan}")
    P.reescribir(INFORME, {"xl/sharedStrings.xml": P.serializar(ss), "docProps/core.xml": sin_autor(core)})
    if instrucciones:
        previo = P.COMENTARIOS_MD.read_text(encoding="utf-8")
        filas = "\n".join(f"| informe_legal.xlsx | (celda) | {i} |" for i in instrucciones)
        P.COMENTARIOS_MD.write_text(previo.rstrip("\n") + "\n\n# Instrucciones quitadas del informe legal\n\n"
                                    "| Plantilla | Celda | Instrucción |\n|---|---|---|\n" + filas + "\n",
                                    encoding="utf-8")
    print(f"{INFORME.name}: etiquetas limpias ({len(instrucciones)} instrucciones copiadas)")


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    preparar_carta(Path(sys.argv[1]))
    preparar_informe(Path(sys.argv[2]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
