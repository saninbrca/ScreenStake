import * as functions from "firebase-functions/v1";
import * as admin from "firebase-admin";
import Stripe from "stripe";

admin.initializeApp();

const REGION = "us-central1";
const MILLIS_PER_DAY = 86_400_000;

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
    const { paymentIntentId, challengeId, userId, amountCents, partialRefundCents } = req.body as {
      paymentIntentId: string;
      challengeId?: string;
      userId?: string;
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
    if (challengeId && userId) {
      const db = admin.firestore();
      await db.collection("users").doc(userId)
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

    functions.logger.info("joinGroupChallenge: payment intent created", { groupId, userId });
    res.json({ paymentIntentId: paymentIntent.id, clientSecret: paymentIntent.client_secret, isImmediateCapture });
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

    const startDate = Date.now();
    const durationDays: number = (gc["durationDays"] as number) ?? 7;
    const endDate = startDate + durationDays * MILLIS_PER_DAY;

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
    await requireAuth(req);
    const { iban, accountHolderName, userId } = req.body as {
      iban: string;
      accountHolderName: string;
      userId: string;
    };
    if (!iban || !accountHolderName || !userId) {
      throw new HttpError(400, "iban, accountHolderName, and userId are required.");
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

    await db.collection("users").doc(userId).set({
      stripeConnectedAccountId: account.id,
      payoutIban: iban,
      payoutName: accountHolderName,
      payoutSetupAt: Date.now(),
    }, { merge: true });

    functions.logger.info(`Custom connected account created: ${account.id} for user ${userId}`);
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
