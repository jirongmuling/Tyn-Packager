# Tyn Packager

把 Tyranor-Next 里已扫描识别的 **Kirikiri 游戏**制作成**直装版 APK** 的工具。

## 定位

Tyn Packager 是**独立应用**（包名 `com.tyranor.next.packager`），与 Tyranor-Next 分开维护：

- **Tyranor-Next** 负责扫描识别游戏，并通过只读 `ContentProvider` 共享游戏数据；
- **Tyn Packager** 负责读取这些数据、改造 APK（包名、图标、内置游戏资源）、签名出包。

两者通过 Tyranor 的 `docs/GAME_LIBRARY_PROVIDER.md` 契约定向通信，不需要 Tyranor 内置打包能力。

## 功能规划

| 能力 | 状态 |
|---|---|
| 读取 Tyranor 游戏库（只读） | ✅ 已完成 |
| 所有文件访问权限引导 + 首启引导 | ✅ 已完成 |
| 改包名 / 改应用名 | ✅ 已完成 |
| 替换启动器图标（本地图片，五档尺寸） | ✅ 已完成 |
| 按游戏名抓取封面（VNDB） | ✅ 已完成 |
| 内置游戏资源进 APK（流式写入） | ✅ 已完成 |
| 测试签名（自签证书，设备内持久化） | ✅ 已完成 |
| 自定义签名（PKCS12/JKS/BKS keystore） | ✅ 已完成 |
| APK 签名（apksig v1/v2/v3） | ✅ 已完成 |
| 产物安装 / 分享 / 导出到下载目录 | ✅ 已完成 |
| 打包历史记录 | ✅ 已完成 |

## 技术栈

- Kotlin 2.3.20 + Compose（Material 3）
- AGP 9.3.3 / Gradle 9.5.1，minSdk 26，compileSdk 37
- [ARSCLib](https://github.com/REAndroid/ARSCLib) 1.4.0 —— 二进制 `AndroidManifest.xml` / `resources.arsc` 读写
- `com.android.tools.build:apksig` 9.5.2 —— AOSP 官方 APK 签名
- zip4j 2.11.6 —— APK zip 读写

三者均为 Apache-2.0，可安全用于本项目。

## 构建

```bash
./gradlew :app:assembleDebug
```

CI 见 `.github/workflows/android-ci.yml`。

可用 `-PTYRANOR_PACKAGE=xxx` 覆盖 Tyranor 包名（默认 `com.tyranor.next`）。

## 目录结构

```
app/src/main/java/com/tyranor/next/packager/
├── PackagerApp.kt              应用入口
├── data/
│   ├── TyranorGame.kt          游戏数据模型
│   └── TyranorLibraryClient.kt 读取 Tyranor 游戏库（只读）
├── sign/
│   ├── SigningKey.kt           密钥材料 + keystore 读取
│   ├── TestSigningKeyProvider.kt 测试签名（自签证书，设备内持久化）
│   ├── CertificateBuilder.kt   自签 X.509 构造（无 BouncyCastle）
│   └── ApkSignerWrapper.kt     apksig 封装
└── ui/
    ├── MainActivity.kt
    ├── HomeViewModel.kt
    ├── screen/HomeScreen.kt
    └── theme/Theme.kt
```
