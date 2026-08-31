# 放哪了（FindIt）

[English](README_EN.md)

![Build](https://github.com/dsglenn35-netizen/FindIt/actions/workflows/build.yml/badge.svg)
![License](https://img.shields.io/badge/License-PolyForm%20Noncommercial-blue)
![Release](https://img.shields.io/github/v/release/dsglenn35-netizen/FindIt)
![Stars](https://img.shields.io/github/stars/dsglenn35-netizen/FindIt)

<p align="center">
  <a href="https://github.com/dsglenn35-netizen/FindIt/releases"><strong>⬇️ 下载最新版 APK（GitHub Releases）</strong></a>
</p>

家中小零碎放哪了、一查就知道的 Android 小工具。纯本地运行，不需要联网，数据存在手机里。

<p align="center">
  <img src="app.jpg" alt="放哪了 界面" width="320" />
</p>

## 功能

- 📦 **记位置**：输入「物品名称 + 存放位置」，一键保存
- 📷 **拍照记录**：给物品拍张照，点缩略图可看全屏大图
- 🎤 **语音输入**：说话自动填物品名/位置（调用系统语音，无需权限）
- 🔍 **拼音/模糊搜索**：输入「jd」「jiandao」都能搜到「剪刀」
- 🕘 **最近记录**：按存放时间倒序排列
- 🏠 **按位置分组**：页签切换到「按位置」，按房间分组浏览
- 📊 **统计图表**：每个位置放了多少东西一目了然
- ✏️ **编辑 / 移动轨迹**：点记录可改名字、位置、重拍照片，位置改动自动记录历史（上次在哪、现在在哪）
- 🔗 **分享**：一键把「东西在哪」发给家人
- 💾 **备份导出/导入**：记录+照片打包成 zip，可存到微信/网盘；换机、卸载前导出，随时导入恢复
- ⚙️ **自定义常用位置**：长按位置芯片可重命名/删除，「+ 添加」新增自己的位置
- 🌙 **深色模式**：跟随系统自动切换
- 🔗 **局域网双设备同步**（v2.0）：两台手机/平板同一 WiFi 下扫码互连，记录、位置、照片自动同步（增量 + 冲突自动处理 + 照片按需传输）

## 安装方法（Android 手机）

1. 把 `app-release.apk` 传到手机（微信/QQ 传输、数据线、网盘都行）
2. 在手机上点击该文件安装
3. 若提示「未知来源」，在设置中允许安装该应用即可（不同品牌手机路径略有不同）

要求：Android 8.0（API 26）及以上。

## 数据说明

- 所有数据存在应用私有目录：数据库 `findit.db` + 照片 `files/photos/`，**不需要任何权限，不联网、不上传**
- 升级版本直接用新 APK 覆盖安装即可，数据保留
- ⚠️ 卸载应用会清空数据；**建议定期用「导出备份」把 zip 存到微信/网盘**

## 开发者信息

- 包名：`com.home.findit`
- 构建环境：Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21 + compileSdk 35 / minSdk 26 / targetSdk 35
- 依赖：`androidx.core:core-ktx`（FileProvider）、`pinyin4j`（拼音搜索）

### 命令行构建

```bat
set JAVA_HOME=<你的 JDK 17 路径>
gradlew.bat assembleRelease
```

产物：`app\build\outputs\apk\release\app-release.apk`

### 在 Android Studio 中打开

直接 `Open` 本目录即可，SDK 路径写在 `local.properties`（未提交，需自行创建或由 Studio 自动生成）。

### 签名说明（重要）

- 签名配置从本地文件 `keystore.properties` 读取，**该文件与签名文件 `findit.jks` 均被 .gitignore 排除，不会进仓库**
- 克隆仓库后没有 `keystore.properties` 时，release 构建自动退回 debug 签名，可直接编译安装，但**不能覆盖安装**正式发布的版本
- 要发布正式版：在仓库根目录创建 `keystore.properties`：

```properties
storeFile=../findit.jks
storePassword=你的口令
keyAlias=findit
keyPassword=你的口令
```

- ⚠️ 请务必妥善保管 `findit.jks` 与口令——以后升级必须用同一密钥签名，否则用户无法覆盖安装

## 备份文件格式

zip 内含 `findit.json`（schema=1，含 locations/items/moves）+ `photos/` 目录，可用任意解压软件查看。

## 开源

[PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0)：可自由使用、修改、分发，**禁止商用**（个人/家庭/学习/公益等非商业用途不受限）。欢迎 Star / Issue / PR。
