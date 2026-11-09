# OskarSMS 📱

> **Dedicated Android Auto SMS Verifier for OskarShop.so EVC Plus Payments**

OskarSMS is a lightweight, zero-configuration native Android application built specifically for the OskarShop admin. It replaces third-party forwarders (which suffered from template tag bugs like `[content]` and `%mb%`) with a 100% reliable native background service.

---

## 🚀 Quick Download & Install

1. Download the production signed APK:
   👉 **[Download OskarSMS-v1.0.0-release.apk](https://github.com/LP-SOMBOT/OskarSMS/releases/latest/download/OskarSMS-v1.0.0-release.apk)**
2. Tap the APK to install on the admin Android phone with the Hormuud SIM card.
3. Open the app and grant:
   * **SMS Permission** (`RECEIVE_SMS` & `READ_SMS`)
   * **Battery Optimization** (Set to *Unrestricted / Don't optimize* so Android never pauses the app)
4. Ensure the master switch is **TURNED ON** (green).

**That's it! No login, no URL configuration, no webhooks, and no templates required.**

---

## ⚡ How It Works

1. **Native SMS Interception:**
   When Hormuud sends an EVC Plus confirmation SMS (`[-EVCPlus-] waxaad $1.19 ka heshay 0613982172...`), Android's `SmsReceiver` directly reads the raw message body and sender address into memory.
2. **Built-in Backend Integration:**
   The app immediately performs an authenticated HTTP POST request to:
   `https://beta.oskarshop.so/api/sms-webhook`
   with header `x-forwarder-token: oskar_sms_sec_9941a87e2f5b40c6`.
3. **Instant Order Fulfillment:**
   OskarShop backend parses the amount ($1.19) and payer phone (0613982172), matches the pending order, marks it paid, and triggers automated product delivery.
4. **Live Activity History:**
   The admin can see every received payment, amount, and order status badge directly on the home screen.

---

## 🛡️ Reliability Features

* **Foreground Service:** Runs with a lightweight background service to prevent Android OS from killing the process.
* **Auto-Start on Boot:** Restarts monitoring automatically if the phone is rebooted or powered off.
* **CPU WakeLock:** Guarantees network transmission completes even if the phone screen is locked and asleep.
* **Dual Endpoint Fallback:** Automatically falls back to secondary endpoint if primary network connection is temporarily interrupted.
