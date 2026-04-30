import * as functions from "firebase-functions/v1";
import * as admin from "firebase-admin";
import Stripe from "stripe";

admin.initializeApp();

const REGION = "us-central1";

// ── Stripe ─────────────────────────────────────────────────────────────────────

let _stripe: Stripe | null = null;

function getStripe(): Stripe {
  if (!_stripe) {
    const key = process.env.STRIPE_SECRET_KEY;
    if (!key) throw new Error("STRIPE_SECRET_KEY is not configured.");
    _stripe = new Stripe(key, { apiVersion: "2023-10-16" });
  }
  return _stripe;
}

// ── Auth helper ────────────────────────────────────────────────────────────────

class HttpError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

async function requireAuth(req: functions.https.Request): Promise<string> {
  const authHeader = req.headers.authorization;
  if (!authHeader?.startsWith("Bearer ")) {
    throw new HttpError(401, "Missing Authorization: Bearer <token>");
  }
  try {
    const decoded = await admin.auth().verifyIdToken(authHeader.slice(7));
    return decoded.uid;
  } catch (e) {
    if (e instanceof HttpError) throw e;
    throw new HttpError(401, "Invalid or expired ID token.");
  }
}

function handleError(tag: string, e: unknown, res: functions.Response): void {
  if (e instanceof HttpError) {
    res.status(e.status).json({ error: e.message });
  } else {
    functions.logger.error(`${tag} error`, e);
    res.status(500).json({ error: "Internal server error." });
  }
}

// ── createPaymentIntent ────────────────────────────────────────────────────────

export const createPaymentIntent = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const { amountCents, durationDays, challengeId } = req.body as {
      amountCents: number;
      durationDays: number;
      challengeId: string;
    };

    if (!amountCents || amountCents < 500) throw new HttpError(400, "Minimum amount is €5.00.");
    if (!durationDays || durationDays < 1) throw new HttpError(400, "Duration must be at least 1 day.");

    const isImmediateCapture = durationDays > 7;
    const customerId = await getOrCreateStripeCustomer(userId);

    const paymentIntent = await getStripe().paymentIntents.create({
      amount: amountCents,
      currency: "eur",
      customer: customerId,
      capture_method: isImmediateCapture ? "automatic" : "manual",
      metadata: { userId, challengeId, durationDays: durationDays.toString() },
      description: `Detox Hard Mode challenge — ${challengeId}`,
    });

    await admin.firestore()
      .collection("users").doc(userId)
      .collection("challenges").doc(challengeId)
      .set({ stripePaymentIntentId: paymentIntent.id, stripeCustomerId: customerId }, { merge: true });

    res.json({ paymentIntentId: paymentIntent.id, clientSecret: paymentIntent.client_secret, isImmediateCapture });
  } catch (e) { handleError("createPaymentIntent", e, res); }
});

// ── capturePayment ─────────────────────────────────────────────────────────────

export const capturePayment = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const { paymentIntentId } = req.body as { paymentIntentId: string };
    if (!paymentIntentId) throw new HttpError(400, "paymentIntentId is required.");

    const paymentIntent = await getStripe().paymentIntents.capture(paymentIntentId);

    await admin.firestore()
      .collection("users").doc(userId)
      .collection("paymentCaptures").add({
        paymentIntentId,
        amountCaptured: paymentIntent.amount_received,
        capturedAt: admin.firestore.FieldValue.serverTimestamp(),
      });

    res.json({ success: true });
  } catch (e) { handleError("capturePayment", e, res); }
});

// ── cancelOrRefundPayment ──────────────────────────────────────────────────────

export const cancelOrRefundPayment = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    await requireAuth(req);
    const { paymentIntentId, wasImmediate } = req.body as {
      paymentIntentId: string;
      wasImmediate: boolean;
    };
    if (!paymentIntentId) throw new HttpError(400, "paymentIntentId is required.");

    if (wasImmediate) {
      await getStripe().refunds.create({ payment_intent: paymentIntentId });
    } else {
      await getStripe().paymentIntents.cancel(paymentIntentId);
    }

    res.json({ success: true });
  } catch (e) { handleError("cancelOrRefundPayment", e, res); }
});

// ── createGroupChallenge ───────────────────────────────────────────────────────

export const createGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const { groupId, code, groupData } = req.body as {
      groupId: string;
      code: string;
      groupData: Record<string, unknown>;
    };

    if (!groupId || !code || !groupData) throw new HttpError(400, "groupId, code, and groupData are required.");
    if (groupData["creatorUserId"] !== userId) throw new HttpError(403, "creatorUserId must match the authenticated user.");

    const db = admin.firestore();

    let finalCode = code.toUpperCase();
    const existing = await db.collection("groupChallenges").where("code", "==", finalCode).limit(1).get();
    if (!existing.empty) {
      const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
      finalCode = Array.from({ length: 6 }, () => chars[Math.floor(Math.random() * chars.length)]).join("");
    }

    const buyInCents: number = (groupData["buyInCents"] as number) ?? 500;
    const durationDays: number = (groupData["durationDays"] as number) ?? 7;
    const isImmediateCapture = durationDays > 7;
    const creatorDisplayName: string = (groupData["creatorDisplayName"] as string) ?? "Anonymous";

    const customerId = await getOrCreateStripeCustomer(userId);
    let paymentIntent: Stripe.PaymentIntent;
    try {
      paymentIntent = await getStripe().paymentIntents.create({
        amount: buyInCents,
        currency: "eur",
        customer: customerId,
        capture_method: isImmediateCapture ? "automatic" : "manual",
        metadata: { userId, groupId, type: "group_challenge_buy_in" },
        description: `Detox Group Challenge buy-in — ${groupId}`,
      });
    } catch (e) {
      functions.logger.error("createGroupChallenge: Stripe error", { groupId, error: e });
      throw new HttpError(500, "Failed to create payment intent for creator.");
    }

    const docData = {
      ...groupData,
      code: finalCode,
      groupId,
      status: "waiting",
      participants: [{
        userId,
        displayName: creatorDisplayName,
        paymentIntentId: paymentIntent.id,
        amountCents: buyInCents,
        status: "active",
        opensToday: 0,
        timeUsedMinutes: 0,
        joinedAt: Date.now(),
      }],
      participantUserIds: [userId],
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    };

    try {
      await db.collection("groupChallenges").doc(groupId).set(docData);
      functions.logger.info("createGroupChallenge: created", { groupId, code: finalCode, userId });
    } catch (e) {
      try { await getStripe().paymentIntents.cancel(paymentIntent.id); } catch (_) { /* best effort */ }
      functions.logger.error("createGroupChallenge: Firestore write failed", { groupId, error: e });
      throw new HttpError(500, "Failed to save group challenge.");
    }

    res.json({ code: finalCode, paymentIntentId: paymentIntent.id, clientSecret: paymentIntent.client_secret });
  } catch (e) { handleError("createGroupChallenge", e, res); }
});

// ── joinGroupChallenge ─────────────────────────────────────────────────────────

export const joinGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const { groupId, displayName } = req.body as { groupId: string; displayName: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;
    if (gc["status"] !== "waiting") throw new HttpError(412, "This challenge is no longer accepting players.");

    const participantIds: string[] = gc["participantUserIds"] ?? [];
    if (participantIds.includes(userId)) throw new HttpError(409, "You have already joined this challenge.");
    if (participantIds.length >= (gc["maxParticipants"] ?? 5)) throw new HttpError(429, "This challenge is full.");

    const startDate: number = typeof gc["startDate"] === "number"
      ? gc["startDate"]
      : (gc["startDate"] as admin.firestore.Timestamp)?.toMillis?.() ?? 0;
    if (startDate && startDate <= Date.now()) throw new HttpError(412, "The join window for this challenge has closed.");

    const buyInCents: number = gc["buyInCents"] ?? 500;
    const durationDays: number = gc["durationDays"] ?? 7;
    const isImmediateCapture = durationDays > 7;

    const customerId = await getOrCreateStripeCustomer(userId);
    const paymentIntent = await getStripe().paymentIntents.create({
      amount: buyInCents,
      currency: "eur",
      customer: customerId,
      capture_method: isImmediateCapture ? "automatic" : "manual",
      metadata: { userId, groupId, displayName: displayName ?? "Anonymous", type: "group_challenge_buy_in" },
      description: `Detox Group Challenge buy-in — ${groupId}`,
    });

    await docRef.update({
      participants: admin.firestore.FieldValue.arrayUnion({
        userId,
        displayName: displayName ?? "Anonymous",
        paymentIntentId: paymentIntent.id,
        amountCents: buyInCents,
        status: "active",
        opensToday: 0,
        timeUsedMinutes: 0,
        joinedAt: Date.now(),
      }),
      participantUserIds: admin.firestore.FieldValue.arrayUnion(userId),
    });

    functions.logger.info("joinGroupChallenge: participant added", { groupId, userId });
    res.json({ paymentIntentId: paymentIntent.id, clientSecret: paymentIntent.client_secret, isImmediateCapture });
  } catch (e) { handleError("joinGroupChallenge", e, res); }
});

// ── startGroupChallenge ────────────────────────────────────────────────────────

export const startGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    await requireAuth(req);
    const { groupId } = req.body as { groupId: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;
    if (gc["status"] !== "waiting") {
      functions.logger.info("startGroupChallenge: already status=" + gc["status"], { groupId });
      res.json({ status: gc["status"] });
      return;
    }

    const participants = parseParticipants<{ userId: string; paymentIntentId: string }>(gc["participants"]);
    const isImmediateCapture = (gc["durationDays"] ?? 7) > 7;

    if (participants.length < 2) {
      for (const p of participants) {
        try {
          if (isImmediateCapture) {
            await getStripe().refunds.create({ payment_intent: p.paymentIntentId });
          } else {
            await getStripe().paymentIntents.cancel(p.paymentIntentId);
          }
        } catch (e) {
          functions.logger.error("startGroupChallenge: refund/cancel failed", { groupId, userId: p.userId, error: e });
        }
      }
      await docRef.update({ status: "cancelled" });
      functions.logger.info("startGroupChallenge: cancelled", { groupId, participants: participants.length });
      res.json({ status: "cancelled" });
      return;
    }

    await docRef.update({ status: "active", startDate: Date.now() });
    functions.logger.info("startGroupChallenge: activated", { groupId, participants: participants.length });
    res.json({ status: "active" });
  } catch (e) { handleError("startGroupChallenge", e, res); }
});

// ── cancelGroupChallenge ───────────────────────────────────────────────────────

export const cancelGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const { groupId } = req.body as { groupId: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;
    if (gc["status"] === "cancelled") {
      functions.logger.info("cancelGroupChallenge: already cancelled", { groupId });
      res.json({ status: "cancelled" });
      return;
    }
    if (gc["status"] !== "waiting") {
      throw new HttpError(412, `Cannot cancel a challenge in status '${gc["status"]}'.`);
    }
    if (gc["creatorUserId"] !== userId) {
      throw new HttpError(403, "Only the creator can cancel a group challenge.");
    }

    const participants = parseParticipants<{ userId: string; paymentIntentId: string }>(gc["participants"]);
    const isImmediateCapture = ((gc["durationDays"] as number) ?? 7) > 7;

    for (const p of participants) {
      if (!p.paymentIntentId) continue;
      try {
        if (isImmediateCapture) {
          await getStripe().refunds.create({ payment_intent: p.paymentIntentId });
        } else {
          await getStripe().paymentIntents.cancel(p.paymentIntentId);
        }
        functions.logger.info("cancelGroupChallenge: refunded", { groupId, userId: p.userId });
      } catch (e) {
        functions.logger.error("cancelGroupChallenge: refund/cancel failed", { groupId, userId: p.userId, error: e });
      }
    }

    await docRef.update({ status: "cancelled" });
    functions.logger.info("cancelGroupChallenge: cancelled", { groupId, participants: participants.length });
    res.json({ status: "cancelled" });
  } catch (e) { handleError("cancelGroupChallenge", e, res); }
});

// ── failParticipant ────────────────────────────────────────────────────────────

export const failParticipant = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    await requireAuth(req);
    const { groupId, userId: failedUserId } = req.body as { groupId: string; userId: string };
    if (!groupId || !failedUserId) throw new HttpError(400, "groupId and userId are required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;
    const participants = parseParticipants(gc["participants"]);
    const isImmediateCapture = ((gc["durationDays"] as number) ?? 7) > 7;

    const updatedParticipants = participants.map((p) =>
      p["userId"] === failedUserId ? { ...p, status: "failed", failedAt: Date.now() } : p
    );

    const failedParticipant = participants.find((p) => p["userId"] === failedUserId);
    if (failedParticipant?.["paymentIntentId"] && !isImmediateCapture) {
      try {
        const pi = await getStripe().paymentIntents.retrieve(failedParticipant["paymentIntentId"] as string);
        if (pi.status === "requires_capture") {
          await getStripe().paymentIntents.capture(pi.id);
          functions.logger.info("failParticipant: payment captured", { groupId, userId: failedUserId });
        }
      } catch (e) {
        functions.logger.error("failParticipant: capture failed", { groupId, userId: failedUserId, error: e });
      }
    }

    await docRef.update({ participants: updatedParticipants });
    functions.logger.info("failParticipant: participant failed", { groupId, userId: failedUserId });
    res.json({ success: true });
  } catch (e) { handleError("failParticipant", e, res); }
});

// ── completeGroupChallenge ─────────────────────────────────────────────────────

export const completeGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    await requireAuth(req);
    const { groupId } = req.body as { groupId: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;

    if (gc["status"] === "completed") {
      functions.logger.info("completeGroupChallenge: already completed", { groupId });
      res.json({ success: true, reason: "already_completed" });
      return;
    }

    const participants = parseParticipants(gc["participants"]);
    const buyInCents: number = (gc["buyInCents"] as number) ?? 0;
    const isImmediateCapture = ((gc["durationDays"] as number) ?? 7) > 7;

    const endDate: number = typeof gc["endDate"] === "number"
      ? gc["endDate"]
      : (gc["endDate"] as admin.firestore.Timestamp)?.toMillis?.() ?? 0;
    const now = Date.now();
    const expired = endDate > 0 && endDate <= now;
    const allFailed = participants.length > 0 && participants.every((p) => p["status"] === "failed");

    functions.logger.info(`Group challenge check: endDate=${endDate} now=${now} expired=${expired}`, { groupId, allFailed });

    if (!expired && !allFailed) {
      functions.logger.info("completeGroupChallenge: endDate not yet reached and not all participants failed — skipping", { groupId, endDate, now });
      res.json({ success: false, reason: "not_expired" });
      return;
    }

    const failedParticipants = participants.filter((p) => p["status"] === "failed");
    const successParticipants = participants.filter((p) => p["status"] === "active");

    // ── Pot calculation ──────────────────────────────────────────────────────
    const failedPot = failedParticipants.reduce((sum, p) => sum + ((p["amountCents"] as number) ?? buyInCents), 0);
    const appFee = Math.floor(failedPot * 0.10);
    const distributablePot = failedPot - appFee;
    const perWinnerBonus = successParticipants.length > 0
      ? Math.floor(distributablePot / successParticipants.length)
      : 0;

    functions.logger.info("completeGroupChallenge: pot calculation", {
      groupId,
      totalPot: participants.length * buyInCents,
      failedPot,
      appFee,
      perWinnerBonus,
      winners: successParticipants.length,
      losers: failedParticipants.length,
    });

    functions.logger.info(
      `Group complete: winners=${successParticipants.length} losers=${failedParticipants.length}`
    );
    functions.logger.info(
      `Total pot: ${participants.length * buyInCents} appFee: ${appFee} perWinner: ${perWinnerBonus}`
    );

    // ── Process winners ──────────────────────────────────────────────────────
    const updatedParticipants = await Promise.all(participants.map(async (p) => {
      if (p["status"] === "failed") {
        return { ...p, status: "failed", payoutStatus: "lost", finalPayout: 0 };
      }

      const userId = p["userId"] as string;
      const pid = p["paymentIntentId"] as string;

      // Refund own buy-in
      if (pid) {
        try {
          if (isImmediateCapture) {
            await getStripe().refunds.create({ payment_intent: pid });
          } else {
            const pi = await getStripe().paymentIntents.retrieve(pid);
            if (pi.status === "requires_capture") await getStripe().paymentIntents.cancel(pid);
          }
        } catch (e) {
          functions.logger.error("completeGroupChallenge: refund failed", { groupId, userId, error: e });
        }
      }

      // No bonus pot to distribute
      if (perWinnerBonus <= 0) {
        return { ...p, status: "success", payoutStatus: "completed", finalPayout: buyInCents };
      }

      // Look up connected account
      let connectedAccountId: string | undefined;
      try {
        const userDoc = await db.collection("users").doc(userId).get();
        connectedAccountId = userDoc.data()?.stripeConnectedAccountId as string | undefined;
      } catch (e) {
        functions.logger.error("completeGroupChallenge: user lookup failed", { groupId, userId, error: e });
      }

      if (!connectedAccountId) {
        // Store pending payout for later
        try {
          await db.collection("users").doc(userId)
            .collection("pendingPayouts").add({
              amount: perWinnerBonus,
              currency: "eur",
              groupId,
              createdAt: admin.firestore.FieldValue.serverTimestamp(),
            });
        } catch (e) {
          functions.logger.error("completeGroupChallenge: pendingPayout write failed", { groupId, userId, error: e });
        }
        return { ...p, status: "success", payoutStatus: "pending_payout", finalPayout: buyInCents + perWinnerBonus };
      }

      // Verify payouts are enabled before transferring
      try {
        const account = await getStripe().accounts.retrieve(connectedAccountId);
        if (!account.payouts_enabled) {
          await db.collection("users").doc(userId)
            .collection("pendingPayouts").add({
              amount: perWinnerBonus,
              currency: "eur",
              groupId,
              createdAt: admin.firestore.FieldValue.serverTimestamp(),
            });
          return { ...p, status: "success", payoutStatus: "pending_payout", finalPayout: buyInCents + perWinnerBonus };
        }

        await getStripe().transfers.create({
          amount: perWinnerBonus,
          currency: "eur",
          destination: connectedAccountId,
          description: `Detox Group Challenge winnings - ${groupId}`,
        });
        functions.logger.info(`Transfer sent to ${userId}: ${perWinnerBonus}`);
        return { ...p, status: "success", payoutStatus: "completed", finalPayout: buyInCents + perWinnerBonus };
      } catch (e) {
        functions.logger.error("completeGroupChallenge: transfer failed", { groupId, userId, error: e });
        // Fall back to pending so money isn't lost
        try {
          await db.collection("users").doc(userId)
            .collection("pendingPayouts").add({
              amount: perWinnerBonus,
              currency: "eur",
              groupId,
              createdAt: admin.firestore.FieldValue.serverTimestamp(),
            });
        } catch (_) { /* best effort */ }
        return { ...p, status: "success", payoutStatus: "pending_payout", finalPayout: buyInCents + perWinnerBonus };
      }
    }));

    await docRef.update({
      status: "completed",
      completedAt: Date.now(),
      appFeeCollected: appFee,
      perWinnerBonus,
      participants: updatedParticipants,
    });

    functions.logger.info("completeGroupChallenge: completed", { groupId });
    res.json({ success: true });
  } catch (e) { handleError("completeGroupChallenge", e, res); }
});

// ── claimPendingPayouts ───────────────────────────────────────────────────────

export const claimPendingPayouts = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const db = admin.firestore();

    const userDoc = await db.collection("users").doc(userId).get();
    const accountId = userDoc.data()?.stripeConnectedAccountId as string | undefined;

    if (!accountId) {
      res.json({ transferred: 0, skipped: 0, reason: "no_account" });
      return;
    }

    const account = await getStripe().accounts.retrieve(accountId);
    if (!account.payouts_enabled) {
      res.json({ transferred: 0, skipped: 0, reason: "onboarding_incomplete" });
      return;
    }

    const pendingSnap = await db.collection("users").doc(userId)
      .collection("pendingPayouts").get();

    if (pendingSnap.empty) {
      res.json({ transferred: 0, skipped: 0, reason: "none_pending" });
      return;
    }

    let transferred = 0;
    let skipped = 0;

    for (const doc of pendingSnap.docs) {
      const payout = doc.data();
      const amount = payout.amount as number;
      const groupId = payout.groupId as string;
      try {
        await getStripe().transfers.create({
          amount,
          currency: "eur",
          destination: accountId,
          description: `Detox Group Challenge winnings - ${groupId}`,
        });
        await doc.ref.delete();
        transferred += amount;
        functions.logger.info(`Transfer sent to ${userId}: ${amount}`);
      } catch (e) {
        functions.logger.error("claimPendingPayouts: transfer failed", { userId, amount, error: e });
        skipped += amount;
      }
    }

    res.json({ transferred, skipped });
  } catch (e) { handleError("claimPendingPayouts", e, res); }
});

// ── createConnectedAccount ────────────────────────────────────────────────────

export const createConnectedAccount = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const db = admin.firestore();
    const userRef = db.collection("users").doc(userId);
    const userDoc = await userRef.get();
    let accountId = userDoc.data()?.stripeConnectedAccountId as string | undefined;

    if (!accountId) {
      const account = await getStripe().accounts.create({
        type: "express",
        country: "AT",
        business_type: "individual",
        capabilities: {
          transfers: { requested: true },
          card_payments: { requested: false },
        },
        settings: {
          payouts: {
            schedule: { interval: "manual" },
          },
        },
      });
      accountId = account.id;
      await userRef.set({ stripeConnectedAccountId: accountId }, { merge: true });
      functions.logger.info(`Connected account created: ${accountId} for user ${userId}`);
    }

    const link = await getStripe().accountLinks.create({
      account: accountId,
      refresh_url: "https://detox-33208.web.app/reauth",
      return_url: "https://detox-33208.web.app/return",
      type: "account_onboarding",
    });

    res.json({ url: link.url, accountId });
  } catch (e) { handleError("createConnectedAccount", e, res); }
});

// ── getConnectedAccountStatus ─────────────────────────────────────────────────

export const getConnectedAccountStatus = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const userDoc = await admin.firestore().collection("users").doc(userId).get();
    const accountId = userDoc.data()?.stripeConnectedAccountId as string | undefined;

    if (!accountId) {
      res.json({ hasAccount: false, chargesEnabled: false, payoutsEnabled: false });
      return;
    }

    const account = await getStripe().accounts.retrieve(accountId);
    res.json({
      hasAccount: true,
      chargesEnabled: account.charges_enabled,
      payoutsEnabled: account.payouts_enabled,
    });
  } catch (e) { handleError("getConnectedAccountStatus", e, res); }
});

// ── Helpers ────────────────────────────────────────────────────────────────────

// Firestore can return the participants field as an Array OR a Map/Object when
// arrayRemove+arrayUnion partial snapshots occur. This normalises both cases.
function parseParticipants<T = Record<string, unknown>>(raw: unknown): T[] {
  if (Array.isArray(raw)) return raw as T[];
  if (raw !== null && typeof raw === "object") return Object.values(raw) as T[];
  return [];
}

async function getOrCreateStripeCustomer(userId: string): Promise<string> {
  const userDoc = await admin.firestore().collection("users").doc(userId).get();
  const existingCustomerId = userDoc.data()?.stripeCustomerId as string | undefined;
  if (existingCustomerId) return existingCustomerId;

  const firebaseUser = await admin.auth().getUser(userId);
  const customer = await getStripe().customers.create({
    email: firebaseUser.email,
    name: firebaseUser.displayName,
    metadata: { firebaseUid: userId },
  });

  await admin.firestore()
    .collection("users").doc(userId)
    .set({ stripeCustomerId: customer.id }, { merge: true });

  return customer.id;
}
