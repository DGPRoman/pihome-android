#!/usr/bin/env bash
# Run the hub client's contract tests against a real hub, started here and stopped
# when they finish.
#
#   scripts/contract-test.sh [extra Gradle arguments]
#
# The hub comes from a checkout of pihome-hub: PIHOME_HUB_DIR, or ../pihome-hub
# beside this repository. It is installed into a virtualenv under build/ from its
# hashed lockfile, so the run proves the versions the hub ships with. PYTHON picks
# the interpreter, python3.11 by default when there is one, as the hub's own CI.
#
# Each run gets a hub of its own: new keys, a new database with one admin in it,
# the mock relay backend, loopback only, a port nobody else has. Nothing is read
# from or written to the checkout's .env or the developer's database.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
hub_dir="$(cd "${PIHOME_HUB_DIR:-$root/../pihome-hub}" && pwd)"
python="${PYTHON:-$(command -v python3.11 || command -v python3)}"
venv="$root/build/contract/venv"
config="$root/hub-client/src/contractTest/hub"

work="$(mktemp -d)"
hub_pid=""

finish() {
    status=$?
    if [ -n "$hub_pid" ]; then
        kill "$hub_pid" 2>/dev/null || true
        wait "$hub_pid" 2>/dev/null || true
    fi
    if [ "$status" -ne 0 ] && [ -f "$work/hub.log" ]; then
        echo "--- the hub's log ---" >&2
        tail -n 100 "$work/hub.log" >&2
    fi
    rm -rf "$work"
}
trap finish EXIT

echo "installing the hub from $hub_dir"
if [ ! -x "$venv/bin/python" ]; then
    "$python" -m venv "$venv"
fi
"$venv/bin/pip" install --quiet --disable-pip-version-check --require-hashes \
    --requirement "$hub_dir/requirements/base.txt"
"$venv/bin/pip" install --quiet --disable-pip-version-check --no-deps --editable "$hub_dir"

secret() {
    "$venv/bin/python" -c 'import secrets; print(secrets.token_urlsafe(48))'
}

port="$("$venv/bin/python" -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
admin="contract-admin"
password="$(secret)"
sensor_key="$(secret)"

# Read by both the admin tool and the hub. STATE_DIRECTORY is what a systemd unit
# would set, and would win over nothing here, so it is cleared.
export PIHOME_DATABASE_PATH="$work/hub.db"
unset STATE_DIRECTORY

printf '%s\n' "$password" | "$venv/bin/pihome-hub-admin" create "$admin" --role admin

# From the work directory, so that no .env is found and read.
(
    cd "$work"
    exec env \
        PIHOME_HOST=127.0.0.1 \
        PIHOME_PORT="$port" \
        PIHOME_RELAY_API_KEY="$(secret)" \
        PIHOME_SENSOR_API_KEY="$sensor_key" \
        PIHOME_DEVICE_API_KEY="$(secret)" \
        PIHOME_GPIO_BACKEND=mock \
        PIHOME_RELAY_CONFIG_PATH="$config/relays.yaml" \
        PIHOME_SENSOR_CONFIG_PATH="$config/sensors.yaml" \
        PIHOME_AUTOMATION_CONFIG_PATH="$config/automation.yaml" \
        PIHOME_DEVICE_CONFIG_PATH="$config/devices.yaml" \
        PIHOME_ACCESS_LOG=true \
        "$venv/bin/pihome-hub"
) >"$work/hub.log" 2>&1 &
hub_pid=$!

origin="http://127.0.0.1:$port"
echo "waiting for the hub at $origin"
for _ in $(seq 60); do
    if curl --silent --fail --output /dev/null "$origin/health"; then
        break
    fi
    if ! kill -0 "$hub_pid" 2>/dev/null; then
        echo "the hub stopped before it was ready" >&2
        exit 1
    fi
    sleep 0.5
done
curl --silent --fail --output /dev/null "$origin/health"

PIHOME_CONTRACT_HUB="$origin" \
    PIHOME_CONTRACT_ADMIN="$admin" \
    PIHOME_CONTRACT_PASSWORD="$password" \
    PIHOME_CONTRACT_SENSOR_KEY="$sensor_key" \
    "$root/gradlew" -p "$root" :hub-client:contractTest "$@"
