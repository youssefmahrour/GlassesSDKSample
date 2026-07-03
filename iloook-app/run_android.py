#!/usr/bin/env python3
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def print_step(message: str) -> None:
    print(f"\n[run] {message}")


def find_adb():
    for candidate in [
        os.getenv("ANDROID_HOME"),
        os.getenv("ANDROID_SDK_ROOT"),
        str(ROOT / "sdk"),
    ]:
        if candidate:
            base = Path(candidate)
            for exe in [base / "platform-tools" / "adb", base / "platform-tools" / "adb.exe"]:
                if exe.exists():
                    return str(exe)
    return shutil.which("adb")


def main():
    print_step("Build and install")
    print("This wrapper expects the Android project to be present in the current folder or a nearby directory.")
    print("Adjust the command below if your project uses a different build entry point.")

    adb = find_adb()
    if not adb:
        print("adb was not found. Install Android platform-tools or add it to PATH.")
        sys.exit(1)

    print(f"ADB found at: {adb}")
    print("Use your preferred Gradle or Android Studio workflow to build and install the app.")


if __name__ == "__main__":
    main()
