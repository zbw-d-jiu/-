# 校园自习室预约 · 四角色子 Agent

本目录保存四个项目级 Codex 自定义子 Agent。每个 TOML 文件中的 `developer_instructions` 就是完整中文角色提示词，可直接阅读、修改或复制；模型和思考强度由配置字段指定。

| 角色 | Agent 名称 | 模型 | 思考强度 | 文件 |
| --- | --- | --- | --- | --- |
| 组长（Scrum Master） | `scrum_master` | `gpt-6-astra` | `high`（高） | [scrum_master.toml](scrum_master.toml) |
| 产品负责人（Product Owner） | `product_owner` | `gpt-6-astra` | `high`（高） | [product_owner.toml](product_owner.toml) |
| 开发团队 · 开发 | `developer` | `gpt-6.1-sol` | `medium`（中） | [developer.toml](developer.toml) |
| 开发团队 · 测试 | `tester` | `gpt-6.1-sol` | `medium`（中） | [tester.toml](tester.toml) |

## 如何使用

在本项目中开启后续 Codex 会话后，通过名称要求主 Agent 调用角色，并提供具体任务。例如：

> 请调用 product_owner，先检查当前项目与已确认需求，为校园自习室预约梳理候选 MVP、用户故事和验收标准。未确认的校方规则请列为待确认项。

> 请调用 scrum_master，根据已确认的需求组织本轮迭代，安排 developer 实现、tester 独立验证。按依赖顺序执行，独立工作可以并行；所有结果由主 Agent 汇总。

> 请调用 developer 实现指定故事，交付后调用 tester 对该版本执行验收测试，汇总实际结果和未覆盖风险。

调用时明确故事或任务、输入文档、可写文件范围、验收标准和期望输出。当前文件只定义可复用角色；本次没有启动产品开发，也没有预设技术栈或确认业务需求。

## 协作约定

1. Product Owner 明确用户价值、范围、优先级和业务验收标准；Scrum Master 协调目标、依赖、阻塞和交接。
2. 开发角色按已确认范围实现并自测；测试角色独立检查验收标准、业务边界和回归风险。
3. 各角色向主 Agent 返回结构化结果，由主 Agent 汇总并传递后续任务。避免重复创建相同职责的子 Agent。
4. 写入任务要分配文件归属；依赖相同文件的工作按顺序执行，不覆盖他人的改动。
5. 只有检查确实执行过，才能报告通过。环境缺失、需求待定和未执行检查必须显式记录。
6. 并发数量受会话运行环境限制；四个角色可以分批执行，无需为凑齐数量并发启动无关任务。

## 配置与生效说明

独立项目级配置采用 `.codex/agents/*.toml`，包含 `name`、`description`、`developer_instructions`，以及本项目显式指定的 `model` 和 `model_reasoning_effort`。使用能够读取该格式的 Codex 客户端；若当前会话尚未发现新文件，可在本项目开启新会话后调用。文件创建和语法检查不等于客户端已完成加载或模型调用验证。

自定义角色继承主会话的权限和工具。提示词规定的文件分工属于协作约束，不是单独的安全沙箱。无需修改个人全局配置，也不需要在项目中保存 API 密钥。

格式依据：[OpenAI 官方 Subagents 文档](https://learn.chatgpt.com/docs/agent-configuration/subagents)。
