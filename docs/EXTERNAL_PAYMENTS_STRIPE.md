# External/direct Premium payments
External/direct payment links are intended only for non-Play builds. Do not enable them in the Google Play build.

## Build behavior
| Build | Premium behavior |
| ----- | ---------------- |
| `full`/Play Store | Google Play Billing only. Custom redeem-code UI is hidden. |
| `firebaseEmail`/direct APK | external/direct checkout, Firebase restore, and online Switchly redeem codes |
| `offline`/offline APK | no online payment or account restore. Optional local redeem is enabled only when a private allowlist is supplied at build time. |

The Play Store build must keep external/direct payment links disabled so Google Play Billing remains unchanged.

## Local config
Relevant values can be supplied through `signing.properties`, Gradle properties, or environment variables:
```properties
SWITCHLY_EXTERNAL_PAYMENT_PROVIDER=stripe
SWITCHLY_EXTERNAL_CHECKOUT_URL=https://example.com/checkout
SWITCHLY_EXTERNAL_CUSTOMER_PORTAL_URL=https://example.com/customer-portal
SWITCHLY_REDEEM_API_URL=https://example.com/redeem-code/
```

Do not commit `signing.properties`, API keys, webhook secrets, license signing keys, Stripe secrets, Firebase private keys, or other production secrets.

## Checkout parameters
The app may append query parameters when opening an external checkout or portal URL:
```text
app=at.saltyy.switchly
variant=firebase-email
version=<app version>
provider=stripe
action=checkout or portal
uid=<Firebase uid, only when signed in>
email=<Firebase email, only when signed in>
```

Do not rely on client-provided values alone for granting Premium. They are only hints for your backend.

## Recommended Stripe/direct setup
1. Create a Stripe product/price for Switchly Premium.
2. Create a backend checkout endpoint.
3. Let the backend create the Stripe Checkout Session or PaymentIntent server-side.
4. Pass the Firebase `uid` as metadata when available.
5. Configure a Stripe webhook endpoint.
6. Verify the webhook signature server-side.
7. Only after a verified payment, grant the user an entitlement.
8. For the Firebase/direct APK, store the entitlement in Firestore, for example `switchly_users/<uid>` with `hasPremiumExternal=true` or `hasPremium=true`.
9. Let the app restore the verified entitlement from the Premium screen.
10. Keep the Offline build independent from online account restore.

## Important security note
Opening checkout is not the same as activating Premium.

The app should only open the configured external payment URL. Premium should only be activated after the backend verifies the payment or redeem code.

Do not unlock Premium immediately after a checkout page opens.

## Firebase/direct APK
For `firebaseEmail`, the intended flow is:
- user signs in with Firebase email/password
- app opens external checkout with a user/session reference
- backend verifies payment or redeem code
- backend stores the Premium entitlement
- app restores the entitlement from the Premium screen

## Offline APK
For `offline`, online checkout and online account restore are disabled.

Offline Premium redeem is enabled only when `SWITCHLY_OFFLINE_REDEEM_CODE_ALLOWLIST` is supplied privately at build time using `SALT-OFFLINE-XXXX-XXXX` codes. The allowlist is compiled into that APK, can be extracted by a determined user, and should stay separate from Firebase/Play Billing entitlement flows. Use signed license payloads for stronger offline protection.

## Switchly hosted endpoint
If using the Switchly-hosted website/payment flow, the matching website deployment may provide endpoints like:
```text
https://example.com/pages/pay/checkout/
https://example.com/pages/pay/customer-portal/
https://example.com/pages/pay/stripe-webhook/
https://example.com/pages/pay/redeem-code/
```

Keep production endpoint URLs and secrets out of the public repository.
