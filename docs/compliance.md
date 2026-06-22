# Compliance — Open Legal Questions (DRAFT)

> **Scope:** Austrian/EU legal questions for ScreenStake — GSpG gambling classification, §18 FAGG
> withdrawal-right waiver, DSGVO/GDPR basis for `deviceId`, tax/Gewerbe/USt, 18+ gating, AGB alignment.
> **When to load:** Any work touching money flows, consent capture, data-protection basis, or AGB/ToS.
> _Last verified: 2026-06-22 (commit e287b79)_

> ⚠️ **DRAFT — OPEN LEGAL QUESTIONS ONLY.** This file contains **NO legal advice** and is **NOT a
> compliance sign-off**. Every item below is an open question to put to a qualified Austrian lawyer /
> WKO Gründerservice. **Nothing here is settled until reviewed by counsel.** Do not cite this file as
> authority and do not ship money/consent changes on the basis of it.

---

## How to use this file
- Each section is one legal area, framed as: **(a)** the precise question to put to counsel,
  **(b)** a `TODO` placeholder for counsel's answer, **(c)** the app artifact(s) it affects.
- Do **not** fill in legal conclusions here — those wait for attorney / WKO input.
- When an answer lands, record it under the area's `TODO`, date it, and cross-link the affected docs.

---

## 1. GSpG classification (gambling vs. skill/effort)

**(a) Question for counsel:** Is ScreenStake's stake-and-forfeit mechanic *Glücksspiel* or a *Wette*
under §1 GSpG, or does it fall outside the GSpG because the outcome depends predominantly on the
user's **own behaviour/effort** (keeping under a self-set screen-time limit) rather than on *Zufall*
(chance)? Capture **both** the skill/effort argument **and** the counter-arguments (e.g. is forfeiting
a stake on failure structurally a wager?) as open questions — not as a conclusion.

**(b) Counsel answer:** `TODO — pending attorney / WKO Gründerservice`

**(c) Affected artifacts:** entire Hard Mode money flow (`docs/03`), payout/fees (`docs/09`), AGB
(see §6 below), app-store positioning.

---

## 2. §18 FAGG — withdrawal-right waiver

**(a) Question for counsel:** Does the existing in-payment-flow waiver of the 14-day withdrawal right
hold under §18 FAGG for this product? How must express consent **and** acknowledgement of losing the
withdrawal right be captured and evidenced (wording, timing, logging)?

**(b) Counsel answer:** `TODO — pending attorney / WKO Gründerservice`

**(c) Affected artifacts:** payment/consent step in challenge creation, onboarding/auth consent
(`docs/07`), payout flow (`docs/09`).

---

## 3. DSGVO / GDPR — `deviceId` and consent fields

**(a) Question for counsel:** Is processing `deviceId` (ANDROID_ID) for anti-cheat shared-device
detection lawful under Art. 6(1)(f) (legitimate interest), and is the balancing test documented
adequately? Are the consent fields (`consentAGB`, `consentDatenschutz`, `consentAge18`,
`consentTimestamp`) sufficient for purpose, and what are the retention / data-minimisation obligations?

**(b) Counsel answer:** `TODO — pending attorney / WKO Gründerservice`

**(c) Affected artifacts:** `deviceId` capture + anti-cheat (`docs/10`), consent capture in onboarding
(`docs/07`), privacy policy.

---

## 4. Tax / Gewerbe / USt

**(a) Question for counsel:** How are retained fees (the app's cut of forfeited stakes / 10% group fee)
classified for revenue/tax purposes? What Gewerbe registration applies, and how is VAT (USt) handled on
fees vs. on the pass-through stake/payout?

**(b) Counsel answer:** `TODO — pending attorney / WKO Gründerservice`

**(c) Affected artifacts:** fee model (`docs/09`), payout flow, bookkeeping/invoicing.

---

## 5. Age 18+ gating

**(a) Question for counsel:** Is a self-declared `consentAge18` checkbox adequate age assurance for a
money product, or is stronger age verification required?

**(b) Counsel answer:** `TODO — pending attorney / WKO Gründerservice`

**(c) Affected artifacts:** onboarding/auth consent (`docs/07`), AGB.

---

## 6. AGB / ToS alignment

**(a) Question for counsel:** Once §§1–5 are answered, do the AGB/ToS accurately reflect the legal
classification, the withdrawal-right waiver, data processing, fees/tax, and age gating? Any mandatory
disclosures missing?

**(b) Counsel answer:** `TODO — pending attorney / WKO Gründerservice`

**(c) Affected artifacts:** AGB/ToS document, all consent surfaces (`docs/07`).
