"use strict";

/**
 * Loads the COMPILED index.js with the firebase-admin / firebase-functions / stripe
 * module boundaries replaced by doubles, and hands back the module-private
 * `runGroupChallengeSettlement`.
 *
 * Why this shape:
 *  - We test the compiled artifact, not a copy, so the tests pin the code that actually
 *    deploys.
 *  - `runGroupChallengeSettlement` is intentionally NOT exported from index.ts. In a
 *    Firebase entry-point module an export carries deploy semantics, and Step 4's
 *    scheduler will call it from inside the same file — it never needs to be public.
 *    So instead of widening the production API for tests, we append a re-export to the
 *    BUILD OUTPUT (lib/ is gitignored) and load that. No production source is touched.
 *  - Mocks are installed by priming require.cache before the module is required, which
 *    needs no test framework and no dependency.
 */

const fs = require("fs");
const path = require("path");
const Module = require("module");
const { FakeFirestore, FV } = require("./fake-firestore");
const { facade: stripeFacade } = require("./fake-stripe");

const LIB = path.join(__dirname, "..", "..", "lib", "index.js");
const TESTABLE = path.join(__dirname, "..", "..", "lib", "index.__testable__.js");

/** Live Firestore double; swapped per test via `setDb`. */
let db = new FakeFirestore();
function setDb(next) {
  db = next;
}

function stub(id, exports) {
  const resolved = require.resolve(id);
  require.cache[resolved] = new Module(resolved, null);
  require.cache[resolved].filename = resolved;
  require.cache[resolved].loaded = true;
  require.cache[resolved].exports = exports;
}

function noop() {}

/** Chainable builder that swallows every trigger definition at module load. */
function triggerBuilder() {
  const b = {};
  b.runWith = () => b;
  b.region = () => b;
  b.https = { onRequest: noop };
  b.pubsub = { schedule: () => ({ onRun: noop, timeZone: () => ({ onRun: noop }) }) };
  b.firestore = { document: () => ({ onCreate: noop, onDelete: noop, onWrite: noop, onUpdate: noop }) };
  return b;
}

function install() {
  const fns = triggerBuilder();
  const logs = [];
  fns.logger = {
    info: (...a) => logs.push(["info", ...a]),
    warn: (...a) => logs.push(["warn", ...a]),
    error: (...a) => logs.push(["error", ...a]),
    debug: (...a) => logs.push(["debug", ...a]),
  };
  fns.config = () => ({});
  stub("firebase-functions/v1", fns);

  const firestoreFn = () => db;
  firestoreFn.FieldValue = FV;
  firestoreFn.Timestamp = {
    fromMillis: (m) => ({ toMillis: () => m }),
    now: () => ({ toMillis: () => Date.now() }),
  };
  stub("firebase-admin", {
    initializeApp: noop,
    firestore: firestoreFn,
    auth: () => ({ verifyIdToken: async () => ({ uid: "test-uid" }) }),
    credential: { applicationDefault: noop },
  });

  function FakeStripeCtor() {
    return stripeFacade;
  }
  stub("stripe", FakeStripeCtor);
  process.env.STRIPE_SECRET_KEY = "sk_test_harness";

  // Re-export the module-private settlement body from the BUILD OUTPUT only.
  if (!fs.existsSync(LIB)) {
    throw new Error(`${LIB} missing — run \`npm run build\` first (npm test does this).`);
  }
  const src = fs.readFileSync(LIB, "utf8");
  for (const name of [
    "runGroupChallengeSettlement",
    "runDueGroupChallengeSettlement",
    "forfeitParticipant",
  ]) {
    if (!new RegExp(`\\basync function ${name}\\b`).test(src)) {
      throw new Error(
        `${name} not found in the build output — it changed shape; ` +
          "update test/helpers/load.js before trusting these tests."
      );
    }
  }
  fs.writeFileSync(
    TESTABLE,
    src +
      "\nmodule.exports.__testonly__ = { runGroupChallengeSettlement, runDueGroupChallengeSettlement, forfeitParticipant };\n",
    "utf8"
  );

  const mod = require(TESTABLE);
  return {
    runGroupChallengeSettlement: mod.__testonly__.runGroupChallengeSettlement,
    runDueGroupChallengeSettlement: mod.__testonly__.runDueGroupChallengeSettlement,
    forfeitParticipant: mod.__testonly__.forfeitParticipant,
    logs,
  };
}

const loaded = install();

module.exports = {
  runGroupChallengeSettlement: loaded.runGroupChallengeSettlement,
  runDueGroupChallengeSettlement: loaded.runDueGroupChallengeSettlement,
  forfeitParticipant: loaded.forfeitParticipant,
  fnLogs: loaded.logs,
  setDb,
};
