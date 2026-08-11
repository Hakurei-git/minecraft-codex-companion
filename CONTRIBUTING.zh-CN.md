# 参与 Minecraft Codex Companion

[English](CONTRIBUTING.md)

欢迎协助改进 Minecraft AI NPC、Forge 桥、本地 MCP 服务、AgentKit、文档、翻译和模组兼容适配器。

## 创建 Issue 前

1. 先搜索已有 Issues 与 Discussions。
2. 尽量使用最新 Release、隔离 HMCL 实例和临时世界复现问题。
3. 删除与问题无关的 API Key、Base URL、账号数据、本机绝对路径、会话正文、日志、截图和世界文件。
4. 安装与使用问题放在 Discussions；可稳定复现的 Bug 或范围明确的需求使用 Issue 表单。

## 开发环境

主要源码流程需要 Node.js 24+、PowerShell 5.1+ 和用于 Forge 1.20.1 的 Java 17；只有可选 NeoForge 1.21.1 构建需要 Java 21。

```powershell
npm install
npm run build
npm run typecheck
```

请创建范围明确的分支，保持修改集中，并描述玩家能够观察到的行为，而不只是实现细节。

## 测试要求

- 仅修改文档：检查链接、Markdown 渲染、中英文跳转和隐私说明。
- 修改 TypeScript：运行受影响工作区测试及 `npm run typecheck`。
- 修改安装器：运行 `npm run test:single-exe`；发布候选还必须通过包完整性和本地杀毒门禁。
- 修改 Forge 行为：运行 Forge 测试，并记录在临时世界进行过的实机验收。
- 修改动作链：根据功能补充解析、前置条件、中断、持久化或交付的确定性回归测试。

## 隐私与依赖规则

- 禁止提交 API Key、Token、Cookie、账号文件、私人 Base URL 配置、本机绝对路径、会话、本地状态、日志、截图或 Minecraft 世界。
- 未获得明确再分发许可并记录来源、许可证和 SHA-256 时，不得加入第三方模组 JAR。Book of Dragons 与 Saints Dragons 只是兼容集成，不随项目打包。
- 外部 Skill、MCP 元数据、建筑蓝图、皮肤和库必须具有明确来源及兼容许可证。
- 不得为了让测试通过而削弱仅监听本机的默认值、脱敏、路径边界、权限检查或任务校验。

## Pull Request 内容

Pull Request 应说明：

- 问题与预期行为；
- 受影响的 Minecraft 版本、加载器、AI 入口或动作链；
- 已运行的测试及结果；
- 面向用户的变化所对应的文档更新；
- 没有加入敏感数据或不属于项目的本地文件。

安全漏洞请按照 [SECURITY.md](SECURITY.md) 报告，不要创建公开 Issue 或 Pull Request。
