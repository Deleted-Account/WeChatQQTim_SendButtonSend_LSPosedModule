# SendButtonSend（发送按钮广播触发模块）

一个 Xposed（LSPosed / libxposed API 102）模块：发一条广播即可触发**微信 / QQ / Tim**
聊天页的发送按钮——从此可以用硬件按键、自动化 App、Tasker、Xposed Edge、`adb`
等方式发消息。

[English](README.md)

## 工作原理

模块 Hook 两个**系统框架方法**（与目标 App 自身实现无关，抗 App 更新）：

| Hook | 用途 |
|---|---|
| `android.app.Application#onCreate` | 注册广播接收器，监听 `com.anonymous.sendbuttonsend.SEND` |
| `android.app.Activity#onResume` | 跟踪当前前台 Activity |

收到广播后遍历前台 Activity 的 `DecorView`，收集发送按钮候选，
按 `isShown() && isEnabled() && isClickable()` 过滤后 `performClick()`。

**前台判断在模块侧做，命令侧不管**。广播会发给所有作用域 App 的所有进程，
每个进程自己决定是否响应（非目标包直接 return，没有前台 Activity 的进程跳过）。
所以触发命令就是一条裸广播，不需要 `dumpsys` / 焦点判断——那些本来就不可靠
（Edge 触发时会抢焦点；微信等多进程 App 只有主进程有前台 Activity）。

### 各 App 按钮匹配条件

各 App 发送按钮实现不同，按包名分流匹配：

| App | 发送按钮 | 匹配方式 |
|---|---|---|
| 微信（`com.tencent.mm`） | `Button id=bql text="Send"` | 文字/desc 为 `Send` / `发送` |
| QQ（`com.tencent.mobileqq`） | `AIOInputSendBtn id=send_btn` | 类名含 `SendBtn` 或 resource-id 为 `send_btn` |
| Tim（`com.tencent.tim`） | `AIOInputSendBtn id=send_btn` | 同 QQ（同一套代码库） |

> QQ/Tim 的发送按钮是**自定义 View，不是 TextView**，`getText()` 拿不到文字——
> 只用文字匹配永远找不到它，所以才按 id/类名匹配。

## 使用

在 LSPosed 里启用模块，作用域勾选目标 App
（`com.tencent.mm` / `com.tencent.mobileqq` / `com.tencent.tim`）。

发送消息（**必须停在聊天页、输入框有字、发送按钮可见**）：

```sh
adb shell am broadcast -a com.anonymous.sendbuttonsend.SEND
```

诊断广播——只 dump 当前视图树到 logcat，不点击（适配新 App 或 UI 变更时用）：

```sh
adb shell am broadcast -a com.anonymous.sendbuttonsend.DUMP
adb logcat -s SendButtonSend
```

Xposed Edge Pro：`Keys -> 任意键 -> Shell command`（Root 可选）：

```sh
am broadcast -a com.anonymous.sendbuttonsend.SEND
```

**不要**加 `-p <包名>` 或前台判断——模块侧已经过滤了。

## 环境要求

- Android 8.0+（minSdk 26）
- LSPosed / Vector（或任何 libxposed API 102 框架）
- 构建：JDK 17 + Android SDK（platform 37、build-tools 37.0.0）

## 构建

```sh
cd SendButtonSend
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug    # debug 包
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk ANDROID_HOME=~/Android/Sdk ./gradlew assembleRelease  # release 包
```

两个产物都用 debug keystore 签名，可互相 `adb install -r` 覆盖升级：

| 变体 | 大小 | 说明 |
|---|---|---|
| debug | ~44 KB | 多带一个 ~119KB 的 R8 脱糖垫片 dex（debug 构建专有的工具链开销） |
| release | ~12 KB | 不开混淆——本模块只有一个入口类，R8 最多省几 KB，反而要配 keep 规则防反射加载的入口类名被改 |

## 重装模块后

Vector 上重装 APK 后内存注册表仍指向旧路径，广播会无响应，需要重新挂载
（在**手机上**执行，adb shell 里跑 `su` 会认证失败）：

```sh
su -c "/data/adb/lspd/cli modules disable com.anonymous.sendbuttonsend"
su -c "/data/adb/lspd/cli modules enable com.anonymous.sendbuttonsend"
```

然后 force-stop 并重新打开目标 App 加载新代码。

## 注意事项

- `performClick()` 返回 `true` 不等于消息真发出——务必目视确认。
- 广播到达时发送按钮必须**可见**；模块不会也不应该在错误的界面强行发送。
- 多进程 App 会在多个进程响应同一广播，只有主进程有前台 Activity，
  其他进程打 `no current activity` 属预期。

## License

Unlicense / 公有领域——随意使用。
