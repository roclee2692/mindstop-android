<div align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_mindstop_ginkgo_emblem.png" alt="MindStop Ginkgo Hourglass Emblem" width="128" />
  <h1>MindStop (知止)</h1>
  <p><strong>Enjoy technology. Own your time. Live your life.</strong><br/><em>享受科技，掌控时间，回归生活。</em></p>

  <p>
    <a href="README.md">简体中文 README</a> ·
    <a href="https://github.com/roclee2692/mindstop-android/releases/latest">Download Latest APK (Releases)</a> ·
    <a href="docs/mindstop/BUILD_AND_INSTALL.md">Build &amp; Install Guide</a> ·
    <a href="THIRD_PARTY_NOTICES.md">Third-Party Notices</a>
  </p>

  <p>
    <img src="https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
    <img src="https://img.shields.io/badge/Privacy-100%25%20Offline%20%7C%20Zero%20Internet-2E7D32?style=flat-square" alt="100% Offline" />
    <img src="https://img.shields.io/badge/License-GNU%20GPL%20v3.0-blue?style=flat-square" alt="GPL-3.0" />
    <img src="https://img.shields.io/badge/Version-v0.3.0-orange?style=flat-square" alt="v0.3.0" />
  </p>
</div>

---

## Overview

**MindStop (知止)** is a **local-first, 100% offline, free and open-source** Android self-control and mindful digital-living app.

Rather than treating rest as guilt or locking you out harshly, MindStop is built around one core goal: **helping you manage yourself instead of letting an app dictate your life**.

- **Zero Internet Permission**: Does not declare `android.permission.INTERNET`. Your rules, goals, question banks, sleep schedules, shopping notes, and behavior logs stay strictly on your device.
- **Full-Day Mindful Companion**: Connects four everyday friction points — daytime scrolling & focus, bedtime wind-down, post-alarm morning activation, and intentional shopping decisions.

---

## Key Features (v0.3.0)

### 1. Daytime App Quotas, Intention Gate & Focus
- **Shared App Quotas & Smart Preset Scanner**: Configure daily and per-session shared time budgets by app group or globally, with one-tap scanning for common short-video, social, gaming, reading, and shopping apps.
- **Intention Prompt & Calm Wait**: Before opening a supervised app, pause briefly to choose Study / Fun / Errand and set a planned duration, paired with a floating goal/countdown pill.
- **Independent Study Mode & Elastic Focus**: Study sessions are tracked separately without draining entertainment quotas; Elastic Focus includes a controlled lookup/interrupt budget and rewards early return.
- **Challenge Renewals & Cooldown**: Extend sessions with math, built-in English vocabulary/synonym questions (95 items), or your own UTF-8 CSV question bank — or take a calm cooldown.
- **Ginkgo Growth World**: Stopping on time plants a pending sprout that confirms automatically after a quiet verification window; confirmed history is never wiped by a single slip.
- **100 Curated Bilingual Offline Quotes**: Rotates one fresh Chinese/English quote per day (approx. 3 months without repetition) with zero network access.
- **Local Stats & UTF-8 BOM CSV Export**: Review on-time voluntary exit rate, reflection reasons, and export all records locally.

### 2. Bedtime Wind-down
- Unified **workday and weekend** sleep schedules distinguishing planned sleep opportunity from medical sleep tracking.
- 6-stage progressive wind-down reminders, customizable night restricted vs. allowed apps (reading, music, alarms), low-stimulation night mode (math challenges off by default), and quiet background-confirmed night rewards.

### 3. Morning Activation & Alarm
- Reliable exact alarms (`AlarmManager.setAlarmClock`), lock-screen ringing, custom ringtones, progressive volume, boot recovery, and optional dismiss challenges (normal, math, steps, QR code, NFC).
- **15-Minute Post-Alarm Transition**: Designed for the classic "turned off alarm, went back to bed" loop — pairs a 5-minute physical wake-up routine with a 10-minute morning joy activity, backed by a clear state machine and sleep-deprivation reward guard.

### 4. Intentional Shopping & Unit Price Analysis
- **6-Intent Shopping Gate**: Separates planned buying from logistics, customer service, returns, reviews, and casual browsing.
- **System Share Import**: Share product links/text from shopping apps straight into MindStop's candidate pool.
- **Comparable Unit Price & Percentiles**: Powered by `BigDecimal` base-unit normalization and dimension guards (inspired by MIT-licensed PriceGrab), computing sample `P25 / P50 / P75` and mean, plus **3-2-1 stopping rules** and a transparent user-assumption **search-cost estimator**.
- **24-Hour Cooling Wishlist & Actual Expense Tracker**: Park unplanned wants for 24 hours before deciding, and log real spending & refunds across 7 categories.

---

## Download & Installation

- **Latest Release (APK)**: https://github.com/roclee2692/mindstop-android/releases/latest
- **Source Code**: https://github.com/roclee2692/mindstop-android

Install on Android 8.0+ (API 26+), then follow the onboarding screen to enable Accessibility Service, Display Over Other Apps, Unrestricted Battery (plus Background Pop-up & Autostart on Xiaomi/HyperOS), and Exact Alarm permission if using Morning Activation.

---

## Developer, License & Non-Commercial Brand Notice

- **Developer**: Raelon Veritas Lee ([@roclee2692](https://github.com/roclee2692))
- **License**: Released under **GNU General Public License v3.0 (GPL-3.0)**. See [LICENSE](LICENSE). Any redistribution of binaries must provide the corresponding complete source code and preserve copyright notices.
- **Free & Non-Commercial Brand Notice**:
  - Official builds of MindStop are permanently **free, open source, ad-free, and tracker-free**.
  - The **"MindStop / 知止" brand name, Ginkgo-Hourglass icon, watercolor poster artwork, and original brand assets** may **not** be repackaged, resold, or used for unauthorized commercial distribution.
- **Open-Source Credits**: Derived from [Curbox Android](https://github.com/curbox-app/curbox-android) (GPL-3.0), with algorithms/architectural inspiration from [PriceGrab](https://github.com/mablanco/pricegrab) (MIT), [Upupup](https://github.com/Purgator/Upupup), [Fossify Clock](https://github.com/FossifyOrg/Clock), [NFC Alarm Clock](https://github.com/gabeg805/NFC-Alarm-Clock), and [Loop Habit Tracker](https://github.com/iSoron/uhabits). Full details in [NOTICE.md](NOTICE.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

## Support & Voluntary Donations

MindStop is 100% free with zero paywalls. If it helps you reclaim your focus, sleep, mornings, or spending clarity:
- Star the repository ⭐ and share the download link with friends (`Settings -> About MindStop -> Share download link`).
- Contributions via Issues and Pull Requests are warmly welcome.
- Voluntary donations to support ongoing maintenance are appreciated — details will be kept up to date on the [GitHub repository page](https://github.com/roclee2692/mindstop-android).

---

## Discovery Keywords

MindStop, Android App Blocker, Screen Time Manager, Digital Wellbeing, Mindful Phone Use, Self Control Training, Focus Timer, Bedtime Wind-down, Sleep Procrastination, Morning Alarm, Sleep Inertia, Intentional Shopping, Unit Price Calculator, Offline Android App, Privacy First, Zero Internet Permission, Open Source Android, GPL-3.0, Curbox Fork
