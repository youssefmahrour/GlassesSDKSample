#!/usr/bin/env python3
import argparse
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def print_step(message: str) -> None:
    print(f"\n[run] {message}")


def find_gradle_command():
    if platform.system() == "Windows":
        return [str(ROOT / "gradlew.bat")]
    return ["./gradlew"]


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


def run_command(command, cwd=ROOT):
    print(f"Running: {' '.join(command)}")
    return subprocess.run(command, cwd=cwd, check=False)


def main():
    parser = argparse.ArgumentParser(description="Build the Android app and install it on a connected device")
    parser.add_argument("--skip-install", action="store_true", help="Build the APK without installing it")
    parser.add_argument("--skip-launch", action="store_true", help="Build and install without launching the app")
    args = parser.parse_args()

    print_step("Building the Android app")
    gradle = find_gradle_command()
    result = run_command(gradle + ["clean", "assembleDebug"])
    if result.returncode != 0:
        sys.exit(result.returncode)

    apk_path = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    if not apk_path.exists():
        print("APK was not generated at the expected path.")
        sys.exit(1)

    print(f"Build complete: {apk_path}")

    if args.skip_install:
        print("Skipping installation because --skip-install was provided.")
        return

    adb = find_adb()
    if not adb:
        print("adb was not found. Install Android platform-tools or add it to PATH.")
        sys.exit(1)

    print_step("Installing APK to the connected device")
    install_result = run_command([adb, "install", "-r", str(apk_path)])
    if install_result.returncode != 0:
        sys.exit(install_result.returncode)

    if not args.skip_launch:
        print_step("Launching the app")
        run_command([adb, "shell", "am", "start", "-n", "com.sdk.glassessdksample/.MainActivity"])

    print("Done.")


if __name__ == "__main__":
    main()
