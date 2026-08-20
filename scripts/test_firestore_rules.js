#!/usr/bin/env node
/* eslint-disable no-console */
/**
 * Firestore SECURITY RULES test — runs against the local emulator ONLY.
 *
 * ## Why this exists
 * `firestore.rules` had no test coverage. During the S-02 reserved-username work a proposed
 * clause read `resource.data.username == null` to express "write-once". That looks correct and
 * loads without error — but reading an ABSENT key ERRORS in the rules language rather than
 * evaluating to null, and every account has a users doc BEFORE it has a username. The clause
 * would therefore have denied EVERY profile update on EVERY pre-username account. Loading the
 * ruleset does not catch that; only exercising it does. Hence this file.
 *
 * ## What it does NOT do
 * It never touches production. It talks to 127.0.0.1 and is not wired into the Gradle build —
 * an emulator dependency in the normal unit-test run would be a nuisance. Run it by hand when
 * you change `firestore.rules`, and before any rules deploy.
 *
 * ## Run
 *   firebase emulators:exec --only firestore --project detox-33208 "node scripts/test_firestore_rules.js"
 *
 * Requires the Firebase CLI and a JDK (the Firestore emulator is a Java jar; it self-downloads
 * on first run). Needs no npm install — Node 18+ global fetch only, no Admin SDK, no deps.
 * Exits 0 when every check passes, 1 otherwise, so it can gate a manual pre-deploy step.
 *
 * ## How auth is faked
 * The emulator accepts UNSIGNED JWTs (`alg: none`, empty signature). That is an emulator-only
 * affordance and the reason this needs no service account and no real credentials. `Bearer owner`
 * is the emulator's admin escape hatch — used ONLY to seed fixture state that the rules under
 * test would legitimately forbid a client from creating.
 *
 * ## Adding a case
 * `check(label, status, 'ALLOW' | 'DENY')`. A 2xx counts as ALLOW, anything else as DENY.
 * Assert the ALLOW cases as carefully as the DENY ones: a rule that denies everything is
 * "secure" and useless, and locking out a legitimate path is the failure mode that actually
 * reached production here.
 */

const PROJECT = process.env.GCLOUD_PROJECT || 'detox-33208';
const HOST = process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8080';
const BASE = `http://${HOST}/v1/projects/${PROJECT}/databases/(default)/documents`;
const UID = 'uid-test-1';

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');

/** Unsigned emulator JWT — accepted only by the emulator, never by real Firestore. */
function userToken(uid) {
  const now = Math.floor(Date.now() / 1000);
  return b64({ alg: 'none', kid: '', typ: 'JWT' }) + '.' + b64({
    iss: `https://securetoken.google.com/${PROJECT}`, aud: PROJECT,
    sub: uid, user_id: uid, iat: now, exp: now + 3600, auth_time: now,
    email: 'someone@example.com', email_verified: true,
    firebase: { identities: {}, sign_in_provider: 'password' },
  }) + '.';
}

const asUser = { Authorization: 'Bearer ' + userToken(UID), 'Content-Type': 'application/json' };
const asAdmin = { Authorization: 'Bearer owner', 'Content-Type': 'application/json' };

const S = (v) => ({ stringValue: v });
let pass = 0;
let fail = 0;

/** PATCH with an updateMask = merge/update. WITHOUT a mask it is a full-document REPLACE. */
async function patch(path, fields, headers) {
  const mask = Object.keys(fields).map((k) => `updateMask.fieldPaths=${k}`).join('&');
  const r = await fetch(`${BASE}/${path}?${mask}`, {
    method: 'PATCH', headers, body: JSON.stringify({ fields }),
  });
  return r.status;
}

async function create(coll, id, fields, headers) {
  const r = await fetch(`${BASE}/${coll}?documentId=${encodeURIComponent(id)}`, {
    method: 'POST', headers, body: JSON.stringify({ fields }),
  });
  return r.status;
}

function check(label, status, want) {
  const allowed = status >= 200 && status < 300;
  const ok = allowed === (want === 'ALLOW');
  console.log(`${ok ? '  PASS' : '  FAIL'}  ${label}  ->  ${allowed ? 'ALLOW' : 'DENY'} (want ${want}, http ${status})`);
  if (ok) pass++; else fail++;
}

(async () => {
  // ── users/{uid}.username is WRITE-ONCE (S-02 bypass path a) ────────────────────
  // The usernames/{name} rule guards the uniqueness registry, but this mirror field is
  // what the Profile screen renders — without the write-once clause a modified client
  // could set it to 'admin' and never touch the registry at all.
  console.log('\n-- users/{uid}.username write-once --');

  // Seed as admin: a real account has a users doc BEFORE it has a username
  // (createUserDocument writes email/createdAt at registration; saveUsername adds the
  // handle later). This fixture is the exact shape the naive clause would have broken.
  await patch(`users/${UID}`, { email: S('someone@example.com') }, asAdmin);

  check('unrelated field update, no username yet',
    await patch(`users/${UID}`, { lastSeenAt: S('1') }, asUser), 'ALLOW');
  check('first username write',
    await patch(`users/${UID}`, { username: S('sanin_b') }, asUser), 'ALLOW');
  check('unrelated field update AFTER username set',
    await patch(`users/${UID}`, { lastSeenAt: S('2') }, asUser), 'ALLOW');
  check('re-send the SAME username (idempotent retry)',
    await patch(`users/${UID}`, { username: S('sanin_b') }, asUser), 'ALLOW');
  check('CHANGE username to admin  <-- bypass path (a)',
    await patch(`users/${UID}`, { username: S('admin') }, asUser), 'DENY');
  check('CHANGE username to any other value',
    await patch(`users/${UID}`, { username: S('somethingelse') }, asUser), 'DENY');
  // No updateMask = full-document REPLACE, so an empty body drops username entirely.
  check('full-doc overwrite that drops username',
    await patch(`users/${UID}`, {}, asUser), 'DENY');
  const del = await fetch(`${BASE}/users/${UID}?updateMask.fieldPaths=username`, {
    method: 'PATCH', headers: asUser, body: JSON.stringify({ fields: {} }),
  });
  check('DELETE username via empty-value mask', del.status, 'DENY');
  // displayName is deliberately NOT locked: createUserDocument() rewrites it on every
  // Google sign-in, so a write-once clause there would lock out returning users.
  check('displayName still freely writable (NOT locked, by design)',
    await patch(`users/${UID}`, { displayName: S('whatever') }, asUser), 'ALLOW');

  // ── usernames/{id}: reserved list + canonical form + uniqueness ────────────────
  // Keep this list identical to ReservedUsernames.ENTRIES (a Kotlin unit test asserts
  // the Kotlin copy matches firestore.rules; this asserts the rules actually enforce it).
  console.log('\n-- usernames/{id} reserved + canonical form --');
  const RESERVED = [
    'admin', 'administrator', 'root', 'support', 'help', 'staff', 'team', 'mod', 'moderator',
    'official', 'system', 'security', 'abuse', 'info', 'contact', 'billing', 'payment', 'stripe',
    'finite', 'finiteapp', 'finite_app', 'finite_official', 'api', 'null', 'undefined', 'bot',
    'test', 'me', 'you', 'everyone', 'here', 'all',
  ];
  let reservedDenied = 0;
  for (const n of RESERVED) {
    const st = await create('usernames', n, { uid: S(UID) }, asUser);
    if (!(st >= 200 && st < 300)) reservedDenied++;
    else console.log(`  FAIL  reserved '${n}' was ALLOWED (http ${st})`);
  }
  check(`all ${RESERVED.length} reserved names denied`,
    reservedDenied === RESERVED.length ? 403 : 200, 'DENY');

  // The doc id is lowercased by the CLIENT, so without the ^[a-z0-9_]{3,20}$ guard a
  // modified client could claim 'Admin' — a different doc id that is neither in the
  // reserved list nor colliding with 'admin'.
  check("uppercase 'Admin' (case bypass)", await create('usernames', 'Admin', { uid: S(UID) }, asUser), 'DENY');
  check("mixed-case 'Sanin' (canonical form)", await create('usernames', 'Sanin', { uid: S(UID) }, asUser), 'DENY');
  check("too short 'ab'", await create('usernames', 'ab', { uid: S(UID) }, asUser), 'DENY');
  check("bad charset 'a-b-c'", await create('usernames', 'a-b-c', { uid: S(UID) }, asUser), 'DENY');
  check('21 chars', await create('usernames', 'a'.repeat(21), { uid: S(UID) }, asUser), 'DENY');
  check("free normal handle 'sanin_b'", await create('usernames', 'sanin_b', { uid: S(UID) }, asUser), 'ALLOW');
  check('same handle again (uniqueness)', await create('usernames', 'sanin_b', { uid: S('other') }, asUser), 'DENY');
  check('claiming for a DIFFERENT uid (IDOR guard)',
    await create('usernames', 'free_name', { uid: S('someone-else') }, asUser), 'DENY');
  check('20 chars, valid', await create('usernames', 'a'.repeat(20), { uid: S(UID) }, asUser), 'ALLOW');

  console.log(`\n${fail === 0 ? 'ALL RULES CHECKS PASSED' : 'RULES CHECKS FAILED'} — ${pass} passed, ${fail} failed\n`);
  process.exit(fail === 0 ? 0 : 1);
})();
