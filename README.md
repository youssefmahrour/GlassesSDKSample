# Glasses SDK Sample

A polished Android sample app and desktop companion for experimenting with smart glasses integrations, device connection flows, and simple PC-side control scenarios.

## Overview

This project combines:

- an Android sample app built around the Glasses SDK
- a lightweight Python desktop controller for testing and basic command exchange
- a clean project structure suitable for learning, prototyping, and extending

## Features

- Android sample app with device-related UI and app entry points
- Bluetooth and Wi-Fi related sample code paths
- Python desktop app for local testing and command interaction
- Simple project layout for rapid experimentation

## Project Structure

- `app/` — Android application source and resources
- `pc_replacement_app.py` — desktop Python controller
- `build.gradle` / `settings.gradle` — Gradle project configuration

## Requirements

### Android
- Android Studio
- JDK 17 or newer
- Android SDK 34

### Desktop
- Python 3.9+
- Tkinter (included with most Python installs)

## Getting Started

### Windows
1. Install Python 3.9+.
2. Install Android Studio and the Android SDK.
3. Connect a phone or start an emulator.
4. Run:

```powershell
./setup.ps1
./run.ps1
```

### macOS / Linux
1. Install Python 3.9+.
2. Install Android Studio and the Android SDK.
3. Connect a phone or start an emulator.
4. Run:

```bash
chmod +x ./setup.sh
./setup.sh
./setup.sh --skip-install
./run_android.py
```

### Desktop app
From the project root, run:

```powershell
python pc_replacement_app.py
```

## Usage Notes

- The desktop app starts in demo mode so it can be tested immediately.
- You can connect to a real host/port if you want to extend it for live device communication.
- The Android sample is intended as a foundation for your own smart-glasses integration work.

## License

This project is provided as a sample for development and experimentation.
