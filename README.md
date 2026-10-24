# O.G. Mess (v3.4.2-ULTIMATE)

## 🛡️ Secure Communication & Geofenced Stealth System

O.G. Mess is a high-security messaging framework designed for operational environments where privacy and deniability are paramount. It features a "Decoy UI" (Calendar) that masks a powerful, encrypted communication hub.

### 🚀 Key Features

*   **Dual-Layer UI (The Decoy)**: App starts as a fully functional Calendar. Access to the secure messenger requires a specific sequence (5-click trigger or PIN).
*   **Military-Grade Encryption**: Uses **X25519** for key exchange and **AES-GCM** for message payloads.
*   **Automatic Stealth Mode (Unsafe Zones)**: 
    *   Pre-defined geographic "Unsafe Zones" trigger an automatic lockdown.
    *   **4-Hour Safety Window**: Entering a zone activates a 4-hour persistent "Stealth Mode" where the app remains locked to the decoy UI, even if the user exits the perimeter early.
*   **Operational Privacy**:
    *   **Dual Heartbeats**: Members see colleagues as "ONLINE". Only the **Admin** sees detailed security statuses (Unsafe/Dormant).
    *   **Admin Immunity**: The Admin device (`admin_001`) is immune to geographic lockdowns for continuous command and control.
*   **Resilient Infrastructure**: 
    *   Custom WSS Relay with ngrok-bypass.
    *   Dynamic relay discovery via encrypted GitHub phonebook.

### 🛠️ Technical Stack

*   **Language**: Kotlin (Jetpack Compose)
*   **Architecture**: MVVM with Coroutines & Flow
*   **Security**: Android Jetpack Security Crypto + BouncyCastle
*   **Networking**: Ktor (WebSockets)
*   **Background**: Foreground Services with Android 14+ compatibility (Location & DataSync)

### 📂 Project Structure (The "Point Zero" Snapshot)

- `app/src/main/java/com/lasvegas/secmess/`
    - `crypto/`: Key management and stealth logic.
    - `network/`: WebSocket relay and invitation handlers.
    - `ui/`: Decoy Calendar, Admin Hub, and Secret Chat screens.
    - `util/`: Real-time location monitoring and security triggers.
- `SecMessRelay/`: Python-based master relay server.

### 🚦 Quick Start

1.  **Clone the Repository**: Ensure you use the "Point Zero" commit.
2.  **Configure Relay**: Update `relay_config.txt` with your current ngrok/server URL.
3.  **Build**: Open in Android Studio, sync Gradle, and run `:app:assembleDebug`.

---
*Note: This repository is part of a monitored security research project. Unauthorized use or modification of security protocols is discouraged.*
