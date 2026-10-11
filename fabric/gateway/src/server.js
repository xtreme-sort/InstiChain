import * as grpc from '@grpc/grpc-js';
import { connect, hash, signers } from '@hyperledger/fabric-gateway';
import { createPrivateKey, timingSafeEqual } from 'node:crypto';
import { promises as fs } from 'node:fs';
import http from 'node:http';
import path from 'node:path';

const env = (name, fallback) => process.env[name] || fallback;
const required = (name) => {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
};
const firstFile = async (directory) => {
  const names = (await fs.readdir(directory)).sort();
  if (!names[0]) throw new Error(`No identity file found in ${directory}`);
  return path.join(directory, names[0]);
};

const host = env('FABRIC_BRIDGE_HOST', '127.0.0.1');
const port = Number(env('FABRIC_BRIDGE_PORT', '3001'));
const token = required('FABRIC_BRIDGE_TOKEN');
const channelName = env('FABRIC_CHANNEL_NAME', 'instichain');
const chaincodeName = env('FABRIC_CHAINCODE_NAME', 'instichain');
const mspId = env('FABRIC_MSP_ID', 'Org1MSP');
const cryptoPath = required('FABRIC_CRYPTO_PATH');
const keyDirectory = env('FABRIC_KEY_DIRECTORY', path.join(cryptoPath, 'users', 'User1@org1.example.com', 'msp', 'keystore'));
const certDirectory = env('FABRIC_CERT_DIRECTORY', path.join(cryptoPath, 'users', 'User1@org1.example.com', 'msp', 'signcerts'));
const tlsCertPath = env('FABRIC_TLS_CERT_PATH', path.join(cryptoPath, 'peers', 'peer0.org1.example.com', 'tls', 'ca.crt'));
const peerEndpoint = env('FABRIC_PEER_ENDPOINT', 'localhost:7051');
const peerHostAlias = env('FABRIC_PEER_HOST_ALIAS', 'peer0.org1.example.com');

const tlsRootCert = await fs.readFile(tlsCertPath);
const client = new grpc.Client(peerEndpoint, grpc.credentials.createSsl(tlsRootCert), {
  'grpc.ssl_target_name_override': peerHostAlias,
});
const credentials = await fs.readFile(await firstFile(certDirectory));
const privateKey = createPrivateKey(await fs.readFile(await firstFile(keyDirectory)));
const gateway = connect({
  client,
  identity: { mspId, credentials },
  signer: signers.newPrivateKeySigner(privateKey),
  hash: hash.sha256,
  evaluateOptions: () => ({ deadline: Date.now() + 5_000 }),
  endorseOptions: () => ({ deadline: Date.now() + 15_000 }),
  submitOptions: () => ({ deadline: Date.now() + 5_000 }),
  commitStatusOptions: () => ({ deadline: Date.now() + 60_000 }),
});
const contract = gateway.getNetwork(channelName).getContract(chaincodeName);
const decoder = new TextDecoder();

function authorized(request) {
  const supplied = request.headers.authorization?.replace(/^Bearer /, '') || '';
  const expected = Buffer.from(token);
  const actual = Buffer.from(supplied);
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

function respond(response, status, body) {
  response.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
  response.end(JSON.stringify(body));
}

async function readBody(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 256 * 1024) throw new Error('Request body is too large');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

const server = http.createServer(async (request, response) => {
  try {
    if (!authorized(request)) return respond(response, 401, { detail: 'Unauthorized' });
    if (request.method === 'GET' && request.url === '/health') {
      const head = JSON.parse(decoder.decode(await contract.evaluateTransaction('GetHead')));
      return respond(response, 200, { status: 'ok', channel: channelName, chaincode: chaincodeName, head });
    }
    if (request.method === 'GET' && request.url === '/revocations') {
      const revocations = JSON.parse(decoder.decode(await contract.evaluateTransaction('GetRevocations')));
      return respond(response, 200, revocations);
    }
    if (request.method === 'GET' && request.url?.startsWith('/revocations/')) {
      const id = decodeURIComponent(request.url.slice('/revocations/'.length));
      const revocation = JSON.parse(decoder.decode(await contract.evaluateTransaction('GetRevocation', id)));
      return respond(response, 200, revocation);
    }
    if (request.method === 'GET' && request.url?.startsWith('/events/')) {
      const id = decodeURIComponent(request.url.slice('/events/'.length));
      const event = JSON.parse(decoder.decode(await contract.evaluateTransaction('GetEvent', id)));
      return respond(response, 200, event);
    }
    if (request.method === 'GET' && request.url?.startsWith('/events?')) {
      const url = new URL(request.url, `http://${host}:${port}`);
      const start = url.searchParams.get('start') || '1';
      const limit = url.searchParams.get('limit') || '50';
      const events = JSON.parse(decoder.decode(
        await contract.evaluateTransaction('GetEvents', start, limit),
      ));
      return respond(response, 200, events);
    }
    if (request.method === 'POST' && request.url === '/transactions') {
      const body = await readBody(request);
      const commit = await contract.submitAsync('AppendEvent', { arguments: [JSON.stringify(body)] });
      const event = JSON.parse(decoder.decode(commit.getResult()));
      const status = await commit.getStatus();
      if (!status.successful) {
        return respond(response, 502, { detail: 'Fabric rejected the transaction', code: Number(status.code) });
      }
      return respond(response, 201, {
        ...event,
        submitted_transaction_id: status.transactionId,
        block_number: status.blockNumber.toString(),
        validation_code: Number(status.code),
      });
    }
    return respond(response, 404, { detail: 'Not found' });
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Fabric operation failed';
    console.error(message);
    return respond(response, 502, { detail: message });
  }
});

const shutdown = () => server.close(() => {
  gateway.close();
  client.close();
  process.exit(0);
});
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
server.listen(port, host, () => console.log(`Fabric bridge listening on http://${host}:${port}`));
