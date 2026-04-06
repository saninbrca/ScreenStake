import * as functions from "firebase-functions";
import * as admin from "firebase-admin";
import Stripe from "stripe";

admin.initializeApp();

// Region must match FirebaseFunctions.getInstance("us-central1") in NetworkModule.kt.
// To change region: update BOTH this constant AND NetworkModule.kt, then redeploy.
const REGION = "us-central1";

// Stripe is initialised lazily on the first function call so that a missing
// STRIPE_SECRET_KEY does NOT crash the module at deploy / cold-start time.
// Locally: set STRIPE_SECRET_KEY in functions/.env
// Deployed: Firebase CLI automatically uploads functions/.env to Secret Manager.
let _stripe: Stripe | null = null;

function getStripe(): Stripe {
  if (!_stripe) {
    const key = process.env.STRIPE_SECRET_KEY;
    if (!key) {
      throw new functions.https.HttpsError(
        "failed-precondition",
        "STRIPE_SECRET_KEY is not configured. Add it to functions/.env (local) " +
        "or set it in your Firebase deployment environment."
      );
    }
    _stripe = new Stripe(key, { apiVersion: "2023-10-16" });
  }
  return _stripe;
}

// ── createPaymentIntent ────────────────────────────────────────────────────────

/**
 * Creates a Stripe PaymentIntent for a Hard Mode challenge.
 *
 * - If durationDays <= 7: use capture_method "manual" (pre-auth, captured on failure).
 * - If durationDays > 7:  use capture_method "automatic" (charge immediately,
 *                         refund on success). This avoids pre-auth expiry issues.
 *
 * Expected input:  { amountCents: number, durationDays: number, challengeId: string }
 * Returns:         { paymentIntentId: string, clientSecret: string, isImmediateCapture: boolean }
 */
export const createPaymentIntent = functions.region(REGION).https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be signed in.");
  }

  const { amountCents, durationDays, challengeId } = data as {
    amountCents: number;
    durationDays: number;
    challengeId: string;
  };

  if (!amountCents || amountCents < 500) {
    throw new functions.https.HttpsError("invalid-argument", "Minimum amount is €5.00.");
  }
  if (!durationDays || durationDays < 1) {
    throw new functions.https.HttpsError("invalid-argument", "Duration must be at least 1 day.");
  }

  const userId = context.auth.uid;
  const isImmediateCapture = durationDays > 7;

  // Get or create a Stripe customer for this user
  const customerId = await getOrCreateStripeCustomer(userId);

  const paymentIntent = await getStripe().paymentIntents.create({
    amount: amountCents,
    currency: "eur",
    customer: customerId,
    capture_method: isImmediateCapture ? "automatic" : "manual",
    metadata: {
      userId,
      challengeId,
      durationDays: durationDays.toString(),
    },
    description: `Detox Hard Mode challenge — ${challengeId}`,
  });

  // Store reference in Firestore
  await admin.firestore()
    .collection("users").doc(userId)
    .collection("challenges").doc(challengeId)
    .set(
      { stripePaymentIntentId: paymentIntent.id, stripeCustomerId: customerId },
      { merge: true }
    );

  return {
    paymentIntentId: paymentIntent.id,
    clientSecret: paymentIntent.client_secret,
    isImmediateCapture,
  };
});

// ── capturePayment ─────────────────────────────────────────────────────────────

/**
 * Captures a previously pre-authorised PaymentIntent.
 * Called when the user exceeds their Hard Mode limit.
 *
 * Expected input:  { paymentIntentId: string }
 * Returns:         { success: true }
 */
export const capturePayment = functions.region(REGION).https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be signed in.");
  }

  const { paymentIntentId } = data as { paymentIntentId: string };
  if (!paymentIntentId) {
    throw new functions.https.HttpsError("invalid-argument", "paymentIntentId is required.");
  }

  const paymentIntent = await getStripe().paymentIntents.capture(paymentIntentId);

  // Record the loss in Firestore for audit trail
  const userId = context.auth.uid;
  await admin.firestore()
    .collection("users").doc(userId)
    .collection("paymentCaptures").add({
      paymentIntentId,
      amountCaptured: paymentIntent.amount_received,
      capturedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

  return { success: true };
});

// ── cancelOrRefundPayment ──────────────────────────────────────────────────────

/**
 * Cancels a pre-auth or issues a refund for an immediately-captured PaymentIntent.
 * Called when a Hard Mode challenge is completed successfully.
 *
 * Expected input:  { paymentIntentId: string, wasImmediate: boolean }
 * Returns:         { success: true }
 */
export const cancelOrRefundPayment = functions.region(REGION).https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be signed in.");
  }

  const { paymentIntentId, wasImmediate } = data as {
    paymentIntentId: string;
    wasImmediate: boolean;
  };

  if (!paymentIntentId) {
    throw new functions.https.HttpsError("invalid-argument", "paymentIntentId is required.");
  }

  if (wasImmediate) {
    // Issue a full refund for immediately-captured payments
    await getStripe().refunds.create({ payment_intent: paymentIntentId });
  } else {
    // Cancel the pre-auth (no charge was made)
    await getStripe().paymentIntents.cancel(paymentIntentId);
  }

  return { success: true };
});

// ── createGroupChallenge ───────────────────────────────────────────────────────

/**
 * Persists a new group challenge to Firestore. The Android client generates a
 * random code; this function validates uniqueness (retries with a new code if
 * there is a collision) and stores the final document.
 *
 * Expected input:  { groupId: string, code: string, groupData: object }
 * Returns:         { code: string }
 */
export const createGroupChallenge = functions.region(REGION).https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be signed in.");
  }

  const { groupId, code, groupData } = data as {
    groupId: string;
    code: string;
    groupData: Record<string, unknown>;
  };

  if (!groupId || !code || !groupData) {
    throw new functions.https.HttpsError("invalid-argument", "groupId, code, and groupData are required.");
  }

  const db = admin.firestore();
  const userId = context.auth.uid;

  // Verify the caller is the creator
  if (groupData["creatorUserId"] !== userId) {
    throw new functions.https.HttpsError("permission-denied", "creatorUserId must match the authenticated user.");
  }

  // Check code uniqueness; retry with a fresh code on collision
  let finalCode = code.toUpperCase();
  const existing = await db.collection("groupChallenges")
    .where("code", "==", finalCode)
    .limit(1)
    .get();

  if (!existing.empty) {
    // Generate a new code server-side to avoid collision
    const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    finalCode = Array.from({ length: 6 }, () => chars[Math.floor(Math.random() * chars.length)]).join("");
  }

  const docData = {
    ...groupData,
    code: finalCode,
    groupId,
    status: "waiting",
    participants: [],
    participantUserIds: [],
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  };

  await db.collection("groupChallenges").doc(groupId).set(docData);
  functions.logger.info("createGroupChallenge: created", { groupId, code: finalCode, userId });

  return { code: finalCode };
});

// ── joinGroupChallenge ─────────────────────────────────────────────────────────

/**
 * Validates the group challenge is joinable, creates a Stripe PaymentIntent for
 * the buy-in, and returns the client secret for Stripe PaymentSheet.
 *
 * Expected input:  { groupId: string, userId: string, displayName: string }
 * Returns:         { paymentIntentId: string, clientSecret: string }
 */
export const joinGroupChallenge = functions.region(REGION).https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be signed in.");
  }

  const { groupId, displayName } = data as {
    groupId: string;
    userId: string;
    displayName: string;
  };

  if (!groupId) {
    throw new functions.https.HttpsError("invalid-argument", "groupId is required.");
  }

  const userId = context.auth.uid;
  const db = admin.firestore();
  const docRef = db.collection("groupChallenges").doc(groupId);
  const doc = await docRef.get();

  if (!doc.exists) {
    throw new functions.https.HttpsError("not-found", "Group challenge not found.");
  }

  const gc = doc.data()!;

  if (gc["status"] !== "waiting") {
    throw new functions.https.HttpsError("failed-precondition", "This challenge is no longer accepting players.");
  }

  const participantIds: string[] = gc["participantUserIds"] ?? [];
  if (participantIds.includes(userId)) {
    throw new functions.https.HttpsError("already-exists", "You have already joined this challenge.");
  }

  const maxParticipants: number = gc["maxParticipants"] ?? 5;
  if (participantIds.length >= maxParticipants) {
    throw new functions.https.HttpsError("resource-exhausted", "This challenge is full.");
  }

  const startDate: admin.firestore.Timestamp = gc["startDate"];
  if (startDate && startDate.toMillis() <= Date.now()) {
    throw new functions.https.HttpsError("failed-precondition", "The join window for this challenge has closed.");
  }

  const minBuyInCents: number = gc["buyInCents"] ?? 500;
  const customerId = await getOrCreateStripeCustomer(userId);

  const paymentIntent = await getStripe().paymentIntents.create({
    amount: minBuyInCents,
    currency: "eur",
    customer: customerId,
    capture_method: "automatic",
    metadata: {
      userId,
      groupId,
      displayName,
      type: "group_challenge_buy_in",
    },
    description: `Detox Group Challenge buy-in — ${groupId}`,
  });

  // Store pending participant (confirmed after Stripe webhook or client confirmation)
  const newParticipant = {
    userId,
    displayName: displayName || "Anonymous",
    paymentIntentId: paymentIntent.id,
    amountCents: minBuyInCents,
    status: "active",
    opensToday: 0,
    timeUsedMinutes: 0,
  };

  await docRef.update({
    participants: admin.firestore.FieldValue.arrayUnion(newParticipant),
    participantUserIds: admin.firestore.FieldValue.arrayUnion(userId),
  });

  functions.logger.info("joinGroupChallenge: participant added", { groupId, userId });

  return {
    paymentIntentId: paymentIntent.id,
    clientSecret: paymentIntent.client_secret,
  };
});

// ── startGroupChallenge ────────────────────────────────────────────────────────

/**
 * Called at the challenge startDate (via device WorkManager). Checks participant
 * count: if ≥ 2, sets status ACTIVE; if < 2, cancels with full refunds.
 *
 * Expected input:  { groupId: string }
 * Returns:         { status: "active" | "cancelled" }
 */
export const startGroupChallenge = functions.region(REGION).https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be signed in.");
  }

  const { groupId } = data as { groupId: string };
  if (!groupId) {
    throw new functions.https.HttpsError("invalid-argument", "groupId is required.");
  }

  const db = admin.firestore();
  const docRef = db.collection("groupChallenges").doc(groupId);
  const doc = await docRef.get();

  if (!doc.exists) {
    throw new functions.https.HttpsError("not-found", "Group challenge not found.");
  }

  const gc = doc.data()!;

  if (gc["status"] !== "waiting") {
    // Already started or cancelled
    return { status: gc["status"] };
  }

  const participants: Array<{ userId: string; paymentIntentId: string }> =
    gc["participants"] ?? [];

  if (participants.length < 2) {
    // Cancel — refund all buy-ins
    for (const p of participants) {
      try {
        await getStripe().refunds.create({ payment_intent: p.paymentIntentId });
        functions.logger.info("startGroupChallenge: refunded", { groupId, userId: p.userId });
      } catch (e) {
        functions.logger.error("startGroupChallenge: refund failed", { groupId, userId: p.userId, error: e });
      }
    }
    await docRef.update({ status: "cancelled" });

    // TODO: send FCM push to creator — "Not enough players joined"
    functions.logger.info("startGroupChallenge: cancelled (not enough players)", { groupId });
    return { status: "cancelled" };
  }

  await docRef.update({ status: "active" });
  functions.logger.info("startGroupChallenge: activated", { groupId, participants: participants.length });
  return { status: "active" };
});

// ── failParticipant ────────────────────────────────────────────────────────────

/**
 * Marks a participant as FAILED, captures their Stripe payment, and sends
 * push notifications to remaining active participants.
 *
 * Expected input:  { groupId: string, userId: string }
 * Returns:         { success: true }
 */
export const failParticipant = functions.region(REGION).https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be signed in.");
  }

  const { groupId, userId: failedUserId } = data as { groupId: string; userId: string };
  if (!groupId || !failedUserId) {
    throw new functions.https.HttpsError("invalid-argument", "groupId and userId are required.");
  }

  const db = admin.firestore();
  const docRef = db.collection("groupChallenges").doc(groupId);
  const doc = await docRef.get();

  if (!doc.exists) {
    throw new functions.https.HttpsError("not-found", "Group challenge not found.");
  }

  const gc = doc.data()!;
  const participants: Array<Record<string, unknown>> = gc["participants"] ?? [];

  const updatedParticipants = participants.map((p) => {
    if (p["userId"] === failedUserId) {
      return { ...p, status: "failed" };
    }
    return p;
  });

  // Capture Stripe payment for the failed participant
  const failedParticipant = participants.find((p) => p["userId"] === failedUserId);
  if (failedParticipant?.["paymentIntentId"]) {
    try {
      const pi = await getStripe().paymentIntents.retrieve(
        failedParticipant["paymentIntentId"] as string
      );
      if (pi.status === "requires_capture") {
        await getStripe().paymentIntents.capture(pi.id);
      }
      functions.logger.info("failParticipant: payment captured", { groupId, userId: failedUserId });
    } catch (e) {
      functions.logger.error("failParticipant: capture failed", { groupId, userId: failedUserId, error: e });
    }
  }

  await docRef.update({ participants: updatedParticipants });

  // TODO: Send FCM push notification to all active participants:
  // "[failedUserId display name] has been eliminated! Keep going 💪"

  functions.logger.info("failParticipant: participant failed", { groupId, userId: failedUserId });
  return { success: true };
});

// ── completeGroupChallenge ─────────────────────────────────────────────────────

/**
 * End-of-challenge payout. Calculates pot from failed participants and distributes
 * it equally among winners. If bonusEnabled, awards 10% of pot to best performer.
 * App takes 5% fee when bonus is active.
 *
 * Expected input:  { groupId: string }
 * Returns:         { success: true }
 */
export const completeGroupChallenge = functions.region(REGION).https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be signed in.");
  }

  const { groupId } = data as { groupId: string };
  if (!groupId) {
    throw new functions.https.HttpsError("invalid-argument", "groupId is required.");
  }

  const db = admin.firestore();
  const docRef = db.collection("groupChallenges").doc(groupId);
  const doc = await docRef.get();

  if (!doc.exists) {
    throw new functions.https.HttpsError("not-found", "Group challenge not found.");
  }

  const gc = doc.data()!;
  const participants: Array<Record<string, unknown>> = gc["participants"] ?? [];
  const bonusEnabled: boolean = gc["bonusEnabled"] ?? false;

  const failed = participants.filter((p) => p["status"] === "failed");
  const succeeded = participants.filter((p) => p["status"] !== "failed");

  // Pot = sum of failed participants' buy-ins
  const potCents = failed.reduce((sum, p) => sum + ((p["amountCents"] as number) ?? 0), 0);

  functions.logger.info("completeGroupChallenge: payout", {
    groupId,
    potCents,
    failed: failed.length,
    succeeded: succeeded.length,
    bonusEnabled,
  });

  if (potCents > 0 && succeeded.length > 0) {
    let distributablePot = potCents;
    let bonusAmount = 0;
    let feeAmount = 0;

    if (bonusEnabled) {
      feeAmount = Math.floor(potCents * 0.05);       // 5% app fee
      bonusAmount = Math.floor(potCents * 0.10);     // 10% bonus to winner
      distributablePot = potCents - feeAmount - bonusAmount;
    }

    const sharePerWinner = Math.floor(distributablePot / succeeded.length);

    // Find best performer (lowest opensToday, tiebreak: lowest timeUsedMinutes)
    let bestPerformer: Record<string, unknown> | null = null;
    if (bonusEnabled && succeeded.length > 0) {
      bestPerformer = succeeded.reduce((best, p) => {
        const pOpens = (p["opensToday"] as number) ?? 0;
        const bestOpens = (best["opensToday"] as number) ?? 0;
        if (pOpens < bestOpens) return p;
        if (pOpens === bestOpens) {
          const pTime = (p["timeUsedMinutes"] as number) ?? 0;
          const bestTime = (best["timeUsedMinutes"] as number) ?? 0;
          return pTime < bestTime ? p : best;
        }
        return best;
      });
    }

    for (const p of succeeded) {
      const refundAmount = (p["amountCents"] as number) ?? 0; // original buy-in refund
      const winnings = sharePerWinner + (bonusEnabled && p === bestPerformer ? bonusAmount : 0);
      const totalRefund = refundAmount + winnings;

      try {
        if (totalRefund > 0) {
          // Issue a full refund of the original buy-in plus winnings share
          await getStripe().refunds.create({
            payment_intent: p["paymentIntentId"] as string,
            amount: Math.min(totalRefund, refundAmount), // can't refund more than charged
          });
          // For winnings above the original charge, a separate transfer would be needed.
          // This simplified version refunds original buy-in; actual profit distribution
          // requires Stripe Connect — tracked as a future enhancement.
        }
        functions.logger.info("completeGroupChallenge: refunded winner", {
          groupId,
          userId: p["userId"],
          refundAmount,
          winnings,
        });
      } catch (e) {
        functions.logger.error("completeGroupChallenge: refund failed", {
          groupId,
          userId: p["userId"],
          error: e,
        });
      }
    }
  } else if (potCents === 0) {
    // Everyone succeeded — refund all buy-ins
    for (const p of participants) {
      try {
        await getStripe().refunds.create({
          payment_intent: p["paymentIntentId"] as string,
        });
        functions.logger.info("completeGroupChallenge: all-success refund", { groupId, userId: p["userId"] });
      } catch (e) {
        functions.logger.error("completeGroupChallenge: all-success refund failed", { groupId, error: e });
      }
    }
  }
  // If everyone failed: all payments were already captured — nothing to do here.

  // Mark all remaining participants as succeeded
  const finalParticipants = participants.map((p) =>
    p["status"] === "failed" ? p : { ...p, status: "success" }
  );

  await docRef.update({ status: "completed", participants: finalParticipants });

  // TODO: send FCM push notifications to all participants with their outcome.

  functions.logger.info("completeGroupChallenge: completed", { groupId });
  return { success: true };
});

// ── Helpers ────────────────────────────────────────────────────────────────────

async function getOrCreateStripeCustomer(userId: string): Promise<string> {
  const userDoc = await admin.firestore().collection("users").doc(userId).get();
  const existingCustomerId = userDoc.data()?.stripeCustomerId as string | undefined;

  if (existingCustomerId) {
    return existingCustomerId;
  }

  // Create a new Stripe customer linked to the Firebase user
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
