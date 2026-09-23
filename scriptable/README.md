# 科信空教室 · Scriptable 免费版

这是给只有普通 Apple ID、只用 iPhone/iPad、无法安装未签名 IPA 的同学准备的替代版本。它**不是独立 App 或 IPA**：用户先安装中国区 App Store 中免费的 [Scriptable](https://apps.apple.com/cn/app/scriptable/id1405459188)，再运行本仓库的 `Kexin-Rooms.js`。界面使用 Scriptable 的原生输入框和表格，网络请求直连学校教务系统，没有网页套壳或中转服务器。

## 只用手机安装

1. 在 App Store 安装 [Scriptable](https://apps.apple.com/cn/app/scriptable/id1405459188)（App Store 中国区查询价格为免费；需要 iOS/iPadOS 15.5 或以上）。
2. 在本项目的 GitHub Release 中下载 `Kexin-Rooms.js`。在“文件”App 中把它移到 Scriptable 的脚本文件夹（位于“我的 iPhone/iPad”或 iCloud Drive 中；具体位置取决于 Scriptable 的存储设置）。
3. 打开 Scriptable，找到 `Kexin-Rooms` 并点运行。首次输入**自己的**教务用户名和密码；之后脚本从本机 Keychain 读取。学校要求验证码时会显示图片并让你手动输入。
4. 选择日期、节次和教学楼。每次运行都重新登录并读取学校当前学年、学期、校历和空教室分页数据，只显示科信校区（学校代码 106）的结果。

如果“文件”App 中看不到 Scriptable 文件夹，先打开一次 Scriptable；再检查 Scriptable 的 iCloud 同步设置和“文件”App 的“浏览”位置。不要把 `Kexin-Rooms.js` 放进 GitHub 仓库后再填写账号密码，账号只在手机运行时输入。

## 隐私与限制

- 脚本和 Release 文件**不含任何教务账号密码**。账号只保存在使用者设备上 Scriptable 的 Keychain；脚本仅向 `jwglxxfwpt.hebeu.edu.cn` 的登录与空教室接口发送请求。
- Scriptable 的 Keychain 属于 Scriptable 应用，不是本脚本独享。请不要在同一 Scriptable 中运行不可信的第三方脚本；可在结果页或报错弹窗中清除本机账号。
- 这不是学校官方应用。学校验证码、接口变更、网络不可达或学期日期不在可查询范围时，脚本会报错并停止显示结果，不会伪造或使用缓存数据。
- 已用脱敏学校响应样本验证日期、校区、查询参数、分页和 RSA 加密算法；尚未在用户的 iPhone/iPad 上完成真实账号端到端验收。收到现场反馈后才能确认设备上的具体运行表现。

开发验证：在项目根目录运行 `node scriptable/test_protocol.cjs`。
