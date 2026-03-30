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
