#!/usr/bin/env python3
import os
import platform
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LOCAL_PROPERTIES = ROOT / "local.properties"


def print_step(message: str) -> None:
    print(f"\n[setup] {message}")


def find_android_sdk_root():
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.getenv(key)
        if value:
            path = Path(value)
            if path.exists():
                return path.resolve()

    candidates = []
    if platform.system() == "Windows":
        localapp = os.getenv("LOCALAPPDATA")
        if localapp:
            candidates.append(Path(localapp) / "Android" / "Sdk")
        user_profile = os.getenv("USERPROFILE")
        if user_profile:
            candidates.append(Path(user_profile) / "AppData" / "Local" / "Android" / "Sdk")
        candidates.append(Path(r"C:\Android\Sdk"))
    elif platform.system() == "Darwin":
        candidates.append(Path.home() / "Library" / "Android" / "sdk")
    else:
        candidates.append(Path.home() / "Android" / "Sdk")
        candidates.append(Path("/opt/android-sdk"))

    for candidate in candidates:
        if candidate.exists():
            return candidate.resolve()
    return None


def ensure_local_properties(sdk_root: Path):
    LOCAL_PROPERTIES.write_text(f"sdk.dir={sdk_root}\n", encoding="utf-8")


def main():
    print_step("Checking environment")
    sdk_root = find_android_sdk_root()
    if sdk_root:
        print(f"Android SDK detected at: {sdk_root}")
        ensure_local_properties(sdk_root)
        print(f"Wrote {LOCAL_PROPERTIES}")
    else:
        print("Android SDK not detected. Install Android Studio or set ANDROID_HOME/ANDROID_SDK_ROOT.")


if __name__ == "__main__":
    main()
