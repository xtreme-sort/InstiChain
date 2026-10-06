# Hyperledger Fabric development network

InstiChain uses Hyperledger Fabric as its authoritative append-only ledger. The
FastAPI service remains the web/API layer, and PostgreSQL remains the store for
accounts, sessions and query projections. New administrator events are committed
to Fabric before their PostgreSQL projections are committed.

The local network is Fabric's official two-organization test network:

- `Org1MSP` represents the institute and is the only organization allowed by the
  smart contract to originate InstiChain events.
- `Org2MSP` represents an independent auditor. The chaincode policy requires an
  endorsement from both organizations for every write.
- Both peers keep independently verifiable copies of the channel ledger. The
  ordering service establishes a common transaction order.

This topology demonstrates distributed validation, but the generated identities
and single local machine are for development only. A production deployment must
place the organizations' peers, CAs, keys and administrators under genuinely
separate control.

## One-time setup

Docker must be running and usable by your current user. From the repository root:

```bash
cd fabric/chaincode && npm install
cd ../gateway && npm install
cd ../..
make fabric-install
cp fabric/.env.example fabric/.env
cp backend/.env.example backend/.env  # only when backend/.env does not exist
```

Generate a bridge token:

```bash
openssl rand -hex 32
```

Put that value in `FABRIC_BRIDGE_TOKEN` in `fabric/.env` and in
`INSTICHAIN_FABRIC_BRIDGE_TOKEN` in `backend/.env`. These files are ignored by
Git. Then apply the database migration:

```bash
cd backend
source .venv/bin/activate
python -m alembic upgrade head
cd ..
```

## Start

Run these in separate terminals:

```bash
make fabric-up
make fabric-gateway
make backend
make frontend
```

The first command creates the `instichain` channel and deploys the JavaScript
chaincode with policy `AND('Org1MSP.peer','Org2MSP.peer')`. The gateway bridge is
bound to `127.0.0.1:3001` and accepts only the shared bearer token.

Check the live ledger through FastAPI:

```bash
curl http://127.0.0.1:8000/api/ledger/health
curl http://127.0.0.1:8000/api/ledger/events
```

Create a club or appoint an advisor from the UI, then repeat the events request.
Each result includes Fabric's transaction ID, submitting MSP and commit time.

Stop and remove the disposable Fabric network with `make fabric-down`. Recreating
it produces new development identities and an empty ledger, so do not use this
command for a network containing data you need.
