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

network_running() {
    [[ "$(docker inspect --format '{{.State.Running}}' peer0.org1.example.com 2>/dev/null || true)" == true &&
       "$(docker inspect --format '{{.State.Running}}' peer0.org2.example.com 2>/dev/null || true)" == true &&
       "$(docker inspect --format '{{.State.Running}}' orderer.example.com 2>/dev/null || true)" == true ]]
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
        [[ -x "$ROOT/fabric/chaincode/gradlew" ]] || { echo "The Java chaincode Gradle wrapper is missing." >&2; exit 1; }
        if network_running; then
            echo "Fabric network is already running; the existing ledger was preserved."
            echo "If it still runs JavaScript chaincode, run 'make fabric-upgrade' once."
            exit 0
        fi
        cd "$NETWORK"
        if [[ -f "$NETWORK/channel-artifacts/instichain.block" ]]; then
            ./network.sh up -ca
            echo "Existing Fabric network was resumed; its ledger was preserved."
            echo "Run 'make fabric-upgrade' once to replace JavaScript chaincode with Java."
            exit 0
        fi
        ./network.sh up createChannel -ca -c instichain
        rm -f "$NETWORK/instichain.tar.gz"
        ./network.sh deployCC -c instichain -ccn instichain \
            -ccp "$ROOT/fabric/chaincode" -ccl java \
            -ccep "AND('Org1MSP.peer','Org2MSP.peer')"
        echo "Fabric network and Java chaincode are ready."
        ;;
    upgrade)
        require_docker
        [[ -x "$NETWORK/network.sh" ]] || { echo "Run 'make fabric-install' first." >&2; exit 1; }
        [[ -x "$ROOT/fabric/chaincode/gradlew" ]] || { echo "The Java chaincode Gradle wrapper is missing." >&2; exit 1; }
        cd "$NETWORK"
        if ! network_running; then
            if [[ ! -f "$NETWORK/channel-artifacts/instichain.block" ]]; then
                echo "No existing instichain network was found. Run 'make fabric-up' for a fresh deployment." >&2
                exit 1
            fi
            echo "Resuming the existing Fabric network before upgrading chaincode..."
            ./network.sh up -ca
        fi
        network_running || { echo "Fabric peers did not start; run 'make status' and inspect Docker." >&2; exit 1; }
        rm -f "$NETWORK/instichain.tar.gz"
        ./network.sh deployCC -c instichain -ccn instichain \
            -ccp "$ROOT/fabric/chaincode" -ccl java -ccv 2.0 -ccs 2 \
            -ccep "AND('Org1MSP.peer','Org2MSP.peer')"
        echo "The instichain definition now runs Java chaincode; ledger state was preserved."
        ;;
    down)
        require_docker
        [[ -x "$NETWORK/network.sh" ]] || { echo "Fabric test network is not installed."; exit 0; }
        cd "$NETWORK"
        ./network.sh down
        ;;
    *)
        echo "Usage: bash scripts/fabric.sh {install|up|upgrade|down}" >&2
        exit 2
        ;;
esac
