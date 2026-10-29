# CodeForge IDE for Android

A native Android Studio-inspired mobile IDE foundation with a project tree, text editor, file creation, saving, Gemini assistant, and GitHub Actions debug APK builds.

## APK

Pushes to main trigger the included GitHub Actions workflow. It installs Java, Android SDK, and Gradle, builds the debug APK, and uploads it as an artifact named AndroidStudioForAndroid-debug. Open the Actions tab, select the latest successful Build APK run, and download the artifact.

## Gemini

Tap Gemini in the app and enter a Google Gemini API key. The key is held only for the current app session and is not committed to the project. For production, proxy Gemini through a backend so the key is never exposed in the client.

This is a functional foundation, not a full replacement for Android Studio. Future additions include syntax highlighting, project import/export, on-device Gradle execution, terminal, emulator/device deployment, and secure backend-managed Gemini file operations.