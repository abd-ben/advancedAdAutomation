# 📱 AdAutomation

**AdAutomation** is an Android app that automates ad interactions and reward collection in mobile games — especially tailored for **Score! Match**, but easily adaptable to others.

It uses Android’s Accessibility Service to simulate user taps and swipes, letting your phone do the ad-grinding for you!

---

## ⚙️ Features

- 🔁 Fully automated farming loop:
  - 30 minutes of activity → 8-minute pause → auto-restart
- 🎁 Automatic detection & opening of the *Free Package*
  - Custom delay available for the first run
  - Manual reset option for the free package timer
- 🧠 Detects & exits system popups (e.g. "Open with", browsers, Play Store)
- 💤 Keeps the screen awake via `WakeLock`
- ✨ Simulates precise coordinate clicks to skip ads & collect rewards
- 🛠️ Easily extendable to other games thanks to clean architecture

---

## 🧪 What's New

- 🕐 **Smart Free Package Handling**
  - First run supports a custom delay in minutes
  - Automatically reverts to a default 4-hour delay after use
- 🔁 **Service control via BroadcastReceiver**
  - Supports START / STOP / RESET actions
- 📲 **Simple UI**
  - Input for custom free package time (in minutes)
  - Buttons for start, stop, and reset
  - Shortcut to Accessibility settings

---

## 📦 Project Structure

- `MainActivity`:
  - UI with:
    - Input for free package delay
    - Start / Stop / Reset buttons
    - Accessibility settings redirect
- `AdAutomationService`:
  - Core automation logic (gestures, timing, reward flow)
  - Handles popup interruptions (browser, Google Play...)
  - Manages the free package timer
- Integrated `BroadcastReceiver`: allows remote service control (via app or `adb`)

---

## 🚀 How to Use

1. Install the app on your Android device.
2. Open the app and tap **"Enable Service"** — this will open Android Accessibility settings.
3. In the list, enable **AdAutomation** as an accessibility service.
4. Go back and (Optional) Enter the **remaining time** (in minutes) if it's your first run.
5. Tap **"Start Automation"**.
6. The app will:
   - Launch **Score! Match**
   - Watch and skip ads, collect rewards
   - Open the free package if available
   - Pause for 8 minutes
   - Restart the cycle automatically

---

## 🛠️ Built With

- **Language:** Java
- **Platform:** Android
- **Min SDK:** 24 (Android Nougat)
- **Core components:**
  - `AccessibilityService`
  - `BroadcastReceiver`
  - `ScheduledExecutorService`
  - `PowerManager.WakeLock`
  - `GestureDescription` (tap/swipe simulation)
  - `SharedPreferences`

---

## ⌛ Useful ADB Commands

- Start automation with a custom free package delay:
  ```bash
  adb shell am broadcast -a com.hfad.adautomation.START_SERVICE --ei remainingTime 300000
  ```
- Stop the automation:
  ```bash
  adb shell am broadcast -a com.hfad.adautomation.STOP_SERVICE
  ```
- Reset the free package timer:
  ```bash
  adb shell am broadcast -a com.hfad.adautomation.RESET_FREE_PACKAGE
  ```

---
  
## 📋 TODO

- [ ] Make coordinates editable from the UI
- [ ] Add detection of UI elements (OCR or layout scanning)
- [ ] Show logs and current status in-app
- [ ] Support multiple games

---

## ⚠️ Disclaimer

> This app uses Android Accessibility Services to simulate user interaction.
>
> It is intended for **personal use only**. Automating other apps may violate their terms of service. Use responsibly.

---

## 👨‍💻 Author

Built with ☕ & ⚡ by [ABD BEN]

---

## 📄 License

This is a private project for personal use.
Feel free to fork it, adapt it, or suggest improvements.

---
