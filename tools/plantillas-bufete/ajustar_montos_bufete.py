#!/usr/bin/env python3
"""
ajustar_montos_bufete.py

Deja las plantillas del bufete con la moneda escrita una sola vez: "USD {{tag}}". Desde que
MinutaViviendaData.cifra() formatea los montos como 120.000,00 sin moneda, la plantilla es la que
pone "USD".

  1. Antepone "USD " al tag de dinero donde la plantilla no traía moneda.
  2. Cambia "USD $ {{monto_prestamo}}" por "USD {{monto_prestamo}}".
  3. Une en un solo <w:t> cada tag {{...}} que Word partió en varios runs. No cambia el texto: el
     tag queda en el primer fragmento y conserva el formato de ese run, que es el que poi-tl usa
     al renderizar.

El texto nuevo nunca parte un tag: va en el texto que precede al tag, dentro del mismo fragmento
o en el anterior. El script es idempotente: si la plantilla ya está ajustada no cambia nada.

Uso (desde backend/):
  python tools/plantillas-bufete/ajustar_montos_bufete.py            # aplica
  python tools/plantillas-bufete/ajustar_montos_bufete.py --revisar  # solo informa
"""
import argparse
import re
import sys
from pathlib import Path

from docx import Document
from docx.oxml.ns import qn

RAIZ_BACKEND = Path(__file__).resolve().parents[2]
PLANTILLAS = RAIZ_BACKEND / "src/main/resources/templates/escrituracion"

TAG = re.compile(r"\{\{[^{}]*\}\}")
PARTES_CON_TEXTO = re.compile(r"/word/(document|header\d*|footer\d*|footnotes|endnotes)\.xml")
XML_SPACE = "{http://www.w3.org/XML/1998/namespace}space"

# (plantilla, texto que precede al tag en el párrafo, tag)
ANTEPONER_USD = [
    ("VIVIENDA HIPOTECADA BIESS/CONTRATO_MUTUO_VIVIENDA_HIPOTECADA.docx", "Monto:", "monto_prestamo_numero"),
    ("VIVIENDA HIPOTECADA BIESS/MINUTA_COMPRAVENTA_HIPOTECA.docx", "la suma de", "precio_venta_numeral"),
    ("VIVIENDA TERMINADA INDIVIDUAL - SOLIDARIA/contrato_vivienda_terminada.docx", "Monto:", "monto_prestamo_numeral"),
]
QUITAR_DOLAR = [
    "SUSTITUCION DE HIPOTECA/contrato_sustitucion_hipoteca.docx",
    "TERRENO TERMINADA INDIVIDUAL/contrato_de_vivienda_terminada_y_terreno.docx",
    "VIVIENDA TERMINADA PREFERENCIAL/contrato_de_vivienda_terminada_y_terreno.docx",
]


def partes(doc):
    """Partes XML con texto (cuerpo, encabezados, pies), como DocxMinutaRenderer.PARTE_TEXTO."""
    for part in doc.part.package.iter_parts():
        if PARTES_CON_TEXTO.fullmatch(str(part.partname)) and hasattr(part, "_element"):
            yield part._element


def parrafos(doc):
    for raiz in partes(doc):
        yield from raiz.iter(qn("w:p"))


def fragmentos(p):
    """<w:t> del párrafo en orden de lectura (incluye los de hipervínculos)."""
    return list(p.iter(qn("w:t")))


def poner(t, texto):
    t.text = texto
    if texto != texto.strip():
        t.set(XML_SPACE, "preserve")


def tags_partidos(doc):
    """Tags cuyo texto no cabe en un solo run."""
    partidos = []
    for p in parrafos(doc):
        runs = list(p.iter(qn("w:r")))
        textos = ["".join(t.text or "" for t in r.iter(qn("w:t"))) for r in runs]
        limites, pos = [], 0
        for texto in textos:
            limites.append((pos, pos + len(texto)))
            pos += len(texto)
        for m in TAG.finditer("".join(textos)):
            if not any(a <= m.start() and m.end() <= b for a, b in limites):
                partidos.append(m.group(0))
    return partidos


def tags(doc):
    """Tags {{...}} en orden de aparición, sin repetir (como tagNames de poi-tl)."""
    vistos = {}
    for p in parrafos(doc):
        for m in TAG.finditer("".join(t.text or "" for t in fragmentos(p))):
            vistos.setdefault(m.group(0), None)
    return list(vistos)


def unir_tags_partidos(doc):
    unidos = 0
    for p in parrafos(doc):
        while True:
            ts = fragmentos(p)
            textos = [t.text or "" for t in ts]
            limites, pos = [], 0
            for texto in textos:
                limites.append((pos, pos + len(texto)))
                pos += len(texto)
            partido = next(
                (m for m in TAG.finditer("".join(textos))
                 if not any(a <= m.start() and m.end() <= b for a, b in limites)),
                None,
            )
            if partido is None:
                break
            i = next(k for k, (a, b) in enumerate(limites) if a <= partido.start() < b)
            j = next(k for k, (a, b) in enumerate(limites) if a < partido.end() <= b)
            ini = partido.start() - limites[i][0]
            fin = partido.end() - limites[j][0]
            poner(ts[i], textos[i][:ini] + partido.group(0))
            for k in range(i + 1, j):
                poner(ts[k], "")
            poner(ts[j], textos[j][fin:])
            unidos += 1
    return unidos


def anteponer_usd(doc, contexto, tag):
    """'<contexto> {{tag}}' → '<contexto> USD {{tag}}'. Devuelve 1 si cambió, 0 si ya estaba."""
    marca = "{{" + tag + "}}"
    cambios, ya = 0, 0
    for p in parrafos(doc):
        ts = fragmentos(p)
        for idx, t in enumerate(ts):
            texto = t.text or ""
            pos = texto.find(marca)
            if pos < 0:
                continue
            previo = "".join(x.text or "" for x in ts[:idx]) + texto[:pos]
            if not re.search(re.escape(contexto) + r"\s*(USD\s*)?$", previo):
                continue
            if re.search(r"USD\s*$", previo):
                ya += 1
                continue
            if pos > 0 or idx == 0:
                # Hay texto antes del tag en el mismo fragmento: "USD " va ahí, sin tocar el tag.
                poner(t, texto[:pos] + _separado(texto[:pos]) + "USD " + texto[pos:])
            else:
                anterior = ts[idx - 1]
                poner(anterior, (anterior.text or "") + _separado(anterior.text or "") + "USD ")
            cambios += 1
    if cambios + ya != 1:
        raise SystemExit(f"Se esperaba un '{contexto} {marca}' y hay {cambios + ya}")
    return cambios


def _separado(previo):
    return "" if previo == "" or previo.endswith(" ") else " "


def quitar_dolar(doc):
    """'USD $ {{monto_prestamo}}' → 'USD {{monto_prestamo}}' dentro del mismo fragmento."""
    patron = re.compile(r"USD\s*\$\s*(?=\{\{monto_prestamo\}\})")
    cambios = 0
    for p in parrafos(doc):
        for t in fragmentos(p):
            texto = t.text or ""
            nuevo, n = patron.subn("USD ", texto)
            if n:
                poner(t, nuevo)
                cambios += n
    return cambios


def procesar(relativa, accion, revisar):
    ruta = PLANTILLAS / relativa
    doc = Document(str(ruta))
    antes_tags, antes_partidos = tags(doc), tags_partidos(doc)
    unidos = unir_tags_partidos(doc)
    cambios = accion(doc)
    despues_tags, despues_partidos = tags(doc), tags_partidos(doc)
    iguales = antes_tags == despues_tags
    print(
        f"{relativa}: tags partidos {len(antes_partidos)} -> {len(despues_partidos)}"
        f" (unidos {unidos}); cambios de moneda {cambios}; tags iguales: {'SÍ' if iguales else 'NO'}"
    )
    if not iguales or despues_partidos:
        raise SystemExit(f"{relativa}: verificación fallida, no se guarda")
    if not revisar and (unidos or cambios):
        doc.save(str(ruta))


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    parser.add_argument("--revisar", action="store_true", help="solo informa, no guarda")
    args = parser.parse_args()
    for relativa, contexto, tag in ANTEPONER_USD:
        procesar(relativa, lambda d, c=contexto, t=tag: anteponer_usd(d, c, t), args.revisar)
    for relativa in QUITAR_DOLAR:
        procesar(relativa, quitar_dolar, args.revisar)
    return 0


if __name__ == "__main__":
    sys.exit(main())
