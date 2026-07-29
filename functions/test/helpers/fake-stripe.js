"use strict";

/**
 * Stripe double. Records every call, in order, into a shared log so tests can pin
 * SEQUENCE (capture-before-refund, Stripe-outside-the-transaction) and not just totals.
 *
 * `getStripe()` in index.ts memoises its client in a module-scoped `_stripe`, and the
 * module is loaded once per test process — so the constructor hands back a stable facade
 * that resolves the live implementation on every call. Each test installs its own via
 * `setStripe()`.
 */

let impl = null;

function setStripe(next) {
  impl = next;
}

/** The facade handed to index.ts, wired once at module load. */
const facade = {
  paymentIntents: {
    retrieve: (...a) => impl.paymentIntents.retrieve(...a),
    capture: (...a) => impl.paymentIntents.capture(...a),
    cancel: (...a) => impl.paymentIntents.cancel(...a),
  },
  refunds: { create: (...a) => impl.refunds.create(...a) },
  transfers: { create: (...a) => impl.transfers.create(...a) },
  accounts: { retrieve: (...a) => impl.accounts.retrieve(...a) },
};

/**
 * @param cfg.status        map paymentIntentId -> PI status (default "requires_capture")
 * @param cfg.failRefund    Set of paymentIntentIds whose refunds.create rejects
 * @param cfg.failTransfer  Set of destination account ids whose transfers.create rejects
 * @param cfg.accounts      map accountId -> { payouts_enabled }
 * @param cfg.log           shared ordered log (shared with the Firestore double)
 */
function makeStripe(cfg = {}) {
  const log = cfg.log ?? [];
  const status = cfg.status ?? {};
  const failRefund = cfg.failRefund ?? new Set();
  const failTransfer = cfg.failTransfer ?? new Set();
  const accounts = cfg.accounts ?? {};

  return {
    log,
    paymentIntents: {
      async retrieve(id) {
        log.push({ op: "stripe.pi.retrieve", id });
        return { id, status: status[id] ?? "requires_capture", amount_received: 0 };
      },
      async capture(id) {
        log.push({ op: "stripe.pi.capture", id });
        status[id] = "succeeded";
        return { id, status: "succeeded", amount_received: 0 };
      },
      async cancel(id) {
        log.push({ op: "stripe.pi.cancel", id });
        status[id] = "canceled";
        return { id, status: "canceled" };
      },
    },
    refunds: {
      async create(params, opts) {
        const pi = params.payment_intent;
        log.push({
          op: "stripe.refund.create",
          pi,
          amount: params.amount,
          idempotencyKey: opts && opts.idempotencyKey,
        });
        if (failRefund.has(pi)) throw new Error(`refund failed for ${pi}`);
        return { id: `re_${pi}` };
      },
    },
    transfers: {
      async create(params, opts) {
        log.push({
          op: "stripe.transfer.create",
          amount: params.amount,
          destination: params.destination,
          idempotencyKey: opts && opts.idempotencyKey,
        });
        if (failTransfer.has(params.destination)) {
          throw new Error(`transfer failed for ${params.destination}`);
        }
        return { id: "tr_1" };
      },
    },
    accounts: {
      async retrieve(id) {
        log.push({ op: "stripe.account.retrieve", id });
        return { id, payouts_enabled: accounts[id]?.payouts_enabled ?? true };
      },
    },
  };
}

module.exports = { facade, setStripe, makeStripe };
