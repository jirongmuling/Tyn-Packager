# 直装包运行方案设计

## 问题

直装包（Tyn Packager 产出的 APK）本质是「Tyranor 本体 + 内置游戏资源」。
启动时需要知道：

1. 游戏目录在哪（已内置到 `assets/game/`）；
2. 用什么引擎启动（Kirikiri）；
3. 启动哪个文件（`launch_target`，如 `data.xp3`）。

Tyranor 原本从 Room 数据库读取这些信息，直装包不能依赖用户设备的库。

## 关键技术事实（已从上游源码核实）

`EngineLauncher.launch(context, game: ScanGame, patchChoice)` **只需要一个 `ScanGame` 对象**，
不要求游戏先入库。因此直装包完全可以自己构造 `ScanGame` 后直接启动。

`ScanGame` 的必要字段：

| 字段 | 直装包取值 |
|---|---|
| `title` | 内置元数据里的游戏名 |
| `uri` | 内置游戏目录的 `file://` 路径 |
| `engine` | `KIRIKIRI` |
| `launchTarget` | 内置元数据里的启动文件名 |

## 方案

### 1. 内置元数据

打包时在 APK 里写一份 `assets/tyn_packager/game.json`：

```json
{
  "schemaVersion": 1,
  "title": "游戏名",
  "engine": "KIRIKIRI",
  "launchTarget": "data.xp3",
  "assetPrefix": "assets/game",
  "packageName": "com.tyn.games.xxx",
  "builtAt": 1795283561597
}
```

### 2. 首次启动解包

游戏资源放在 assets 里无法被引擎按文件路径读取（引擎需要真实文件系统路径），
因此首启必须解包到应用外部私有目录：

```
/storage/emulated/0/Android/data/<pkg>/files/game/
```

这一步在启动器 Activity 里做，带进度；完成后写标记避免重复解包。

### 3. 启动

解包完成后构造 `ScanGame` 并调用 `EngineLauncher.launch()`。

## 进度

- [x] 打包时写入 `game.json`（`InstallerMetadata`）
- [x] Tyranor 侧新增按路径启动入口 `InstallerLaunchActivity`
- [ ] 直装包侧的启动器组件（解包 assets + 调用该入口）
- [ ] 解包进度与断点续传
- [ ] 存储空间检查

## 启动入口（已实现）

Tyranor 新增 `InstallerLaunchActivity`，Action：

```
com.tyranor.next.action.LAUNCH_GAME_BY_PATH
```

参数：

| Extra | 说明 |
|---|---|
| `extra_game_path` | 游戏目录真实路径（必填） |
| `extra_title` | 标题（可选） |
| `extra_launch_target` | 启动文件，如 `data.xp3`（可选，缺省自动推断） |
| `extra_engine` | 引擎，当前仅 `KIRIKIRI` |

特性：**不查库、不写库**，直接构造 `ScanGame` 调用 `EngineLauncher.launch()`。
路径会校验存在性与越界（拒绝含 `..` 的路径）。

## 架构决策（重要）

直装包的源 APK 是 **Tyranor 本体**，不是 Tyn Packager。因此：

- 直装包运行时需要的「解包 + 启动」组件，**必须存在于 Tyranor 侧**；
- Tyn Packager 侧的同名实现仅用于本地调试与说明，不会出现在产物里。

于是打包流程需要做三件事：

1. 改包名、名称、图标（已实现）；
2. 写入 `assets/tyn_packager/game.json` 与 `assets/game/`（已实现）；
3. **把 `LAUNCHER` intent-filter 从 Tyranor 主界面移到内置启动器组件**（待实现）。

第 3 步是「点图标直接进游戏」的关键，也是目前唯一未完成的部分。

## 启动器职责

`InstallerBootstrapActivity`（Tyranor 侧）：

1. 读取 `assets/tyn_packager/game.json`；
2. 若不存在 → 说明是原版 Tyranor，正常走主界面；
3. 若存在 → 首启解包 `assets/game/` 到 `Android/data/<pkg>/files/game/`；
4. 解包完成或已解包 → 构造 `ScanGame` 调用 `EngineLauncher.launch()`。
