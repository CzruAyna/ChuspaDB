import argparse
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("copias", nargs="+", type=Path)
parser.add_argument("--maven", default="mvn")
parser.add_argument("--m2", type=Path)
parser.add_argument("--salida", type=Path, default=Path.cwd())
parser.add_argument("--offline", action="store_true")
args = parser.parse_args()
args.salida.mkdir(parents=True, exist_ok=True)
comando = [args.maven, "-B", "--no-transfer-progress", "-Dstyle.color=never"]
if args.m2:
    comando.append(f"-Dmaven.repo.local={args.m2.resolve()}")
if args.offline:
    comando.append("-o")

def diagnosticar(copia):
    print(f"Ejecutando Maven sobre {copia.name}", flush=True)
    resultado = subprocess.run(
        [*comando, "test"], cwd=copia, capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    log = resultado.stdout + resultado.stderr
    (args.salida / (copia.name + "-maven.log")).write_text(log, encoding="utf-8")
    errores = sorted(set(re.findall(r"\[ERROR\] .*?/(src/[^\r\n]+?\.java:\[\d+,\d+\] [^\r\n]+)", log)))
    print(f"{copia.name}: Maven={resultado.returncode}, diagnósticos de compilación={len(errores)}", flush=True)
    for error in errores:
        print(error, flush=True)
    if resultado.returncode and not errores:
        print(log[-4500:], flush=True)
        raise SystemExit(resultado.returncode)
    return resultado.returncode, errores

control = diagnosticar(args.copias[0])
for copia in args.copias[1:]:
    actual = diagnosticar(copia)
    if actual != control:
        raise SystemExit("Las incidencias de Maven difieren de la base.")
print("Los resultados coinciden con la base.", flush=True)
