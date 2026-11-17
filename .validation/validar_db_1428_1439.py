import argparse
import os
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
from urllib.request import urlretrieve

RAIZ = Path(__file__).resolve().parent
MAVEN = "https://repo.maven.apache.org/maven2/"
GAV = {
    "junit": ("org.junit.platform", "junit-platform-console-standalone", "6.1.3"),
    "sqlite": ("org.xerial", "sqlite-jdbc", "3.46.0.0"),
    "slf4j": ("org.slf4j", "slf4j-api", "2.0.20"),
    "mockito": ("org.mockito", "mockito-core", "5.24.0"),
    "buddy": ("net.bytebuddy", "byte-buddy", "1.17.7"),
    "buddy-agent": ("net.bytebuddy", "byte-buddy-agent", "1.17.7"),
    "objenesis": ("org.objenesis", "objenesis", "3.3"),
    "databind": ("com.fasterxml.jackson.core", "jackson-databind", "2.22.2"),
    "core": ("com.fasterxml.jackson.core", "jackson-core", "2.22.2"),
    "annotations": ("com.fasterxml.jackson.core", "jackson-annotations", "2.22"),
}
CASOS = {
    "1428": ("EjecutorQuery", ["core/ErrorQuery.java", "util/SqlValidator.java"]),
    "1439": ("ImportadorJSON", [
        "core/ErrorPersistencia.java", "dao/IConexionDAO.java", "modelo/TipoMotor.java",
        "modelo/Conexion.java", "modelo/ImportacionResultado.java", "servicio/ExportadorJSON.java",
    ]),
}

parser = argparse.ArgumentParser()
parser.add_argument("issue", choices=CASOS)
parser.add_argument("--repo", type=Path, default=Path.cwd())
parser.add_argument("--descargar", action="store_true")
parser.add_argument("--solo-existentes", action="store_true")
args = parser.parse_args()

repo = args.repo.resolve()
m2 = RAIZ / "herramientas_chuspadb" / "m2"
jars = {}
for clave, (grupo, artefacto, version) in GAV.items():
    if args.issue == "1428" and clave in {"databind", "core", "annotations"}:
        continue
    nombre = f"{artefacto}-{version}.jar"
    relativo = Path(*grupo.split(".")) / artefacto / version / nombre
    jar = m2 / relativo
    if clave == "junit" and not args.descargar:
        jar = m2.parent / nombre
    if not jar.is_file() and args.descargar:
        jar.parent.mkdir(parents=True, exist_ok=True)
        descarga = jar.with_suffix(".part")
        urlretrieve(MAVEN + relativo.as_posix(), descarga)
        descarga.replace(jar)
    if not jar.is_file():
        raise FileNotFoundError(jar)
    jars[clave] = jar

base_java = repo / "src/main/java/edu/sisinf/estante"
pruebas = repo / "src/test/java/edu/sisinf/estante/servicio"
servicio, dependencias = CASOS[args.issue]
print(f"ChuspaDB #{args.issue} | {servicio}", flush=True)
with TemporaryDirectory(prefix=f"junit-db-{args.issue}-", dir=RAIZ) as carpeta:
    temporal = Path(carpeta)
    error_base = base_java / "core" / "ErrorChuspaDB.java"
    if not error_base.exists():
        error_base = temporal / "ErrorChuspaDB.java"
        shutil.copyfile(base_java / "core" / "ErrorEstante.java", error_base)
        print("Se usó el nombre ErrorChuspaDB.java únicamente en la copia de compilación.", flush=True)
    clases = temporal / "classes"
    clases.mkdir()
    nivel = os.pathsep.join(str(jar) for jar in jars.values())
    original = servicio + "Test"
    prueba_extra = servicio + "Validacion" + args.issue + "Test"
    fuentes = [error_base, base_java / "modelo/ResultadoQuery.java",
               base_java / "servicio" / (servicio + ".java"),
               *(base_java / d for d in dependencias), pruebas / (original + ".java")]
    suites = [original]
    if not args.solo_existentes:
        fuentes.append(RAIZ / (prueba_extra + ".java"))
        suites.append(prueba_extra)
    subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8",
                    "-cp", nivel, "-d", clases, *fuentes], check=True)
    for nombre in suites:
        print(nombre, flush=True)
        subprocess.run(["java", "-Dfile.encoding=UTF-8", f"-Djava.io.tmpdir={temporal}",
                        f"-javaagent:{jars['mockito']}",
                        "-cp", f"{clases}{os.pathsep}{nivel}",
                        "org.junit.platform.console.ConsoleLauncher", "execute",
                        "--select-class", "edu.sisinf.chuspadb.servicio." + nombre,
                        "--details", "summary", "--disable-ansi-colors", "--fail-if-no-tests"],
                       check=True)
