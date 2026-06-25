# 阶段 9 正式前端 MVP 页面

完成日期：2026-06-14

## 1. 阶段结论

第 9 阶段已完成。

本阶段把原来偏开发调试用途的页面，扩展为一个正式的旅行规划 MVP 入口：

```text
自然语言输入
-> 自动整理需求表
-> 用户检查并确认需求
-> 异步生成完整旅行方案
-> 查看生成进度
-> 查看 Markdown 方案
-> 用自然语言继续修改方案
-> 查看历史版本
-> 管理简单用户偏好记忆
```

`agent-lab.html` 保留为开发调试页，新入口为：

```text
AgentProjectTrip/src/main/resources/static/travel-planner.html
```

浏览器访问地址：

```text
http://localhost:8080/travel-planner.html
```

## 2. 本阶段核心思想

第 9 阶段不改变 Agent 核心规划能力，也不修改 LangGraph 工作流。

本阶段只解决一个产品问题：

```text
让用户不需要理解 requirementId、jobId、planId、GraphResult 等内部对象，
也能在一个页面里完成一次完整旅行规划。
```

也就是说，前端负责把后端已经存在的接口串成一个清晰流程。

## 3. 本阶段解决的问题

### 3.1 原来的问题

```text
开发调试页技术字段较多
用户必须理解后端状态才能测试
异步任务、版本、记忆、需求确认分散
生成进度不够产品化
历史版本切换容易造成状态混淆
确认后的需求表仍然看起来可以编辑，容易让用户误以为未保存的改动会被用于生成
```

### 3.2 现在的效果

```text
用户只需要按 Step 1 到 Step 5 操作
前端自动保存 sessionId、requirementId、jobId、planId
刷新页面后可以恢复当前流程
生成任务完成后自动加载计划
自然语言修改后可以生成新版本
点击旧版本后，新版本入口不会消失
确认后的需求表会锁定，避免未保存改动造成误解
底部仍保留开发者 JSON 调试面板，但默认折叠
```

## 4. 新增和修改的功能

### 4.1 正式前端入口

新增：

```text
travel-planner.html
```

用途：

```text
作为用户正式测试旅行规划系统的入口页面。
页面包含顶部状态栏、自然语言输入区、需求表单区、生成进度区、方案展示区、修改区、偏好记忆区和 JSON 调试区。
```

### 4.2 独立样式文件

新增：

```text
app.css
```

用途：

```text
提供正式页面布局、表单、按钮、进度条、版本按钮、记忆列表、方案文本和响应式移动端样式。
```

### 4.3 独立前端交互脚本

新增：

```text
agent-app.js
```

用途：

```text
维护前端页面状态机，调用后端 API，处理轮询、刷新恢复、版本切换和用户反馈。
```

### 4.4 顶部状态栏

新增状态：

```text
当前步骤
需求状态
生成任务
生成额度
方案版本
```

其中“生成额度”从异步任务结果里的 `result.remainingCredits` 读取。
如果任务还没有完成，则显示“待生成”。

### 4.5 需求表单锁定

新增逻辑：

```text
没有 requirementId 时，需求表不可编辑
异步请求执行中，需求表不可编辑
需求确认后，需求表不可编辑
点击“继续编辑”后，需求状态回到 DRAFT，表单重新解锁
```

这样可以避免用户确认后直接改表单，但忘记保存和重新确认，导致后端仍按旧需求生成。

### 4.6 版本状态修复

新增前端状态：

```text
currentVersion
latestVersion
```

含义：

```text
currentVersion：用户当前正在查看的版本
latestVersion：该 plan 已经生成到的最新版本
```

修复的问题：

```text
点击 v1 后，v2 不再从版本列表消失。
版本列表统一根据 latestVersion、planRecord.currentVersion、currentVersion 的最大值渲染。
```

### 4.7 浏览器缓存处理

`travel-planner.html` 给静态资源增加版本参数：

```text
app.css?v=20260614-3
agent-app.js?v=20260614-3
```

用途：

```text
避免浏览器继续加载旧的 CSS / JS，导致用户看到的页面不是最新代码。
```

### 4.8 复制方案兜底

修改：

```text
copyPlan()
```

新增能力：

```text
如果浏览器不支持 navigator.clipboard.writeText，页面会提示用户手动复制，而不是静默失败。
```

## 5. 使用到的后端接口

本阶段前端复用已有后端接口，没有新增后端 Controller。

```text
POST   /api/v1/requirements/draft
PUT    /api/v1/requirements/{requirementId}
POST   /api/v1/requirements/{requirementId}/confirm
POST   /api/v1/requirements/{requirementId}/generate-async
GET    /api/v1/jobs/{jobId}
GET    /api/v1/plans/{planId}
GET    /api/v1/plans/{planId}/versions/{version}
POST   /api/v1/plans/{planId}/modify
GET    /api/v1/memories?sessionId={sessionId}
POST   /api/v1/memories
DELETE /api/v1/memories/{memoryId}
```

## 6. 修改文件清单

### 6.1 新增文件

```text
AgentProjectTrip/src/main/resources/static/travel-planner.html
AgentProjectTrip/src/main/resources/static/app.css
AgentProjectTrip/src/main/resources/static/agent-app.js
阶段 9 正式前端 MVP 页面.md
```

### 6.2 修改文件

```text
第9阶段开发.md
阶段9-17开发规划与维护清单.md
```

### 6.3 未修改但保留

```text
AgentProjectTrip/src/main/resources/static/agent-lab.html
```

说明：

```text
agent-lab.html 继续作为开发调试页面。
travel-planner.html 作为正式体验页面。
```

## 7. 新增 Java 类

本阶段没有新增 Java 类。

## 8. 删除 Java 类

本阶段没有删除 Java 类。

## 9. 新增和修改的主要前端函数

### 9.1 draftRequirement()

用途：

```text
读取用户自然语言输入，调用 /api/v1/requirements/draft，把模型整理出的 TravelRequirementSpec 填入表单。
```

### 9.2 saveRequirement()

用途：

```text
保存用户手动修改后的需求表。
本函数只更新 requirement，不启动 Graph，不扣费。
```

### 9.3 confirmRequirement()

用途：

```text
调用确认接口，把校验通过的需求表锁定为 CONFIRMED。
确认成功后才允许点击“生成完整方案”。
```

### 9.4 generatePlanAsync()

用途：

```text
创建异步生成任务，拿到 jobId 后交给轮询逻辑。
它不会等待完整方案生成完成，因此页面不会长时间卡住。
```

### 9.5 loadJobStatus()

用途：

```text
轮询 /api/v1/jobs/{jobId}。
任务成功后自动加载 plan。
任务失败后显示错误原因。
```

### 9.6 loadPlan()

用途：

```text
按 planId 加载最新旅行方案，并同步 currentVersion / latestVersion。
```

### 9.7 modifyPlan()

用途：

```text
把用户的自然语言修改意见提交给 /api/v1/plans/{planId}/modify。
如果修改成功，则保存新版本并刷新方案。
如果属于核心需求变化，则回到需求表确认流程。
```

### 9.8 loadVersion()

用途：

```text
读取历史版本。
用户查看旧版本时，只改变 currentVersion，不降低 latestVersion。
```

### 9.9 saveMemory() / deactivateMemory()

用途：

```text
保存和禁用用户偏好记忆。
当前仍是开发期简单记忆管理，不是正式用户系统。
```

### 9.10 persistState() / restoreState()

用途：

```text
把当前浏览器会话里的 requirementId、jobId、planId、版本号和最近提示保存到 localStorage。
刷新页面后恢复当前流程。
```

### 9.11 setRequirementFormLocked()

用途：

```text
统一控制需求表是否可编辑。
避免确认后用户继续编辑表单但未保存，导致生成结果与页面表单不一致。
```

### 9.12 extractRemainingCredits()

用途：

```text
从 GenerationJobResponse.result.remainingCredits 中提取剩余额度。
如果后端暂未返回该字段，则保留前端已有状态。
```

## 10. 测试和验证

### 10.1 已执行检查

```text
node --check AgentProjectTrip/src/main/resources/static/agent-app.js
通过
```

```text
前端 HTML id 与 agent-app.js 绑定检查
结果：JS 绑定的 47 个 id 全部存在
说明：progressBox 是纯样式容器，不需要 JS 绑定
```

```text
mvn process-resources
通过
作用：把 src/main/resources/static 同步到 target/classes/static，确保运行中的 Spring Boot 能读到最新前端资源
```

```text
node --check AgentProjectTrip/target/classes/static/agent-app.js
通过
```

### 10.2 未执行的检查

```text
mvn test
未执行
原因：本阶段只修改静态前端和 Markdown 文档，没有修改 Java 生产代码。
```

## 11. 手动验收流程

访问：

```text
http://localhost:8080/travel-planner.html?stage9=final
```

完整测试：

```text
1. 点击“重新开始”。
2. 点击“填入示例”。
3. 点击“整理需求”。
4. 检查需求表是否自动填入目的地、天数、预算、偏好等字段。
5. 修改一个字段，例如住宿偏好。
6. 点击“保存修改”。
7. 点击“确认需求”。
8. 确认需求表被锁定，输入框变成禁用状态。
9. 点击“生成完整方案”。
10. 检查进度条和任务状态是否变化。
11. 等任务成功后，检查旅行方案是否自动展示。
12. 检查顶部“生成额度”是否显示剩余次数。
13. 在“继续修改方案”里输入：把行程安排得更轻松一点。
14. 点击“提交修改”。
15. 检查是否出现 v1、v2。
16. 点击 v1，确认 v2 不会消失。
17. 点击 v2，确认能切回新版本。
18. 刷新页面，确认当前 session、方案和版本状态能恢复。
19. 展开“开发者调试 JSON”，确认 currentVersion、latestVersion、remainingCredits 等状态存在。
```

## 12. 遗留问题

第 9 阶段完成的是正式前端 MVP，不代表产品已经完整。

仍然遗留：

```text
需求表单还可以继续做得更产品化
没有正式登录系统
没有正式支付系统
额度只是开发期生成次数
航班分支代码还没有正式接入 Graph 分支执行链路
BranchDispatch 仍然不是模型驱动派发
酒店、预算、签证等分支还未完整实现
页面还没有任务历史列表
页面还没有真正的多用户账户隔离体验
```

## 13. 下一阶段衔接

下一阶段建议进入：

```text
阶段 10：需求表单体验完善
```

原因：

```text
正式页面已经能跑通闭环。
下一步应该提高“生成前输入质量”，减少无效生成和 token 浪费。
```

