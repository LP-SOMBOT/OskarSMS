# OskarSMS 📱

> **Dedicated Android Auto-Verifier & Live Order Alerts for OskarShop.so**

OskarSMS is a lightweight, zero-configuration native Android application built specifically for the OskarShop admin. It connects directly to **https://oskarshop.so** to automatically verify incoming Hormuud EVC Plus payment SMS messages and deliver real-time native push notifications whenever customers place new orders.

---

## 🚀 Quick Download & Install

1. Download the production signed APK:
   👉 **[Download OskarSMS-v1.1.1-release.apk](https://github.com/LP-SOMBOT/OskarSMS/releases/download/v1.1.1/OskarSMS-v1.1.1-release.apk)**
   *(Direct APK: [OskarSMS.apk](https://github.com/LP-SOMBOT/OskarSMS/releases/download/v1.1.1/OskarSMS.apk))*
2. Tap the APK to install or update on the admin Android phone with the Hormuud SIM card.
3. Open the app and grant:
   * **SMS Permission** (`RECEIVE_SMS` & `READ_SMS`)
   * **Notification Permission** (`POST_NOTIFICATIONS` for Android 13+)
   * **Battery Optimization** (Set to *Unrestricted / Don't optimize* so Android never pauses the app)
4. Ensure the master switch is **TURNED ON** (green).

**That's it! No login, no URL configuration, no webhooks, and no templates required.**

---

## ✨ Features (v1.1.1)

* **Production Connected**: Pre-configured directly to `https://oskarshop.so`.
* **Live New Order Push Notifications**: The admin receives instant heads-up alerts with sound and vibration whenever a customer places a new order, both in the foreground and background!
* **Quick Admin Actions**: One-tap shortcut from notification directly into the admin panel (`https://oskarshop.so/admin`).
* **Strict Incoming Filter**: Only forwards SMS messages containing `"ka heshay"` (incoming money receipts). Outgoing transfers (`ayaad uwareejisay`) are never sent.
* **Smart Matching Engine**: Matches exact two-decimal values without rounding up (e.g. `$1.19999838` -> `$1.19`), handles all phone prefixes (`061...`, `61...`, `25261...`, `077...`), and automatically assigns orders by timestamp.
* **Test Alert Button**: Tap to hear and test the notification sound and vibration anytime.

---

## 🛡️ Reliability Features

* **Foreground Service:** Runs with a lightweight background service to prevent Android OS from killing the process.
* **Auto-Start on Boot:** Restarts monitoring automatically if the phone is rebooted or powered off.
* **CPU WakeLock:** Guarantees network transmission completes even if the phone screen is locked and asleep.
* **Dual Endpoint Fallback:** Automatically falls back between `https://oskarshop.so` and secondary endpoints.
