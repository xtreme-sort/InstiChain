# Ethereum Ledger

An alternative to the Fabric ledger. Set `INSTICHAIN_LEDGER=ethereum` in
`backend/.env` and the backend writes institutional events to the
`InstiChainLedger` Solidity contract instead of the Fabric chaincode. Nothing else
in the application changes: controllers, signatures, and the
"update PostgreSQL only after the ledger confirms" rule work the same way.

## What goes on-chain

Only a **keccak256 hash** of each signed canonical event document, plus its
request ID, event type, sequence number, block, and timestamp. Names, emails,
award text, and payloads stay in PostgreSQL. The contract enforces the same rules
as the chaincode:

- strictly increasing sequence numbers;
- idempotent request IDs (an identical retry is a no-op, a different event is rejected);
- an appointment or credential can be revoked only once, and only if it was recorded
  (credential IDs are registered by their `ACHIEVEMENT_ISSUED` event);
- only accounts on the contract's writer list can append.

`GET /api/ledger/events` joins each on-chain entry with the PostgreSQL copy and
returns `hash_verified`. If the database row was altered, it reports `false`.

Ed25519 signatures are still verified by the backend; the EVM has no cheap
Ed25519 check. The signature is part of the hashed document.

## Local setup

From the repository root, in separate terminals:

```bash
make ethereum-install    # once
make ethereum-node       # keeps running; the chain lives in memory
make ethereum-deploy     # prints the contract address
```

On a fresh node the address is always
`0x5FbDB2315678afecb367f032d93F642f64180aa3`, which is already in
`backend/.env.example`. Then set `INSTICHAIN_LEDGER=ethereum` and run
`make backend`.

The Hardhat node forgets its chain when it stops. PostgreSQL does not, so after
restarting the node, use a fresh database too (or switch back to Fabric). The
backend detects this: before each write it checks that the latest event recorded
in PostgreSQL is still on-chain, and refuses to write if the chain was reset. For a
persistent or multi-organization chain, run a private network such as Hyperledger
Besu with institute and auditor validators, deploy there, and point
`INSTICHAIN_ETHEREUM_RPC_URL`, `_CHAIN_ID`, and `_PRIVATE_KEY` at it.

## Tests

```bash
make ethereum-test       # Solidity contract tests
```

Java client against a live chain (node running and contract deployed):

```bash
cd backend
INSTICHAIN_ETHEREUM_IT_CONTRACT=0x5FbDB2315678afecb367f032d93F642f64180aa3 \
  ./mvnw test -Dtest=EthereumServiceIT
```

## Files

| File | Purpose |
| --- | --- |
| `contracts/InstiChainLedger.sol` | The ledger contract |
| `test/InstiChainLedger.test.js` | Contract tests |
| `scripts/deploy.js` | Deploys and writes `deployments/<network>.json` |
| `hardhat.config.js` | Compiler and network settings |
| `../backend/.../ledger/EthereumService.java` | Java client (web3j) |
