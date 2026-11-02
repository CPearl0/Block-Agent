# BlockAgent

[English](#english) | [简体中文](#简体中文)

## English

BlockAgent is a Minecraft client-side AI mod that controls the current player to gather resources, build, and carry out other tasks. It supports continuous action execution, memory, and incremental project development. Currently supports Minecraft 1.21.1 with NeoForge.

### Installation

1. Install Minecraft 1.21.1 and the corresponding NeoForge version using HMCL or another launcher.
2. Place `blockagent-neoforge-1.21.1-0.2.0.jar` in the game instance's `mods/` directory.
3. Launch the game and configure your LLM service in the generated `config/blockagent-client.json`.
4. Join a world, run `/blockagent reload`, then use `/blockagent <task>` to give an instruction.

BlockAgent does not need to be installed on the server.

Configuration file:

```json
{
  "provider": {
    "baseUrl": "https://your-provider.example/v1",
    "apiType": "openai-chat",
    "apiKey": "${BLOCKAGENT_API_KEY}",
    "model": "your-model",
    "reasoningEffort": "auto",
    "thinkingMode": "auto",
    "timeoutMs": 120000
  },
  "autonomy": { "enabled": false }
}
```

`apiKey` accepts a literal key or `${ENV_VAR_NAME}`; the launcher must pass the environment variable to the game process. Supported protocols are `openai-chat`, `openai-responses`, and `anthropic`.

### Controls

| Command | Action |
| --- | --- |
| `/blockagent <task>` | Start a task, replacing and cancelling the previous one |
| `/blockagent stop` | Stop and release control |
| `/blockagent status` | Show the current status |
| `/blockagent reload` | Stop the current task and reload configuration |
| F8 | Stop shortcut |

The chat stays open while a task is running. You can chat, issue commands, or switch to another window. Escape does not close the chat during a task. When the task ends, normal controls return and unsent text is preserved. If a valid container menu is still open, it is restored first; opening normal chat afterwards retrieves the draft. Death, disconnection, and screens requiring confirmation end control takeover.

Feedback appears in local chat, and control commands are not sent to the server. Memory and projects are stored in the instance's `blockagent/memory/` directory. When `autonomy.enabled` is explicitly enabled, the agent can resume pending work on the same server and in the same dimension while idle; stopping pauses continuation.

### Building and Development

Open this directory directly in IDEA and select Java 21 as the Gradle JVM, or run from the project root:

```powershell
.\gradlew.bat build
```

On Linux/macOS, use `./gradlew build`. The mod and source JARs are written to `build/libs/`.

```powershell
.\gradlew.bat spotlessApply  # Format code
.\gradlew.bat check          # Check formatting and run core tests
.\gradlew.bat runClient      # Launch the development client
```

Java uses Google Java Format's AOSP four-space style, enforced by Spotless, with wildcard imports forbidden. `build` includes formatting checks and 7 core tests. After making changes, run `spotlessApply`, then `build`.

```text
BlockAgent/
├── gradle/wrapper/          Gradle Wrapper
├── src/main/java/io/github/cpearl0/blockagent/
│   ├── BlockAgentMod.java   Client entry point, commands, and lifecycle
│   ├── core/               LLM, configuration, execution, cancellation, and memory
│   ├── game/               Native observation, navigation, mining, and containers
│   │   └── mixin/          Client state and input control
│   └── bridge/             Explicitly enabled local development interface
├── src/main/resources/     NeoForge metadata, mixins, and language files
├── src/test/java/          Two core test files
└── docs/                   Development documentation
```

Metadata versions, dependency versions, and artifact names are managed through `gradle.properties` and the build script. See the [development guide](docs/DEVELOPMENT.md) for the development interface and implementation boundaries.

### Capability Boundaries

Actions read actual blocks, items, components, tags, synchronized recipes, and container slots, and verify server results. Navigation currently focuses on loaded, dry areas with solid support. Complex machines' energy, research, and custom recipes need separate validation. The previous full NBT blueprint workflow has not yet been migrated; building currently uses generic native actions.

### References and Acknowledgements

This project drew on the following open-source projects for Minecraft automation and LLM agent design:

- [Mineflayer](https://github.com/PrismarineJS/mineflayer): Minecraft bots and game interaction.
- [Mindcraft](https://github.com/mindcraft-bots/mindcraft): Minecraft LLM agents and task execution.

Thanks to their authors and contributors.

### AI Usage Disclosure

AI was used in this project's design, implementation, documentation, and other development work.

### License

This project is open source under the [MIT License](LICENSE).

## 简体中文

BlockAgent 是一个 Minecraft 客户端 AI 模组，控制当前玩家完成资源收集、建造和其他任务，支持连续动作执行、记忆和渐进式项目恢复。目前支持1.21.1 Neoforge。

### 安装

1. 在 HMCL 或其他启动器中安装 Minecraft 1.21.1 与相应 NeoForge。
2. 把 `blockagent-neoforge-1.21.1-0.2.0.jar` 放入游戏实例的 `mods/`。
3. 启动游戏，在实例生成的 `config/blockagent-client.json` 中配置 LLM 服务。
4. 进入世界，执行 `/blockagent reload`，然后用 `/blockagent <task>` 下达指令。

服务器不需要安装 BlockAgent。

配置文件

```json
{
  "provider": {
    "baseUrl": "https://your-provider.example/v1",
    "apiType": "openai-chat",
    "apiKey": "${BLOCKAGENT_API_KEY}",
    "model": "your-model",
    "reasoningEffort": "auto",
    "thinkingMode": "auto",
    "timeoutMs": 120000
  },
  "autonomy": { "enabled": false }
}
```

`apiKey` 支持直接填写或 `${环境变量名}`；环境变量必须由启动器传入游戏进程。服务协议支持 `openai-chat`、`openai-responses`、`anthropic`。

### 控制

| 指令 | 功能 |
| --- | --- |
| `/blockagent <task>` | 开始任务，替换并取消旧任务 |
| `/blockagent stop` | 停止并释放控制 |
| `/blockagent status` | 查看状态 |
| `/blockagent reload` | 停止旧任务并重新读取配置 |
| F8 | 停止快捷键 |

运行时保持聊天框，可继续聊天、发送指令或切换到其他窗口。Escape 不关闭运行中的聊天框。任务结束后恢复普通操作并保留未发送文字；若仍有有效容器菜单，则先恢复菜单，之后打开普通聊天可取回草稿。死亡、断线及需要确认的界面会结束控制接管。

反馈显示在本地聊天中，控制命令不发给服务器。记忆与项目保存在实例的 `blockagent/memory/`。显式开启 `autonomy.enabled` 后，空闲时可以继续同一服务器、同一维度的待办；停止会暂停接续。

### 构建与开发

用 IDEA 直接打开本目录，选择 Java 21 作为 Gradle JVM；也可以在项目根目录运行：

```powershell
.\gradlew.bat build
```

Linux/macOS 使用 `./gradlew build`。产物和源码包在 `build/libs/`。

```powershell
.\gradlew.bat spotlessApply  # 格式化
.\gradlew.bat check          # 格式检查和核心测试
.\gradlew.bat runClient      # 开发客户端
```

Java 使用 Google Java Format 的 AOSP 四空格风格，由 Spotless 检查并禁止通配符导入。`build` 包含格式检查和 7 项核心测试。修改完成后先运行 `spotlessApply`，再运行 `build`。

```text
BlockAgent/
├── gradle/wrapper/          Gradle Wrapper
├── src/main/java/io/github/cpearl0/blockagent/
│   ├── BlockAgentMod.java   客户端入口、指令和生命周期
│   ├── core/               LLM、配置、程序执行、取消与记忆
│   ├── game/               原生观察、导航、挖掘和容器交互
│   │   └── mixin/          客户端状态与输入接管
│   └── bridge/             显式启用的本地开发接口
├── src/main/resources/     NeoForge 元数据、Mixin 和语言文件
├── src/test/java/          两个核心测试文件
└── docs/                   开发说明
```

元数据版本、依赖版本和产物名称由 `gradle.properties` 与构建脚本统一管理。详细的开发接口和实现边界见 [开发说明](docs/DEVELOPMENT.md)。

### 能力边界

动作读取真实方块、物品、组件、标签、同步配方和容器槽位，并核对服务端结果。当前导航主要覆盖已加载、干燥且有支撑的区域；复杂机器的能源、研究和自定义配方需要单独验证。旧版完整 NBT 蓝图流程尚未迁入，当前通过通用原生动作建造。

### 参考与致谢

本项目在 Minecraft 自动化交互和 LLM 智能体设计方面参考了以下开源项目：

- [Mineflayer](https://github.com/PrismarineJS/mineflayer)：Minecraft 机器人与游戏交互。
- [Mindcraft](https://github.com/mindcraft-bots/mindcraft)：Minecraft LLM 智能体与任务执行。

感谢这些项目的作者和贡献者。

### AI 使用声明

本项目在设计、实现、文档编写等开发工作中使用了 AI。

### 许可

本项目采用 [MIT License](LICENSE) 开源。
