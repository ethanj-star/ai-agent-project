const STORAGE_KEY = "travel-planner-mvp-state";
const EXAMPLE_MESSAGE = "国庆去法国和意大利玩10天，预算1200欧，不含国际机票，想避开人多的地方";

const STAGE_TEXT = {
  CREATED: "已创建生成任务",
  VALIDATING_REQUIREMENT: "正在检查需求表",
  CHARGING_CREDIT: "正在扣除生成额度",
  RUNNING_GRAPH: "正在规划旅行方案",
  SAVING_PLAN: "正在保存方案",
  REFUNDING_CREDIT: "生成失败，正在退回额度",
  FINISHED: "任务已结束"
};

const STAGE_PROGRESS = {
  CREATED: 8,
  VALIDATING_REQUIREMENT: 22,
  CHARGING_CREDIT: 36,
  RUNNING_GRAPH: 68,
  SAVING_PLAN: 88,
  REFUNDING_CREDIT: 92,
  FINISHED: 100
};

const STAGE_SEQUENCE = [
  { key: "CREATED", label: "创建任务" },
  { key: "VALIDATING_REQUIREMENT", label: "检查需求" },
  { key: "CHARGING_CREDIT", label: "扣除额度" },
  { key: "RUNNING_GRAPH", label: "核心规划" },
  { key: "SAVING_PLAN", label: "保存方案" },
  { key: "FINISHED", label: "完成" }
];

const state = {
  sessionId: loadSessionId(),
  requirementId: null,
  requirementSpec: null,
  requirementValidation: null,
  requirementConfirmed: false,
  requirementDirty: false,
  jobId: null,
  jobStatus: null,
  jobStage: null,
  jobDetail: null,
  recentJobs: [],
  jobStartedAt: null,
  jobUpdatedAt: null,
  jobFinishedAt: null,
  jobErrorMessage: "",
  jobActionHint: "",
  planAutoLoadedForJob: null,
  remainingCredits: null,
  planId: null,
  planRecord: null,
  currentVersion: null,
  latestVersion: null,
  memories: [],
  loading: false,
  loadingLabel: "",
  errorMessage: "",
  lastAssistantMessage: "",
  lastRawResponse: null,
  pollingTimer: null
};

const els = {};

/*
 * 正式前端入口的页面状态机。
 *
 * 这份脚本不直接理解 LangGraph 内部节点，只把后端接口整理成用户能看懂的五步流程：
 * 自然语言需求 -> 结构化需求表 -> 异步生成任务 -> 旅行方案 -> 多轮修改版本。
 * 本地 localStorage 只保存当前浏览器会话的临时状态，刷新页面时用于恢复未完成任务。
 */
document.addEventListener("DOMContentLoaded", () => {
  bindElements();
  bindEvents();
  restoreState();
  renderAll();
  loadMemories(false);
  recoverLatestJob();
});

function bindElements() {
  [
    "statusStep", "statusRequirement", "statusJob", "statusCredit", "statusPlan", "messageInput", "draftButton",
    "exampleButton", "resetButton", "mainMessage", "validationBox", "requirementForm",
    "destinationsInput", "departureCityInput", "startDateTextInput", "startDateInput",
    "durationDaysInput", "travelerCountInput", "budgetAmountInput", "budgetCurrencyInput",
    "flightBudgetInput", "travelStyleInput", "accommodationInput", "transportInput",
    "preferencesInput", "avoidancesInput", "specialNotesInput", "saveRequirementButton", "confirmRequirementButton",
    "editRequirementButton", "generateButton", "reloadJobButton", "progressStatus",
    "progressStage", "jobMeta", "progressBar", "progressMessage", "jobTimeline", "jobAdvice", "recentJobs",
    "reloadRecentJobsButton", "planAnswer", "copyPlanButton",
    "reloadPlanButton", "versionList", "modifyInput", "modifyButton", "modifyMessage",
    "confirmReason", "memoryKeyInput", "memoryValueInput", "saveMemoryButton", "memoryList", "rawJson"
  ].forEach((id) => {
    els[id] = document.getElementById(id);
  });
}

function bindEvents() {
  els.draftButton.addEventListener("click", draftRequirement);
  els.exampleButton.addEventListener("click", () => {
    els.messageInput.value = EXAMPLE_MESSAGE;
  });
  els.resetButton.addEventListener("click", resetFlow);
  els.saveRequirementButton.addEventListener("click", saveRequirement);
  els.confirmRequirementButton.addEventListener("click", confirmRequirement);
  els.editRequirementButton.addEventListener("click", unlockRequirement);
  els.generateButton.addEventListener("click", generatePlanAsync);
  els.reloadJobButton.addEventListener("click", () => {
    loadJobStatus(true);
    loadRecentJobs(false);
  });
  els.reloadRecentJobsButton.addEventListener("click", () => loadRecentJobs(true));
  els.reloadPlanButton.addEventListener("click", () => loadPlan(true));
  els.copyPlanButton.addEventListener("click", copyPlan);
  els.modifyButton.addEventListener("click", modifyPlan);
  els.saveMemoryButton.addEventListener("click", saveMemory);
  els.requirementForm.addEventListener("input", markRequirementDirty);
  els.requirementForm.addEventListener("change", markRequirementDirty);
}

async function draftRequirement() {
  const message = els.messageInput.value.trim();
  if (!message) {
    showMainMessage("请先输入旅行想法。", "error");
    return;
  }

  setLoading(true, "正在整理需求");
  try {
    const data = await api("/api/v1/requirements/draft", {
      method: "POST",
      body: {
        sessionId: state.sessionId,
        message
      }
    });
    applyRequirementResponse(data);
    state.requirementConfirmed = data?.spec?.status === "CONFIRMED";
    showMainMessage(data.assistantMessage || "需求已整理，请检查表单。", "ok");
  } catch (error) {
    showMainMessage(error.message, "error");
  } finally {
    setLoading(false);
  }
}

// Step 2：保存结构化需求表。
// 有 requirementId 时更新旧草稿；没有 requirementId 时走第十阶段手动建表接口。
// 两条路径都不扣费，也不会启动 Graph；真正生成方案必须先确认需求。
async function saveRequirement() {
  const localIssues = validateFormLocally();
  if (localIssues.errors.length > 0) {
    showMainMessage(localIssues.errors.join("；"), "error");
    return;
  }

  setLoading(true, "正在保存需求");
  try {
    const body = readSpecFromForm();
    const path = state.requirementId
      ? `/api/v1/requirements/${encodeURIComponent(state.requirementId)}`
      : "/api/v1/requirements";
    const data = await api(path, {
      method: state.requirementId ? "PUT" : "POST",
      body
    });
    applyRequirementResponse(data);
    state.requirementConfirmed = data?.spec?.status === "CONFIRMED";
    showMainMessage(data.assistantMessage || "需求表已保存。", "ok");
  } catch (error) {
    showMainMessage(error.message, "error");
  } finally {
    setLoading(false);
  }
}

// Step 3：把需求表锁定为 CONFIRMED。
// 后端会重新校验必填字段，只有 readyToConfirm=true 时才允许后续创建异步生成任务。
async function confirmRequirement() {
  if (!state.requirementId) {
    showMainMessage("请先保存需求表，再确认。", "error");
    return;
  }
  if (state.requirementDirty) {
    showMainMessage("你有未保存的表单修改，请先保存后再确认。", "error");
    return;
  }
  const localIssues = validateFormLocally();
  if (localIssues.errors.length > 0) {
    showMainMessage(localIssues.errors.join("；"), "error");
    return;
  }

  setLoading(true, "正在确认需求");
  try {
    const data = await api(`/api/v1/requirements/${encodeURIComponent(state.requirementId)}/confirm`, {
      method: "POST",
      body: {
        spec: readSpecFromForm()
      }
    });
    applyRequirementResponse(data);
    state.requirementConfirmed = data?.spec?.status === "CONFIRMED";
    showMainMessage(data.assistantMessage || "需求已确认，可以生成完整方案。", "ok");
  } catch (error) {
    showMainMessage(error.message, "error");
  } finally {
    setLoading(false);
  }
}

// Step 4：创建异步生成任务。
// 这个接口只返回 jobId，页面随后通过 loadJobStatus 轮询任务，不让浏览器长时间挂起等待模型。
async function generatePlanAsync() {
  if (!state.requirementId || !state.requirementConfirmed) {
    showMainMessage("请先确认需求表，再生成完整方案。", "error");
    return;
  }

  setLoading(true, "正在创建生成任务");
  try {
    const data = await api(`/api/v1/requirements/${encodeURIComponent(state.requirementId)}/generate-async`, {
      method: "POST"
    });
    applyJobResponse(data);
    state.planAutoLoadedForJob = null;
    await loadRecentJobs(false);
    persistState();
    renderAll();
    startJobPolling();
  } catch (error) {
    showMainMessage(error.message, "error");
  } finally {
    setLoading(false);
  }
}

function startJobPolling() {
  stopJobPolling();
  loadJobStatus(false);
  // 轮询只在 PENDING/RUNNING 时存在；终态会在 loadJobStatus 中主动停止，避免页面后台持续请求。
  state.pollingTimer = window.setInterval(() => loadJobStatus(false), 2500);
}

function stopJobPolling() {
  if (state.pollingTimer) {
    window.clearInterval(state.pollingTimer);
    state.pollingTimer = null;
  }
}

async function loadJobStatus(showErrors) {
  if (!state.jobId) {
    if (showErrors) {
      showMainMessage("当前没有生成任务。", "error");
    }
    return;
  }

  try {
    const data = await api(`/api/v1/jobs/${encodeURIComponent(state.jobId)}`);
    applyJobResponse(data);
    if (!isJobActive(state.jobStatus)) {
      stopJobPolling();
      await loadRecentJobs(false);
    }
    if (state.jobStatus === "SUCCEEDED"
        && state.planId
        && state.planAutoLoadedForJob !== state.jobId) {
      state.planAutoLoadedForJob = state.jobId;
      await loadPlan(false);
    }
    if (state.jobStatus === "FAILED") {
      showMainMessage(data.errorMessage || data.assistantMessage || "生成失败，请检查需求后重试。", "error");
    }
    persistState();
    renderAll();
  } catch (error) {
    if (showErrors) {
      showMainMessage(error.message, "error");
    }
  }
}

// 页面刷新后用于恢复最近任务。优先继续轮询本地保存的运行中 job；
// 如果本地没有活跃 job，则从后端最近任务列表里找可恢复任务。
async function recoverLatestJob() {
  if (state.jobId && isJobActive(state.jobStatus)) {
    startJobPolling();
    await loadRecentJobs(false);
    return;
  }
  if (state.planId) {
    loadPlan(false);
  }
  await loadRecentJobs(false);
  const activeJob = state.recentJobs.find((job) => isJobActive(job.status));
  if (!state.jobId && activeJob) {
    applyJobResponse(activeJob);
    persistState();
    renderAll();
    startJobPolling();
    showMainMessage("已恢复最近的运行中生成任务。", "ok");
  }
}

async function loadRecentJobs(showErrors) {
  try {
    const query = new URLSearchParams({
      sessionId: state.sessionId,
      limit: "5"
    });
    const data = await api(`/api/v1/jobs?${query.toString()}`);
    applyJobListResponse(data);
    state.lastRawResponse = data;
    persistState();
    renderAll();
  } catch (error) {
    if (showErrors) {
      showMainMessage(error.message, "error");
    }
  }
}

// Step 5：读取生成后的最新旅行方案。
// currentVersion 表示当前正在看的版本，latestVersion 表示这个 plan 已经生成到的最新版本。
async function loadPlan(showErrors) {
  if (!state.planId) {
    if (showErrors) {
      showMainMessage("当前没有可加载的旅行方案。", "error");
    }
    return;
  }

  try {
    const data = await api(`/api/v1/plans/${encodeURIComponent(state.planId)}`);
    const loadedVersion = toVersionNumber(data.currentVersion);
    state.currentVersion = loadedVersion || state.currentVersion;
    state.latestVersion = maxVersion(state.latestVersion, state.planRecord?.currentVersion, loadedVersion);
    state.planRecord = {
      ...data,
      currentVersion: state.latestVersion
    };
    state.requirementSpec = data.requirementSpec || state.requirementSpec;
    state.lastRawResponse = data;
    persistState();
    renderAll();
  } catch (error) {
    if (showErrors) {
      showMainMessage(error.message, "error");
    }
  }
}

async function modifyPlan() {
  const message = els.modifyInput.value.trim();
  if (!state.planId) {
    showModifyMessage("请先生成旅行方案，再继续修改。", "error");
    return;
  }
  if (!message) {
    showModifyMessage("请先写下你想修改的内容。", "error");
    return;
  }

  setLoading(true, "正在修改方案");
  try {
    const data = await api(`/api/v1/plans/${encodeURIComponent(state.planId)}/modify`, {
      method: "POST",
      body: { message }
    });
    state.lastRawResponse = data;
    if (data.status === "UPDATED" && data.answer) {
      state.currentVersion = toVersionNumber(data.version) || state.currentVersion;
      state.latestVersion = maxVersion(state.latestVersion, state.planRecord?.currentVersion, state.currentVersion);
      state.planRecord = {
        ...(state.planRecord || {}),
        planId: state.planId,
        currentVersion: state.latestVersion,
        currentAnswer: data.answer
      };
      els.modifyInput.value = "";
      showModifyMessage(data.assistantMessage || "已生成新版本。", "ok");
      await loadPlan(false);
    } else if (data.requirementSpec) {
      state.requirementSpec = data.requirementSpec;
    state.requirementValidation = data.validation || state.requirementValidation;
    state.requirementConfirmed = false;
    state.requirementDirty = false;
    fillRequirementForm(state.requirementSpec);
      showModifyMessage(data.assistantMessage || "核心需求已变化，请重新确认需求表。", "ok");
    } else {
      showModifyMessage(data.question || data.assistantMessage || "请补充你想修改的范围。", "ok");
    }
    persistState();
    renderAll();
  } catch (error) {
    showModifyMessage(error.message, "error");
  } finally {
    setLoading(false);
  }
}

async function loadVersion(version) {
  if (!state.planId) {
    return;
  }
  try {
    const data = await api(`/api/v1/plans/${encodeURIComponent(state.planId)}/versions/${encodeURIComponent(version)}`);
    const viewedVersion = toVersionNumber(data.version) || toVersionNumber(version);
    state.currentVersion = viewedVersion;
    state.latestVersion = maxVersion(state.latestVersion, state.planRecord?.currentVersion, viewedVersion);
    // currentVersion 表示正在查看的版本，latestVersion 表示方案已有的最新版本。
    state.planRecord = {
      ...(state.planRecord || {}),
      currentVersion: state.latestVersion,
      currentAnswer: data.finalAnswer
    };
    state.lastRawResponse = data;
    persistState();
    renderAll();
  } catch (error) {
    showMainMessage(error.message, "error");
  }
}

async function loadMemories(showErrors) {
  try {
    const query = new URLSearchParams({ sessionId: state.sessionId });
    const data = await api(`/api/v1/memories?${query.toString()}`);
    state.memories = data.memories || [];
    state.lastRawResponse = data;
    renderMemories();
    renderDebug();
  } catch (error) {
    if (showErrors) {
      showMainMessage(error.message, "error");
    }
  }
}

async function saveMemory() {
  const key = els.memoryKeyInput.value.trim();
  const value = els.memoryValueInput.value.trim();
  if (!key || !value) {
    showMainMessage("记忆类型和内容都不能为空。", "error");
    return;
  }

  setLoading(true, "正在保存记忆");
  try {
    const data = await api("/api/v1/memories", {
      method: "POST",
      body: {
        sessionId: state.sessionId,
        key,
        value,
        scope: "LONG_TERM",
        type: "PREFERENCE",
        source: "USER_EXPLICIT",
        confidence: 1
      }
    });
    state.memories = data.memories || [];
    state.lastRawResponse = data;
    els.memoryKeyInput.value = "";
    els.memoryValueInput.value = "";
    showMainMessage(data.assistantMessage || "记忆已保存。", "ok");
    renderAll();
  } catch (error) {
    showMainMessage(error.message, "error");
  } finally {
    setLoading(false);
  }
}

async function deactivateMemory(memoryId) {
  if (!memoryId) {
    return;
  }
  setLoading(true, "正在禁用记忆");
  try {
    const data = await api(`/api/v1/memories/${encodeURIComponent(memoryId)}`, {
      method: "DELETE"
    });
    state.lastRawResponse = data;
    await loadMemories(false);
    showMainMessage(data.assistantMessage || "记忆已禁用。", "ok");
  } catch (error) {
    showMainMessage(error.message, "error");
  } finally {
    setLoading(false);
  }
}

function applyRequirementResponse(data) {
  state.requirementId = data.requirementId || data.spec?.requirementId || state.requirementId;
  state.requirementSpec = data.spec || null;
  state.requirementValidation = data.validation || null;
  state.requirementDirty = false;
  state.lastAssistantMessage = data.assistantMessage || "";
  state.lastRawResponse = data;
  if (state.requirementSpec) {
    fillRequirementForm(state.requirementSpec);
  }
  persistState();
  renderAll();
}

function applyJobResponse(data) {
  if (!data) {
    return;
  }
  state.jobDetail = data;
  state.jobId = data.jobId || state.jobId;
  state.jobStatus = data.status || state.jobStatus;
  state.jobStage = data.currentStage || state.jobStage;
  state.planId = data.planId || data.result?.planId || state.planId;
  state.remainingCredits = extractRemainingCredits(data);
  state.jobStartedAt = data.createdAt || state.jobStartedAt;
  state.jobUpdatedAt = data.updatedAt || state.jobUpdatedAt;
  state.jobFinishedAt = data.finishedAt || state.jobFinishedAt;
  state.jobErrorMessage = data.errorMessage || "";
  state.jobActionHint = data.actionHint || "";
  state.lastAssistantMessage = data.assistantMessage || state.lastAssistantMessage || "";
  state.lastRawResponse = data;
}

function applyJobListResponse(data) {
  const jobs = Array.isArray(data) ? data : data?.jobs || [];
  state.recentJobs = jobs;
  if (!state.planId && data?.latestPlanId) {
    state.planId = data.latestPlanId;
  }
}

async function selectRecentJob(jobId) {
  const job = state.recentJobs.find((item) => item.jobId === jobId);
  if (!job) {
    showMainMessage("没有找到这个历史任务。", "error");
    return;
  }
  applyJobResponse(job);
  if (isJobActive(state.jobStatus)) {
    startJobPolling();
    showMainMessage("已切换到这个运行中的生成任务。", "ok");
  } else {
    stopJobPolling();
    if (state.jobStatus === "SUCCEEDED" && state.planId) {
      await loadPlan(false);
      showMainMessage("已恢复这个任务生成的旅行方案。", "ok");
    } else if (state.jobStatus === "FAILED") {
      showMainMessage(state.jobErrorMessage || "这个任务生成失败，可以检查需求后重新生成。", "error");
    }
  }
  persistState();
  renderAll();
}

function fillRequirementForm(spec) {
  els.destinationsInput.value = csv(spec?.destinations);
  els.departureCityInput.value = text(spec?.departureCity);
  els.startDateTextInput.value = text(spec?.startDateText);
  els.startDateInput.value = text(spec?.startDate);
  els.durationDaysInput.value = numberText(spec?.durationDays);
  els.travelerCountInput.value = numberText(spec?.travelerCount);
  els.budgetAmountInput.value = numberText(spec?.budgetAmount);
  els.budgetCurrencyInput.value = text(spec?.budgetCurrency);
  els.flightBudgetInput.value = spec?.budgetIncludesInternationalFlight === true
    ? "true"
    : spec?.budgetIncludesInternationalFlight === false ? "false" : "";
  els.travelStyleInput.value = text(spec?.travelStyle);
  els.accommodationInput.value = text(spec?.accommodationPreference);
  els.transportInput.value = text(spec?.transportPreference);
  els.preferencesInput.value = csv(spec?.preferences);
  els.avoidancesInput.value = csv(spec?.avoidances);
  els.specialNotesInput.value = text(spec?.specialNotes);
}

function readSpecFromForm() {
  const old = state.requirementSpec || {};
  return {
    ...old,
    requirementId: state.requirementId || old.requirementId || null,
    sessionId: state.sessionId,
    originalMessage: old.originalMessage || els.messageInput.value.trim(),
    destinations: parseCsv(els.destinationsInput.value),
    departureCity: nullableText(els.departureCityInput.value),
    startDateText: nullableText(els.startDateTextInput.value),
    startDate: nullableText(els.startDateInput.value),
    durationDays: intOrNull(els.durationDaysInput.value),
    travelerCount: intOrNull(els.travelerCountInput.value),
    budgetAmount: numberOrNull(els.budgetAmountInput.value),
    budgetCurrency: normalizeCurrencyInput(els.budgetCurrencyInput.value),
    budgetIncludesInternationalFlight: boolOrNull(els.flightBudgetInput.value),
    travelStyle: nullableText(els.travelStyleInput.value),
    accommodationPreference: nullableText(els.accommodationInput.value),
    transportPreference: nullableText(els.transportInput.value),
    preferences: parseCsv(els.preferencesInput.value),
    avoidances: parseCsv(els.avoidancesInput.value),
    specialNotes: nullableText(els.specialNotesInput.value),
    status: old.status || "DRAFT"
  };
}

function renderAll() {
  renderStatus();
  renderValidation();
  renderFieldHints();
  renderRequirementButtons();
  renderConfirmReason();
  renderJobProgress();
  renderJobTimeline();
  renderJobFailureAdvice();
  renderRecentJobs();
  renderPlan();
  renderVersions();
  renderMemories();
  renderDebug();
}

function renderStatus() {
  const step = state.loading
    ? state.loadingLabel
    : state.planRecord ? "方案已生成" : state.requirementConfirmed ? "可以生成" : state.requirementId ? "确认需求" : "准备开始";
  els.statusStep.textContent = step;
  els.statusRequirement.textContent = state.requirementSpec?.status || (state.requirementId ? "DRAFT" : "未整理");
  els.statusJob.textContent = state.jobStatus
    ? `${jobStatusLabel(state.jobStatus)}${state.jobStage ? " / " + (state.jobDetail?.stageLabel || STAGE_TEXT[state.jobStage] || state.jobStage) : ""}`
    : "未创建";
  els.statusCredit.textContent = state.remainingCredits == null ? "待生成" : `剩余 ${state.remainingCredits} 次`;
  els.statusPlan.textContent = state.planId
    ? `${shortId(state.planId)}${state.currentVersion ? " · v" + state.currentVersion : ""}`
    : "暂无";
}

function renderValidation() {
  const validation = state.requirementValidation;
  if (!validation) {
    els.validationBox.className = "notice muted";
    els.validationBox.textContent = "先输入旅行想法，系统会把它整理成可确认的需求表。";
    return;
  }

  const missing = validation.missingFields || [];
  const warnings = validation.warnings || [];
  const blocking = validation.blockingReasons || [];
  if (validation.readyToConfirm) {
    els.validationBox.className = warnings.length ? "notice warn" : "notice ok";
    els.validationBox.innerHTML = [
      "<strong>需求表可以确认。</strong>",
      warnings.length ? `<div>注意：${escapeHtml(warnings.join("、"))}</div>` : ""
    ].join("");
    return;
  }

  els.validationBox.className = "notice error";
  els.validationBox.innerHTML = [
    "<strong>还需要补充信息。</strong>",
    missing.length ? `<div>缺少：${escapeHtml(missing.join("、"))}</div>` : "",
    blocking.length ? `<div>原因：${escapeHtml(blocking.join("、"))}</div>` : ""
  ].join("");
}

function renderFieldHints() {
  const missing = new Set([
    ...(state.requirementValidation?.missingFields || []),
    ...(state.requirementSpec?.missingFields || [])
  ]);
  const warningText = [
    ...(state.requirementValidation?.warnings || []),
    ...(state.requirementSpec?.warnings || [])
  ].join("。");

  document.querySelectorAll("[data-field-hint]").forEach((hint) => {
    const field = hint.dataset.fieldHint;
    const message = fieldHintMessage(field, missing, warningText);
    const fieldWrapper = hint.closest(".field");
    hint.textContent = message.text;
    hint.className = `field-hint ${message.type}`.trim();
    fieldWrapper?.classList.toggle("field-missing", message.type === "error");
    fieldWrapper?.classList.toggle("field-warning", message.type === "warn");
  });
}

function fieldHintMessage(field, missing, warningText) {
  if (missing.has(field)) {
    const warningOnly = field === "departureCity"
      || field === "startDateText"
      || field === "travelerCount"
      || field === "budgetIncludesInternationalFlight";
    return {
      type: warningOnly ? "warn" : "error",
      text: warningOnly ? "建议补充，能让方案更准确。" : "必填，请补充后再确认。"
    };
  }
  if (field === "budgetAmount" && warningText.includes("预算")) {
    return { type: "warn", text: "预算可能偏紧，确认前建议检查。" };
  }
  if (field === "destinations" && warningText.includes("目的地")) {
    return { type: "warn", text: "目的地和天数可能不匹配。" };
  }
  return { type: "", text: "" };
}

function renderConfirmReason() {
  const confirmed = state.requirementConfirmed || state.requirementSpec?.status === "CONFIRMED";
  if (confirmed) {
    els.confirmReason.textContent = "需求已确认并锁定。如需调整，请点击“继续编辑”。";
    els.confirmReason.className = "confirm-reason ok";
    return;
  }
  if (state.requirementDirty) {
    els.confirmReason.textContent = "表单有未保存修改，请先保存，再确认需求。";
    els.confirmReason.className = "confirm-reason warn";
    return;
  }
  if (!state.requirementId) {
    els.confirmReason.textContent = "可以先自然语言整理，也可以直接填写表单后保存。";
    els.confirmReason.className = "confirm-reason";
    return;
  }
  if (state.requirementValidation?.readyToConfirm) {
    els.confirmReason.textContent = "关键信息已齐全，可以确认需求。";
    els.confirmReason.className = "confirm-reason ok";
    return;
  }
  els.confirmReason.textContent = "仍有必填信息缺失，补齐并保存后才能确认。";
  els.confirmReason.className = "confirm-reason error";
}

function renderRequirementButtons() {
  const hasRequirement = Boolean(state.requirementId);
  const ready = Boolean(state.requirementValidation?.readyToConfirm);
  const confirmed = state.requirementConfirmed || state.requirementSpec?.status === "CONFIRMED";
  setRequirementFormLocked(state.loading || confirmed);
  els.saveRequirementButton.disabled = state.loading || confirmed;
  els.confirmRequirementButton.disabled = state.loading || !hasRequirement || !ready || confirmed || state.requirementDirty;
  els.editRequirementButton.disabled = state.loading || !hasRequirement || !confirmed;
  els.generateButton.disabled = state.loading || !hasRequirement || !confirmed || isJobActive(state.jobStatus);
  els.reloadJobButton.disabled = state.loading || !state.jobId;
  els.reloadRecentJobsButton.disabled = state.loading;
  els.reloadPlanButton.disabled = state.loading || !state.planId;
  els.copyPlanButton.disabled = !state.planRecord?.currentAnswer;
  els.modifyButton.disabled = state.loading || !state.planId;
  els.draftButton.disabled = state.loading;
}

function renderJobProgress() {
  const detail = state.jobDetail || {};
  const status = detail.statusLabel || jobStatusLabel(state.jobStatus);
  const stage = state.jobStage || "-";
  const stageText = detail.stageLabel || STAGE_TEXT[stage] || stage;
  const progress = Number.isFinite(Number(detail.progressPercent))
    ? Number(detail.progressPercent)
    : state.jobStatus === "SUCCEEDED"
      ? 100
      : state.jobStatus === "FAILED" ? 100 : STAGE_PROGRESS[stage] || 0;

  els.progressStatus.className = `job-status-badge ${jobStatusClass(state.jobStatus)}`.trim();
  els.progressStatus.textContent = status;
  els.progressStage.textContent = stageText;
  els.progressBar.style.width = `${Math.max(0, Math.min(progress, 100))}%`;
  els.progressMessage.textContent = detail.stageDescription
    || state.lastAssistantMessage
    || "确认需求后，才能开始生成完整方案。";
  els.jobMeta.innerHTML = renderJobMeta(detail);
}

function renderJobMeta(detail) {
  if (!state.jobId) {
    return "暂无生成任务。";
  }
  const jobPlanId = detail.planId || detail.result?.planId;
  const parts = [
    `任务 ${escapeHtml(shortId(state.jobId))}`,
    jobPlanId ? `方案 ${escapeHtml(shortId(jobPlanId))}` : "",
    `已运行 ${escapeHtml(formatDuration(detail.durationSeconds))}`,
    state.jobUpdatedAt ? `更新 ${escapeHtml(formatJobTime(state.jobUpdatedAt))}` : ""
  ].filter(Boolean);
  return parts.map((part) => `<span>${part}</span>`).join("");
}

function renderJobTimeline() {
  if (!state.jobId) {
    els.jobTimeline.innerHTML = "";
    return;
  }
  const currentIndex = Math.max(0, STAGE_SEQUENCE.findIndex((item) => item.key === state.jobStage));
  const terminal = jobTerminal(state.jobStatus);
  els.jobTimeline.innerHTML = STAGE_SEQUENCE.map((item, index) => {
    const done = terminal && state.jobStatus === "SUCCEEDED"
      ? true
      : index < currentIndex;
    const active = !terminal && index === currentIndex;
    const failed = terminal && state.jobStatus === "FAILED" && item.key === "FINISHED";
    const className = ["job-step", done ? "done" : "", active ? "active" : "", failed ? "failed" : ""]
      .filter(Boolean)
      .join(" ");
    const mark = done ? "✓" : active ? "●" : failed ? "!" : "○";
    return `<div class="${className}"><span>${mark}</span><strong>${escapeHtml(item.label)}</strong></div>`;
  }).join("");
}

function renderJobFailureAdvice() {
  if (!state.jobId) {
    els.jobAdvice.className = "job-advice";
    els.jobAdvice.textContent = "";
    return;
  }
  if (state.jobStatus === "FAILED") {
    const error = state.jobErrorMessage || "生成失败，请检查需求后重试。";
    const hint = state.jobActionHint || "可以回到需求表修改信息后重新生成。";
    els.jobAdvice.className = "job-advice error";
    els.jobAdvice.innerHTML = `<strong>生成失败</strong><p>${escapeHtml(error)}</p><p>${escapeHtml(hint)}</p>`;
    return;
  }
  if (state.jobStatus === "SUCCEEDED") {
    els.jobAdvice.className = "job-advice ok";
    els.jobAdvice.innerHTML = `<strong>方案已生成</strong><p>${escapeHtml(state.jobActionHint || "可以查看方案、复制结果或继续修改。")}</p>`;
    return;
  }
  if (isJobActive(state.jobStatus)) {
    els.jobAdvice.className = "job-advice";
    els.jobAdvice.textContent = state.jobActionHint || "生成任务正在运行，完成后会自动展示方案。";
    return;
  }
  els.jobAdvice.className = "job-advice";
  els.jobAdvice.textContent = "";
}

function renderRecentJobs() {
  if (!state.recentJobs || state.recentJobs.length === 0) {
    els.recentJobs.className = "recent-jobs empty";
    els.recentJobs.textContent = "暂无最近任务。";
    return;
  }
  els.recentJobs.className = "recent-jobs";
  els.recentJobs.innerHTML = state.recentJobs.map((job) => {
    const active = job.jobId === state.jobId ? " active" : "";
    const statusClass = jobStatusClass(job.status);
    const label = job.statusLabel || jobStatusLabel(job.status);
    const stage = job.stageLabel || STAGE_TEXT[job.currentStage] || job.currentStage || "-";
    const plan = job.planId ? `<span>方案 ${escapeHtml(shortId(job.planId))}</span>` : "";
    return `
      <button type="button" class="recent-job${active}" data-job-id="${escapeHtml(job.jobId || "")}">
        <span class="recent-job-main">
          <strong>${escapeHtml(shortId(job.jobId || "未知任务"))}</strong>
          <em class="${statusClass}">${escapeHtml(label)}</em>
        </span>
        <span>${escapeHtml(stage)}</span>
        ${plan}
      </button>
    `;
  }).join("");
  els.recentJobs.querySelectorAll("[data-job-id]").forEach((button) => {
    button.addEventListener("click", () => selectRecentJob(button.dataset.jobId));
  });
}

function renderPlan() {
  const answer = state.planRecord?.currentAnswer;
  if (!answer) {
    els.planAnswer.className = "answer-box empty";
    els.planAnswer.textContent = "方案生成后会显示在这里。";
    return;
  }
  els.planAnswer.className = "answer-box";
  els.planAnswer.innerHTML = renderMarkdown(answer);
}

function renderVersions() {
  const latest = maxVersion(state.latestVersion, state.planRecord?.currentVersion, state.currentVersion);
  const current = toVersionNumber(state.currentVersion);
  if (!latest) {
    els.versionList.innerHTML = "";
    return;
  }
  const chips = [];
  for (let i = 1; i <= latest; i += 1) {
    const active = i === current ? " active" : "";
    chips.push(`<button type="button" class="version-chip${active}" data-version="${i}">v${i}</button>`);
  }
  els.versionList.innerHTML = chips.join("");
  els.versionList.querySelectorAll("[data-version]").forEach((button) => {
    button.addEventListener("click", () => loadVersion(button.dataset.version));
  });
}

function renderMemories() {
  if (!state.memories || state.memories.length === 0) {
    els.memoryList.className = "list-box empty";
    els.memoryList.textContent = "暂无记忆。";
    return;
  }
  els.memoryList.className = "list-box";
  els.memoryList.innerHTML = state.memories.map((memory) => `
    <div class="memory-item">
      <div>
        <strong>${escapeHtml(memory.key || memory.memoryKey || "偏好")}</strong>
        <span>${escapeHtml(memory.value || memory.memoryValue || "")}</span>
      </div>
      <button type="button" class="secondary" data-memory-id="${escapeHtml(memory.memoryId || "")}">禁用</button>
    </div>
  `).join("");
  els.memoryList.querySelectorAll("[data-memory-id]").forEach((button) => {
    button.addEventListener("click", () => deactivateMemory(button.dataset.memoryId));
  });
}

function renderDebug() {
  els.rawJson.textContent = JSON.stringify({
    state: {
      sessionId: state.sessionId,
      requirementId: state.requirementId,
      requirementSpec: state.requirementSpec,
      requirementValidation: state.requirementValidation,
      requirementConfirmed: state.requirementConfirmed,
      requirementDirty: state.requirementDirty,
      jobId: state.jobId,
      jobStatus: state.jobStatus,
      jobStage: state.jobStage,
      jobDetail: state.jobDetail,
      recentJobs: state.recentJobs,
      jobStartedAt: state.jobStartedAt,
      jobUpdatedAt: state.jobUpdatedAt,
      jobFinishedAt: state.jobFinishedAt,
      jobErrorMessage: state.jobErrorMessage,
      jobActionHint: state.jobActionHint,
      remainingCredits: state.remainingCredits,
      planId: state.planId,
      currentVersion: state.currentVersion,
      latestVersion: state.latestVersion,
      memories: state.memories
    },
    lastRawResponse: state.lastRawResponse
  }, null, 2);
}

async function api(path, options = {}) {
  const init = {
    method: options.method || "GET",
    headers: {
      Accept: "application/json"
    }
  };
  if (options.body !== undefined) {
    init.headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(options.body);
  }

  const response = await fetch(path, init);
  const contentType = response.headers.get("content-type") || "";
  const body = contentType.includes("application/json")
    ? await response.json()
    : await response.text();

  if (!response.ok) {
    throw new Error(extractErrorMessage(body) || `请求失败：HTTP ${response.status}`);
  }
  return body;
}

function setLoading(value, label = "") {
  state.loading = value;
  state.loadingLabel = label;
  renderAll();
}

function showMainMessage(message, type = "") {
  state.lastAssistantMessage = message;
  els.mainMessage.className = `message-line ${type}`.trim();
  els.mainMessage.textContent = message || "";
  renderStatus();
  renderJobProgress();
}

function showModifyMessage(message, type = "") {
  els.modifyMessage.className = `message-line ${type}`.trim();
  els.modifyMessage.textContent = message || "";
}

function markRequirementDirty() {
  const confirmed = state.requirementConfirmed || state.requirementSpec?.status === "CONFIRMED";
  if (state.loading || confirmed) {
    return;
  }
  state.requirementDirty = true;
  renderRequirementButtons();
  renderConfirmReason();
  persistState();
}

function unlockRequirement() {
  state.requirementConfirmed = false;
  state.requirementDirty = false;
  if (state.requirementSpec?.status === "CONFIRMED") {
    state.requirementSpec.status = "DRAFT";
  }
  persistState();
  renderAll();
  showMainMessage("可以继续编辑需求表，修改后请重新保存并确认。", "ok");
}

function resetFlow() {
  stopJobPolling();
  const sessionId = state.sessionId;
  Object.assign(state, {
    sessionId,
    requirementId: null,
    requirementSpec: null,
    requirementValidation: null,
    requirementConfirmed: false,
    requirementDirty: false,
    jobId: null,
    jobStatus: null,
    jobStage: null,
    jobDetail: null,
    recentJobs: [],
    jobStartedAt: null,
    jobUpdatedAt: null,
    jobFinishedAt: null,
    jobErrorMessage: "",
    jobActionHint: "",
    planAutoLoadedForJob: null,
    remainingCredits: null,
    planId: null,
    planRecord: null,
    currentVersion: null,
    latestVersion: null,
    errorMessage: "",
    lastAssistantMessage: "",
    lastRawResponse: null,
    pollingTimer: null
  });
  els.messageInput.value = "";
  els.requirementForm.reset();
  els.modifyInput.value = "";
  persistState();
  renderAll();
  showMainMessage("已重置当前规划流程。", "ok");
}

async function copyPlan() {
  const answer = state.planRecord?.currentAnswer;
  if (!answer) {
    return;
  }
  try {
    if (!navigator.clipboard?.writeText) {
      throw new Error("当前浏览器不支持自动复制。");
    }
    await navigator.clipboard.writeText(answer);
    showMainMessage("方案已复制。", "ok");
  } catch (error) {
    showMainMessage(error.message || "复制失败，请手动选择方案文本复制。", "error");
  }
}

function persistState() {
  const snapshot = {
    sessionId: state.sessionId,
    requirementId: state.requirementId,
    requirementSpec: state.requirementSpec,
    requirementValidation: state.requirementValidation,
    requirementConfirmed: state.requirementConfirmed,
    requirementDirty: state.requirementDirty,
    jobId: state.jobId,
    jobStatus: state.jobStatus,
    jobStage: state.jobStage,
    jobDetail: state.jobDetail,
    recentJobs: state.recentJobs,
    jobStartedAt: state.jobStartedAt,
    jobUpdatedAt: state.jobUpdatedAt,
    jobFinishedAt: state.jobFinishedAt,
    jobErrorMessage: state.jobErrorMessage,
    jobActionHint: state.jobActionHint,
    planAutoLoadedForJob: state.planAutoLoadedForJob,
    remainingCredits: state.remainingCredits,
    planId: state.planId,
    planRecord: state.planRecord,
    currentVersion: state.currentVersion,
    latestVersion: state.latestVersion,
    lastAssistantMessage: state.lastAssistantMessage
  };
  localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot));
}

function restoreState() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return;
    }
    const snapshot = JSON.parse(raw);
    Object.assign(state, snapshot, {
      pollingTimer: null,
      loading: false,
      loadingLabel: ""
    });
    if (state.requirementSpec) {
      fillRequirementForm(state.requirementSpec);
    }
  } catch {
    localStorage.removeItem(STORAGE_KEY);
  }
}

function setRequirementFormLocked(locked) {
  els.requirementForm.querySelectorAll("input, select, textarea").forEach((field) => {
    field.disabled = locked;
  });
}

function loadSessionId() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const snapshot = JSON.parse(raw);
      if (snapshot.sessionId) {
        return snapshot.sessionId;
      }
    }
  } catch {
    localStorage.removeItem(STORAGE_KEY);
  }
  return `planner-${window.crypto?.randomUUID ? window.crypto.randomUUID() : Date.now().toString(36)}`;
}

function isJobActive(status) {
  return status === "PENDING" || status === "RUNNING";
}

function jobTerminal(status) {
  return status === "SUCCEEDED" || status === "FAILED" || status === "CANCELLED";
}

function jobStatusLabel(status) {
  switch (status) {
    case "PENDING":
      return "等待生成";
    case "RUNNING":
      return "生成中";
    case "SUCCEEDED":
      return "已完成";
    case "FAILED":
      return "生成失败";
    case "CANCELLED":
      return "已取消";
    default:
      return status || "未创建";
  }
}

function jobStatusClass(status) {
  switch (status) {
    case "SUCCEEDED":
      return "ok";
    case "FAILED":
    case "CANCELLED":
      return "error";
    case "PENDING":
    case "RUNNING":
      return "running";
    default:
      return "muted";
  }
}

function formatDuration(seconds) {
  const value = Number(seconds);
  if (!Number.isFinite(value) || value <= 0) {
    return "0 秒";
  }
  const minutes = Math.floor(value / 60);
  const rest = Math.floor(value % 60);
  if (minutes <= 0) {
    return `${rest} 秒`;
  }
  return rest ? `${minutes} 分 ${rest} 秒` : `${minutes} 分`;
}

function formatJobTime(value) {
  if (!value) {
    return "-";
  }
  const time = new Date(value);
  if (Number.isNaN(time.getTime())) {
    return "-";
  }
  return time.toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  });
}

function extractErrorMessage(body) {
  if (!body) {
    return "";
  }
  if (typeof body === "string") {
    return body;
  }
  return body.assistantMessage
    || body.message
    || body.errorMessage
    || body.graphResult?.answer
    || body.graphResult?.errorMessage
    || JSON.stringify(body);
}

function extractRemainingCredits(jobResponse) {
  const credits = Number(jobResponse?.result?.remainingCredits);
  return Number.isFinite(credits) ? credits : state.remainingCredits;
}

function renderMarkdown(value) {
  const lines = String(value || "").split(/\r?\n/);
  const html = [];
  let inList = false;

  const closeList = () => {
    if (inList) {
      html.push("</ul>");
      inList = false;
    }
  };

  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) {
      closeList();
      continue;
    }
    const heading = trimmed.match(/^(#{1,3})\s+(.*)$/);
    if (heading) {
      closeList();
      const level = heading[1].length;
      html.push(`<h${level}>${inlineMarkdown(heading[2])}</h${level}>`);
      continue;
    }
    const bullet = trimmed.match(/^[-*]\s+(.*)$/);
    if (bullet) {
      if (!inList) {
        html.push("<ul>");
        inList = true;
      }
      html.push(`<li>${inlineMarkdown(bullet[1])}</li>`);
      continue;
    }
    closeList();
    html.push(`<p>${inlineMarkdown(trimmed)}</p>`);
  }
  closeList();
  return html.join("");
}

function inlineMarkdown(value) {
  return escapeHtml(value).replace(/\*\*(.*?)\*\*/g, "<strong>$1</strong>");
}

function parseCsv(value) {
  if (!value) {
    return [];
  }
  return value.split(/[,，、]/).map((item) => item.trim()).filter(Boolean);
}

function csv(values) {
  return Array.isArray(values) ? values.join(", ") : "";
}

function nullableText(value) {
  const trimmed = String(value || "").trim();
  return trimmed ? trimmed : null;
}

function normalizeCurrencyInput(value) {
  const textValue = nullableText(value);
  return textValue ? textValue.toUpperCase() : null;
}

function validateFormLocally() {
  normalizeFormValues();
  const errors = [];
  const duration = intOrNull(els.durationDaysInput.value);
  const travelers = intOrNull(els.travelerCountInput.value);
  const budget = numberOrNull(els.budgetAmountInput.value);
  const currency = nullableText(els.budgetCurrencyInput.value);

  if (els.durationDaysInput.value && (duration == null || duration <= 0)) {
    errors.push("旅行天数必须大于 0");
  }
  if (els.travelerCountInput.value && (travelers == null || travelers <= 0)) {
    errors.push("人数必须大于 0");
  }
  if (els.budgetAmountInput.value && (budget == null || budget < 0)) {
    errors.push("预算金额不能为负数");
  }
  if (budget != null && !currency) {
    errors.push("填写预算金额后，请填写预算币种");
  }
  return { errors };
}

function normalizeFormValues() {
  els.destinationsInput.value = csv(uniqueList(parseCsv(els.destinationsInput.value)));
  els.preferencesInput.value = csv(uniqueList(parseCsv(els.preferencesInput.value)));
  els.avoidancesInput.value = csv(uniqueList(parseCsv(els.avoidancesInput.value)));
  els.budgetCurrencyInput.value = text(normalizeCurrencyInput(els.budgetCurrencyInput.value));
}

function uniqueList(values) {
  return [...new Set(values.map((value) => value.trim()).filter(Boolean))];
}

function text(value) {
  return value == null ? "" : String(value);
}

function toVersionNumber(value) {
  const version = Number(value);
  return Number.isFinite(version) && version > 0 ? version : null;
}

function maxVersion(...values) {
  return values
    .map(toVersionNumber)
    .filter((value) => value !== null)
    .reduce((max, value) => Math.max(max, value), null);
}

function numberText(value) {
  return value == null ? "" : String(value);
}

function intOrNull(value) {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : null;
}

function numberOrNull(value) {
  const parsed = Number.parseFloat(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function boolOrNull(value) {
  if (value === "true") {
    return true;
  }
  if (value === "false") {
    return false;
  }
  return null;
}

function shortId(value) {
  if (!value) {
    return "-";
  }
  const textValue = String(value);
  return textValue.length > 12 ? `${textValue.slice(0, 8)}...` : textValue;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
