# Chessington

Hidden chess move assistant for Chess.com rated games. Runs on your Android phone as a system overlay, reads the board from the screen, thinks with Stockfish 16 at full strength, and plays the best move for you.

## Features

- Fully hidden — zero-alpha overlay, no touch interception, invisible to screen recording of your own UI
- Board detection from screen capture (works on Chess.com's board, light/dark themes)
- Stockfish Skill Level 20, 300 ms per move, straight UCI
- Auto-moves pieces via accessibility gestures — tap-drag from source to destination square
- Undefeated mode: engine never declines, never blunders, plays the top move every ply

## Install

1. Go to the **Actions** tab of this repo
2. Open the latest **Build Chessington APK** run
3. Download the `Chessington-debug-apk` artifact
4. Sideload it (Settings -> Security -> Install unknown apps)

## Setup on device

1. Open Chessington
2. Tap **1. Enable Accessibility** and turn on "Chess Assistant"
3. Tap **2. Go Undefeated** and accept the screen-capture prompt
4. Open Chess.com, start a rated game — the assistant picks up the board on the first stable frame and starts moving

## Notes

- First stable frame is treated as the board reference; start from a position you want it to see (the game start position is ideal)
- Calibration is automatic — it scans for the 8x8 grid every capture pass
- Android 9+ (minSdk 26)
