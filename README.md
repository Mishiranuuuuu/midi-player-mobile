# Midi Player Mobile

Midi Player Mobile is an Android app for playing MIDI files and using them as a macro for in-game instruments.

Originally, this project was created for Heartopia, but it can also be adapted for other games or instruments that use similar input timing.

A note on the UI: The interface was built with AI assistance.

## Features

- Play MIDI files on mobile
- Use MIDI playback as a macro for in-game instruments
- Simple project structure for editing and customization
- Easy to modify for other games or layouts

# For development purpose

## Requirements

- [Android Studio installed](https://developer.android.com/studio)
- A device or emulator for testing
- Basic knowledge of Android development if you want to edit the source
- Maybe a session installation app if your device refuse to install
We recommend using [Split APKs Installer (SAI)](https://play.google.com/store/apps/details?id=com.mtv.sai) but other works fine

## Build Instructions

1.  Clone the repository:
    ```bash
    git clone https://github.com/Mishiranuuuuu/midi-player-mobile
    cd midi-player-mobile
    ```

2.  Build the app:
    You can use
    ```bash
    ./gradlew assembleDebug
    ```
    For a debug build, or using
    ```bash
    ./gradlew assembleRelease
    ```
    For a release build.
    > Release builds require a signing keystore. See Android's [signing guide](https://developer.android.com/studio/publish/app-signing) to set one up before running this.

You can edit the layout of the marker to match other games so it work with more games too

# License

MIT License