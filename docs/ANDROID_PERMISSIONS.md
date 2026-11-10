# Android Permissions and Platform Restrictions

Target device: iQOO Neo 10R (vivo/iQOO Funtouch OS; Android version to be confirmed on device).

| Capability | Mechanism | Notes |
|---|---|---|
| Notifications | `NotificationListenerService`, user grants "Notification access" | Restricted settings may block it for sideloaded apps |
| SMS (Phase 4) | Runtime `RECEIVE_SMS` + `READ_SMS` | Separate opt-in; app is NOT the default SMS app. Fallback: notifications only |
| Gmail (Phase 5) | OAuth 2.0, `gmail.readonly` | No password stored; token in Android Keystore-backed storage |
| Network | `INTERNET`, `ACCESS_NETWORK_STATE` | HTTPS only; cleartext disabled |
| Post-reboot recovery | `RECEIVE_BOOT_COMPLETED` | WorkManager persists its own jobs |

## Sideloading restrictions (verified from Google support pages, Nov 2026)
- **Restricted settings (Android 13+):** if notification access or SMS is greyed out, open Settings > Apps > Finance Collector > More (⋮) > Allow restricted settings, then retry.
- **Developer verification:** enforced from Sep 2026 in Brazil, Indonesia, Singapore, Thailand; global expansion planned for 2027 on certified devices. Fallback: Developer options > "Allow apps from unverified developers" (one-time, 24-hour delay). Whether `adb install` is exempt is unverified; to be tested on the device.

## OEM background limits
vivo/iQOO devices aggressively kill background apps. The user should enable auto-start and set battery usage to unrestricted for the app. The listener uses `requestRebind` on disconnect, and the health screen reports listener state from real system state.

## Data minimization
SMS is read only from sender entities you enable (header route prefix and category suffix are ignored, e.g. VM-SBIUPI-S and AD-SBIUPI-T both match SBIUPI). Messages from any other sender, including phone numbers, are never stored or counted. The history scan reads the inbox by id with a saved checkpoint and does not keep messages in memory.

OTP/authentication notifications and messages are dropped before storage. Only allowlisted packages/senders are captured.
