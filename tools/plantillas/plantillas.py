#!/usr/bin/env python3
"""Mantenimiento de plantillas DOCX de escrituración y notaría.

Recorre templates/escrituracion y templates/notaria (cuerpo, encabezados, pies y cuadros de texto).

Comandos (desde backend/):
  py -3 tools/plantillas/plantillas.py revisar [--tags]      # solo lectura: informe por plantilla
  py -3 tools/plantillas/plantillas.py unir-tags             # une tags {{…}} partidos entre runs
  py -3 tools/plantillas/plantillas.py comentarios           # copia comentarios a COMENTARIOS_PLANTILLAS.md y los quita
  py -3 tools/plantillas/plantillas.py renumerar ARCHIVO.docx PARTE   # renumera cláusulas de la parte (1, 2…)

Todo comando que escribe deja antes un respaldo de la plantilla en %TEMP%/lexia-plantillas-respaldo/<fecha>/
(fuera del repo). Las plantillas son del bufete: el informe muestra solo textos de la plantilla, nunca
datos de un expediente.
"""
from __future__ import annotations

import argparse
import datetime as dt
import re
import shutil
import sys
import tempfile
import unicodedata
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

from lxml import etree

RAIZ = Path(__file__).resolve().parents[2]
CARPETAS = [
    RAIZ / "src/main/resources/templates/escrituracion",
    RAIZ / "src/main/resources/templates/notaria",
]
COMENTARIOS_MD = Path(__file__).resolve().parent / "COMENTARIOS_PLANTILLAS.md"

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
NS = {"w": W}
Q = lambda tag: f"{{{W}}}{tag}"  # noqa: E731

TAG = re.compile(r"\{\{[^{}]*\}\}")
BORRADOR = [
    ("XXXX", re.compile(r"X{4,}", re.I)),
    ("XX.000,00", re.compile(r"XX\.000,00", re.I)),
    ("____", re.compile(r"_{4,}")),
]
LINEA_FIRMA = re.compile(r"\s*_{4,}\s*")  # línea de firma: no es texto de borrador

# --- Ordinales de cláusula -------------------------------------------------------------------

UNIDADES = {
    "PRIMERA": 1, "PRIMERO": 1, "SEGUNDA": 2, "SEGUNDO": 2, "TERCERA": 3, "TERCERO": 3,
    "CUARTA": 4, "CUARTO": 4, "QUINTA": 5, "QUINTO": 5, "SEXTA": 6, "SEXTO": 6,
    "SEPTIMA": 7, "SEPTIMO": 7, "OCTAVA": 8, "OCTAVO": 8, "NOVENA": 9, "NOVENO": 9,
}
DECENAS = {"DECIMA": 10, "DECIMO": 10, "VIGESIMA": 20, "VIGESIMO": 20, "TRIGESIMA": 30, "TRIGESIMO": 30}
ESPECIALES = {"UNDECIMA": 11, "DUODECIMA": 12}
UNIDADES_TXT = ["", "PRIMERA", "SEGUNDA", "TERCERA", "CUARTA", "QUINTA", "SEXTA", "SÉPTIMA", "OCTAVA", "NOVENA"]
DECENAS_TXT = {10: "DÉCIMA", 20: "VIGÉSIMA", 30: "TRIGÉSIMA"}

CLAUSULA = re.compile(r"^\s*CL[AÁ]USULA\s+([A-ZÁÉÍÓÚÜÑ ]+?)\s*(?=[:.\-–—]|$)", re.I)
# «SEGUNDA PARTE: CONTRATO DE HIPOTECA … - CLÁUSULA PRIMERA. …» abre parte y puede traer su primera cláusula.
PARTE = re.compile(r"^\s*(PRIMERA|SEGUNDA|TERCERA)\s+PARTE\b", re.I)
CLAUSULA_EN_PARTE = re.compile(r"CL[AÁ]USULA\s+([A-ZÁÉÍÓÚÜÑ ]+?)\s*(?=[:.\-–—]|$)", re.I)


def sin_tildes(texto: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", texto) if unicodedata.category(c) != "Mn")


def ordinal(palabras: str) -> tuple[int | None, str]:
    """Número y texto del ordinal al inicio de `palabras` («SÉPTIMA INSPECCIÓN» → 7, «SÉPTIMA»)."""
    originales = palabras.split()
    total, usadas = 0, 0
    for original in originales:
        token = sin_tildes(original).upper()
        partes = [token[:6], token[6:]] if token.startswith(("DECIMO", "DECIMA")) and len(token) > 6 else [token]
        valores = [ESPECIALES.get(t) or DECENAS.get(t) or UNIDADES.get(t) for t in partes]
        if any(v is None for v in valores):
            break
        total += sum(valores)
        usadas += 1
    return (total or None), " ".join(originales[:usadas]) or palabras.strip()


def ordinal_a_numero(palabras: str) -> int | None:
    return ordinal(palabras)[0]


def numero_a_ordinal(n: int) -> str:
    """Estilo de las plantillas del bufete: «DÉCIMA PRIMERA», «VIGÉSIMA»."""
    decena, unidad = (n // 10) * 10, n % 10
    if decena == 0:
        return UNIDADES_TXT[unidad]
    base = DECENAS_TXT[decena]
    return base if unidad == 0 else f"{base} {UNIDADES_TXT[unidad]}"


# --- Lectura -----------------------------------------------------------------------------------


def plantillas() -> list[Path]:
    out: list[Path] = []
    for carpeta in CARPETAS:
        out.extend(sorted(p for p in carpeta.rglob("*.docx") if not p.name.startswith("~$")))
    return out


def partes_xml(z: zipfile.ZipFile) -> list[str]:
    return [
        n for n in z.namelist()
        if n == "word/document.xml" or re.fullmatch(r"word/(header|footer)\d*\.xml", n)
    ]


def parrafos(root: etree._Element):
    """Cada w:p con solo sus w:t propios (los de un cuadro de texto anidado van en su propio w:p)."""
    for p in root.iter(Q("p")):
        ts = [t for t in p.iter(Q("t")) if next(t.iterancestors(Q("p"))) is p]
        yield p, ts


def texto(ts) -> str:
    return "".join(t.text or "" for t in ts)


@dataclass
class Informe:
    nombre: str
    tags: dict[str, int] = field(default_factory=dict)
    partidos: list[str] = field(default_factory=list)
    borrador: list[str] = field(default_factory=list)
    amarillos: int = 0
    comentarios: int = 0
    clausulas: list[list[tuple[int | None, str]]] = field(default_factory=list)


def tags_partidos(ts) -> list[str]:
    """Tags {{…}} cuyo texto atraviesa más de un w:t."""
    limites, pos = [], 0
    for t in ts:
        pos += len(t.text or "")
        limites.append(pos)
    out = []
    for m in TAG.finditer(texto(ts)):
        inicio = next(i for i, fin in enumerate(limites) if fin > m.start())
        fin = next(i for i, f in enumerate(limites) if f >= m.end())
        if inicio != fin:
            out.append(m.group())
    return out


def revisar(path: Path) -> Informe:
    inf = Informe(path.relative_to(RAIZ / "src/main/resources/templates").as_posix())
    with zipfile.ZipFile(path) as z:
        inf.comentarios = len(etree.fromstring(z.read("word/comments.xml")).findall(Q("comment"))) \
            if "word/comments.xml" in z.namelist() else 0
        for parte in partes_xml(z):
            root = etree.fromstring(z.read(parte))
            inf.amarillos += len(root.xpath('.//w:rPr/w:highlight[@w:val="yellow"]', namespaces=NS))
            for p, ts in parrafos(root):
                linea = texto(ts)
                for m in TAG.finditer(linea):
                    inf.tags[m.group()] = inf.tags.get(m.group(), 0) + 1
                inf.partidos.extend(tags_partidos(ts))
                for etiqueta, rx in BORRADOR:
                    if rx.search(linea) and not LINEA_FIRMA.fullmatch(linea):
                        inf.borrador.append(f"{etiqueta}: {linea.strip()[:90]}")
                if parte == "word/document.xml":
                    for nueva_parte, n, palabra in clausula_de(linea):
                        if nueva_parte or not inf.clausulas:
                            inf.clausulas.append([])
                        if n is not None or palabra:
                            inf.clausulas[-1].append((n, palabra))
    return inf


def clausula_de(linea: str):
    """[(abre_parte, número, ordinal)] del párrafo: encabezado de parte y/o «CLÁUSULA <ordinal>» inicial."""
    out = []
    if PARTE.match(linea):
        m = CLAUSULA_EN_PARTE.search(linea)
        out.append((True, *ordinal(m.group(1))) if m else (True, None, ""))
        return out
    m = CLAUSULA.match(linea)
    if m:
        out.append((False, *ordinal(m.group(1))))
    return out


def problemas_clausulas(parte: list[tuple[int | None, str]]) -> list[str]:
    out, esperado = [], 1
    vistos: set[int] = set()
    for n, palabra in parte:
        if n is None:
            out.append(f"ordinal no reconocido «{palabra}»")
            continue
        if n in vistos:
            out.append(f"repetida «{palabra}»")
        elif n != esperado:
            out.append(f"salto: «{palabra}» donde iba {numero_a_ordinal(esperado)}")
        vistos.add(n)
        esperado = n + 1
    return out


def cmd_revisar(args) -> int:
    hallazgos = 0
    for path in plantillas():
        inf = revisar(path)
        print(f"\n== {inf.nombre}")
        print(f"   tags distintos: {len(inf.tags)}   resaltados amarillos: {inf.amarillos}   comentarios: {inf.comentarios}")
        if args.tags:
            print("   " + " ".join(sorted(inf.tags)))
        for t in inf.partidos:
            print(f"   TAG PARTIDO {t}")
        for b in inf.borrador:
            print(f"   BORRADOR {b}")
        for i, parte in enumerate(inf.clausulas, 1):
            ords = ", ".join(p for _, p in parte)
            print(f"   parte {i}: {ords}")
            for prob in problemas_clausulas(parte):
                print(f"   CLÁUSULA parte {i}: {prob}")
                hallazgos += 1
        hallazgos += len(inf.partidos) + len(inf.borrador) + inf.comentarios
    print(f"\nHallazgos: {hallazgos}")
    return 0


# --- Escritura ---------------------------------------------------------------------------------


def respaldar(path: Path) -> None:
    destino = Path(tempfile.gettempdir()) / "lexia-plantillas-respaldo" / dt.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    destino.mkdir(parents=True, exist_ok=False)
    shutil.copy2(path, destino / path.name)
    print(f"   respaldo: {destino / path.name}")


def reescribir(path: Path, cambios: dict[str, bytes], quitar: set[str] = frozenset()) -> None:
    """Reescribe el zip conservando orden, compresión y fecha de cada entrada."""
    respaldar(path)
    with zipfile.ZipFile(path) as zin:
        entradas = [(i, zin.read(i.filename)) for i in zin.infolist()]
    tmp = path.with_suffix(".tmp")
    with zipfile.ZipFile(tmp, "w") as zout:
        for info, data in entradas:
            if info.filename in quitar:
                continue
            zout.writestr(info, cambios.get(info.filename, data))
    tmp.replace(path)


def serializar(root) -> bytes:
    return etree.tostring(root, xml_declaration=True, encoding="UTF-8", standalone=True)


def unir_en_parrafo(ts) -> int:
    """Lleva cada tag partido completo al primer w:t donde empieza (conserva el formato de ese run)."""
    unidos = 0
    while True:
        limites, pos = [], 0
        for t in ts:
            limites.append((pos, pos + len(t.text or "")))
            pos += len(t.text or "")
        partido = None
        for m in TAG.finditer(texto(ts)):
            i = next(k for k, (a, b) in enumerate(limites) if b > m.start())
            j = next(k for k, (a, b) in enumerate(limites) if b >= m.end())
            if i != j:
                partido = (m, i, j)
                break
        if not partido:
            return unidos
        m, i, j = partido
        a_i, _ = limites[i]
        ti = ts[i]
        ti.text = (ti.text or "")[: m.start() - a_i] + m.group()
        for k in range(i + 1, j + 1):
            a_k, b_k = limites[k]
            resto = (ts[k].text or "")[max(0, m.end() - a_k):] if k == j else ""
            ts[k].text = resto
        # Lo que seguía al tag en el primer w:t no existe (el tag termina en otro w:t).
        for t in (ts[i], ts[j]):
            if t.text and (t.text != t.text.strip()):
                t.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
        unidos += 1


def cmd_unir_tags(args) -> int:
    for path in plantillas():
        cambios: dict[str, bytes] = {}
        total = 0
        with zipfile.ZipFile(path) as z:
            for parte in partes_xml(z):
                root = etree.fromstring(z.read(parte))
                antes = sorted(m.group() for _, ts in parrafos(root) for m in TAG.finditer(texto(ts)))
                n = sum(unir_en_parrafo(ts) for _, ts in parrafos(root))
                if n:
                    despues = sorted(m.group() for _, ts in parrafos(root) for m in TAG.finditer(texto(ts)))
                    if antes != despues:
                        raise SystemExit(f"{path.name}:{parte}: la lista de tags cambió al unir; no se escribe")
                    cambios[parte] = serializar(root)
                    total += n
        if cambios:
            print(f"{path.name}: {total} tag(s) unido(s)")
            reescribir(path, cambios)
    return 0


def cmd_comentarios(args) -> int:
    filas: list[str] = []
    for path in plantillas():
        with zipfile.ZipFile(path) as z:
            if "word/comments.xml" not in z.namelist():
                continue
            comentarios = etree.fromstring(z.read("word/comments.xml"))
            textos = {
                c.get(Q("id")): " ".join(texto(list(p.iter(Q("t")))) for p in c.iter(Q("p"))).strip()
                for c in comentarios.findall(Q("comment"))
            }
            doc = etree.fromstring(z.read("word/document.xml"))
            for p, ts in parrafos(doc):
                for ref in p.iter(Q("commentRangeStart"), Q("commentReference")):
                    cid = ref.get(Q("id"))
                    if ref.tag == Q("commentRangeStart") and cid in textos:
                        filas.append(f"| {path.name} | {texto(ts).strip()[:120]} | {textos.pop(cid)} |")
            for cid, t in textos.items():
                filas.append(f"| {path.name} | (sin párrafo) | {t} |")
            for tag in ("commentRangeStart", "commentRangeEnd"):
                for el in doc.iter(Q(tag)):
                    el.getparent().remove(el)
            for ref in list(doc.iter(Q("commentReference"))):
                run = ref.getparent()
                run.getparent().remove(run)
            rels = etree.fromstring(z.read("word/_rels/document.xml.rels"))
            for rel in list(rels):
                if rel.get("Target", "").endswith("comments.xml") or "comments" in rel.get("Type", "").lower():
                    rels.remove(rel)
            ctypes = etree.fromstring(z.read("[Content_Types].xml"))
            for o in list(ctypes):
                if "comments" in (o.get("PartName") or "").lower():
                    ctypes.remove(o)
            quitar = {n for n in z.namelist() if re.fullmatch(r"word/comments\w*\.xml", n)}
        print(f"{path.name}: comentarios quitados")
        reescribir(
            path,
            {
                "word/document.xml": serializar(doc),
                "word/_rels/document.xml.rels": serializar(rels),
                "[Content_Types].xml": serializar(ctypes),
            },
            quitar,
        )
    if filas:
        encabezado = "# Comentarios de Word quitados de las plantillas\n\n| Plantilla | Párrafo | Comentario |\n|---|---|---|\n"
        previo = COMENTARIOS_MD.read_text(encoding="utf-8") if COMENTARIOS_MD.exists() else encabezado
        COMENTARIOS_MD.write_text(previo + "\n".join(filas) + "\n", encoding="utf-8")
    return 0


def cmd_renumerar(args) -> int:
    path = next((p for p in plantillas() if p.name == args.archivo), None)
    if path is None:
        raise SystemExit(f"No existe la plantilla {args.archivo}")
    with zipfile.ZipFile(path) as z:
        root = etree.fromstring(z.read("word/document.xml"))
    parte, siguiente, cambios = 0, 1, 0
    for p, ts in parrafos(root):
        encontradas = clausula_de(texto(ts))
        if not encontradas:
            continue
        abre, n, actual = encontradas[0]
        if abre or parte == 0:
            parte += 1
            siguiente = 1
        if not actual:
            continue
        if parte != args.parte:
            siguiente += 1
            continue
        nuevo = numero_a_ordinal(siguiente)
        if sin_tildes(actual).upper() != sin_tildes(nuevo).upper():
            reemplazar_en_parrafo(ts, actual, nuevo)
            print(f"   «{actual}» → «{nuevo}»")
            cambios += 1
        siguiente += 1
    if cambios:
        reescribir(path, {"word/document.xml": serializar(root)})
    print(f"{path.name}: {cambios} cláusula(s) renumerada(s) en la parte {args.parte}")
    return 0


def reemplazar_en_parrafo(ts, viejo: str, nuevo: str) -> None:
    """Reemplaza la primera aparición de `viejo` aunque cruce varios w:t (el texto nuevo va al primero)."""
    completo = texto(ts)
    inicio = completo.find(viejo)
    if inicio < 0:
        raise SystemExit(f"No se encontró «{viejo}» en el párrafo")
    fin, pos, puesto = inicio + len(viejo), 0, False
    for t in ts:
        a, b = pos, pos + len(t.text or "")
        pos = b
        if b <= inicio or a >= fin:
            continue
        s = t.text or ""
        izq = s[: max(0, inicio - a)]
        der = s[max(0, fin - a):] if fin < b else ""
        t.text = izq + (nuevo if not puesto else "") + der
        puesto = True


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("revisar")
    r.add_argument("--tags", action="store_true", help="lista los tags de cada plantilla")
    sub.add_parser("unir-tags")
    sub.add_parser("comentarios")
    n = sub.add_parser("renumerar")
    n.add_argument("archivo")
    n.add_argument("parte", type=int)
    args = ap.parse_args()
    return {
        "revisar": cmd_revisar,
        "unir-tags": cmd_unir_tags,
        "comentarios": cmd_comentarios,
        "renumerar": cmd_renumerar,
    }[args.cmd](args)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
