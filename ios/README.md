# 科信空教室 iOS

原生 SwiftUI 客户端，直连河北工程大学教务系统，仅查询东校区（科信学院，代码 106）的空闲教室。每次查询都从学校重新读取当前学年、学期、校历和筛选配置。无 WebView、代理服务器或本地结果缓存。

没有付费开发者账号、只用手机的同学可使用本项目另行提供的 [免费 Scriptable 脚本版](../scriptable/README.md)。它采用 Scriptable 的原生列表和本机钥匙串，但不是本 SwiftUI 应用的独立安装包。

## 个人设备安装

本目录的 CI 构建产物是**未签名 IPA**。iPhone 不能直接点开安装，需由设备持有者使用 AltStore Classic / AltServer 在 Windows 或 Mac 上用自己的 Apple ID 签名并安装。免费 Apple ID 的安装通常需每 7 天刷新一次；不要把 Apple ID、密码、签名证书、设备 UDID 上传到本仓库。正式面向公众分发须另行处理签名及适用备案要求。侧载方式本身不是备案豁免。

Windows 个人安装：

1. 按 [AltStore 官方 Windows 指南](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows) 安装 Apple 官网版 iTunes、iCloud 与 AltServer。
2. 用数据线连接 iPhone，解锁并在手机上信任电脑；在 iTunes 中登录你自己的 Apple ID。
3. 按住 Shift 点击任务栏的 AltServer 图标，选择 **Sideload .ipa…**，选取 `Kexin-Rooms-iOS-1.0.0-unsigned.ipa`。AltServer 会在你的电脑上为你的设备签名并安装。
4. 在 iPhone 的“设置 → 通用 → VPN 与设备管理”中信任对应开发者；iOS 16 及以上还要在“设置 → 隐私与安全性 → 开发者模式”开启开发者模式。
5. 免费 Apple ID 安装后通常每 7 天需用同一电脑重新签名安装，或使用 AltStore Classic 的刷新功能。首次打开应用再输入教务账号密码。

这一步需要你的 iPhone 和 Apple ID，因此 CI 只能验证编译与未签名包结构，不能代替你完成手机上的签名、安装或真实账号登录验收。

## 构建

Xcode 16+、XcodeGen、iOS SDK。在 `ios` 目录执行：

```sh
xcodegen generate
xcodebuild -project KexinRooms.xcodeproj -scheme KexinRooms -configuration Release -sdk iphoneos -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
mkdir -p Payload
cp -R build/Build/Products/Release-iphoneos/KexinRooms.app Payload/
zip -qr Kexin-Rooms-iOS-1.0.0-unsigned.ipa Payload
```

签名只在设备持有者本机完成。登录信息使用 iOS Keychain 的仅本机可用属性保存；首次需在 App 内自行输入教务账号密码，学校要求验证码时手动输入。
