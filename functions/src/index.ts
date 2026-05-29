import * as functions from "firebase-functions/v1";
import * as admin from "firebase-admin";
import Stripe from "stripe";

admin.initializeApp();

const REGION = "us-central1";
const MILLIS_PER_DAY = 86_400_000;

/**
 * Returns 23:59:59.999 of the day that is durationDays days after startMs.
 * durationDays - 1 because startMs already counts as day 1. Mirrors the client
 * DateUtils.endOfDayMillis — closes the "Last Day Loophole".
 */
function endOfDayMillis(startMs: number, durationDays: number): number {
  const d = new Date(startMs);
  d.setDate(d.getDate() + (durationDays - 1));
  d.setHours(23, 59, 59, 999);
  return d.getTime();
}

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

// ── Participant type ───────────────────────────────────────────────────────────

interface Participant {
  userId: string;
  paymentIntentId: string;
  status?: string;
  displayName?: string;
  amountCents?: number;
  payoutStatus?: string;
}

// ── createPaymentIntent ────────────────────────────────────────────────────────

export const createPaymentIntent = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const { amountCents, durationDays, challengeId, isGroupChallenge } = req.body as {
      amountCents: number;
      durationDays: number;
      challengeId: string;
      isGroupChallenge?: boolean;
    };

    if (!amountCents || amountCents < 500) throw new HttpError(400, "Minimum amount is €5.00.");
    if (!durationDays || durationDays < 1) throw new HttpError(400, "Duration must be at least 1 day.");

    // Group Challenges always use manual capture (5-day auth window).
    // Hard Mode keeps the durationDays > 7 → automatic logic unchanged.
    const isImmediateCapture = isGroupChallenge ? false : durationDays > 7;
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
    const verifiedUserId = await requireAuth(req);
    const { paymentIntentId, challengeId, amountCents, partialRefundCents } = req.body as {
      paymentIntentId: string;
      challengeId?: string;
      amountCents?: number;
      partialRefundCents?: number;
    };
    if (!paymentIntentId) throw new HttpError(400, "paymentIntentId is required.");

    const pi = await getStripe().paymentIntents.retrieve(paymentIntentId);
    const fullAmount = pi.amount;
    let refundedAmount: number;

    if (partialRefundCents && partialRefundCents > 0) {
      // Redemption Challenge win: PI already captured, partial refund (60% of original)
      await getStripe().refunds.create({ payment_intent: paymentIntentId, amount: partialRefundCents });
      refundedAmount = partialRefundCents;
    } else if (pi.status === "requires_capture" && amountCents && amountCents < fullAmount) {
      // Hard Mode win with 20% app fee: capture full pre-auth, then refund 80%
      await getStripe().paymentIntents.capture(paymentIntentId);
      await getStripe().refunds.create({ payment_intent: paymentIntentId, amount: amountCents });
      refundedAmount = amountCents;
    } else if (pi.status === "requires_capture") {
      // Full cancel — no fee (e.g. nobody-failed group challenge fallback)
      await getStripe().paymentIntents.cancel(paymentIntentId);
      refundedAmount = fullAmount;
    } else {
      // Already captured: partial or full refund
      if (amountCents && amountCents < fullAmount) {
        await getStripe().refunds.create({ payment_intent: paymentIntentId, amount: amountCents });
        refundedAmount = amountCents;
      } else {
        await getStripe().refunds.create({ payment_intent: paymentIntentId });
        refundedAmount = fullAmount;
      }
    }

    // Update Firestore challenge doc with payout tracking fields
    if (challengeId && verifiedUserId) {
      const db = admin.firestore();
      await db.collection("users").doc(verifiedUserId)
        .collection("challenges").doc(challengeId)
        .set({
          payoutStatus: "refunded",
          payoutAmount: refundedAmount,
          appFeeAmount: fullAmount - refundedAmount,
          payoutDate: Date.now(),
        }, { merge: true });
    }

    res.json({ success: true });
  } catch (e) { handleError("cancelOrRefundPayment", e, res); }
});

// ── createGroupChallenge ───────────────────────────────────────────────────────
// Step 2 of creator join flow: called only after PaymentSheetResult.Completed.
// Accepts the pre-authorized paymentIntentId — does NOT create a new PaymentIntent.

export const createGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const { groupId, code, groupData, paymentIntentId } = req.body as {
      groupId: string;
      code: string;
      groupData: Record<string, unknown>;
      paymentIntentId: string;
    };

    if (!groupId || !code || !groupData || !paymentIntentId) {
      throw new HttpError(400, "groupId, code, groupData, and paymentIntentId are required.");
    }
    if (groupData["creatorUserId"] !== userId) throw new HttpError(403, "creatorUserId must match the authenticated user.");

    const db = admin.firestore();

    let finalCode = code.toUpperCase();
    const existing = await db.collection("groupChallenges").where("code", "==", finalCode).limit(1).get();
    if (!existing.empty) {
      const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
      finalCode = Array.from({ length: 6 }, () => chars[Math.floor(Math.random() * chars.length)]).join("");
    }

    const buyInCents: number = (groupData["buyInCents"] as number) ?? 500;
    const creatorDisplayName: string = (groupData["creatorDisplayName"] as string) ?? "Anonymous";

    const docData = {
      ...groupData,
      code: finalCode,
      groupId,
      status: "waiting",
      authorizationExpiresAt: Date.now() + 5 * MILLIS_PER_DAY,
      participants: [{
        userId,
        displayName: creatorDisplayName,
        paymentIntentId,
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
      functions.logger.error("createGroupChallenge: Firestore write failed", { groupId, error: e });
      throw new HttpError(500, "Failed to save group challenge.");
    }

    res.json({ code: finalCode, paymentIntentId });
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

    const customerId = await getOrCreateStripeCustomer(userId);
    const paymentIntent = await getStripe().paymentIntents.create({
      amount: buyInCents,
      currency: "eur",
      customer: customerId,
      capture_method: "manual",
      metadata: { userId, groupId, displayName: displayName ?? "Anonymous", type: "group_challenge_buy_in" },
      description: `Detox Group Challenge buy-in — ${groupId}`,
    });

    functions.logger.info("joinGroupChallenge: payment intent created", { groupId, userId });
    res.json({ paymentIntentId: paymentIntent.id, clientSecret: paymentIntent.client_secret, isImmediateCapture: false });
  } catch (e) { handleError("joinGroupChallenge", e, res); }
});

// ── confirmGroupJoin ───────────────────────────────────────────────────────────
// Called after PaymentSheetResult.Completed — adds user to participants only
// after Stripe confirms the payment intent is valid.

export const confirmGroupJoin = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const userId = await requireAuth(req);
    const { groupId, paymentIntentId } = req.body as { groupId: string; paymentIntentId: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");
    if (!paymentIntentId) throw new HttpError(400, "paymentIntentId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;

    // Idempotency: user already in — payment was confirmed, just return success.
    const participantIds: string[] = gc["participantUserIds"] ?? [];
    if (participantIds.includes(userId)) {
      functions.logger.info("confirmGroupJoin: already joined (idempotent)", { groupId, userId });
      res.json({ success: true, alreadyJoined: true });
      return;
    }

    if (gc["status"] !== "waiting") throw new HttpError(412, "This challenge is no longer accepting players.");
    if (participantIds.length >= (gc["maxParticipants"] ?? 5)) throw new HttpError(429, "This challenge is full.");

    // Verify the PaymentIntent belongs to this user and group and is paid.
    const paymentIntent = await getStripe().paymentIntents.retrieve(paymentIntentId);
    if (paymentIntent.metadata.userId !== userId) throw new HttpError(403, "Payment intent does not belong to this user.");
    if (paymentIntent.metadata.groupId !== groupId) throw new HttpError(403, "Payment intent does not belong to this group.");

    const validStatuses = ["requires_capture", "succeeded", "processing"];
    if (!validStatuses.includes(paymentIntent.status)) {
      throw new HttpError(402, `Payment not completed. Status: ${paymentIntent.status}`);
    }

    const buyInCents: number = gc["buyInCents"] ?? 500;
    const displayName: string = paymentIntent.metadata.displayName ?? "Anonymous";

    await docRef.update({
      participants: admin.firestore.FieldValue.arrayUnion({
        userId,
        displayName,
        paymentIntentId,
        amountCents: buyInCents,
        status: "active",
        opensToday: 0,
        timeUsedMinutes: 0,
        joinedAt: Date.now(),
      }),
      participantUserIds: admin.firestore.FieldValue.arrayUnion(userId),
    });

    functions.logger.info("confirmGroupJoin: participant added", { groupId, userId });
    res.json({ success: true });
  } catch (e) { handleError("confirmGroupJoin", e, res); }
});

// ── startGroupChallenge ────────────────────────────────────────────────────────

export const startGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const verifiedUserId = await requireAuth(req);
    const { groupId } = req.body as { groupId: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;
    if (gc["creatorUserId"] !== verifiedUserId) {
      throw new HttpError(403, "Only the creator can start this challenge.");
    }
    if (gc["status"] !== "waiting") {
      functions.logger.info("startGroupChallenge: already status=" + gc["status"], { groupId });
      res.json({ status: gc["status"] });
      return;
    }

    const participants = parseParticipants<{ userId: string; displayName: string; paymentIntentId: string }>(gc["participants"]);

    if (participants.length < 2) {
      for (const p of participants) {
        try {
          const pi = await getStripe().paymentIntents.retrieve(p.paymentIntentId);
          if (pi.status === "requires_capture") {
            await getStripe().paymentIntents.cancel(p.paymentIntentId);
          }
        } catch (e) {
          functions.logger.error("startGroupChallenge: cancel failed for <2 participants", { groupId, userId: p.userId, error: e });
        }
      }
      await docRef.update({ status: "cancelled" });
      functions.logger.info("startGroupChallenge: cancelled (< 2 participants)", { groupId, participants: participants.length });
      res.json({ status: "cancelled" });
      return;
    }

    // Pre-flight: all PIs must be requires_capture before we capture any.
    for (const p of participants) {
      const pi = await getStripe().paymentIntents.retrieve(p.paymentIntentId);
      if (pi.status !== "requires_capture") {
        functions.logger.warn("startGroupChallenge: payment not ready", { groupId, userId: p.userId, piStatus: pi.status });
        res.status(400).json({
          error: "payment_not_ready",
          message: `Payment for ${p.displayName} is not ready. Their authorization may have expired.`,
        });
        return;
      }
    }

    // Capture all PIs — money is now charged.
    const captured: string[] = [];
    for (const p of participants) {
      try {
        await getStripe().paymentIntents.capture(p.paymentIntentId);
        captured.push(p.paymentIntentId);
        functions.logger.info("startGroupChallenge: captured PI", { groupId, userId: p.userId });
      } catch (e) {
        functions.logger.error("startGroupChallenge: capture failed mid-loop — rolling back", { groupId, userId: p.userId, error: e });
        for (const piId of captured) {
          try { await getStripe().refunds.create({ payment_intent: piId }); } catch (re) {
            functions.logger.error("startGroupChallenge: rollback refund failed", { piId, error: re });
          }
        }
        res.status(500).json({ error: "capture_failed", message: "Failed to capture a payment. All captured payments have been refunded." });
        return;
      }
    }

    const startDate = Date.now();
    const durationDays: number = (gc["durationDays"] as number) ?? 7;
    const endDate = endOfDayMillis(startDate, durationDays);

    functions.logger.info("startGroupChallenge: setting startDate + endDate", {
      groupId,
      startDate,
      endDate,
      durationDays,
      participants: participants.length,
    });

    await docRef.update({ status: "active", startDate, endDate });
    functions.logger.info("startGroupChallenge: activated", { groupId, participants: participants.length, startDate, endDate });
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

    const participants = parseParticipants<Participant>(gc["participants"]);

    const updatedParticipants = [...participants];
    for (let i = 0; i < updatedParticipants.length; i++) {
      const p = updatedParticipants[i];
      if (!p.paymentIntentId) continue;
      try {
        const pi = await getStripe().paymentIntents.retrieve(p.paymentIntentId);
        if (pi.status === "requires_capture") {
          await getStripe().paymentIntents.cancel(p.paymentIntentId);
          functions.logger.info("cancelGroupChallenge: PI cancelled", { groupId, userId: p.userId });
        } else if (pi.status === "canceled") {
          functions.logger.info("cancelGroupChallenge: PI already cancelled (idempotent)", { groupId, userId: p.userId });
        } else {
          functions.logger.warn("cancelGroupChallenge: unexpected PI status", { groupId, userId: p.userId, piStatus: pi.status });
        }
        updatedParticipants[i] = { ...p, status: "refunded" };
      } catch (e) {
        functions.logger.error("cancelGroupChallenge: cancel failed", { groupId, userId: p.userId, error: e });
      }
    }

    await docRef.update({ status: "cancelled", participants: updatedParticipants });
    functions.logger.info("cancelGroupChallenge: cancelled", { groupId, participants: participants.length });
    res.json({ status: "cancelled" });
  } catch (e) { handleError("cancelGroupChallenge", e, res); }
});

// ── failParticipant ────────────────────────────────────────────────────────────

export const failParticipant = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const verifiedUserId = await requireAuth(req);
    const { groupId, userId: failedUserId } = req.body as { groupId: string; userId: string };
    if (!groupId || !failedUserId) throw new HttpError(400, "groupId and userId are required.");
    if (verifiedUserId !== failedUserId) throw new HttpError(403, "You can only fail yourself.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;
    const participants = parseParticipants(gc["participants"]);

    const updatedParticipants = participants.map((p) =>
      p["userId"] === failedUserId ? { ...p, status: "failed", failedAt: Date.now() } : p
    );

    const failedParticipant = participants.find((p) => p["userId"] === failedUserId);
    if (failedParticipant?.["paymentIntentId"]) {
      try {
        const pi = await getStripe().paymentIntents.retrieve(failedParticipant["paymentIntentId"] as string);
        if (pi.status === "requires_capture") {
          await getStripe().paymentIntents.capture(pi.id);
          functions.logger.info("failParticipant: payment captured", { groupId, userId: failedUserId });
        } else if (pi.status === "succeeded") {
          // PI was already captured when creator started the challenge — no action needed.
          functions.logger.info("failParticipant: PI already captured at challenge start, skipping", { groupId, userId: failedUserId });
        } else {
          functions.logger.warn("failParticipant: unexpected PI status", { groupId, userId: failedUserId, piStatus: pi.status });
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

// ── expireGroupChallenge ───────────────────────────────────────────────────────
// Called by DailyEvaluationWorker when authorizationExpiresAt has passed.
// Any authenticated user can trigger this — no creator check.
// Idempotent: already-cancelled challenges are silently accepted.

export const expireGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    await requireAuth(req);
    const { groupId } = req.body as { groupId: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;

    if (gc["status"] === "cancelled") {
      functions.logger.info("expireGroupChallenge: already cancelled (idempotent)", { groupId });
      res.json({ status: "cancelled" });
      return;
    }
    if (gc["status"] !== "waiting") {
      functions.logger.info("expireGroupChallenge: not in waiting status — skipping", { groupId, status: gc["status"] });
      res.json({ status: gc["status"] });
      return;
    }

    const authorizationExpiresAt: number = (gc["authorizationExpiresAt"] as number) ?? 0;
    if (authorizationExpiresAt > 0 && Date.now() < authorizationExpiresAt) {
      functions.logger.warn("expireGroupChallenge: authorization not yet expired", { groupId, authorizationExpiresAt, now: Date.now() });
      res.status(412).json({ error: "not_expired", message: "Authorization window has not yet expired." });
      return;
    }

    const participants = parseParticipants<Participant>(gc["participants"]);

    const updatedParticipants = [...participants];
    for (let i = 0; i < updatedParticipants.length; i++) {
      const p = updatedParticipants[i];
      if (!p.paymentIntentId) continue;
      try {
        const pi = await getStripe().paymentIntents.retrieve(p.paymentIntentId);
        if (pi.status === "requires_capture") {
          await getStripe().paymentIntents.cancel(p.paymentIntentId);
          functions.logger.info("expireGroupChallenge: PI cancelled", { groupId, userId: p.userId });
        } else if (pi.status === "canceled") {
          functions.logger.info("expireGroupChallenge: PI already cancelled (idempotent)", { groupId, userId: p.userId });
        } else {
          functions.logger.warn("expireGroupChallenge: unexpected PI status", { groupId, userId: p.userId, piStatus: pi.status });
        }
        updatedParticipants[i] = { ...p, status: "refunded" };
      } catch (e) {
        functions.logger.error("expireGroupChallenge: PI cancel failed", { groupId, userId: p.userId, error: e });
      }
    }

    await docRef.update({ status: "cancelled", participants: updatedParticipants });
    functions.logger.info("expireGroupChallenge: expired and cancelled", { groupId, participants: participants.length });
    res.json({ status: "cancelled" });
  } catch (e) { handleError("expireGroupChallenge", e, res); }
});

// ── leaveGroupChallenge ────────────────────────────────────────────────────────
// Regular participant (non-creator) leaves a WAITING challenge.
// Stripe PI is cancelled → 100% refund.
// If remaining participants < 2 → status set to "cancelled".

export const leaveGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const verifiedUserId = await requireAuth(req);
    const { groupId } = req.body as { groupId: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;

    // 2. Verify status == "waiting"
    if (gc["status"] !== "waiting") {
      throw new HttpError(400, "Du kannst eine aktive Challenge nicht verlassen.");
    }

    const participants = parseParticipants<Participant>(gc["participants"]);

    // 3. Verify user is a participant
    const participant = participants.find((p) => p.userId === verifiedUserId);
    if (!participant) {
      throw new HttpError(403, "Du bist kein Teilnehmer dieser Challenge.");
    }

    // 4. Verify user is NOT the creator
    if (gc["creatorUserId"] === verifiedUserId) {
      throw new HttpError(400, "Als Ersteller kannst du die Challenge nicht verlassen. Lösche die Challenge stattdessen.");
    }

    // 5+6. Find and verify PaymentIntent
    const paymentIntentId = participant.paymentIntentId;
    if (!paymentIntentId) throw new HttpError(500, "Kein Payment gefunden.");

    const pi = await getStripe().paymentIntents.retrieve(paymentIntentId);
    if (pi.status !== "requires_capture") {
      throw new HttpError(400, `Zahlung kann nicht storniert werden. Status: ${pi.status}`);
    }

    // 7. Cancel Stripe PI — FIRST before any Firestore write
    await getStripe().paymentIntents.cancel(paymentIntentId);
    functions.logger.info("leaveGroupChallenge: PI cancelled", { groupId, userId: verifiedUserId });

    // 8. Only after Stripe cancel → update Firestore
    const remainingParticipants = participants.filter((p) => p.userId !== verifiedUserId);
    const newStatus = remainingParticipants.length < 2 ? "cancelled" : (gc["status"] as string);

    const updateData: Record<string, unknown> = {
      participants: remainingParticipants,
      participantUserIds: admin.firestore.FieldValue.arrayRemove(verifiedUserId),
    };
    if (newStatus === "cancelled") {
      updateData["status"] = "cancelled";
    }
    await docRef.update(updateData);

    functions.logger.info("leaveGroupChallenge: participant removed", {
      groupId,
      userId: verifiedUserId,
      remaining: remainingParticipants.length,
      newStatus,
    });

    const amountCents: number = (participant.amountCents ?? (gc["buyInCents"] as number)) ?? 0;
    res.json({ success: true, amountCents });
  } catch (e) { handleError("leaveGroupChallenge", e, res); }
});

// ── deleteGroupChallenge ───────────────────────────────────────────────────────
// Creator deletes a WAITING challenge → cancels ALL participant PIs → 100% refund each.

export const deleteGroupChallenge = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    const verifiedUserId = await requireAuth(req);
    const { groupId } = req.body as { groupId: string };
    if (!groupId) throw new HttpError(400, "groupId is required.");

    const db = admin.firestore();
    const docRef = db.collection("groupChallenges").doc(groupId);
    const doc = await docRef.get();
    if (!doc.exists) throw new HttpError(404, "Group challenge not found.");

    const gc = doc.data()!;

    // 2. Verify status == "waiting"
    if (gc["status"] !== "waiting") {
      throw new HttpError(400, "Du kannst nur eine wartende Challenge löschen.");
    }

    // 3. Verify caller is the creator
    if (gc["creatorUserId"] !== verifiedUserId) {
      throw new HttpError(403, "Nur der Ersteller kann die Challenge löschen.");
    }

    const participants = parseParticipants<Participant>(gc["participants"]);

    // 4. For each participant: cancel their PI — all Stripe work BEFORE Firestore write
    const updatedParticipants = [...participants];
    for (let i = 0; i < updatedParticipants.length; i++) {
      const p = updatedParticipants[i];
      if (!p.paymentIntentId) continue;
      try {
        const pi = await getStripe().paymentIntents.retrieve(p.paymentIntentId);
        if (pi.status === "requires_capture") {
          await getStripe().paymentIntents.cancel(p.paymentIntentId);
          functions.logger.info("deleteGroupChallenge: PI cancelled", { groupId, userId: p.userId });
        } else if (pi.status === "canceled") {
          functions.logger.info("deleteGroupChallenge: PI already cancelled (idempotent)", { groupId, userId: p.userId });
        } else {
          functions.logger.warn("deleteGroupChallenge: unexpected PI status — skipping", { groupId, userId: p.userId, piStatus: pi.status });
        }
        updatedParticipants[i] = { ...p, status: "refunded" };
      } catch (e) {
        functions.logger.error("deleteGroupChallenge: PI cancel failed", { groupId, userId: p.userId, error: e });
      }
    }

    // 5. Only after ALL Stripe cancels → update Firestore
    await docRef.update({ status: "cancelled", participants: updatedParticipants });

    functions.logger.info("deleteGroupChallenge: challenge deleted by creator", {
      groupId,
      creatorId: verifiedUserId,
      participantCount: participants.length,
    });

    res.json({ success: true });
  } catch (e) { handleError("deleteGroupChallenge", e, res); }
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

    // "active" = still in the challenge; "completed" = CF already marked them on a prior run.
    // Both statuses mean the participant did NOT fail — include both when counting winners.
    const failedParticipants = participants.filter((p) => p["status"] === "failed");
    const successParticipants = participants.filter(
      (p) => p["status"] === "active" || p["status"] === "completed"
    );

    // Per docs/09_payout_and_fees.md: nobodyFailed iff every participant is active OR completed.
    // Stricter than failedParticipants.length === 0 — a stale "success" status (from a partial
    // prior run of the someone-failed path) correctly returns false here instead of true.
    const nobodyFailed = participants.every(
      (p) => p["status"] === "active" || p["status"] === "completed"
    );

    functions.logger.info("completeGroupChallenge: participant statuses", {
      groupId,
      total: participants.length,
      active: participants.filter((p) => p["status"] === "active").length,
      failed: failedParticipants.length,
      completed: participants.filter((p) => p["status"] === "completed").length,
      other: participants.filter(
        (p) => p["status"] !== "active" && p["status"] !== "failed" && p["status"] !== "completed"
      ).map((p) => ({ userId: p["userId"], status: p["status"] })),
      nobodyFailed,
    });

    // ── Nobody-failed case: 100% refund for all, no app fee ─────────────────
    if (nobodyFailed) {
      functions.logger.info("completeGroupChallenge: nobodyFailed=true — full refund path", { groupId });
      const updatedParticipants = await Promise.all(participants.map(async (p) => {
        const userId = p["userId"] as string;
        const pid = p["paymentIntentId"] as string;
        if (pid) {
          try {
            const pi = await getStripe().paymentIntents.retrieve(pid);
            functions.logger.info("completeGroupChallenge: nobody-failed PI status", { groupId, userId, pid, piStatus: pi.status });
            if (pi.status === "requires_capture") {
              await getStripe().paymentIntents.cancel(pid);
              functions.logger.info("completeGroupChallenge: nobody-failed PI cancelled (full cancel)", { groupId, userId, pid });
            } else if (pi.status === "succeeded") {
              await getStripe().refunds.create({ payment_intent: pid });
              functions.logger.info("completeGroupChallenge: nobody-failed PI full-refunded", { groupId, userId, pid });
            } else {
              functions.logger.warn("completeGroupChallenge: nobody-failed PI in unexpected status — skipping Stripe op", { groupId, userId, pid, piStatus: pi.status });
            }
          } catch (e) {
            functions.logger.error("completeGroupChallenge: full refund failed", { groupId, userId, error: e });
          }
        }
        return { ...p, status: "completed", payoutStatus: "completed", finalPayout: p["amountCents"] ?? buyInCents };
      }));
      await docRef.update({
        status: "completed",
        completedAt: Date.now(),
        prizePool: 0,
        appFee: 0,
        prizePerWinner: 0,
        nobodyFailed: true,
        participants: updatedParticipants,
      });
      functions.logger.info("completeGroupChallenge: completed (nobody failed)", { groupId });
      res.json({ success: true, nobodyFailed: true });
      return;
    }

    // ── Someone-failed path: winners get 80% of own stake + prize share ─────
    functions.logger.info("completeGroupChallenge: nobodyFailed=false — someone-failed path", {
      groupId,
      winners: successParticipants.length,
      losers: failedParticipants.length,
    });

    // ── Pot calculation ───────────────────────────────────────────────────────
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
      distributablePot,
      perWinnerBonus,
      winners: successParticipants.length,
      losers: failedParticipants.length,
    });

    // ── Process winners: 80% stake refund + prize share ──────────────────────
    const updatedParticipants = await Promise.all(participants.map(async (p) => {
      if (p["status"] === "failed") {
        return { ...p, status: "failed", payoutStatus: "lost", finalPayout: 0 };
      }

      const userId = p["userId"] as string;
      const pid = p["paymentIntentId"] as string;
      const participantStake: number = (p["amountCents"] as number) ?? buyInCents;
      const stakeRefund = Math.floor(participantStake * 0.80);

      // Refund 80% of own stake — capture first if PI is still pre-authorized
      functions.logger.info("completeGroupChallenge: processing winner stake refund (80%)", {
        groupId, userId, participantStake, stakeRefund,
      });
      if (pid) {
        try {
          const pi = await getStripe().paymentIntents.retrieve(pid);
          functions.logger.info("completeGroupChallenge: winner PI status", { groupId, userId, pid, piStatus: pi.status });
          if (pi.status === "requires_capture") {
            await getStripe().paymentIntents.capture(pid);
            await getStripe().refunds.create({ payment_intent: pid, amount: stakeRefund });
            functions.logger.info("completeGroupChallenge: winner PI captured-then-partial-refunded", { groupId, userId, stakeRefund });
          } else {
            await getStripe().refunds.create({ payment_intent: pid, amount: stakeRefund });
            functions.logger.info("completeGroupChallenge: winner PI partial-refunded (already captured)", { groupId, userId, stakeRefund });
          }
        } catch (e) {
          functions.logger.error("completeGroupChallenge: stake refund failed", { groupId, userId, error: e });
        }
      }

      const displayName = (p["displayName"] as string) ?? "";

      // No bonus pot to distribute — stake refund only
      if (perWinnerBonus <= 0) {
        return { ...p, status: "success", payoutStatus: "completed", finalPayout: stakeRefund };
      }

      // Look up connected account
      let connectedAccountId: string | undefined;
      try {
        const userDoc = await db.collection("users").doc(userId).get();
        connectedAccountId = userDoc.data()?.stripeConnectedAccountId as string | undefined;
      } catch (e) {
        functions.logger.error("completeGroupChallenge: user lookup failed", { groupId, userId, error: e });
      }

      const writePendingPayout = async () => {
        await db.collection("users").doc(userId)
          .collection("pendingPayouts").add({
            amount: perWinnerBonus,
            stakeRefundCents: stakeRefund,
            currency: "eur",
            groupId,
            displayName,
            status: "pending_account_setup",
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
          });
      };

      if (!connectedAccountId) {
        try { await writePendingPayout(); } catch (e) {
          functions.logger.error("completeGroupChallenge: pendingPayout write failed", { groupId, userId, error: e });
        }
        return { ...p, status: "success", payoutStatus: "pending_payout", finalPayout: stakeRefund + perWinnerBonus };
      }

      // Verify payouts are enabled before transferring
      try {
        const account = await getStripe().accounts.retrieve(connectedAccountId);
        if (!account.payouts_enabled) {
          await writePendingPayout();
          return { ...p, status: "success", payoutStatus: "pending_payout", finalPayout: stakeRefund + perWinnerBonus };
        }

        await getStripe().transfers.create({
          amount: perWinnerBonus,
          currency: "eur",
          destination: connectedAccountId,
          description: `Prize for group challenge ${groupId}`,
        });
        functions.logger.info(`Transfer sent to ${userId}: ${perWinnerBonus}`);

        // Record successful transfer on user doc
        await db.collection("users").doc(userId).set({
          [`pendingPayouts_completed.${groupId}`]: {
            amount: perWinnerBonus,
            stakeRefundCents: stakeRefund,
            status: "transferred",
            groupId,
            transferredAt: Date.now(),
          }
        }, { merge: true });

        return { ...p, status: "success", payoutStatus: "completed", finalPayout: stakeRefund + perWinnerBonus };
      } catch (e) {
        functions.logger.error("completeGroupChallenge: transfer failed", { groupId, userId, error: e });
        // Fall back to pending so money isn't lost
        try { await writePendingPayout(); } catch (_) { /* best effort */ }
        return { ...p, status: "success", payoutStatus: "pending_payout", finalPayout: stakeRefund + perWinnerBonus };
      }
    }));

    await docRef.update({
      status: "completed",
      completedAt: Date.now(),
      prizePool: distributablePot,
      appFee,
      prizePerWinner: perWinnerBonus,
      nobodyFailed: false,
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
    const verifiedUserId = await requireAuth(req);
    const { iban, accountHolderName } = req.body as {
      iban: string;
      accountHolderName: string;
    };
    if (!iban || !accountHolderName) {
      throw new HttpError(400, "iban and accountHolderName are required.");
    }

    const db = admin.firestore();

    const account = await getStripe().accounts.create({
      type: "custom",
      country: "AT",
      capabilities: {
        transfers: { requested: true },
      },
      external_account: {
        object: "bank_account",
        country: "AT",
        currency: "eur",
        account_holder_name: accountHolderName,
        account_holder_type: "individual",
        account_number: iban,
      } as any,
      tos_acceptance: {
        date: Math.floor(Date.now() / 1000),
        ip: req.ip ?? "0.0.0.0",
      },
    });

    await db.collection("users").doc(verifiedUserId).set({
      stripeConnectedAccountId: account.id,
      payoutIban: iban,
      payoutName: accountHolderName,
      payoutSetupAt: Date.now(),
    }, { merge: true });

    functions.logger.info(`Custom connected account created: ${account.id} for user ${verifiedUserId}`);
    res.json({ success: true, accountId: account.id });
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

// ── checkPermissionViolations ─────────────────────────────────────────────────
// Finds users whose overlay/accessibility permission has been missing for > 24h
// and captures their Hard Mode Stripe payments server-side.
// Called hourly by scheduledPermissionCheck, and as fallback from DailyEvaluationWorker.

async function runPermissionViolationCheck(): Promise<number> {
  const now = Date.now();
  const twentyFourHours = 24 * 60 * 60 * 1000;
  const db = admin.firestore();
  let processed = 0;

  const snapshot = await db
    .collectionGroup("permissionStatus")
    .where("permissionLostAt", "!=", null)
    .get();

  for (const doc of snapshot.docs) {
    const data = doc.data();
    const lostAt: number = data["permissionLostAt"];

    if (!lostAt || (now - lostAt) < twentyFourHours) continue;
    if (data["capturedAt"]) continue;
    if (data["permissionRestoredAt"]) continue;

    const userId = doc.ref.parent.parent!.id;

    // Solo Hard Mode challenges
    const challenges = await db.collection("users").doc(userId)
      .collection("challenges")
      .where("status", "==", "active")
      .get();

    for (const challenge of challenges.docs) {
      const cd = challenge.data();
      if (cd["mode"] !== "hard") continue;
      const paymentIntentId: string | undefined = cd["stripePaymentIntentId"] ?? cd["paymentIntentId"];
      if (!paymentIntentId) continue;

      try {
        await getStripe().paymentIntents.capture(paymentIntentId);
        await challenge.ref.update({
          status: "failed",
          failReason: "permission_violation",
          failedAt: now,
          payoutStatus: "captured",
        });
        functions.logger.info(`checkPermissionViolations: captured solo challenge ${challenge.id} user ${userId}`);
      } catch (e) {
        functions.logger.error(`checkPermissionViolations: capture failed for ${challenge.id}`, e);
      }
    }

    // Group Challenge participants
    const groups = await db.collectionGroup("participants")
      .where("userId", "==", userId)
      .where("status", "==", "active")
      .get();

    for (const participant of groups.docs) {
      const pd = participant.data();
      if (!pd["paymentIntentId"]) continue;
      try {
        await getStripe().paymentIntents.capture(pd["paymentIntentId"]);
        await participant.ref.update({
          status: "failed",
          failReason: "permission_violation",
          failedAt: now,
        });
        functions.logger.info(`checkPermissionViolations: captured group participant user ${userId}`);
      } catch (e) {
        functions.logger.error(`checkPermissionViolations: group capture failed`, e);
      }
    }

    // Mark as captured — Admin SDK only field, enforced by Firestore rules on client
    await doc.ref.update({ capturedAt: now, captureReason: "permission_loss_24h" });
    processed++;
  }

  // ── Usage violation check (accessibility disabled + blocked app used > 1h ago) ─
  const oneHour = 60 * 60 * 1000;
  const usageSnapshot = await db
    .collectionGroup("permissionStatus")
    .where("usageViolationDetectedAt", "!=", null)
    .get();

  for (const doc of usageSnapshot.docs) {
    const data = doc.data();
    const violatedAt: number = data["usageViolationDetectedAt"];

    if (!violatedAt || (now - violatedAt) < oneHour) continue;
    if (data["usageCapturedAt"]) continue;

    const userId = doc.ref.parent.parent!.id;

    const challenges = await db.collection("users").doc(userId)
      .collection("challenges")
      .where("status", "==", "active")
      .get();

    for (const challenge of challenges.docs) {
      const cd = challenge.data();
      if (cd["mode"] !== "hard") continue;
      const paymentIntentId: string | undefined = cd["stripePaymentIntentId"] ?? cd["paymentIntentId"];
      if (!paymentIntentId) continue;

      try {
        await getStripe().paymentIntents.capture(paymentIntentId);
        await challenge.ref.update({
          status: "failed",
          failReason: "usage_violation",
          failedAt: now,
          payoutStatus: "captured",
        });
        functions.logger.info(`checkPermissionViolations: usage violation captured solo challenge ${challenge.id} user ${userId}`);
      } catch (e) {
        functions.logger.error(`checkPermissionViolations: usage violation capture failed for ${challenge.id}`, e);
      }
    }

    await doc.ref.update({ usageCapturedAt: now, captureReason: "usage_violation_1h" });
    processed++;
  }

  return processed;
}

export const checkPermissionViolations = functions.region(REGION).https.onRequest(async (req, res) => {
  try {
    // Accept authenticated user calls (DailyEvaluationWorker fallback via Bearer token)
    // OR internal scheduler calls via x-internal-secret header
    const internalSecret = req.headers["x-internal-secret"];
    const isInternalCall = !!process.env.INTERNAL_SECRET && internalSecret === process.env.INTERNAL_SECRET;
    if (!isInternalCall) {
      await requireAuth(req);
    }

    const processed = await runPermissionViolationCheck();
    functions.logger.info(`checkPermissionViolations: processed=${processed}`);
    res.json({ processed });
  } catch (e) { handleError("checkPermissionViolations", e, res); }
});

export const scheduledPermissionCheck = functions.region(REGION)
  .pubsub.schedule("every 1 hours")
  .onRun(async () => {
    const processed = await runPermissionViolationCheck();
    functions.logger.info(`scheduledPermissionCheck: processed=${processed}`);
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
