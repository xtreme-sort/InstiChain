'use strict';

const { Contract } = require('fabric-contract-api');

const EVENT_PREFIX = 'event:';
const HEAD_KEY = 'ledger:head';
const EVENT_TYPE = /^[A-Z][A-Z0-9_]{0,63}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
}

function parseObject(value, label) {
  let parsed;
  try {
    parsed = JSON.parse(value);
  } catch {
    throw new Error(`${label} must be valid JSON`);
  }
  if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') {
    throw new Error(`${label} must be a JSON object`);
  }
  return parsed;
}

function timestampToIso(timestamp) {
  const seconds = Number(timestamp.seconds.toString());
  return new Date(seconds * 1000 + Math.floor(timestamp.nanos / 1_000_000)).toISOString();
}

class InstiChainContract extends Contract {
  async AppendEvent(ctx, requestJson) {
    if (ctx.clientIdentity.getMSPID() !== 'Org1MSP') {
      throw new Error('Only an InstituteMSP identity may append events');
    }
    const request = parseObject(requestJson, 'request');
    const required = ['request_id', 'event_type', 'object_ids', 'payload', 'actor_id', 'actor_key_id'];
    for (const field of required) {
      if (request[field] === undefined || request[field] === null) {
        throw new Error(`request.${field} is required`);
      }
    }
    if (!UUID.test(request.request_id) || !UUID.test(request.actor_id) || !UUID.test(request.actor_key_id)) {
      throw new Error('request IDs must be lowercase UUIDs');
    }
    if (!EVENT_TYPE.test(request.event_type)) {
      throw new Error('event_type must be uppercase snake case');
    }
    if (Array.isArray(request.object_ids) || request.object_ids === null || typeof request.object_ids !== 'object' ||
        Array.isArray(request.payload) || request.payload === null || typeof request.payload !== 'object') {
      throw new Error('object_ids and payload must be JSON objects');
    }

    const key = EVENT_PREFIX + request.request_id;
    const existingBytes = await ctx.stub.getState(key);
    if (existingBytes && existingBytes.length > 0) {
      const existing = JSON.parse(existingBytes.toString('utf8'));
      const comparable = {
        request_id: existing.request_id,
        event_type: existing.event_type,
        object_ids: existing.object_ids,
        payload: existing.payload,
        actor_id: existing.actor_id,
        actor_key_id: existing.actor_key_id,
        signature: existing.signature || null,
      };
      const incoming = {
        request_id: request.request_id,
        event_type: request.event_type,
        object_ids: request.object_ids,
        payload: request.payload,
        actor_id: request.actor_id,
        actor_key_id: request.actor_key_id,
        signature: request.signature || null,
      };
      if (JSON.stringify(canonical(comparable)) !== JSON.stringify(canonical(incoming))) {
        throw new Error(`request_id ${request.request_id} already contains a different event`);
      }
      return JSON.stringify(existing);
    }

    const headBytes = await ctx.stub.getState(HEAD_KEY);
    const previous = headBytes && headBytes.length > 0 ? JSON.parse(headBytes.toString('utf8')) : null;
    const event = {
      schema_version: 1,
      sequence: previous ? previous.sequence + 1 : 1,
      request_id: request.request_id,
      event_type: request.event_type,
      object_ids: request.object_ids,
      payload: request.payload,
      actor_id: request.actor_id,
      actor_key_id: request.actor_key_id,
      signature: request.signature || null,
      fabric_transaction_id: ctx.stub.getTxID(),
      committed_at: timestampToIso(ctx.stub.getTxTimestamp()),
      submitting_msp: ctx.clientIdentity.getMSPID(),
    };
    const bytes = Buffer.from(JSON.stringify(event));
    await ctx.stub.putState(key, bytes);
    await ctx.stub.putState(`sequence:${String(event.sequence).padStart(20, '0')}`, bytes);
    await ctx.stub.putState(HEAD_KEY, Buffer.from(JSON.stringify({
      sequence: event.sequence,
      request_id: event.request_id,
      fabric_transaction_id: event.fabric_transaction_id,
    })));
    ctx.stub.setEvent('InstiChainEvent', bytes);
    return JSON.stringify(event);
  }

  async GetEvent(ctx, requestId) {
    if (!UUID.test(requestId)) throw new Error('requestId must be a lowercase UUID');
    const bytes = await ctx.stub.getState(EVENT_PREFIX + requestId);
    if (!bytes || bytes.length === 0) throw new Error(`event ${requestId} does not exist`);
    return bytes.toString('utf8');
  }

  async GetHead(ctx) {
    const bytes = await ctx.stub.getState(HEAD_KEY);
    return bytes && bytes.length > 0 ? bytes.toString('utf8') : JSON.stringify({ sequence: 0 });
  }

  async GetEvents(ctx, startSequence, limit) {
    const start = Math.max(1, Number.parseInt(startSequence, 10) || 1);
    const count = Math.min(200, Math.max(1, Number.parseInt(limit, 10) || 50));
    const end = start + count;
    const iterator = await ctx.stub.getStateByRange(
      `sequence:${String(start).padStart(20, '0')}`,
      `sequence:${String(end).padStart(20, '0')}`,
    );
    const events = [];
    for (;;) {
      const item = await iterator.next();
      if (item.value) events.push(JSON.parse(item.value.value.toString('utf8')));
      if (item.done) break;
    }
    await iterator.close();
    return JSON.stringify(events);
  }
}

module.exports = InstiChainContract;
