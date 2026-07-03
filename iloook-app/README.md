# ilook-app Helper Scripts

This folder contains a portable setup and build workflow for the ilook-app project.

## What is included
- `setup.py` for detecting Java and Android SDK paths
- `setup.ps1` and `setup.sh` for easy setup on Windows and Unix-like systems
- `run_android.py` for building and installing the APK on a connected device
- `run.ps1` for Windows users

## Quick start

### Windows
```powershell
./setup.ps1
./run.ps1
```

### macOS / Linux
```bash
chmod +x ./setup.sh
./setup.sh
python3 run_android.py
```
