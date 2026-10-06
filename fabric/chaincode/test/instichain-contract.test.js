'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const InstiChainContract = require('../lib/instichain-contract');

function context(msp = 'Org1MSP') {
  const state = new Map();
  let tx = 0;
  return {
    state,
    ctx: {
      clientIdentity: { getMSPID: () => msp },
      stub: {
        getState: async (key) => state.get(key) || Buffer.alloc(0),
        putState: async (key, value) => state.set(key, value),
        getTxID: () => `fabric-tx-${++tx}`,
        getTxTimestamp: () => ({ seconds: { toString: () => '1735689600' }, nanos: 0 }),
        setEvent: () => {},
      },
    },
  };
}

const request = {
  request_id: '109159b2-a4b7-55a5-8f48-52c5d07105d4',
  event_type: 'CLUB_CREATED',
  object_ids: { club_id: '109159b2-a4b7-55a5-8f48-52c5d07105d4' },
  payload: { slug: 'coding', name: 'Coding Club' },
  actor_id: '839b729a-a280-4f2b-8ec6-b4419156fae5',
  actor_key_id: '72b247a5-eae2-40c7-b397-a6f4822bda34',
  signature: 'test-signature',
};

test('appends an event and returns the same event for an idempotent retry', async () => {
  const { ctx, state } = context();
  const contract = new InstiChainContract();
  const first = JSON.parse(await contract.AppendEvent(ctx, JSON.stringify(request)));
  const second = JSON.parse(await contract.AppendEvent(ctx, JSON.stringify(request)));
  assert.equal(first.sequence, 1);
  assert.deepEqual(second, first);
  assert.equal(JSON.parse(state.get('ledger:head')).sequence, 1);
});

test('rejects a changed event with a reused request ID', async () => {
  const { ctx } = context();
  const contract = new InstiChainContract();
  await contract.AppendEvent(ctx, JSON.stringify(request));
  await assert.rejects(
    contract.AppendEvent(ctx, JSON.stringify({ ...request, payload: { slug: 'changed' } })),
    /already contains a different event/,
  );
});

test('rejects writes from a non-institute organization', async () => {
  const { ctx } = context('Org2MSP');
  await assert.rejects(new InstiChainContract().AppendEvent(ctx, JSON.stringify(request)), /InstituteMSP/);
});
