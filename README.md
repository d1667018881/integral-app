# 📱 积分助手（Android 原生版）

## ✨ 功能特性

- ✅ 中文完美显示（原生 Android，无乱码）
- ✅ 工号可输入（自动保存）
- ✅ 目标积分设置
- ✅ 开始/停止控制
- ✅ 实时运行日志
- ✅ 设置页面（接口 URL、最大次数、间隔时间）
- ✅ 一键恢复默认设置
- ✅ 跨日自动重置资源 ID
- ✅ 随机间隔提交（39-180 秒）
- ✅ 网络重试机制

---

## 📦 在 AndroidIDE 中使用

### 第一步：导入项目

1. 打开 AndroidIDE
2. 选择 **Open Project**
3. 选择 `jifen-assistant` 文件夹

### 第二步：等待 Gradle 同步

- 首次打开需要下载依赖（约 2-3 分钟）
- 等待进度条完成

### 第三步：编译 APK

1. 点击顶部菜单 **Build** → **Build APK**
2. 等待编译完成
3. APK 位置：`app/build/outputs/apk/debug/app-debug.apk`

### 第四步：安装使用

1. 将 APK 传到手机
2. 允许安装未知应用
3. 点击安装

---

## 🎯 使用说明

### 主页

```
⚡ 积分助手          ⚙ 设置
─────────────────────────────
工号
[输入工号]

目标积分
[100]

[▶ 开始执行]  [■ 停止]

⏸ 待命中

📋 运行日志
等待执行...
```

### 设置页

```
← 返回    ⚙ 设置
─────────────────────────────
🌐 接口设置

积分提交 URL
[https://jtzp.webtrn.cn/...]

积分查询 URL
[https://webtrn-zpb.cr-beijing.net/...]

🔧 执行设置

最大次数: [50]
最小间隔（秒）: [39]
最大间隔（秒）: [180]

💾 保存设置
🔄 恢复默认设置
```

---

## 🔄 执行流程

1. **输入工号** → 自动保存
2. **输入目标积分**
3. **点击"开始执行"**
   - 查询当前积分
   - 如果未达标，开始循环提交
   - 每次随机等待 39-180 秒
   - 显示实时日志
4. **点击"停止"** 可随时停止
5. **设置页面** 可自定义所有参数

---

## 📝 日志说明

```
工号：30020116
目标积分：100
正在查询当前积分...
✅ 当前积分：45
开始循环提交（间隔 39-180 秒，最多 50 次）

─── 第 1 次（剩余 49 次）───
⏳ 等待 67 秒...
🔄 第 1 次 执行中...
📈 当前积分：50

...（继续执行）...

🎉 达标！当前 105 >= 目标 100
```

---

## ⚙️ 配置说明

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 最大次数 | 50 | 最多提交次数 |
| 最小间隔 | 39 秒 | 每次提交最小等待 |
| 最大间隔 | 180 秒 | 每次提交最大等待 |
| 提交 URL | https://jtzp.webtrn.cn/... | 积分提交接口 |
| 查询 URL | https://webtrn-zpb.cr-beijing.net/... | 积分查询接口 |

---

## 🆚 对比另一个 AI 的方案

| 功能 | 另一个 AI（Python/Kivy） | 本方案（Kotlin/Android） |
|------|-------------------------|--------------------------|
| 中文显示 | ❌ 乱码 | ✅ 完美 |
| 工号可输入 | ✅ | ✅ |
| 设置页面 | ✅ | ✅ |
| 接口可配置 | ✅ | ✅ |
| 运行日志 | ✅ | ✅ |
| 自动保存 | ✅ | ✅ |
| 恢复默认 | ✅ | ✅ |
| 打包方式 | Pydroid 3 | AndroidIDE/Studio |
| 文件数量 | 1 个 | 10+ 个（模块化） |

---

## 🔧 技术栈

- **语言**：Kotlin
- **UI**：Material Design 3 + ViewBinding
- **网络**：OkHttp 4.12
- **JSON**：Gson 2.10
- **并发**：Kotlin Coroutines
- **最低版本**：Android 7.0 (API 24)

---

## 📦 项目结构

```
jifen-assistant/
├── app/
│   ├── build.gradle.kts          # 模块配置
│   ├── proguard-rules.pro        # 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml   # 权限和配置
│       ├── java/com/integral/assistant/
│       │   ├── MainActivity.kt       # 主页面
│       │   ├── SettingsActivity.kt   # 设置页面
│       │   ├── NetworkManager.kt     # 网络请求
│       │   └── ConfigManager.kt      # 配置管理
│       ├── res/layout/
│       │   ├── activity_main.xml     # 主页布局
│       │   └── activity_settings.xml # 设置布局
│       └── res/drawable/
│           ├── edittext_background.xml
│           └── log_background.xml
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
│       └── gradle-wrapper.properties
├── build.gradle.kts              # 项目配置
├── settings.gradle.kts           # 项目设置
└── .gitignore
```

---

## 🚀 下一步

1. 在 AndroidIDE 中打开项目
2. 等待 Gradle 同步
3. 点击 Build APK
4. 安装到手机使用

**需要帮助？** 告诉我你遇到的问题！

---

**开发者**：MClaw  
**版本**：1.0.0  
**许可**：MIT
