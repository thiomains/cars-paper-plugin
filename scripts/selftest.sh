#!/usr/bin/env bash
#
# Automatische Verifikation des Auto-Plugins.
#
# Baut das Plugin, startet einen Wegwerf-Paper-Server (Flachwelt, fester Seed),
# laesst dort "/car selftest" laufen und wertet das Ergebnis aus.
#
#   scripts/selftest.sh                  nur Ergebniszeilen und Fehlschlaege
#   scripts/selftest.sh --verbose        zusaetzlich jeden Tick und das komplette Serverlog
#   scripts/selftest.sh --only step      nur Szenarien, deren Name "step" enthaelt
#   scripts/selftest.sh --update-paper   neuestes Paper-JAR holen
#   scripts/selftest.sh --keep           Welt behalten (Standard: frische Welt je Lauf)
#
# Exit: 0 = alles gruen, 1 = Testfehler, 2 = Harness-Fehler (Build, Server, Timeout).
#
# Maschinenspezifische Pfade gehoeren nicht ins Repo: liegt scripts/env.local vor,
# wird es eingelesen (z. B. MVN=/pfad/zu/mvn und JAVA=/pfad/zu/java).

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT/.testserver"
LOG_DIR="$SERVER_DIR/logs"
MC_VERSION="26.2"
BOOT_TIMEOUT=180
TEST_TIMEOUT=600

VERBOSE=0
ONLY=""
UPDATE_PAPER=0
KEEP_WORLD=0

while [ $# -gt 0 ]; do
  case "$1" in
    --verbose|-v) VERBOSE=1 ;;
    --only) ONLY="${2:-}"; shift ;;
    --update-paper) UPDATE_PAPER=1 ;;
    --keep) KEEP_WORLD=1 ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unbekannte Option: $1" >&2; exit 2 ;;
  esac
  shift
done

[ -f "$ROOT/scripts/env.local" ] && . "$ROOT/scripts/env.local"
MVN="${MVN:-mvn}"
JAVA="${JAVA:-java}"

fail_harness() { echo "HARNESS-FEHLER: $*" >&2; exit 2; }

command -v "$MVN" >/dev/null 2>&1 || fail_harness "Maven nicht gefunden ($MVN). MVN=... setzen oder scripts/env.local anlegen."
command -v "$JAVA" >/dev/null 2>&1 || fail_harness "Java nicht gefunden ($JAVA). JAVA=... setzen oder scripts/env.local anlegen."

# ── 1. Build ────────────────────────────────────────────────────────────────
echo "== Build =="
"$MVN" -q -B -f "$ROOT/pom.xml" clean package || fail_harness "Build fehlgeschlagen"
JAR="$(ls -t "$ROOT"/target/auto-*.jar 2>/dev/null | head -1)"
[ -n "$JAR" ] || fail_harness "Kein Artefakt in target/"
echo "   $(basename "$JAR")"

# ── 2. Testserver vorbereiten ───────────────────────────────────────────────
mkdir -p "$SERVER_DIR/plugins" "$LOG_DIR"

if [ ! -f "$SERVER_DIR/paper.jar" ] || [ "$UPDATE_PAPER" = 1 ]; then
  echo "== Paper $MC_VERSION laden =="
  URL="$(curl -sf -m 60 -H 'User-Agent: auto-plugin-selftest' \
    "https://fill.papermc.io/v3/projects/paper/versions/$MC_VERSION/builds" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["downloads"]["server:default"]["url"])')" \
    || fail_harness "Paper-Build-Liste nicht abrufbar"
  curl -sf -m 600 -o "$SERVER_DIR/paper.jar.part" "$URL" || fail_harness "Download fehlgeschlagen"
  mv "$SERVER_DIR/paper.jar.part" "$SERVER_DIR/paper.jar"
  echo "   $(basename "$URL")"
fi

echo "eula=true" > "$SERVER_DIR/eula.txt"
# Flachwelt mit festem Seed: identische Welt bei jedem Lauf und praktisch keine Weltgenerierung.
cat > "$SERVER_DIR/server.properties" <<'PROPS'
level-type=minecraft:flat
level-seed=auto-selftest
generate-structures=false
spawn-monsters=false
spawn-npcs=false
spawn-animals=false
online-mode=false
max-players=1
view-distance=6
simulation-distance=6
sync-chunk-writes=false
PROPS

[ "$KEEP_WORLD" = 1 ] || rm -rf "$SERVER_DIR/world" "$SERVER_DIR/world_nether" "$SERVER_DIR/world_the_end"
rm -f "$SERVER_DIR"/plugins/auto-*.jar
cp "$JAR" "$SERVER_DIR/plugins/"

# ── 3. Server starten und Selftest fahren ───────────────────────────────────
STAMP="$(date +%Y-%m-%dT%H-%M-%S)"
LOG="$LOG_DIR/selftest-$STAMP.log"
PIPE="$SERVER_DIR/.stdin"
rm -f "$PIPE"; mkfifo "$PIPE"

cleanup() {
  [ -n "${TAIL_PID:-}" ] && kill "$TAIL_PID" 2>/dev/null
  [ -n "${SERVER_PID:-}" ] && kill "$SERVER_PID" 2>/dev/null
  rm -f "$PIPE"
}
trap cleanup EXIT

echo "== Server starten =="
( cd "$SERVER_DIR" && "$JAVA" -Xmx1536M -jar paper.jar nogui < "$PIPE" > "$LOG" 2>&1 ) &
SERVER_PID=$!
exec 3> "$PIPE"

if [ "$VERBOSE" = 1 ]; then
  tail -f "$LOG" & TAIL_PID=$!
else
  ( tail -f "$LOG" | grep --line-buffered -o '\[Selftest\].*' ) & TAIL_PID=$!
fi

wait_for() { # muster timeout
  local pattern="$1" limit="$2" waited=0
  while ! grep -q "$pattern" "$LOG" 2>/dev/null; do
    kill -0 "$SERVER_PID" 2>/dev/null || return 1
    sleep 1
    waited=$((waited + 1))
    [ "$waited" -ge "$limit" ] && return 1
  done
  return 0
}

wait_for 'Done (' "$BOOT_TIMEOUT" || { cleanup; fail_harness "Server nicht hochgekommen — Log: $LOG"; }

CMD="car selftest"
[ "$VERBOSE" = 1 ] && CMD="$CMD --verbose"
[ -n "$ONLY" ] && CMD="$CMD $ONLY"
echo "$CMD" >&3

if ! wait_for '\[Selftest\] SUMMARY' "$TEST_TIMEOUT"; then
  LAST="$(grep -o '\[Selftest\] START.*\|\[Selftest\] \(PASS\|FAIL\|KNOWN-FAIL\|UNEXPECTED-PASS\) [a-z0-9-]*' "$LOG" | tail -1)"
  echo "stop" >&3
  sleep 5
  cleanup
  fail_harness "Selftest ohne Ergebnis (Timeout ${TEST_TIMEOUT}s). Zuletzt: ${LAST:-nichts}. Log: $LOG"
fi

echo "stop" >&3
wait "$SERVER_PID" 2>/dev/null
sleep 1
kill "$TAIL_PID" 2>/dev/null

# ── 4. Auswerten ────────────────────────────────────────────────────────────
SUMMARY="$(grep -o '\[Selftest\] SUMMARY.*' "$LOG" | tail -1)"
FAILED="$(echo "$SUMMARY" | sed -n 's/.*failed=\([0-9]*\).*/\1/p')"
KNOWN="$(echo "$SUMMARY" | sed -n 's/.*known-fail=\([0-9]*\).*/\1/p')"
PASSED="$(echo "$SUMMARY" | sed -n 's/.*passed=\([0-9]*\).*/\1/p')"
CRASHES="$(grep -c 'Exception\|SEVERE\|/ERROR\]' "$LOG" 2>/dev/null)"
CRASHES="${CRASHES:-0}"

echo
echo "== Ergebnis =="
echo "bestanden=${PASSED:-?}  fehlgeschlagen=${FAILED:-?}  bekannte Bugs=${KNOWN:-0}"
[ "${KNOWN:-0}" -gt 0 ] 2>/dev/null && grep -o '\[Selftest\] KNOWN-FAIL.*' "$LOG" | sed 's/\[Selftest\] /  /'

STATUS=0
if [ "${FAILED:-1}" != "0" ]; then
  echo
  echo "FEHLGESCHLAGEN:"
  grep -o '\[Selftest\] \(FAIL\|UNEXPECTED-PASS\).*' "$LOG" | sed 's/\[Selftest\] /  /'
  STATUS=1
fi
if [ "${CRASHES:-0}" -gt 0 ]; then
  echo
  echo "Fehler im Serverlog ($CRASHES Zeilen):"
  grep -n 'Exception\|SEVERE\|/ERROR\]' "$LOG" | head -5 | sed 's/^/  /'
  STATUS=1
fi

echo
echo "Vollstaendiges Serverlog: $LOG"
exit "$STATUS"
