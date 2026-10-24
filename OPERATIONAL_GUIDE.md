# Operational Guide: Stealth & Security Protocols

This document details the internal security mechanisms of O.G. Mess v3.4.2.

## 1. Stealth Mode (4 Hours 10 Minutes)

The app implements a persistent stealth state to protect team members during "interviews" or inspections. (The only affected user is the one that entered the zone).

- **Trigger**: Crossing the 150m radius of the coordinate in `UnsafePlacesRegistry.kt` (Updated based on new GeoJSON).
- **Duration**: **4 Hours and 10 Minutes (Fixed)**.
- **Behavior**:
    - The UI is immediately swapped to `CalendarScreen`.
    - Navigation to `SecretChatScreen` or Sub-Admin functions is disabled.
    - All network heartbeats are masked (Team sees "Offline", Admin sees "Unsafe").
- **Persistence**: The end-time is stored in `EncryptedSharedPreferences`. Rebooting the device does NOT reset the timer.

## 2. Admin Privileges (`admin_001`)

The device identified as `admin_001` (Master Tier) operates under different rules:

- **Immunity**: No geographic lockdowns. The Admin can enter Unsafe Zones without losing access to the messenger.
- **Visibility**: The only role capable of seeing the "Magenta" status dot for members in trouble.
- **Bypass**: Can manually force-refresh the relay fleet via the discovery system.

## 3. Communication Security

- **Forward Secrecy**: Handled via dynamic session keys (X25519).
- **Decoy Persistence**: The app "remembers" its unlocked state for the current session, but will revert to the decoy if the `UnsafeZone` trigger is hit.

## 4. Emergency Procedures

- **Wipe**: If a user is compromised, the Admin can (via future command packets) trigger a remote wipe of the local `EncryptedSharedPreferences`.
  - **Stealth Manual Override**: There is currently NO manual override for the 4h 10m stealth window to ensure the user cannot be coerced into opening the app.
  