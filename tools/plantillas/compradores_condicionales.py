#!/usr/bin/env python3
"""Vivienda Terminada Individual/Solidaria: redacción con uno o dos compradores (fase 2.2).

Cada párrafo de comparecientes con dos personas queda envuelto en la sección poi-tl
{{?tiene_comprador_2}}…{{/tiene_comprador_2}} (redacción actual, «Los cónyuges…») y se agrega una copia
en singular dentro de {{?un_solo_comprador}}…{{/un_solo_comprador}}. Los tags de sección van solos en su
párrafo, así poi-tl quita esos párrafos al renderizar. Los datos los calcula MinutaViviendaData.secciones().

Dos compradores no casados entre sí: por ahora usan la sección de cónyuges (pendiente de la abogada,
ver COMENTARIOS_PLANTILLAS.md).

Idempotente: si la plantilla ya tiene las secciones, no hace nada.

Uso (desde backend/):
  py -3 tools/plantillas/compradores_condicionales.py
"""
from __future__ import annotations

import copy
import sys
import zipfile

import plantillas as P
from lxml import etree

DOS = "tiene_comprador_2"
UNO = "un_solo_comprador"
W14 = "http://schemas.microsoft.com/office/word/2010/wordml"

MINUTA = "minuta_vivienda_terminada_compraventa_hipoteca.docx"
CONTRATO = "contrato_vivienda_terminada.docx"

# (inicio del párrafo, [(texto plural, texto singular)])
CAMBIOS = {
    MINUTA: [
        (
            "B) Los cónyuges el/la señor(a) {{nombre_comprador_1}}",
            [
                ("B) Los cónyuges el/la señor(a) ", "B) El/La señor(a) "),
                (", y el/la señor(a) {{nombre_comprador_2}}, con cédula de ciudadanía No. {{cedula_comprador_2}}", ""),
                (
                    "de profesión {{profesion_comprador_1}} y {{profesion_comprador_2}}, respectivamente, por sus "
                    "propios y personales derechos y por los que representan de la sociedad conyugal por ellos "
                    "formada, domiciliado(s) en",
                    "de profesión {{profesion_comprador_1}}, por sus propios y personales derechos, domiciliado(a) en",
                ),
                ("a quienes en adelante se les denominará", "a quien en adelante se le denominará"),
            ],
        ),
        (
            "Dos) Por otra parte comparecen los cónyuges",
            [
                ("comparecen los cónyuges el/la señor(a) ", "comparece el/la señor(a) "),
                (", y el/la señor(a) {{nombre_deudor_2}}, con cédula de ciudadanía No. {{cedula_deudor_2}}", ""),
                (
                    "de profesión {{profesion_deudor_1}} y {{profesion_deudor_2}}, respectivamente, por sus propios "
                    "y personales derechos y por los que representan de la sociedad conyugal por ellos formada, "
                    "domiciliados en",
                    "de profesión {{profesion_deudor_1}}, por sus propios y personales derechos, domiciliado(a) en",
                ),
                ("a quien(es) en lo posterior se le(s) denominará", "a quien en lo posterior se le denominará"),
            ],
        ),
    ],
    CONTRATO: [
        (
            "Y por otra parte comparece el/la señor(a) {{nombre_deudor_1}}",
            [
                (", y el/la señor(a) {{nombre_deudor_2}}, con cédula de ciudadanía No. {{cedula_deudor_2}}", ""),
                ("profesión {{profesion_deudor_1}} y {{profesion_deudor_2}},", "profesión {{profesion_deudor_1}},"),
                ("domiciliado(s)", "domiciliado(a)"),
                ("a quien(es) en lo posterior se le(s) denominará", "a quien en lo posterior se le denominará"),
            ],
        ),
    ],
}


def sin_ids(el) -> None:
    """Una copia no puede repetir w14:paraId/textId del original."""
    for nodo in el.iter():
        for attr in (f"{{{W14}}}paraId", f"{{{W14}}}textId"):
            nodo.attrib.pop(attr, None)


def parrafo_tag(modelo, tag: str):
    """Párrafo con solo el tag de sección, con el formato de párrafo del modelo."""
    p = etree.Element(P.Q("p"))
    ppr = modelo.find(P.Q("pPr"))
    if ppr is not None:
        p.append(copy.deepcopy(ppr))
    r = etree.SubElement(p, P.Q("r"))
    t = etree.SubElement(r, P.Q("t"))
    t.text = "{{" + tag + "}}"
    return p


def envolver(inicio, fin, seccion: str) -> None:
    """Pone {{?seccion}} antes de `inicio` y {{/seccion}} después de `fin` (mismo padre)."""
    inicio.addprevious(parrafo_tag(inicio, "?" + seccion))
    fin.addnext(parrafo_tag(fin, "/" + seccion))


def ajustar(path) -> bool:
    with zipfile.ZipFile(path) as z:
        root = etree.fromstring(z.read("word/document.xml"))
    if "{{?" + DOS + "}}" in "".join(P.texto(ts) for _, ts in P.parrafos(root)):
        print(f"{path.name}: ya tiene las secciones")
        return False
    lista = list(P.parrafos(root))
    for prefijo, reemplazos in CAMBIOS[path.name]:
        encontrados = [(p, ts) for p, ts in lista if P.texto(ts).startswith(prefijo)]
        if len(encontrados) != 1:
            raise SystemExit(f"{path.name}: se esperaba un párrafo que empiece con «{prefijo}», hay {len(encontrados)}")
        plural, _ = encontrados[0]
        singular = copy.deepcopy(plural)
        sin_ids(singular)
        ts_singular = [t for t in singular.iter(P.Q("t")) if next(t.iterancestors(P.Q("p"))) is singular]
        for viejo, nuevo in reemplazos:
            P.reemplazar_en_parrafo(ts_singular, viejo, nuevo)
        plural.addnext(singular)
        envolver(plural, plural, DOS)
        envolver(singular, singular, UNO)
    if path.name == CONTRATO:
        envolver_segunda_firma(root)
    P.reescribir(path, {"word/document.xml": P.serializar(root)})
    print(f"{path.name}: secciones de uno/dos compradores agregadas")
    return True


def envolver_segunda_firma(root) -> None:
    """Bloque de firma del segundo deudor: desde la línea de firma hasta «C.C.: {{cedula_deudor_2}}»."""
    lista = [(p, P.texto(ts).strip()) for p, ts in P.parrafos(root)]
    fin = next(i for i, (_, t) in enumerate(lista) if t == "C.C.: {{cedula_deudor_2}}")
    inicio = fin
    while inicio > 0 and not P.LINEA_FIRMA.fullmatch(lista[inicio][1] or "x"):
        inicio -= 1
    # Los renglones en blanco antes de la línea de firma (en la misma celda) son el espacio entre firmas.
    celda = lista[fin][0].getparent()
    while inicio > 0 and lista[inicio - 1][1] == "" and lista[inicio - 1][0].getparent() is celda:
        inicio -= 1
    if lista[inicio][0].getparent() is not lista[fin][0].getparent():
        raise SystemExit("La firma del segundo deudor no está en un solo bloque")
    envolver(lista[inicio][0], lista[fin][0], DOS)


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    for path in P.plantillas():
        if path.name in CAMBIOS:
            ajustar(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
