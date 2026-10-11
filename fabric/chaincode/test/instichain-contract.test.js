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
        getStateByRange: async (start, end) => {
          const matches = [...state.entries()]
            .filter(([key]) => key >= start && key < end)
            .sort(([left], [right]) => (left < right ? -1 : 1));
          let index = 0;
          return {
            next: async () => {
              const entry = matches[index++];
              return { value: entry ? { value: entry[1] } : undefined, done: index >= matches.length };
            },
            close: async () => {},
          };
        },
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

const appointmentId = '2f9c7a4e-91d2-4c6b-ae31-7f1b0c5d8e62';
const grant = {
  ...request,
  request_id: appointmentId,
  event_type: 'ADVISOR_APPOINTED',
  object_ids: { club_id: request.object_ids.club_id, appointment_id: appointmentId },
  payload: { position: 'faculty_advisor' },
};
const revocation = {
  ...request,
  request_id: '7c2d5b81-3ae4-4f90-b6c7-1d8e2f0a4593',
  event_type: 'ROLE_REVOKED',
  object_ids: { club_id: request.object_ids.club_id, appointment_id: appointmentId },
  payload: { position: 'faculty_advisor', reason: 'Advisor left the institute' },
};

test('indexes a revocation and reports it for the revoked subject', async () => {
  const { ctx } = context();
  const contract = new InstiChainContract();
  await contract.AppendEvent(ctx, JSON.stringify(grant));
  assert.equal(JSON.parse(await contract.GetRevocation(ctx, appointmentId)).revoked, false);
  const event = JSON.parse(await contract.AppendEvent(ctx, JSON.stringify(revocation)));
  const status = JSON.parse(await contract.GetRevocation(ctx, appointmentId));
  assert.equal(status.revoked, true);
  assert.equal(status.event_type, 'ROLE_REVOKED');
  assert.equal(status.request_id, event.request_id);
  const listed = JSON.parse(await contract.GetRevocations(ctx));
  assert.equal(listed.length, 1);
  assert.equal(listed[0].subject_id, appointmentId);
});

test('rejects revoking an unknown or already revoked subject', async () => {
  const { ctx } = context();
  const contract = new InstiChainContract();
  await assert.rejects(contract.AppendEvent(ctx, JSON.stringify(revocation)), /no granting event/);
  await contract.AppendEvent(ctx, JSON.stringify(grant));
  await contract.AppendEvent(ctx, JSON.stringify(revocation));
  await assert.rejects(
    contract.AppendEvent(ctx, JSON.stringify({ ...revocation, request_id: '5a1e9d73-62bc-4807-9e4f-3b0a7c2d1856' })),
    /already revoked/,
  );
});

test('rejects a revocation without a subject ID', async () => {
  const { ctx } = context();
  await assert.rejects(
    new InstiChainContract().AppendEvent(ctx, JSON.stringify({
      ...revocation, object_ids: { club_id: request.object_ids.club_id },
    })),
    /requires object_ids.appointment_id/,
  );
});
