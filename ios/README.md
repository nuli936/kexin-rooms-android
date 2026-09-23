# 科信空教室 iOS

原生 SwiftUI 客户端，直连河北工程大学教务系统，仅查询东校区（科信学院，代码 106）的空闲教室。每次查询都从学校重新读取当前学年、学期、校历和筛选配置。无 WebView、代理服务器或本地结果缓存。

## 个人设备安装

本目录的 CI 构建产物是**未签名 IPA**。iPhone 不能直接点开安装，需由设备持有者使用 AltStore Classic / AltServer 在 Windows 或 Mac 上用自己的 Apple ID 签名并安装。免费 Apple ID 的安装通常需每 7 天刷新一次；不要把 Apple ID、密码、签名证书、设备 UDID 上传到本仓库。正式面向公众分发须另行处理签名及适用备案要求。

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
