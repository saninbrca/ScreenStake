import * as functions from "firebase-functions/v1";
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
 * there is a collision), creates a Stripe PaymentIntent for the creator's buy-in,
 * and stores the creator as the first participant.
 *
 * Expected input:  { groupId: string, code: string, groupData: object }
 * Returns:         { code: string, paymentIntentId: string, clientSecret: string }
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

  // Create Stripe PaymentIntent for the creator's buy-in
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
      metadata: {
        userId,
        groupId,
        type: "group_challenge_buy_in",
      },
      description: `Detox Group Challenge buy-in — ${groupId}`,
    });
  } catch (e) {
    functions.logger.error("createGroupChallenge: Stripe PaymentIntent creation failed", { groupId, error: e });
    throw new functions.https.HttpsError("internal", "Failed to create payment intent for creator.");
  }

  // Creator is the first participant
  const creatorParticipant = {
    userId,
    displayName: creatorDisplayName,
    paymentIntentId: paymentIntent.id,
    amountCents: buyInCents,
    status: "active",
    opensToday: 0,
    timeUsedMinutes: 0,
    joinedAt: Date.now(),
  };

  const docData = {
    ...groupData,
    code: finalCode,
    groupId,
    status: "waiting",
    participants: [creatorParticipant],
    participantUserIds: [userId],
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  };

  try {
    await db.collection("groupChallenges").doc(groupId).set(docData);
    functions.logger.info("Firestore write result: " + groupId);
    functions.logger.info("createGroupChallenge: created", { groupId, code: finalCode, userId });
  } catch (e) {
    // Cancel the PaymentIntent if Firestore write fails to avoid dangling pre-auth
    try { await getStripe().paymentIntents.cancel(paymentIntent.id); } catch (_) { /* best effort */ }
    functions.logger.error("createGroupChallenge: Firestore write failed", { groupId, error: e });
    throw new functions.https.HttpsError("internal", "Failed to save group challenge.");
  }

  return {
    code: finalCode,
    paymentIntentId: paymentIntent.id,
    clientSecret: paymentIntent.client_secret,
  };
});

// ── joinGroupChallenge ─────────────────────────────────────────────────────────

/**
 * Validates the group challenge is joinable, creates a Stripe PaymentIntent for
 * the buy-in (pre-auth for ≤7 days, immediate capture for >7 days), adds the
 * participant to the group challenge, and returns the client secret for Stripe PaymentSheet.
 *
 * Expected input:  { groupId: string, displayName: string }
 * Returns:         { paymentIntentId: string, clientSecret: string, isImmediateCapture: boolean }
 */
export const joinGroupChallenge = functions.region(REGION).https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be signed in.");
  }

  const { groupId, displayName } = data as {
    groupId: string;
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

  const startDate: number = typeof gc["startDate"] === "number"
    ? gc["startDate"]
    : (gc["startDate"] as admin.firestore.Timestamp)?.toMillis?.() ?? 0;
  if (startDate && startDate <= Date.now()) {
    throw new functions.https.HttpsError("failed-precondition", "The join window for this challenge has closed.");
  }

  const buyInCents: number = gc["buyInCents"] ?? 500;
  const durationDays: number = gc["durationDays"] ?? 7;
  const isImmediateCapture = durationDays > 7;

  const customerId = await getOrCreateStripeCustomer(userId);
  const paymentIntent = await getStripe().paymentIntents.create({
    amount: buyInCents,
    currency: "eur",
    customer: customerId,
    // Pre-auth for short challenges; immediate capture for long ones (pre-auth expires in 7 days)
    capture_method: isImmediateCapture ? "automatic" : "manual",
    metadata: {
      userId,
      groupId,
      displayName: displayName ?? "Anonymous",
      type: "group_challenge_buy_in",
    },
    description: `Detox Group Challenge buy-in — ${groupId}`,
  });

  const newParticipant = {
    userId,
    displayName: displayName ?? "Anonymous",
    paymentIntentId: paymentIntent.id,
    amountCents: buyInCents,
    status: "active",
    opensToday: 0,
    timeUsedMinutes: 0,
    joinedAt: Date.now(),
  };

  await docRef.update({
    participants: admin.firestore.FieldValue.arrayUnion(newParticipant),
    participantUserIds: admin.firestore.FieldValue.arrayUnion(userId),
  });

  functions.logger.info("joinGroupChallenge: participant added", { groupId, userId });

  return {
    paymentIntentId: paymentIntent.id,
    clientSecret: paymentIntent.client_secret,
    isImmediateCapture,
  };
});

// ── startGroupChallenge ────────────────────────────────────────────────────────

/**
 * Called at the challenge startDate (via device WorkManager). Checks participant
 * count: if ≥ 2, sets status ACTIVE; if < 2, cancels with full refunds/cancellations.
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
    functions.logger.info("startGroupChallenge: already in status=" + gc["status"], { groupId });
    return { status: gc["status"] };
  }

  const participants: Array<{ userId: string; paymentIntentId: string; amountCents: number }> =
    gc["participants"] ?? [];
  const durationDays: number = gc["durationDays"] ?? 7;
  const isImmediateCapture = durationDays > 7;

  if (participants.length < 2) {
    // Cancel — refund or cancel pre-auth for all buy-ins
    for (const p of participants) {
      try {
        if (isImmediateCapture) {
          await getStripe().refunds.create({ payment_intent: p.paymentIntentId });
        } else {
          await getStripe().paymentIntents.cancel(p.paymentIntentId);
        }
        functions.logger.info("startGroupChallenge: refunded/cancelled", { groupId, userId: p.userId });
      } catch (e) {
        functions.logger.error("startGroupChallenge: refund/cancel failed", { groupId, userId: p.userId, error: e });
      }
    }
    await docRef.update({ status: "cancelled" });
    functions.logger.info("startGroupChallenge: cancelled (not enough players)", { groupId, participants: participants.length });
    return { status: "cancelled" };
  }

  await docRef.update({ status: "active" });
  functions.logger.info("startGroupChallenge: activated", { groupId, participants: participants.length });
  return { status: "active" };
});

// ── failParticipant ────────────────────────────────────────────────────────────

/**
 * Marks a participant as FAILED, captures their Stripe payment (or skips if already
 * immediate-captured), and sends push notifications to remaining active participants.
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
  const durationDays: number = (gc["durationDays"] as number) ?? 7;
  const isImmediateCapture = durationDays > 7;

  const updatedParticipants = participants.map((p) => {
    if (p["userId"] === failedUserId) {
      return { ...p, status: "failed", failedAt: Date.now() };
    }
    return p;
  });

  // Capture Stripe payment for the failed participant (only if pre-auth; immediate was already charged)
  const failedParticipant = participants.find((p) => p["userId"] === failedUserId);
  if (failedParticipant?.["paymentIntentId"] && !isImmediateCapture) {
    try {
      const pi = await getStripe().paymentIntents.retrieve(
        failedParticipant["paymentIntentId"] as string
      );
      if (pi.status === "requires_capture") {
        await getStripe().paymentIntents.capture(pi.id);
        functions.logger.info("failParticipant: payment captured", { groupId, userId: failedUserId });
      }
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
 * End-of-challenge payout. For successful participants: cancel their pre-auth (or
 * refund if immediate-capture). For failed participants: their payments were already
 * captured via failParticipant. If bonusEnabled, best performer gets an extra 10% of
 * the pot; app takes 5% fee (Stripe Connect required for full distribution — simplified here).
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
  const durationDays: number = (gc["durationDays"] as number) ?? 7;
  const isImmediateCapture = durationDays > 7;

  const failed = participants.filter((p) => p["status"] === "failed");
  const succeeded = participants.filter((p) => p["status"] !== "failed");

  functions.logger.info("completeGroupChallenge: payout start", {
    groupId,
    failed: failed.length,
    succeeded: succeeded.length,
    bonusEnabled,
    isImmediateCapture,
  });

  // Find best performer for bonus (lowest opensToday, tiebreak: lowest timeUsedMinutes)
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

  // Process successful participants: cancel pre-auth or refund immediate-capture
  for (const p of succeeded) {
    try {
      const pid = p["paymentIntentId"] as string;
      if (!pid) continue;

      if (isImmediateCapture) {
        // Was charged immediately — issue full refund
        await getStripe().refunds.create({ payment_intent: pid });
        functions.logger.info("completeGroupChallenge: refunded winner (immediate)", { groupId, userId: p["userId"] });
      } else {
        // Pre-auth — cancel (no charge was made)
        const pi = await getStripe().paymentIntents.retrieve(pid);
        if (pi.status === "requires_capture") {
          await getStripe().paymentIntents.cancel(pid);
          functions.logger.info("completeGroupChallenge: cancelled pre-auth for winner", { groupId, userId: p["userId"] });
        }
      }

      if (bonusEnabled && p === bestPerformer) {
        functions.logger.info("completeGroupChallenge: best performer bonus (Stripe Connect needed for transfer)", {
          groupId, userId: p["userId"],
        });
        // NOTE: Distributing winnings from captured pot to winners requires Stripe Connect transfers.
        // Tracked as a future enhancement — for now we simply cancel/refund the winner's own buy-in.
      }
    } catch (e) {
      functions.logger.error("completeGroupChallenge: process winner failed", { groupId, userId: p["userId"], error: e });
    }
  }

  // Mark all remaining active participants as succeeded
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
