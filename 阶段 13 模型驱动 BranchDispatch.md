# 阶段 13 模型驱动 BranchDispatch

完成日期：2026-06-14

对应总控文档：`阶段9-17开发规划与维护清单.md`

## 1. 本阶段核心思想

第13阶段把分支任务派发从“Java 写死规则”升级为“核心模型建议 + Java Guard 校验”的结构。

现在流程是：

```text
TravelPlanState
 -> ModelBranchDispatchNode 让 DeepSeek Pro 判断应该调用哪些分支
 -> BranchDispatchGuardNode 校验工具类型、参数完整性、数量上限和能力边界
 -> BranchExecuteNode 只执行 Guard 接受后的 BranchTask
 -> PlanDraftNode 把工具结果和派发记录一起交给 Planner
```

这样做的重点不是让模型无限自由调用工具，而是让模型负责理解复杂需求，Java 负责工程边界、安全兜底和成本控制。

## 2. 本阶段解决的问题

```text
旧 BranchDispatchNode 主要靠 Java 关键词和硬逻辑判断分支任务。
复杂需求下，硬编码规则不容易理解用户真实意图。
随着工具增多，纯 if/keyword 派发会越来越难维护。
直接让模型控制工具又太危险，容易调用不存在工具、参数不足工具或过量工具。
以前分支派发缺少“为什么派发 / 为什么拒绝”的可追踪记录。
```

## 3. 新增功能

```text
DeepSeek Pro 根据用户需求、结构化需求表、RAG 摘要和可用工具清单输出分支任务建议。
Guard 校验模型输出，只允许 KNOWLEDGE / WEATHER / PLACES / FLIGHT / HOTEL。
Guard 拒绝未知工具，例如 VISA / TRAIN / RESTAURANT / FORECAST_WEATHER。
Guard 限制最多 5 个分支任务，并对重复任务去重。
Guard 校验 FLIGHT 必须有出发地、目的地和明确 startDate。
Guard 校验 HOTEL 必须有目的地、startDate 和 durationDays。
Guard 限制 WEATHER 只用于当前/实时天气，不把 current weather 误当未来天气预报。
模型失败、非法 JSON 或空建议时，自动回退旧 BranchDispatchNode。
分支派发结果和拒绝原因会进入 TravelPlanState，供后续 Planner 使用。
```

同时修复了前端生成进度的两个体验问题：

```text
核心规划长时间运行时，“已运行 X 秒”由前端本地时钟持续刷新。
生成失败时，timeline 不再把未执行过的后续步骤误标为完成。
```

## 4. 修改的业务逻辑

Graph 主流程中的分支派发从：

```text
BranchDispatchNode -> BranchExecuteNode
```

调整为优先：

```text
ModelBranchDispatchNode -> BranchDispatchGuardNode -> BranchExecuteNode
```

兼容策略：

```text
如果 ModelBranchDispatchNode 或 BranchDispatchGuardNode 未注入，继续使用旧 BranchDispatchNode。
如果模型调用失败、输出非法 JSON、输出空任务，Guard 回退旧 BranchDispatchNode。
如果模型输出了任务但被 Guard 拒绝，不再偷偷用旧规则把它加回来。
```

## 5. 修改的前端内容

修改文件：

```text
AgentProjectTrip/src/main/resources/static/agent-app.js
```

新增前端逻辑：

```text
jobClockTimer：生成任务运行中使用的本地计时器。
startJobClock()：在任务运行中启动本地秒级刷新。
stopJobClock()：任务结束或停止轮询时清理本地计时器。
currentJobDurationSeconds(...)：优先用 createdAt 到当前时间计算运行时长，终态则用 finishedAt 固定时长。
elapsedSeconds(...)：统一计算两个时间点之间的秒数。
```

修改前端逻辑：

```text
renderJobMeta(...)：不再只依赖后端 durationSeconds，而是使用 currentJobDurationSeconds(...)。
renderJobTimeline(...)：失败终态不再把未执行步骤标绿，只保留失败步骤提示。
resetFlow() / restoreState()：同步清理 jobClockTimer，避免刷新恢复后重复计时。
```

## 6. 修改的后端接口

本阶段没有新增 HTTP 接口。

后端对外接口保持不变：

```text
POST /api/v1/requirements/{requirementId}/generate-async
GET  /api/v1/jobs/{jobId}
GET  /api/v1/jobs?sessionId=...
```

## 7. 修改的代码文件

修改文件：

```text
AgentProjectTrip/src/main/java/com/travel/agent/ai/graph/LangGraphPlannerFacade.java
AgentProjectTrip/src/main/java/com/travel/agent/ai/graph/model/BranchTask.java
AgentProjectTrip/src/main/java/com/travel/agent/ai/graph/model/TravelPlanState.java
AgentProjectTrip/src/main/java/com/travel/agent/ai/graph/node/BranchDispatchNode.java
AgentProjectTrip/src/main/java/com/travel/agent/ai/graph/node/PlanDraftNode.java
AgentProjectTrip/src/main/resources/static/agent-app.js
AgentProjectTrip/src/test/java/com/travel/agent/ai/graph/LangGraphPlannerFacadeTest.java
```

新增文件：

```text
AgentProjectTrip/src/main/java/com/travel/agent/ai/graph/model/BranchDispatchDecision.java
AgentProjectTrip/src/main/java/com/travel/agent/ai/graph/model/BranchDispatchIssue.java
AgentProjectTrip/src/main/java/com/travel/agent/ai/graph/model/BranchDispatchPolicy.java
AgentProjectTrip/src/main/java/com/travel/agent/ai/graph/model/BranchTaskSuggestion.java
AgentProjectTrip/src/main/java/com/travel/agent/ai/graph/node/ModelBranchDispatchNode.java
AgentProjectTrip/src/main/java/com/travel/agent/ai/graph/node/BranchDispatchGuardNode.java
AgentProjectTrip/src/test/java/com/travel/agent/ai/graph/node/ModelBranchDispatchNodeTest.java
AgentProjectTrip/src/test/java/com/travel/agent/ai/graph/node/BranchDispatchGuardNodeTest.java
```

## 8. 新增的类

`ModelBranchDispatchNode`

```text
负责调用核心模型 DeepSeek Pro，让模型输出结构化分支任务建议。
它只负责“建议”，不直接执行工具。
```

`BranchDispatchGuardNode`

```text
负责校验模型建议，是模型派发和真实工具执行之间的安全边界。
它决定哪些建议可以变成 BranchTask，哪些必须拒绝、裁剪或 fallback。
```

`BranchDispatchDecision`

```text
承载模型整体派发结果，包括任务建议、备注、是否 fallback 和 fallback 原因。
```

`BranchTaskSuggestion`

```text
承载模型建议的单个任务，字段包括 type、priority、reason。
type 使用 String 承接未知工具类型，便于 Guard 拒绝而不是反序列化失败。
```

`BranchDispatchPolicy`

```text
集中定义允许的分支工具类型和最大任务数量。
当前允许 KNOWLEDGE / WEATHER / PLACES / FLIGHT / HOTEL，最多 5 个任务。
```

`BranchDispatchIssue`

```text
记录模型建议被接受、拒绝、裁剪或 fallback 的原因。
后续 Trace 面板可以直接复用这些记录。
```

## 9. 删除或废弃的类

本阶段没有删除类。

旧 `BranchDispatchNode` 没有废弃，保留为规则派发 fallback。

## 10. 新增的方法

核心新增方法：

```text
ModelBranchDispatchNode.dispatch(TravelPlanState)
ModelBranchDispatchNode.callModel(String, String)
BranchDispatchGuardNode.guard(TravelPlanState, BranchDispatchDecision)
BranchDispatchPolicy.defaultPolicy()
BranchDispatchPolicy.isAllowedType(BranchTaskType)
BranchDispatchPolicy.maxTaskCount()
BranchTaskSuggestion.normalizedType()
BranchDispatchDecision.fallback(String)
BranchDispatchIssue.accepted(String, String)
BranchDispatchIssue.rejected(String, String)
BranchDispatchIssue.trimmed(String, String)
BranchDispatchIssue.fallback(String)
LangGraphPlannerFacade.setModelBranchDispatchNode(...)
LangGraphPlannerFacade.setBranchDispatchGuardNode(...)
```

前端新增方法：

```text
startJobClock()
stopJobClock()
currentJobDurationSeconds(...)
elapsedSeconds(...)
```

## 11. 删除或废弃的方法

本阶段没有删除方法。

旧规则派发相关方法继续保留，用于模型派发失败时兜底。

## 12. 关键代码细节和用途

`LangGraphPlannerFacade.runBranchWorkflow(...)`

```text
优先执行 ModelBranchDispatchNode -> BranchDispatchGuardNode。
当模型派发节点或 Guard 节点不可用时，回退 BranchDispatchNode。
这样既接入了模型决策，又保持旧测试和旧流程稳定。
```

`ModelBranchDispatchNode.dispatch(...)`

```text
读取 TravelPlanState，组织用户输入、需求表、RAG 摘要和可用工具边界。
调用 DeepSeek Pro 输出 JSON。
解析成功后写入 BranchDispatchDecision。
解析失败时返回 fallback decision。
```

`BranchDispatchGuardNode.guard(...)`

```text
读取 BranchDispatchDecision。
按白名单、数量上限、重复类型、参数完整性检查模型建议。
接受的建议转成 BranchTask。
拒绝和裁剪的建议写成 BranchDispatchIssue。
必要时调用旧 BranchDispatchNode 兜底。
```

`PlanDraftNode.formatBranchDispatchIssues(...)`

```text
把模型派发记录注入 Planner prompt。
Planner 可以知道哪些工具结果可信、哪些工具建议被拒绝，避免编造不存在的工具数据。
```

`agent-app.js` 本地计时器

```text
后端轮询间隔是 2.5 秒，且长模型调用期间后端阶段不一定更新。
前端用本地 createdAt 计算已运行秒数，让用户看到页面仍然活着。
```

## 13. 注释规范执行情况

本阶段新增 Java 类均按注释规范补充了类级 Javadoc，说明：

```text
系统位置
职责边界
输入输出
失败和 fallback 策略
读写 TravelPlanState 的字段
模型只建议、不执行工具
Guard 是安全边界
```

前端新增逻辑使用了少量必要注释，说明本地时钟与后端轮询的关系。

## 14. 测试方式和测试结果

自动化测试：

```text
mvn test
通过，Tests run: 123, Failures: 0, Errors: 0, Skipped: 0
```

前端脚本检查：

```text
node --check AgentProjectTrip/src/main/resources/static/agent-app.js
通过。
```

静态资源同步：

```text
mvn process-resources
通过。
```

人工测试：

```text
正式前端页面 travel-planner.html 生成流程测试通过。
核心规划阶段已运行秒数可以持续刷新。
生成失败时进度步骤不会误标后续步骤为完成。
```

## 15. 遗留问题

```text
模型派发质量还没有 Trace 面板可视化，只能看日志和最终方案。
火车、签证、预算精算、门票开放时间、未来天气、安全提醒等工具仍未真实接入。
BranchDispatchNode 仍作为 fallback 存在，未来可以改名为 RuleBasedBranchDispatchNode。
模型派发目前是一次性建议，还没有多轮“执行结果 -> 模型再判断是否需要补查”的闭环。
```

## 16. 下一阶段衔接点

下一阶段建议进入阶段 14：预算 / 交通 / 签证 / 未来工具分支。

阶段 14 可以继续沿用本阶段的模型派发 + Guard 结构：

```text
先定义新 BranchTaskType。
再定义工具能力边界和参数要求。
让 ModelBranchDispatchNode 知道新工具存在。
让 BranchDispatchGuardNode 决定何时允许调用。
最后由 BranchAgentFacade 接入真实服务或明确降级。
```
