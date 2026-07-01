# WifiDog Authenticator – Build Instructions

## Prerequisites
- Android Studio Flamingo (2022.2.1) or newer  **OR** command-line SDK tools
- Java 8+ (JDK 11 recommended)
- Android SDK with API 33 and API 21 platforms installed

---

## 1 · Import into Android Studio

1. Extract the ZIP (or clone the repo).
2. Open Android Studio → **File → Open** → select the root `WifiDogAuthenticator` folder.
3. Wait for Gradle sync to complete.
4. Connect a device or start an emulator.
5. Click **Run ▶**.

---

## 2 · Build APK from the command line

```bash
# In the project root
./gradlew assembleDebug
```

The APK will be at:
```
app/build/outputs/apk/debug/app-debug.apk
```

For a release APK (unsigned):
```bash
./gradlew assembleRelease
```

---

## 3 · AIDE (Android IDE on-device)

1. Copy the entire `WifiDogAuthenticator` folder to the device (e.g. `/sdcard/WifiDogAuthenticator`).
2. Open AIDE → **Open Project** → select the folder.
3. Tap **Build → Build Project**.
4. Tap **Run** to install and launch.

**Note:** AIDE may not support `build.gradle` features added after API 26.
Use `compileSdkVersion 33` with `minSdkVersion 21` as configured.

---

## 4 · Configure AES Key & IV

Edit `CryptoService.java`:

```java
private static final String KEY_HEX = "000102030405060708090a0b0c0d0e0f"; // your 16-byte key
private static final String IV_HEX  = "101112131415161718191a1b1c1d1e1f"; // your 16-byte IV
```

Replace the placeholder hex strings with your actual AES-128 key and IV (each must be exactly **32 hex characters = 16 bytes**).

If you paste the session URL directly without encryption, the app will fall back to treating the input as a plain URL automatically.

---

## 5 · Runtime Usage

1. **IP Address** – enter the WifiDog gateway IP (e.g. `192.168.1.1`).
2. **Encrypted URL** – paste the Base64-encoded AES-encrypted portal URL  
   (or paste the plain portal URL directly if encryption is not used).
3. Tap **START** – the app will:
   - Decrypt the URL (or use it as-is)
   - Request battery optimisation bypass (accept it for best reliability)
   - Start a foreground service that survives screen-off and app backgrounding
   - Fetch the `sessionId` from the portal redirect URL
   - Send auth requests every 10 s in batches of 3
4. Tap **STOP** to terminate the loop.

---

## 6 · Auto-restart on Boot

The `BootReceiver` restarts the service after reboot if it was running when the device was last shut down.  No additional configuration is required.

---

## 7 · Project Structure

```
WifiDogAuthenticator/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/violet/wifidogauthenticator/
│   │   │   ├── activities/
│   │   │   │   └── MainActivity.java
│   │   │   ├── adapters/
│   │   │   │   └── LogAdapter.java
│   │   │   ├── models/
│   │   │   │   └── LogModel.java
│   │   │   ├── services/
│   │   │   │   ├── AuthForegroundService.java
│   │   │   │   ├── AuthService.java
│   │   │   │   └── BootReceiver.java
│   │   │   └── utils/
│   │   │       ├── CryptoService.java
│   │   │       ├── Logger.java
│   │   │       └── SessionExtractor.java
│   │   └── res/
│   │       ├── drawable/
│   │       │   ├── btn_clear_bg.xml
│   │       │   ├── btn_start_bg.xml
│   │       │   ├── btn_stop_bg.xml
│   │       │   └── input_bg.xml
│   │       ├── layout/
│   │       │   ├── activity_main.xml
│   │       │   └── item_log.xml
│   │       ├── values/
│   │       │   ├── colors.xml
│   │       │   ├── strings.xml
│   │       │   └── themes.xml
│   │       └── xml/
│   │           └── network_security_config.xml
│   ├── build.gradle
│   └── proguard-rules.pro
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── build.gradle
├── settings.gradle
└── BUILD_INSTRUCTIONS.md
```

---

## 8 · Dependencies Used

| Library | Version | Purpose |
|---------|---------|---------|
| `androidx.appcompat` | 1.6.1 | AppCompat base |
| `material` | 1.9.0 | Material Design components |
| `constraintlayout` | 2.1.4 | Flexible layouts |
| `recyclerview` | 1.3.1 | Log list |
| `okhttp3` | 4.10.0 | HTTP networking + redirect following |

All are standard AndroidX / Google libraries compatible with AIDE.
