import * as functions from "firebase-functions";
import * as admin from "firebase-admin";
import Stripe from "stripe";

admin.initializeApp();

// Initialize Stripe with the secret key from Firebase environment config.
// Deploy with: firebase functions:config:set stripe.secret_key="sk_test_..."
const stripe = new Stripe(functions.config().stripe.secret_key as string, {
  apiVersion: "2024-04-10",
});

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
export const createPaymentIntent = functions.https.onCall(async (data, context) => {
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

  const paymentIntent = await stripe.paymentIntents.create({
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
export const capturePayment = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be signed in.");
  }

  const { paymentIntentId } = data as { paymentIntentId: string };
  if (!paymentIntentId) {
    throw new functions.https.HttpsError("invalid-argument", "paymentIntentId is required.");
  }

  const paymentIntent = await stripe.paymentIntents.capture(paymentIntentId);

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
export const cancelOrRefundPayment = functions.https.onCall(async (data, context) => {
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
    await stripe.refunds.create({ payment_intent: paymentIntentId });
  } else {
    // Cancel the pre-auth (no charge was made)
    await stripe.paymentIntents.cancel(paymentIntentId);
  }

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
  const customer = await stripe.customers.create({
    email: firebaseUser.email,
    name: firebaseUser.displayName,
    metadata: { firebaseUid: userId },
  });

  await admin.firestore()
    .collection("users").doc(userId)
    .set({ stripeCustomerId: customer.id }, { merge: true });

  return customer.id;
}
