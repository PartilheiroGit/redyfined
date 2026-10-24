# SecMess Project Status Checklist

This document tracks the current state of the project, implemented features, and pending items.

## 🟢 Implemented & Working
- [x] **Disguise System**: Functional Calendar facade with secret interactions.
    - [x] **Setup**: Long-press Top Bar (Month/Year) -> Highlights digits as you tap 4 days -> "PIN Saved".
    - [x] **Unlock**: Long-press 1st PIN digit (it highlights) -> Tap remaining 3 digits (they highlight) -> "Correct PIN" -> Unlock.
    - [x] **Visual Feedback**: Real-time instruction labels and digit highlighting during PIN entry.
    - [x] **Agenda Lock**: Agenda editing is disabled while a PIN sequence is being recorded.
- [x] **Stable Network**: Reverted to stable hostname-based WSS (VPN compatible).
- [x] **Hierarchical Roles**: Admin (Master), Referrer (Sub-Admin), and Client (Standard).
- [x] **First-Use PIN**: Users set their own PIN using calendar day taps on the first unlock attempt.
- [x] **Opaque QR Codes**: Invitation payloads are double-encoded (`SEC_V1_` prefix) and unreadable by standard scanners.
- [x] **Location Sharing**: Users can share encrypted GPS pins in chat.
- [x] **Remote Admin Control**: Kill-switch (Disable Access) and Promotion (Standard -> Referrer) via system commands.

## 🟡 In Progress / Needs Verification
- [ ] **User Lists**: Verifying correct contact visibility for Admin vs. Referrer vs. Client (User said: "cant check this").
- [ ] **VPN Stability**: Testing persistent connectivity across different VPN providers.
- [ ] **100+ User Scale**: Verifying UI performance of the `UserListPanel` with 100+ active entries.

## 🔴 Missing or Wrong (Need Feedback)
- [ ] **Advanced Permissioning**: Finer control over who can see whom in the user list.

---
> [!TIP]
> You can edit this file directly or tell me to check/uncheck items as we progress.
