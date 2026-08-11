# 公开建筑资源与依赖安全审计

审计日期：2026-08-07。审计只读取公开仓库元数据、许可证和项目说明；未使用登录令牌，未发送本机目录、Minecraft 存档、模组、配置、聊天记录或 API Key。

## 可参考项目

| 项目 | 许可 | 结论 |
| --- | --- | --- |
| [Litematica](https://github.com/maruohon/litematica) | LGPL-3.0 | 可用于核对 `.litematic` 格式与兼容性；本项目不复制其代码或资源。 |
| [ObjToSchematic](https://github.com/LucasDower/ObjToSchematic) | BSD-3-Clause | 可作为离线模型转结构工具的参考；当前便携包不捆绑该程序。 |
| [schematic4j](https://github.com/SandroHc/schematic4j) | MIT | 可作为 Java 结构解析候选；现有本地解析器已覆盖所需格式，因此暂不增加依赖和攻击面。 |
| [Prismarine Schematic](https://github.com/PrismarineJS/prismarine-schematic) | MIT | 可参考跨版本 schematic 读写接口；当前没有新增运行时依赖。 |
| [EngineHub SchematicWebViewer](https://github.com/EngineHub/SchematicWebViewer) | MIT | 可参考现代 `.schem` 的只读可视化；不复制或运行其前端代码。 |
| [Sponge Schematic Specification](https://github.com/SpongePowered/Schematic-Specification) | 仓库元数据未声明 SPDX 许可 | 只核对格式概念；在许可明确前不复制规范文本或内容。 |

搜索到的若干结构文件合集没有明确许可证。即使内容可公开下载，也不代表允许再分发，因此没有导入。没有找到同时满足“明确许可、版本兼容、无命令/NBT/刷怪笼、可审计”的刷线机或刷怪机结构，故不会用来源不明的文件填充内置模板。

## 当前集成方式

- 本地导入支持 JSON、Sponge `.schem`、Litematica `.litematic` 和 PNG；文件不会上传。
- 导入后只生成未确认预览，并统一经过方块白名单、安全属性、体积和数量限制。
- 命令方块、结构方块、拼图方块、刷怪笼、传送门、方块实体 NBT 和自动执行内容会被拒绝。
- 九个内置模板均由本项目自行生成并以 CC0-1.0 发布；刷石机的水和岩浆通过游戏内标准桶交互放置，黑暗刷怪塔不含刷怪笼，所有模板均不使用命令或 NBT。
- 外部 Skill 必须声明作者、许可和 HTTPS 来源，通过敏感数据检查后仍需本地批准；内容哈希变化会撤销批准。

## 后续引入门槛

新增公开 Skill、MCP 或建筑结构必须同时满足：明确可再分发许可、固定版本/提交、无安装脚本和可执行文件、默认无网络与文件权限、无密钥或本机路径、通过静态安全门与离线测试。无法满足任一项就保持为用户自行选择的本地文件，不进入发布包。
