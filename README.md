# SendButtonSend

An Xposed (LSPosed / libxposed API 102) module that triggers the **send button** in
WeChat / QQ / Tim chat pages with a single broadcast — so you can send a message
from a hardware key, an automation app, Tasker, Xposed Edge, `adb`, etc.

[中文说明](README.zh-CN.md)

## Download

Prebuilt APKs are published on the
[Releases](https://github.com/Deleted-Account/WeChatQQTim_SendButtonSend_LSPosedModule/releases)
page (debug and release variants). This repository tracks source only.

## Why this module

Scrcpy (with UHID keyboard) lets you type into the phone's chat input box straight
from your PC keyboard — but the on-screen **send** button cannot be clicked that
way. This module is the workaround for that: keep typing with scrcpy, then fire
one broadcast to hit the real send button programmatically.

## Recommended setup

- **IME: [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android)** —
  Chinese/English input on the phone, pairs well with scrcpy typing.
- **Trigger: Xposed Edge Pro** (Root access optional), two suggested key bindings
  (Shell command):

| Purpose | Command |
|---|---|
| Send the typed message (suggested: F12 — does not conflict with scrcpy) | `am broadcast -a com.anonymous.sendbuttonsend.SEND` |
| Toggle pinyin / English input | `IME=org.fcitx.fcitx5.android/.input.FcitxInputMethodService; PY=-988136023; EN=283797060; cur=$(settings get secure selected_input_method_subtype); if [ "$cur" = "$PY" ]; then tgt=$EN; else tgt=$PY; fi; settings put secure selected_input_method_subtype $tgt; ime set $IME` |

`PY` / `EN` are IME subtype IDs and can differ per device. If the toggle does not
switch input, read the current ID with
`settings get secure selected_input_method_subtype` while in pinyin mode and in
English mode, and substitute your own values.

## How it works

The module hooks two **system framework methods** (independent of the target
app's own implementation, so it survives app updates):

| Hook | Purpose |
|---|---|
| `android.app.Application#onCreate` | Register an exported broadcast receiver for `com.anonymous.sendbuttonsend.SEND` |
| `android.app.Activity#onResume` | Track the current foreground activity |

On broadcast, it walks the activity's `DecorView`, collects send-button
candidates, filters by `isShown() && isEnabled() && isClickable()`, and calls
`performClick()` on the first real match.

**Foreground judgment lives in the module, not in your command.** The broadcast
is delivered to every process of every scoped app; each process decides for
itself whether to respond (non-target packages return immediately, processes
without a foreground activity skip). So the trigger command stays a plain
broadcast with no `dumpsys`/focus checks — those are unreliable anyway
(Xposed Edge steals focus when it fires, and multi-process apps like WeChat
only have a foreground activity in their main process).

### Per-app button matching

Each app implements its send button differently, so matching is per-package:

| App | Send button | Matched by |
|---|---|---|
| WeChat (`com.tencent.mm`) | `Button id=bql text="Send"` | text / content-description `Send` / `发送` |
| QQ (`com.tencent.mobileqq`) | `AIOInputSendBtn id=send_btn` | class name contains `SendBtn` or resource id `send_btn` |
| Tim (`com.tencent.tim`) | `AIOInputSendBtn id=send_btn` | same as QQ (shared codebase) |

> QQ/Tim's send button is a **custom view, not a TextView** — text matching
> alone can never find it. That is why id/class-name matching is used there.

## Usage

The module must be enabled in LSPosed with scope covering the target apps
(`com.tencent.mm` / `com.tencent.mobileqq` / `com.tencent.tim`).

Send a message (you must be **on a chat page with text typed and the send
button visible**):

```sh
adb shell am broadcast -a com.anonymous.sendbuttonsend.SEND
```

Diagnostic broadcast — dumps the current view tree to logcat without clicking
(useful when adapting to a new app or a UI change):

```sh
adb shell am broadcast -a com.anonymous.sendbuttonsend.DUMP
adb logcat -s SendButtonSend
```

Xposed Edge Pro: `Keys -> any key -> Shell command` (Root access optional):

```sh
am broadcast -a com.anonymous.sendbuttonsend.SEND
```

Do **not** add `-p <package>` or foreground checks — the module already filters.

## Requirements

- Android 8.0+ (minSdk 26)
- LSPosed / Vector (or any libxposed API 102 framework)
- Build: JDK 17 + Android SDK (platform 37, build-tools 37.0.0)

## Build

```sh
cd SendButtonSend
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug    # debug APK
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk ANDROID_HOME=~/Android/Sdk ./gradlew assembleRelease  # release APK
```

Two artifacts are produced (both signed with the debug keystore so either can
be installed over the other with `adb install -r`):

| Variant | Size | Notes |
|---|---|---|
| debug | ~44 KB | carries an extra ~119 KB R8 desugar-shim dex (debug-only toolchain overhead) |
| release | ~12 KB | no minification — the module has a single entry class, so R8 would save a few KB at best while risking the reflected Xposed entry class name |

## After (re)installing

On Vector, the module's in-memory registry still points at the old APK path
after a reinstall, so re-enable it (run on the phone, `su` from adb shell
fails):

```sh
su -c "/data/adb/lspd/cli modules disable com.anonymous.sendbuttonsend"
su -c "/data/adb/lspd/cli modules enable com.anonymous.sendbuttonsend"
```

Then force-stop and relaunch the target apps so they pick up the new code.

## Notes & limitations

- `performClick()` returning `true` does not guarantee the message was sent —
  always verify visually.
- The send button must be **visible** when the broadcast arrives; the module
  will not and should not force-send from a wrong screen.
- Multi-process apps respond to the broadcast in several processes; only the
  main process has a foreground activity, so `no current activity` logs from
  other processes are expected.

## License

Unlicense / public domain — do whatever you want with it.
