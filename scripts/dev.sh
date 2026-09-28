#!/usr/bin/env bash
# Linux/WSL process ownership is checked using /proc, never just a port number.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
SERVICE="${1:-}"
ACTION="${2:-start}"
case "$SERVICE:$ACTION" in
    frontend:start|frontend:stop|frontend:status|backend:start|backend:stop|backend:status) ;;
    *) echo "Usage: bash scripts/dev.sh {frontend|backend} {start|stop|status}" >&2; exit 2 ;;
esac
[[ -d /proc/self ]] || { echo "These scripts require Linux or WSL." >&2; exit 1; }

find_servers() {
    local process cwd executable argument matched app
    local -a arguments
    for process in /proc/[0-9]*; do
        [[ -O "$process" && -r "$process/cmdline" ]] || continue
        cwd="$(readlink "$process/cwd" 2>/dev/null)" || continue
        [[ "$cwd" == "$ROOT" || "$cwd" == "$ROOT/"* ]] || continue
        arguments=()
        mapfile -d '' -t arguments < "$process/cmdline" 2>/dev/null || continue
        ((${#arguments[@]})) || continue
        executable="${arguments[0]##*/}"
        matched=false
        app=false
        if [[ "$SERVICE" == backend && ( "$executable" == python* || "$executable" == uvicorn ) ]]; then
            for argument in "${arguments[@]}"; do
                [[ "$argument" == uvicorn || "$argument" == */uvicorn ]] && matched=true
                [[ "$argument" == app.main:app ]] && app=true
            done
            if [[ "$matched" == true && "$app" == true ]]; then
                echo "${process##*/}"
            fi
        elif [[ "$SERVICE" == frontend && ( "$executable" == node || "$executable" == nodejs ) ]]; then
            for argument in "${arguments[@]}"; do
                if [[ "$argument" == */vite/bin/vite.js || "$argument" == */.bin/vite ]]; then
                    echo "${process##*/}"
                    break
                fi
            done
        fi
    done
}

alive() {
    local stat rest
    [[ -r "/proc/$1/stat" ]] || return 1
    stat="$(< "/proc/$1/stat")" || return 1
    rest="${stat##*) }"
    [[ "$rest" != Z\ * && "$rest" != X\ * ]]
}

# Save descendants before stopping reload supervisors or Vite's helper processes.
collect_tree() {
    local pid="$1" child
    local -a children=()
    targets+=("$pid")
    if [[ -r "/proc/$pid/task/$pid/children" ]]; then
        read -r -a children < "/proc/$pid/task/$pid/children" || true
        for child in "${children[@]}"; do collect_tree "$child"; done
    fi
}

stop_servers() {
    local pid attempt running
    local -a servers=() targets=()
    mapfile -t servers < <(find_servers)
    if ((${#servers[@]} == 0)); then
        echo "$SERVICE is stopped."
        return
    fi
    for pid in "${servers[@]}"; do collect_tree "$pid"; done
    for pid in "${targets[@]}"; do kill -TERM "$pid" 2>/dev/null || true; done
    for ((attempt=0; attempt<50; attempt++)); do
        running=false
        for pid in "${targets[@]}"; do alive "$pid" && running=true; done
        [[ "$running" == false ]] && break
        sleep 0.1
    done
    for pid in "${targets[@]}"; do
        if alive "$pid"; then kill -KILL "$pid" 2>/dev/null || true; fi
    done
    echo "Stopped $SERVICE (server PIDs: ${servers[*]})."
}

if [[ "$ACTION" == status ]]; then
    mapfile -t servers < <(find_servers)
    if ((${#servers[@]})); then
        echo "$SERVICE is running (PIDs: ${servers[*]})."
    else
        echo "$SERVICE is stopped."
    fi
    exit 0
fi

if [[ "$ACTION" == stop ]]; then
    stop_servers
    exit 0
fi

if [[ "$SERVICE" == backend ]]; then
    [[ -x "$ROOT/backend/.venv/bin/python" ]] || { echo "Create backend/.venv and install backend/requirements.txt first." >&2; exit 1; }
    port=8000
else
    command -v node >/dev/null || { echo "Install Node.js first." >&2; exit 1; }
    [[ -f "$ROOT/frontend/node_modules/vite/bin/vite.js" ]] || { echo "Run npm ci in frontend/ first." >&2; exit 1; }
    port=5173
fi
command -v ss >/dev/null || { echo "Install iproute2 (ss) to check development ports." >&2; exit 1; }
stop_servers
if [[ -n "$(ss -H -ltn "sport = :$port")" ]]; then
    echo "Port $port is still occupied by another process; it has not been killed." >&2
    exit 1
fi
echo "Starting $SERVICE at http://127.0.0.1:$port (Ctrl+C to stop)."
if [[ "$SERVICE" == backend ]]; then
    cd "$ROOT/backend"
    exec .venv/bin/python -m uvicorn app.main:app --reload --host 127.0.0.1 --port "$port"
else
    cd "$ROOT/frontend"
    exec node node_modules/vite/bin/vite.js --host 127.0.0.1 --port "$port" --strictPort
fi
