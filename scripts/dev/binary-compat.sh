#!/usr/bin/env bash
# Comprueba la compatibilidad binaria de Core con los paquetes ya instalados (RESTRUCTURE_PLAN.md, F1 y V-bin).
# Los modulos opcionales y las extensiones se compilan una vez, contra el Core que corre al instalarlos, y no se
# recompilan cuando Core se actualiza. Este script compila los modulos y extensiones de <ref-base> (el Core "viejo"),
# arma la distribucion del arbol actual (el Core "nuevo") y usa jdeps para ver si alguna clase
# com.ideasdeveloper.bms.server.* o com.ideasdeveloper.bms.modules.* que esos paquetes referencian ya no existe.
# Solo mira clases (a nivel de miembro lo cubren SdkSurfaceTest y la regla R1).
#
# Uso: scripts/dev/binary-compat.sh <ref-base>      (por ejemplo el commit o la etiqueta del Core publicado)
# Variables opcionales:
#   BMS_COMPAT_TRIM_ROOT=1  Recorta build.gradle.kts y gradle-daemon-jvm.properties de ambos arboles durante la
#                           compilacion (los restaura al salir). Solo para entornos sin el plugin de Android,
#                           como el contenedor de desarrollo; nunca lo uses para confirmar cambios.
#   JAVA_HOME               JDK 21 (con jdeps).
# Salida: 0 si no falta ninguna clase; 1 si falta alguna (se listan); 2 si el script no pudo ejecutarse.
set -Eeuo pipefail

die() { printf 'ERROR: %s\n' "$*" >&2; exit 2; }

[[ $# -eq 1 ]] || die 'Uso: scripts/dev/binary-compat.sh <ref-base>'
readonly BASE_REF=$1
ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || die 'Ejecutalo dentro del repositorio de BMS.'
readonly ROOT
readonly INIT="$ROOT/gradle/ci/with-extensions.init.gradle.kts"
[[ -f "$INIT" ]] || die "Falta $INIT"
git -C "$ROOT" rev-parse --verify --quiet "$BASE_REF^{commit}" >/dev/null || die "Referencia desconocida: $BASE_REF"
if [[ -n "${JAVA_HOME:-}" ]]; then JDEPS="$JAVA_HOME/bin/jdeps"; else JDEPS=$(command -v jdeps || true); fi
[[ -x "$JDEPS" ]] || die 'No encuentro jdeps (instala un JDK 21).'

WORK=$(mktemp -d)
readonly WORK
BASE_TREE="$WORK/base"
TRIMMED=()

trim_root() {
  local dir=$1
  [[ "${BMS_COMPAT_TRIM_ROOT:-}" == 1 ]] || return 0
  cp "$dir/build.gradle.kts" "$WORK/$(basename "$dir").build.gradle.kts"
  cp "$dir/gradle/gradle-daemon-jvm.properties" "$WORK/$(basename "$dir").daemon.properties"
  TRIMMED+=("$dir")
  rm -f "$dir/gradle/gradle-daemon-jvm.properties"
  sed -i '/androidApplication\|androidMultiplatformLibrary\|composeMultiplatform\|composeCompiler/d' "$dir/build.gradle.kts"
}

cleanup() {
  local dir
  for dir in "${TRIMMED[@]}"; do
    cp "$WORK/$(basename "$dir").build.gradle.kts" "$dir/build.gradle.kts"
    cp "$WORK/$(basename "$dir").daemon.properties" "$dir/gradle/gradle-daemon-jvm.properties"
  done
  git -C "$ROOT" worktree remove --force "$BASE_TREE" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

printf '==> 1/4 Compilando modulos y extensiones de %s\n' "$BASE_REF"
git -C "$ROOT" worktree add --detach "$BASE_TREE" "$BASE_REF" >/dev/null 2>&1 || die "No pude crear el arbol de $BASE_REF"
trim_root "$BASE_TREE"
(cd "$BASE_TREE" && ./gradlew -PserverOnly=true -I "$INIT" :server:modulesClasses :server:testClasses --console=plain -q)
BASE_MODULES="$BASE_TREE/server/build/classes/kotlin/modules"
BASE_TEST_CLASSES="$BASE_TREE/server/build/classes/kotlin/test"
[[ -d "$BASE_MODULES" ]] || die "No se compilaron los modulos de $BASE_REF"

# Clases de las extensiones: salen de la salida de pruebas porque :server no las compila solo (F3). Solo se toman los
# paquetes com.ideasdeveloper.bms.extensions.<id> de carpetas reales de extensions/ (no los de prueba, como
# i18n_ns_ext) y se descartan las clases *Test*: no viajan en el paquete instalado.
EXT_CLASSES="$WORK/extension-classes"
mkdir -p "$EXT_CLASSES"
for ext_dir in "$BASE_TREE"/extensions/*/; do
  ext_id=$(basename "$ext_dir")
  if [[ -d "$BASE_TEST_CLASSES/com/ideasdeveloper/bms/extensions/$ext_id" ]]; then
    (cd "$BASE_TEST_CLASSES" && find "com/ideasdeveloper/bms/extensions/$ext_id" -name '*.class' ! -name '*Test*' -print0 |
      xargs -0 -r cp --parents -t "$EXT_CLASSES")
  fi
done

printf '==> 2/4 Armando la distribucion del arbol actual\n'
trim_root "$ROOT"
(cd "$ROOT" && ./gradlew -PserverOnly=true :server:installDist --console=plain -q)
NEW_LIB="$ROOT/server/build/install/server/lib"
[[ -d "$NEW_LIB" ]] || die "No existe $NEW_LIB"

printf '==> 3/4 Ejecutando jdeps\n'
CP="$BASE_MODULES:$EXT_CLASSES"
for jar in "$NEW_LIB"/*.jar; do CP="$CP:$jar"; done
REPORT="$WORK/jdeps.txt"
"$JDEPS" --multi-release 21 --missing-deps -cp "$CP" "$BASE_MODULES" "$EXT_CLASSES" >"$REPORT" 2>&1 || true

printf '==> 4/4 Buscando clases de Core que faltan\n'
# jdeps escribe "   <clase origen> -> <clase destino>   not found" por cada dependencia sin resolver.
MISSING=$(grep -E -- '-> com\.ideasdeveloper\.bms\.(server|modules)\.[^ ]+ +not found' "$REPORT" |
  sed -E 's/^ +//' | sort -u || true)
if [[ -n "$MISSING" ]]; then
  printf 'FALLA: los paquetes compilados en %s referencian clases que el Core actual ya no tiene:\n' "$BASE_REF" >&2
  printf '%s\n' "$MISSING" >&2
  exit 1
fi
printf 'OK: ninguna clase com.ideasdeveloper.bms.(server|modules).* referenciada por los paquetes de %s falta en el Core actual.\n' "$BASE_REF"
