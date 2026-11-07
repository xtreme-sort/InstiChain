#!/usr/bin/env node
// Measures public verification under concurrent load: p50, p95 and error rate from real
// requests against a running stack. Report only numbers this script actually printed.
//
//   LOAD_TOKENS=tokenA,tokenB node scripts/load-verify.mjs
//
// LOAD_TOKENS are share tokens from student share links (create them first, for example
// with scripts/e2e.mjs or the vault). Optional: LOAD_API (default http://127.0.0.1:8000),
// LOAD_CONCURRENCY (default 50) and LOAD_REQUESTS (default 1000).
//
// All requests come from one address, so start the backend with
// INSTICHAIN_PUBLIC_VERIFICATION_PER_MINUTE raised above the request count, or the
// per-client limit will turn most requests into 429s.

const API = process.env.LOAD_API || 'http://127.0.0.1:8000'
const CONCURRENCY = Number(process.env.LOAD_CONCURRENCY || 50)
const TOTAL = Number(process.env.LOAD_REQUESTS || 1000)
const TOKENS = (process.env.LOAD_TOKENS || '').split(',').map((token) => token.trim()).filter(Boolean)

if (TOKENS.length === 0) {
  console.error('Set LOAD_TOKENS to one or more share tokens.')
  process.exit(2)
}

const durations = []
const statuses = new Map()
let next = 0

async function worker() {
  while (next < TOTAL) {
    const token = TOKENS[next % TOKENS.length]
    next += 1
    const started = performance.now()
    let status = 'network_error'
    try {
      const response = await fetch(`${API}/api/public/verification`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }),
      })
      await response.arrayBuffer()
      status = response.status
    } catch { /* counted as network_error */ }
    durations.push(performance.now() - started)
    statuses.set(status, (statuses.get(status) || 0) + 1)
  }
}

function percentile(sorted, fraction) {
  return sorted[Math.min(sorted.length - 1, Math.ceil(fraction * sorted.length) - 1)]
}

const started = performance.now()
await Promise.all(Array.from({ length: CONCURRENCY }, worker))
const elapsed = (performance.now() - started) / 1000
const sorted = [...durations].sort((left, right) => left - right)
const errors = TOTAL - (statuses.get(200) || 0)
const withinTwo = sorted.filter((value) => value <= 2000).length / sorted.length

console.log(`Public verification load test against ${API}`)
console.log(`  requests ${TOTAL}, concurrency ${CONCURRENCY}, tokens ${TOKENS.length}, ${elapsed.toFixed(1)} s`)
console.log(`  throughput ${(TOTAL / elapsed).toFixed(1)} req/s`)
console.log(`  p50 ${percentile(sorted, 0.5).toFixed(0)} ms, p95 ${percentile(sorted, 0.95).toFixed(0)} ms, max ${sorted.at(-1).toFixed(0)} ms`)
console.log(`  error rate ${((errors / TOTAL) * 100).toFixed(2)}%  ${JSON.stringify(Object.fromEntries(statuses))}`)
console.log(`  within 2 s: ${(withinTwo * 100).toFixed(1)}% (design target: 95%)`)
process.exitCode = errors === 0 && withinTwo >= 0.95 ? 0 : 1
