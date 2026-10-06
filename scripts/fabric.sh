#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
RUNTIME="$ROOT/fabric/runtime"
SAMPLES="$RUNTIME/fabric-samples"
NETWORK="$SAMPLES/test-network"
ACTION="${1:-}"

require_docker() {
    command -v docker >/dev/null || { echo "Docker is required." >&2; exit 1; }
    docker info >/dev/null 2>&1 || {
        echo "Docker is not running or your user cannot access its socket." >&2
        echo "Start Docker, then run: sudo usermod -aG docker \$USER" >&2
        echo "Log out and back in after changing group membership." >&2
        exit 1
    }
}

case "$ACTION" in
    install)
        require_docker
        command -v curl >/dev/null || { echo "curl is required." >&2; exit 1; }
        mkdir -p "$RUNTIME"
        cd "$RUNTIME"
        curl --fail --location --silent --show-error \
            --output install-fabric.sh \
            https://raw.githubusercontent.com/hyperledger/fabric/release-2.5/scripts/install-fabric.sh
        chmod +x install-fabric.sh
        ./install-fabric.sh --fabric-version 2.5.16 --ca-version 1.5.17 docker binary samples
        ;;
    up)
        require_docker
        [[ -x "$NETWORK/network.sh" ]] || { echo "Run 'make fabric-install' first." >&2; exit 1; }
        [[ -f "$ROOT/fabric/chaincode/package-lock.json" ]] || { echo "Run npm install in fabric/chaincode first." >&2; exit 1; }
        cd "$NETWORK"
        ./network.sh down
        ./network.sh up createChannel -ca -c instichain
        ./network.sh deployCC -c instichain -ccn instichain \
            -ccp "$ROOT/fabric/chaincode" -ccl javascript \
            -ccep "AND('Org1MSP.peer','Org2MSP.peer')"
        echo "Fabric network is ready. Start the bridge with 'make fabric-gateway'."
        ;;
    down)
        require_docker
        [[ -x "$NETWORK/network.sh" ]] || { echo "Fabric test network is not installed."; exit 0; }
        cd "$NETWORK"
        ./network.sh down
        ;;
    gateway)
        [[ -f "$ROOT/fabric/.env" ]] || { echo "Copy fabric/.env.example to fabric/.env and set a random token." >&2; exit 1; }
        [[ -d "$ROOT/fabric/gateway/node_modules" ]] || { echo "Run npm install in fabric/gateway first." >&2; exit 1; }
        [[ -d "$NETWORK/organizations/peerOrganizations/org1.example.com" ]] || { echo "Run 'make fabric-up' first." >&2; exit 1; }
        set -a
        source "$ROOT/fabric/.env"
        set +a
        export FABRIC_CRYPTO_PATH="$NETWORK/organizations/peerOrganizations/org1.example.com"
        cd "$ROOT/fabric/gateway"
        exec npm start
        ;;
    *)
        echo "Usage: bash scripts/fabric.sh {install|up|down|gateway}" >&2
        exit 2
        ;;
esac
