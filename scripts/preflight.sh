#!/usr/bin/env sh

# Checks everything Operator needs before a test session. Runs in bash, zsh, and POSIX sh.

set -u

base_url="http://localhost:8080"
skip_database=false
timeout_seconds=90

usage() {
    cat <<'EOF'
Usage: scripts/preflight.sh [--base-url URL] [--skip-database] [--timeout-seconds N]

Checks local configuration, optionally starts PostgreSQL, and reads the backend's /health
report without printing secrets.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --base-url)
            [ "$#" -ge 2 ] || { echo "--base-url requires a value" >&2; exit 2; }
            base_url=$2
            shift 2
            ;;
        --skip-database)
            skip_database=true
            shift
            ;;
        --timeout-seconds)
            [ "$#" -ge 2 ] || { echo "--timeout-seconds requires a value" >&2; exit 2; }
            timeout_seconds=$2
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

case "$timeout_seconds" in
    ''|*[!0-9]*) echo "--timeout-seconds must be a positive integer" >&2; exit 2 ;;
esac
[ "$timeout_seconds" -gt 0 ] || { echo "--timeout-seconds must be a positive integer" >&2; exit 2; }

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo=$(dirname "$script_dir")
cd "$repo" || exit 1

if [ -t 1 ]; then
    green='\033[32m'; yellow='\033[33m'; red='\033[31m'; cyan='\033[36m'; gray='\033[90m'; reset='\033[0m'
else
    green=''; yellow=''; red=''; cyan=''; gray=''; reset=''
fi

problems_count=0
problems=''

ok() { printf "${green}  [ ok ] %s${reset}\n" "$1"; }
warn() { printf "${yellow}  [warn] %s${reset}\n" "$1"; }
bad() {
    printf "${red}  [FAIL] %s${reset}\n" "$1"
    problems_count=$((problems_count + 1))
    if [ -z "$problems" ]; then problems=$1; else problems="$problems
$1"; fi
}
head_line() { printf "\n${cyan}%s${reset}\n" "$1"; }

tmp_dir=$(mktemp -d 2>/dev/null || mktemp -d -t operator-preflight)
trap 'rm -f "$tmp_dir/health.json" "$tmp_dir/health-fields" "$tmp_dir/curl-error" "$tmp_dir/json-error"; rmdir "$tmp_dir" 2>/dev/null || true' EXIT HUP INT TERM

head_line "Configuration"
if [ ! -f .env ]; then
    bad ".env does not exist. Copy .env.example to .env and fill it in."
else
    ok ".env exists"
    if grep -Eq '^(OPENROUTER_API_KEY|ELEVENLABS_API_KEY|TRANSCRIPTION_API_KEY)=[^[:space:]]' .env.example; then
        bad ".env.example contains a key. That file is TRACKED BY GIT. Move it to .env and run: git checkout -- .env.example"
    else
        ok ".env.example is clean (it is tracked by git, so it must stay that way)"
    fi
    if git ls-files --error-unmatch .env >/dev/null 2>&1; then
        bad ".env is tracked by git. It must not be."
    else
        ok ".env is not tracked by git"
    fi
fi

if [ "$skip_database" = false ]; then
    head_line "Database"
    if ! command -v docker >/dev/null 2>&1; then
        warn "Docker not found. The backend still runs, but memory is in-memory and lost on restart."
    elif ! docker info >/dev/null 2>&1; then
        warn "Docker is installed but not running. Start Docker Desktop, or use --skip-database."
    else
        ok "Docker is running"
        if ! docker compose -f backend/docker-compose.yml up -d >/dev/null 2>&1; then
            bad "Docker Compose could not start PostgreSQL"
        else
            deadline=$(( $(date +%s) + timeout_seconds ))
            ready=false
            while [ "$(date +%s)" -lt "$deadline" ]; do
                if docker exec operator-postgres pg_isready -U operator -d operator >/dev/null 2>&1; then
                    ready=true
                    break
                fi
                sleep 2
            done
            if [ "$ready" = true ]; then
                ok "PostgreSQL is accepting connections"
            else
                bad "PostgreSQL did not become ready within $timeout_seconds s"
            fi
        fi
    fi
fi

head_line "Backend"
health_body="$tmp_dir/health.json"
health_fields="$tmp_dir/health-fields"
health_error=''
health_ok=false

if ! command -v curl >/dev/null 2>&1; then
    health_error='curl is not installed'
elif ! curl -sS --max-time 5 -o "$health_body" "$base_url/health" 2>"$tmp_dir/curl-error"; then
    health_error=$(tr '\n' ' ' <"$tmp_dir/curl-error")
elif ! command -v python3 >/dev/null 2>&1; then
    health_error='python3 is required to parse /health'
elif python3 - "$health_body" >"$health_fields" 2>"$tmp_dir/json-error" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    health = json.load(source)

def emit(value):
    if isinstance(value, bool):
        value = "true" if value else "false"
    elif value is None:
        value = ""
    print(str(value).replace("\r", " ").replace("\n", " "))

database = health.get("database") or {}
providers = health.get("providers") or {}
emit(health.get("version"))
emit(health.get("status"))
emit(database.get("reachable", False))
emit(database.get("configured", False))
emit(database.get("pgvector"))
emit(database.get("migrationsApplied"))
emit(database.get("error"))
emit(health.get("memoryBackend"))
for name in ("ai", "transcription", "tts"):
    slot = providers.get(name) or {}
    emit(slot.get("configured", False))
    emit(slot.get("provider"))
    emit(slot.get("detail"))
PY
then
    health_ok=true
else
    health_error=$(tr '\n' ' ' <"$tmp_dir/json-error")
fi

if [ "$health_ok" = false ]; then
    warn "Not responding at $base_url ($health_error). Start it in another window with:"
    printf "${gray}         ./gradlew :backend:run -Poperator.skipAndroid=true${reset}\n"
else
    field() { sed -n "${1}p" "$health_fields"; }
    version=$(field 1); status=$(field 2)
    database_reachable=$(field 3); database_configured=$(field 4)
    pgvector=$(field 5); migrations=$(field 6); database_error=$(field 7); memory_backend=$(field 8)
    ok "Backend $version is up (status: $status)"

    if [ "$database_reachable" = true ]; then
        ok "Database reachable — pgvector $pgvector, $migrations migrations"
    elif [ "$database_configured" = true ]; then
        bad "DATABASE_URL is set but unreachable: $database_error"
    else
        warn "No database configured — memory is '$memory_backend' and will not survive a restart"
    fi

    head_line "Provider slots"
    slot_number=0
    for slot in ai transcription tts; do
        slot_number=$((slot_number + 1))
        base=$((9 + (slot_number - 1) * 3))
        configured=$(field "$base")
        provider=$(field $((base + 1)))
        detail=$(field $((base + 2)))
        case "$slot" in
            ai) hint='OPENROUTER_API_KEY + OPERATOR_FAST_MODEL_ID   (unlocks Ask Operator, memory, deciding)' ;;
            transcription) hint='TRANSCRIPTION_API_KEY + OPERATOR_TRANSCRIPTION_MODEL_ID   (unlocks listening)' ;;
            tts) hint='ELEVENLABS_API_KEY + voice and model IDs   (unlocks speech out)' ;;
        esac
        if [ "$configured" = true ]; then
            ok "$(printf '%-14s %s — %s' "$slot" "$provider" "$detail")"
        else
            warn "$(printf '%-14s not configured. Set: %s' "$slot" "$hint")"
        fi
    done

    if [ "$(field 9)" = true ]; then
        head_line "First call"
        printf "${gray}  Ready. Try:${reset}\n"
        printf '%s\n' '    curl -s localhost:8080/ai/respond -H "content-type: application/json" -d '\''{"prompt":"Say hello in one short sentence."}'\'''
    fi
fi

head_line "Summary"
if [ "$problems_count" -eq 0 ]; then
    printf "${green}  Nothing blocking. Warnings above are optional features, not faults.${reset}\n"
else
    printf "${red}  %s thing(s) to fix:${reset}\n" "$problems_count"
    printf '%s\n' "$problems" | while IFS= read -r problem; do printf "${red}    - %s${reset}\n" "$problem"; done
    exit 1
fi
