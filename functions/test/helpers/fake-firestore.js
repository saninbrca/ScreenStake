"use strict";

/**
 * Minimal in-memory Firestore double — only the surface `runGroupChallengeSettlement`
 * (and the module-load path of index.ts) actually touches.
 *
 * Deliberately faithful on the three semantics the settlement code depends on:
 *   - `update()` requires the doc to exist and merges keys LITERALLY (settlement never
 *     uses dotted update paths).
 *   - `set(data, {merge:true})` also merges keys literally — matching the real Admin SDK,
 *     where dots in a `set()` key are NOT field paths. index.ts relies on this for the
 *     `pendingPayouts_completed.<groupId>` bookkeeping key.
 *   - `create()` THROWS if the doc already exists. That is the deterministic-ledger
 *     guarantee behind the one-pendingPayouts-doc-per-group-per-user invariant.
 *
 * Every read and write is appended to `log` so tests can assert ORDER (invariants #1/#28)
 * and not merely final state.
 */

const FV = {
  delete: () => ({ __fv: "delete" }),
  increment: (n) => ({ __fv: "increment", n }),
  serverTimestamp: () => ({ __fv: "serverTimestamp" }),
  arrayUnion: (...v) => ({ __fv: "arrayUnion", v }),
  arrayRemove: (...v) => ({ __fv: "arrayRemove", v }),
};

const SERVER_TS = 1750000000000;

function applyOps(target, data) {
  const out = { ...target };
  for (const [k, v] of Object.entries(data)) {
    if (v && typeof v === "object" && v.__fv) {
      if (v.__fv === "delete") delete out[k];
      else if (v.__fv === "increment") out[k] = (out[k] ?? 0) + v.n;
      else if (v.__fv === "serverTimestamp") out[k] = SERVER_TS;
      else if (v.__fv === "arrayUnion") out[k] = [...(out[k] ?? []), ...v.v];
      else if (v.__fv === "arrayRemove") {
        out[k] = (out[k] ?? []).filter((x) => !v.v.includes(x));
      }
    } else {
      out[k] = v;
    }
  }
  return out;
}

class DocRef {
  constructor(db, path) {
    this.db = db;
    this.path = path;
    this.id = path.split("/").pop();
  }
  collection(name) {
    return new CollRef(this.db, `${this.path}/${name}`);
  }
  _snap() {
    const data = this.db.store.get(this.path);
    return {
      exists: data !== undefined,
      id: this.id,
      ref: this,
      data: () => (data === undefined ? undefined : { ...data }),
    };
  }
  async get() {
    this.db.log.push({ op: "get", path: this.path });
    return this._snap();
  }
  async set(data, opts) {
    this.db.log.push({ op: "set", path: this.path, merge: !!(opts && opts.merge), data });
    const prev = opts && opts.merge ? this.db.store.get(this.path) ?? {} : {};
    this.db.store.set(this.path, applyOps(prev, data));
  }
  async update(data) {
    if (!this.db.store.has(this.path)) {
      throw new Error(`NOT_FOUND: no document to update at ${this.path}`);
    }
    this.db.log.push({ op: "update", path: this.path, data });
    this.db.store.set(this.path, applyOps(this.db.store.get(this.path), data));
  }
  async create(data) {
    if (this.db.store.has(this.path)) {
      throw new Error(`ALREADY_EXISTS: document already exists at ${this.path}`);
    }
    this.db.log.push({ op: "create", path: this.path, data });
    this.db.store.set(this.path, applyOps({}, data));
  }
}

/** Equality-only query — the single shape the schedulers use (`where(f, "==", v)`). */
class Query {
  constructor(db, path, filters) {
    this.db = db;
    this.path = path;
    this.filters = filters;
  }
  where(field, op, value) {
    if (op !== "==") throw new Error(`fake-firestore: only "==" is supported, got "${op}"`);
    return new Query(this.db, this.path, [...this.filters, [field, value]]);
  }
  async get() {
    const prefix = `${this.path}/`;
    const docs = [];
    for (const [p, data] of this.db.store) {
      // Direct children only — a path with a further "/" belongs to a sub-collection.
      if (!p.startsWith(prefix) || p.slice(prefix.length).includes("/")) continue;
      if (!this.filters.every(([f, v]) => data[f] === v)) continue;
      docs.push(new DocRef(this.db, p)._snap());
    }
    this.db.log.push({ op: "query", path: this.path, filters: this.filters, hits: docs.length });
    return { docs, empty: docs.length === 0, size: docs.length };
  }
}

class CollRef extends Query {
  constructor(db, path) {
    super(db, path, []);
  }
  doc(id) {
    return new DocRef(this.db, `${this.path}/${id}`);
  }
}

class FakeFirestore {
  constructor() {
    this.store = new Map();
    this.log = [];
    this.txCount = 0;
    /** Hook: (n, db) => void, fired just BEFORE transaction #n's body runs. */
    this.onTransaction = null;
  }
  collection(name) {
    return new CollRef(this, name);
  }
  async runTransaction(fn) {
    this.txCount += 1;
    const n = this.txCount;
    if (this.onTransaction) this.onTransaction(n, this);
    this.log.push({ op: "tx.begin", n });
    const writes = [];
    const tx = {
      get: async (ref) => {
        this.log.push({ op: "tx.get", path: ref.path, n });
        return ref._snap();
      },
      update: (ref, data) => writes.push(["update", ref, data]),
      set: (ref, data, opts) => writes.push(["set", ref, data, opts]),
    };
    const result = await fn(tx);
    for (const [kind, ref, data, opts] of writes) {
      if (kind === "update") {
        this.log.push({ op: "tx.update", path: ref.path, data, n });
        this.store.set(ref.path, applyOps(this.store.get(ref.path) ?? {}, data));
      } else {
        this.log.push({ op: "tx.set", path: ref.path, data, n });
        const prev = opts && opts.merge ? this.store.get(ref.path) ?? {} : {};
        this.store.set(ref.path, applyOps(prev, data));
      }
    }
    this.log.push({ op: "tx.commit", n });
    return result;
  }

  // ── test conveniences ────────────────────────────────────────────────────────
  seed(path, data) {
    this.store.set(path, data);
    return this;
  }
  read(path) {
    return this.store.get(path);
  }
  ops(filter) {
    return this.log.filter((e) => (filter ? filter.test(e.op) : true));
  }
}

module.exports = { FakeFirestore, FV, SERVER_TS };
