#!/usr/bin/env python3
"""Sustituye la cláusula de precio de las minutas del bufete.

Individual/Solidaria: los bloques XXXXXX y USD XX.000,00 pasan a tags de un solo run.
Individual/Solidaria, Preferencial y Terreno: los anticipos van después de la entrada con
{{detalle_entrada}} («(USD entrada), {{detalle_entrada}}, y el valor restante…»). El saldo ya lo
redacta la plantilla («…que LA PARTE COMPRADORA paga … con préstamo hipotecario…»), así que
{{detalle_forma_pago}} (anticipos + saldo BIESS) no va en estas tres; solo en Hipotecada.

Idempotente: se puede correr varias veces. Cada tag queda dentro de un solo <w:t>.

Uso (desde backend/):
  py -3 tools/plantillas-bufete/ajustar_clausula_precio.py
"""
import zipfile
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
DIR = RAIZ / "src/main/resources/templates/escrituracion"

PRECIO = "{{precio_compraventa_letras}} DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA (USD {{precio_compraventa_numero}})"
ENTRADA = "{{valor_entrada_letras}} DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA  (USD {{valor_entrada_numero}})"
SALDO = "{{saldo_compraventa_letras}} DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA (USD {{saldo_compraventa_numero}}),"

ARCHIVOS = [
    DIR / "VIVIENDA TERMINADA INDIVIDUAL - SOLIDARIA/minuta_vivienda_terminada_compraventa_hipoteca.docx",
    DIR / "VIVIENDA TERMINADA PREFERENCIAL/minuta_compraventa_y_hipoteca_preferencial.docx",
    DIR / "TERRENO TERMINADA INDIVIDUAL/minuta_compraventa_y_hipoteca_terreno.docx",
]

REEMPLAZOS = {
    "XXXXXX MIL CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA (USD XX.000,00)": PRECIO,
    "XXXXXXXXXX CON 00/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA  (USD XX.000,00)": ENTRADA,
    "XXXXXXXXXCON 00/100 DÓLARES DE LOS ESTADOS UNIDOS DE AMÉRICA (USD XX.000,00),": SALDO,
    # Versión anterior de este script: detalle completo después del saldo (duplicaba el saldo).
    "pagados de la siguiente manera: {{detalle_forma_pago}}, ": "",
}

FIN_ENTRADA = "{{valor_entrada_numero}})</w:t>"
CON_DETALLE = "{{valor_entrada_numero}}), {{detalle_entrada}}</w:t>"


def ajustar(path: Path) -> int:
    with zipfile.ZipFile(path) as zin:
        partes = [(i, zin.read(i.filename)) for i in zin.infolist()]
    xml = next(data for info, data in partes if info.filename == "word/document.xml")
    cambios = 0
    for viejo, nuevo in REEMPLAZOS.items():
        token = viejo.encode("utf-8")
        if token in xml:
            xml = xml.replace(token, nuevo.encode("utf-8"), 1)
            cambios += 1
    if b"{{detalle_entrada}}" not in xml:
        fin = FIN_ENTRADA.encode("utf-8")
        if xml.count(fin) != 1:
            raise SystemExit(f"{path.name}: no se encontró un único cierre de la entrada")
        xml = xml.replace(fin, CON_DETALLE.encode("utf-8"), 1)
        cambios += 1
    if cambios:
        with zipfile.ZipFile(path, "w") as zout:
            for info, data in partes:
                if info.filename == "word/document.xml":
                    data = xml
                zout.writestr(info, data)
    return cambios


def main() -> None:
    for path in ARCHIVOS:
        print(path.name, ajustar(path))


if __name__ == "__main__":
    main()
