# Hyperledger Fabric Development Network

InstiChain uses the official Fabric two-organization test network. `Org1MSP`
represents the institute and submits transactions. `Org2MSP` represents an
auditor. The endorsement policy requires both peers for every write, so either
organization can independently retain and verify the ordered ledger.

The Spring Boot API uses the Java Fabric Gateway client directly. There is no
separate Node gateway service.

## Fresh setup

From the repository root:

```bash
make docker
make fabric-install
make fabric-up
```

`make fabric-up` creates channel `instichain` and deploys the Java contract in
`fabric/chaincode` with policy `AND('Org1MSP.peer','Org2MSP.peer')`.

## Upgrade an existing development ledger

If the network already has the former JavaScript chaincode at sequence 1, keep
the ledger. Start or resume it, then execute the upgrade once:

```bash
make fabric-up
make fabric-upgrade
```

This commits version 2, sequence 2 of the same chaincode definition. Its keys and
transaction names are compatible, so existing events remain readable. Do not run
the upgrade twice. A fresh network needs only `make fabric-up`.

## Inspect the ledger

With Fabric and the backend running:

```bash
curl http://127.0.0.1:8000/api/ledger/health
curl 'http://127.0.0.1:8000/api/ledger/events?start_sequence=1&limit=50'
curl http://127.0.0.1:8000/api/ledger/fabric-revocations
```

`make backend` discovers the test network's Org1 certificate, private key, and
TLS certificate. For another deployment, configure the `INSTICHAIN_FABRIC_*`
variables described in `backend/.env.example`.

`make fabric-down` deletes the disposable local network, identities, and ledger.
Do not use it to merely pause work. Leaving Docker and the Fabric containers
running is fine for local development.

The test network runs both organizations on one machine for demonstration. A
production deployment must put their peers, CAs, keys, and administrators under
separate organizational control.
