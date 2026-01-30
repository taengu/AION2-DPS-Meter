class DpsApp {
  constructor() {
    if (DpsApp.instance) return DpsApp.instance;

    this.POLL_MS = 200;
    this.USER_NAME = "";
    this.onlyShowUser = false;
    this.storageKeys = {
      userName: "dpsMeter.userName",
      onlyShowUser: "dpsMeter.onlyShowUser",
      detailsBackgroundOpacity: "dpsMeter.detailsBackgroundOpacity",
      targetSelection: "dpsMeter.targetSelection",
    };

    this.dpsFormatter = new Intl.NumberFormat("en-US");
    this.lastJson = null;
    this.isCollapse = false;

    // 빈데이터 덮어쓰기 방지 스냅샷
    this.lastSnapshot = null;
    // reset 직후 서버가 구 데이터 계속 주는 현상 방지
    this.resetPending = false;

    this.BATTLE_TIME_BASIS = "render";
    this.GRACE_MS = 30000;
    this.GRACE_ARM_MS = 1000;


    // battleTime 캐시
    this._battleTimeVisible = false;
    this._lastBattleTimeMs = null;

    this._pollTimer = null;

    this.i18n = window.i18n;
    this.targetSelection = "mostDamage";
    this.lastTargetMode = "";
    this.lastTargetName = "";

    DpsApp.instance = this;
  }

  safeGetStorage(key) {
    try {
      return localStorage.getItem(key);
    } catch (e) {
      globalThis.uiDebug?.log?.("localStorage.get blocked", { key, error: String(e) });
      return null;
    }
  }

  safeSetStorage(key, value) {
    try {
      localStorage.setItem(key, value);
    } catch (e) {
      globalThis.uiDebug?.log?.("localStorage.set blocked", { key, error: String(e) });
    }
  }


  static createInstance() {
    if (!DpsApp.instance) DpsApp.instance = new DpsApp();
    return DpsApp.instance;
  }

  start() {
    this.elList = document.querySelector(".list");
    this.elBossName = document.querySelector(".bossName");
    this.elBossName.textContent = this.getDefaultTargetLabel();

    this.resetBtn = document.querySelector(".resetBtn");
    this.collapseBtn = document.querySelector(".collapseBtn");

    this.bindHeaderButtons();
    this.bindDragToMoveWindow();

    this.meterUI = createMeterUI({
      elList: this.elList,
      dpsFormatter: this.dpsFormatter,
      getUserName: () => this.USER_NAME,
      onClickUserRow: (row) => this.detailsUI.open(row),
    });

    this.battleTime = createBattleTimeUI({
      rootEl: document.querySelector(".battleTime"),
      tickSelector: ".tick",
      statusSelector: ".status",
      analysisSelector: ".analysisStatus",
      getAnalysisText: () => this.i18n?.t("battleTime.analysing", "Analysing data..."),
      graceMs: this.GRACE_MS,
      graceArmMs: this.GRACE_ARM_MS,
      visibleClass: "isVisible",
    });
    this.battleTime.setVisible(false);

    this.detailsPanel = document.querySelector(".detailsPanel");
    this.detailsClose = document.querySelector(".detailsClose");
    this.detailsTitle = document.querySelector(".detailsTitle");
    this.detailsStatsEl = document.querySelector(".detailsStats");
    this.skillsListEl = document.querySelector(".skills");

    this.detailsUI = createDetailsUI({
      detailsPanel: this.detailsPanel,
      detailsClose: this.detailsClose,
      detailsTitle: this.detailsTitle,
      detailsStatsEl: this.detailsStatsEl,
      skillsListEl: this.skillsListEl,
      dpsFormatter: this.dpsFormatter,
      getDetails: (row) => this.getDetails(row),
    });
    this.setupDetailsPanelSettings();
    this.setupSettingsPanel();
    this.detailsUI?.updateLabels?.();
    this.i18n?.onChange?.((lang) => {
      if (this.languageSelect) {
        this.languageSelect.value = lang;
      }
      this.detailsUI?.updateLabels?.();
      if (this.battleTime?.setAnalysisTextProvider) {
        this.battleTime.setAnalysisTextProvider(() =>
          this.i18n?.t("battleTime.analysing", "Analysing data...")
        );
      }
      this.refreshConnectionInfo();
      this.refreshBossLabel();
    });
    window.ReleaseChecker?.start?.();

    this.startPolling();
    this.fetchDps();
  }

  nowMs() {
    return typeof performance !== "undefined" ? performance.now() : Date.now();
  }

  safeParseJSON(raw, fallback = {}) {
    if (typeof raw !== "string") {
      return fallback;
    }
    try {
      const value = JSON.parse(raw);
      return value && typeof value === "object" ? value : fallback;
    } catch {
      return fallback;
    }
  }

  startPolling() {
    if (this._pollTimer) return;
    this._pollTimer = setInterval(() => this.fetchDps(), this.POLL_MS);
  }

  stopPolling() {
    if (!this._pollTimer) return;
    clearInterval(this._pollTimer);
    this._pollTimer = null;
  }

  resetAll({ callBackend = true } = {}) {
    this.resetPending = !!callBackend;


    this.lastSnapshot = null;
    this.lastJson = null;
    this.lastTargetMode = "";
    this.lastTargetName = "";

    this._battleTimeVisible = false;
    this._lastBattleTimeMs = null;
    this.battleTime?.reset?.();
    this.battleTime?.setVisible?.(false);

    this.detailsUI?.close?.();
    this.meterUI?.onResetMeterUi?.();

    if (this.elBossName) {
      this.elBossName.textContent = this.getDefaultTargetLabel();
    }
    if (callBackend) {
      window.javaBridge?.resetDps?.();
    }
  }




  fetchDps() {
    if (this.isCollapse) return;
    const now = this.nowMs();
    const raw = window.dpsData?.getDpsData?.();
    // globalThis.uiDebug?.log?.("getBattleDetail", raw);

    // 값이 없으면 타이머 숨김
    if (typeof raw !== "string") {
      this._rawLastChangedAt = now;

      this._lastBattleTimeMs = null;
      this._battleTimeVisible = false;
      this.battleTime.setVisible(false);
      return;
    }

    if (raw === this.lastJson) {
      const shouldBeVisible = this._battleTimeVisible && !this.isCollapse;

      this.battleTime.setVisible(shouldBeVisible);
      if (shouldBeVisible) {
        this.battleTime.update(now, this._lastBattleTimeMs);
      }

      return;
    }

    this.lastJson = raw;

    const { rows, targetName, targetMode, battleTimeMs } = this.buildRowsFromPayload(raw);
    this._lastBattleTimeMs = battleTimeMs;
    this.lastTargetMode = targetMode;
    this.lastTargetName = targetName;


    const showByServer = rows.length > 0;
    if (this.resetPending) {
      const resetAck = rows.length === 0;

      this._battleTimeVisible = false;
      this.battleTime.setVisible(false);

      if (!resetAck) {
        return;
      }

      this.resetPending = false;
    }
    // 빈값은 ui 안덮어씀
    let rowsToRender = rows;
    if (rows.length === 0) {
      if (this.lastSnapshot) rowsToRender = this.lastSnapshot;
      else {
        this._battleTimeVisible = false;
        this.battleTime.setVisible(false);
        return;
      }
    } else {
      this.lastSnapshot = rows;
    }

    // 타이머 표시 여부
    const showByRender = rowsToRender.length > 0;
    const showBattleTime = this.BATTLE_TIME_BASIS === "server" ? showByServer : showByRender;

    const eligible = showBattleTime && Number.isFinite(Number(battleTimeMs));

    this._battleTimeVisible = eligible;
    const shouldBeVisible = eligible && !this.isCollapse;

    this.battleTime.setVisible(shouldBeVisible);

    if (shouldBeVisible) {
      this.battleTime.update(now, battleTimeMs);
    }

    if (this.onlyShowUser && this.USER_NAME) {
      rowsToRender = rowsToRender.filter((row) => row.name === this.USER_NAME);
    }

    // render
    this.elBossName.textContent = targetName ? targetName : this.getDefaultTargetLabel(targetMode);
    this.meterUI.updateFromRows(rowsToRender);
  }

  buildRowsFromPayload(raw) {
    const payload = this.safeParseJSON(raw, {});
    const targetName = typeof payload?.targetName === "string" ? payload.targetName : "";
    const targetMode = typeof payload?.targetMode === "string" ? payload.targetMode : "";

    const mapObj = payload?.map && typeof payload.map === "object" ? payload.map : {};
    const rows = this.buildRowsFromMapObject(mapObj);

    const battleTimeMsRaw = payload?.battleTime;
    const battleTimeMs = Number.isFinite(Number(battleTimeMsRaw)) ? Number(battleTimeMsRaw) : null;

    return { rows, targetName, targetMode, battleTimeMs };
  }

  buildRowsFromMapObject(mapObj) {
    const rows = [];

    for (const [id, value] of Object.entries(mapObj || {})) {
      const isObj = value && typeof value === "object";

      const job = isObj ? (value.job ?? "") : "";
      const nickname = isObj ? (value.nickname ?? "") : "";
      const name = nickname || String(id);

      const dpsRaw = isObj ? value.dps : value;
      const dps = Math.trunc(Number(dpsRaw));

      // 소수점 한자리
      const contribRaw = isObj ? Number(value.damageContribution) : NaN;
      const damageContribution = Number.isFinite(contribRaw)
        ? Math.round(contribRaw * 10) / 10
        : NaN;

      if (!Number.isFinite(dps)) {
        continue;
      }

      rows.push({
        id: String(id),
        name,
        job,
        dps,
        damageContribution,
        isUser: name === this.USER_NAME,
      });
    }

    return rows;
  }

  async getDetails(row) {
    const raw = await window.dpsData?.getBattleDetail?.(row.id);
    let detailObj = raw;
    // globalThis.uiDebug?.log?.("getBattleDetail", detailObj);

    if (typeof raw === "string") detailObj = this.safeParseJSON(raw, {});
    if (!detailObj || typeof detailObj !== "object") detailObj = {};

    const skills = [];
    let totalDmg = 0;

    let totalTimes = 0;
    let totalCrit = 0;
    let totalParry = 0;
    let totalBack = 0;
    let totalPerfect = 0;
    let totalDouble = 0;

    for (const [code, value] of Object.entries(detailObj)) {
      if (!value || typeof value !== "object") continue;

      const nameRaw = typeof value.skillName === "string" ? value.skillName.trim() : "";
      const translatedName = this.i18n?.getSkillName?.(code, nameRaw) ?? nameRaw;
      const baseName =
        translatedName ||
        this.i18n?.format?.("skills.fallback", { code }, `Skill ${code}`) ||
        `Skill ${code}`;
      const dotName =
        this.i18n?.format?.("skills.dot", { name: baseName }, `${baseName} - DOT`) ||
        `${baseName} - DOT`;

      // 공통
      const pushSkill = ({
        codeKey,
        name,
        time,
        dmg,
        crit = 0,
        parry = 0,
        back = 0,
        perfect = 0,
        double = 0,
        countForTotals = true,
      }) => {
        const dmgInt = Math.trunc(Number(String(dmg ?? "").replace(/,/g, ""))) || 0;
        if (dmgInt <= 0) {
          return;
        }

        const t = Number(time) || 0;

        totalDmg += dmgInt;
        if (countForTotals) {
          totalTimes += t;
          totalCrit += Number(crit) || 0;
          totalParry += Number(parry) || 0;
          totalBack += Number(back) || 0;
          totalPerfect += Number(perfect) || 0;
          totalDouble += Number(double) || 0;
        }
        skills.push({
          code: String(codeKey),
          name,
          time: t,
          crit: Number(crit) || 0,
          parry: Number(parry) || 0,
          back: Number(back) || 0,
          perfect: Number(perfect) || 0,
          double: Number(double) || 0,
          dmg: dmgInt,
        });
      };

      // 일반 피해
      pushSkill({
        codeKey: code,
        name: baseName,
        time: value.times,
        dmg: value.damageAmount,
        crit: value.critTimes,
        parry: value.parryTimes,
        back: value.backTimes,
        perfect: value.perfectTimes,
        double: value.doubleTimes,
      });

      // 도트피해
      if (Number(String(value.dotDamageAmount ?? "").replace(/,/g, "")) > 0) {
        pushSkill({
          codeKey: `${code}-dot`, // 유니크키
          name: dotName,
          time: value.dotTimes,
          dmg: value.dotDamageAmount,
          countForTotals: false,
        });
      }
    }

    const pct = (num, den) => {
      if (den <= 0) return 0;
      return Math.round((num / den) * 1000) / 10;
    };
    const contributionPct = Number(row?.damageContribution);
    const combatTime = this.battleTime?.getCombatTimeText?.() ?? "00:00";

    return {
      totalDmg,
      contributionPct,
      totalCritPct: pct(totalCrit, totalTimes),
      totalParryPct: pct(totalParry, totalTimes),
      totalBackPct: pct(totalBack, totalTimes),
      totalPerfectPct: pct(totalPerfect, totalTimes),
      totalDoublePct: pct(totalDouble, totalTimes),
      combatTime,

      skills,
    };
  }

  bindHeaderButtons() {
    this.collapseBtn?.addEventListener("click", () => {
      this.isCollapse = !this.isCollapse;

      // 접히면 polling 멈추고 완전 초기화
      if (this.isCollapse) {
        this.stopPolling();
        this.elList.style.display = "none";
        this.resetAll({ callBackend: true });
      } else {
        // 펼치면 polling 재개하고 즉시 1회 fetch
        this.elList.style.display = "grid";
        this.startPolling();
        this.fetchDps();
      }

      const iconName = this.isCollapse ? "arrow-down-wide-narrow" : "arrow-up-wide-narrow";
      const iconEl =
        this.collapseBtn.querySelector("svg") || this.collapseBtn.querySelector("[data-lucide]");
      if (!iconEl) {
        return;
      }

      iconEl.setAttribute("data-lucide", iconName);
      window.lucide?.createIcons?.({ root: this.collapseBtn });
    });
    this.resetBtn?.addEventListener("click", () => {
      this.resetAll({ callBackend: true });
    });
  }

  setupSettingsPanel() {
    this.settingsPanel = document.querySelector(".settingsPanel");
    this.settingsClose = document.querySelector(".settingsClose");
    this.settingsBtn = document.querySelector(".settingsBtn");
    this.lockedIp = document.querySelector(".lockedIp");
    this.lockedPort = document.querySelector(".lockedPort");
    this.resetDetectBtn = document.querySelector(".resetDetectBtn");
    this.characterNameInput = document.querySelector(".characterNameInput");
    this.onlyMeCheckbox = document.querySelector(".onlyMeCheckbox");
    this.discordButton = document.querySelector(".discordButton");
    this.languageSelect = document.querySelector(".languageSelect");
    this.targetSelect = document.querySelector(".targetSelect");

    const storedName = this.safeGetStorage(this.storageKeys.userName) || "";
    const storedOnlyShow = this.safeGetStorage(this.storageKeys.onlyShowUser) === "true";
    const storedTargetSelection = this.safeGetStorage(this.storageKeys.targetSelection);

    this.setUserName(storedName, { persist: false, syncBackend: true });
    this.setOnlyShowUser(storedOnlyShow, { persist: false });
    this.setTargetSelection(storedTargetSelection || this.targetSelection, {
      persist: false,
      syncBackend: true,
    });

    if (this.characterNameInput) {
      this.characterNameInput.value = this.USER_NAME;
      this.characterNameInput.addEventListener("input", (event) => {
        const value = event.target?.value ?? "";
        this.setUserName(value, { persist: true, syncBackend: true });
      });
    }

    if (this.onlyMeCheckbox) {
      this.onlyMeCheckbox.checked = this.onlyShowUser;
      this.onlyMeCheckbox.addEventListener("change", (event) => {
        const isChecked = !!event.target?.checked;
        this.setOnlyShowUser(isChecked, { persist: true });
      });
    }

    if (this.languageSelect) {
      const currentLanguage = this.i18n?.getLanguage?.() || "en";
      this.languageSelect.value = currentLanguage;
      this.languageSelect.addEventListener("change", (event) => {
        const value = event.target?.value;
        if (value) {
          this.i18n?.setLanguage?.(value, { persist: true });
        }
      });
    }

    if (this.targetSelect) {
      this.targetSelect.value = this.targetSelection;
      this.targetSelect.addEventListener("change", (event) => {
        const value = event.target?.value;
        if (value) {
          this.setTargetSelection(value, { persist: true, syncBackend: true });
        }
      });
    }

    this.settingsBtn?.addEventListener("click", () => {
      this.toggleSettingsPanel();
    });

    this.settingsClose?.addEventListener("click", () => this.closeSettingsPanel());

    this.resetDetectBtn?.addEventListener("click", () => {
      window.javaBridge?.resetAutoDetection?.();
      this.refreshConnectionInfo();
    });

    this.discordButton?.addEventListener("click", () => {
      window.javaBridge?.openBrowser?.("https://discord.gg/Aion2Global");
    });
  }

  setupDetailsPanelSettings() {
    this.detailsOpacityInput = document.querySelector(".detailsOpacityInput");
    this.detailsOpacityValue = document.querySelector(".detailsOpacityValue");
    this.detailsSettingsBtn = document.querySelector(".detailsSettingsBtn");
    this.detailsSettingsMenu = document.querySelector(".detailsSettingsMenu");

    const storedOpacity = this.safeGetStorage(this.storageKeys.detailsBackgroundOpacity);
    const initialOpacity = this.parseDetailsOpacity(storedOpacity);
    this.setDetailsBackgroundOpacity(initialOpacity, { persist: false });

    if (this.detailsOpacityInput) {
      this.detailsOpacityInput.value = String(Math.round(initialOpacity * 100));
      this.detailsOpacityInput.addEventListener("input", (event) => {
        const nextValue = Number(event.target?.value);
        const nextOpacity = Number.isFinite(nextValue) ? nextValue / 100 : 1;
        this.setDetailsBackgroundOpacity(nextOpacity, { persist: true });
      });
    }

    this.detailsSettingsBtn?.addEventListener("click", (event) => {
      event.stopPropagation();
      this.toggleDetailsSettingsMenu();
    });

    this.detailsSettingsMenu?.addEventListener("click", (event) => {
      event.stopPropagation();
    });

    this.detailsClose?.addEventListener("click", () => {
      this.closeDetailsSettingsMenu();
    });

    document.addEventListener("click", (event) => {
      if (!this.detailsSettingsMenu?.classList.contains("isOpen")) {
        return;
      }
      const target = event.target;
      if (
        this.detailsSettingsMenu?.contains(target) ||
        this.detailsSettingsBtn?.contains(target)
      ) {
        return;
      }
      this.closeDetailsSettingsMenu();
    });
  }

  parseDetailsOpacity(value) {
    if (value === null || value === undefined || value === "") {
      return 0.5;
    }
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) {
      return 0.5;
    }
    return Math.min(1, Math.max(0, parsed));
  }

  setDetailsBackgroundOpacity(opacity, { persist = false } = {}) {
    const clamped = Math.min(1, Math.max(0, opacity));
    if (this.detailsPanel) {
      this.detailsPanel.style.setProperty("--details-bg-opacity", clamped);
    }
    if (this.detailsOpacityValue) {
      this.detailsOpacityValue.textContent = `${Math.round(clamped * 100)}%`;
    }
    if (this.detailsOpacityInput && document.activeElement !== this.detailsOpacityInput) {
      this.detailsOpacityInput.value = String(Math.round(clamped * 100));
    }
    if (persist) {
      this.safeSetStorage(this.storageKeys.detailsBackgroundOpacity, String(clamped));
    }
  }

  toggleDetailsSettingsMenu() {
    if (!this.detailsSettingsMenu) return;
    this.detailsSettingsMenu.classList.toggle("isOpen");
  }

  closeDetailsSettingsMenu() {
    this.detailsSettingsMenu?.classList.remove("isOpen");
  }

  toggleSettingsPanel() {
    if (!this.settingsPanel) return;
    const isOpen = this.settingsPanel.classList.toggle("isOpen");
    if (isOpen) {
      this.detailsUI?.close?.();
      this.refreshConnectionInfo();
    }
  }

  closeSettingsPanel() {
    this.settingsPanel?.classList.remove("isOpen");
  }

  setUserName(name, { persist = false, syncBackend = false } = {}) {
    const trimmed = String(name ?? "").trim();
    this.USER_NAME = trimmed;
    if (persist) {
      localStorage.setItem(this.storageKeys.userName, trimmed);
    }
    if (syncBackend) {
      window.javaBridge?.setCharacterName?.(trimmed);
    }
    if (!this.isCollapse) {
      this.fetchDps();
    }
  }

  setOnlyShowUser(enabled, { persist = false } = {}) {
    this.onlyShowUser = !!enabled;
    if (persist) {
      localStorage.setItem(this.storageKeys.onlyShowUser, String(this.onlyShowUser));
    }
    if (!this.isCollapse) {
      this.fetchDps();
    }
  }

  setTargetSelection(mode, { persist = false, syncBackend = false } = {}) {
    this.targetSelection = mode || "mostDamage";
    if (persist) {
      localStorage.setItem(this.storageKeys.targetSelection, String(this.targetSelection));
    }
    if (syncBackend) {
      window.javaBridge?.setTargetSelection?.(this.targetSelection);
    }
    if (this.targetSelect && document.activeElement !== this.targetSelect) {
      this.targetSelect.value = this.targetSelection;
    }
  }

  refreshConnectionInfo() {
    if (!this.lockedIp || !this.lockedPort) return;
    const raw = window.javaBridge?.getConnectionInfo?.();
    if (typeof raw !== "string") {
      this.lockedIp.textContent = "-";
      this.lockedPort.textContent = "-";
      return;
    }
    const info = this.safeParseJSON(raw, {});
    const ip = info?.ip || "-";
    const port = Number.isFinite(Number(info?.port))
      ? String(info.port)
      : this.i18n?.t("connection.auto", "Auto");
    this.lockedIp.textContent = ip;
    this.lockedPort.textContent = port;
  }

  getDefaultTargetLabel(targetMode = "") {
    if (targetMode === "allTargets") {
      return this.i18n?.t("target.all", "All targets") ?? "All targets";
    }
    return this.i18n?.t("header.title", "DPS METER") ?? "DPS METER";
  }

  refreshBossLabel() {
    if (!this.elBossName) return;
    if (this.lastTargetName) {
      return;
    }
    this.elBossName.textContent = this.getDefaultTargetLabel(this.lastTargetMode);
  }

  bindDragToMoveWindow() {
    let isDragging = false;
    let startX = 0,
      startY = 0;
    let initialStageX = 0,
      initialStageY = 0;

    document.addEventListener("mousedown", (e) => {
      isDragging = true;
      startX = e.screenX;
      startY = e.screenY;
      initialStageX = window.screenX;
      initialStageY = window.screenY;
    });

    document.addEventListener("mousemove", (e) => {
      if (!isDragging) return;
      if (!window.javaBridge) return;

      const deltaX = e.screenX - startX;
      const deltaY = e.screenY - startY;
      window.javaBridge.moveWindow(initialStageX + deltaX, initialStageY + deltaY);
    });

    document.addEventListener("mouseup", () => {
      isDragging = false;
    });
  }
}

DpsApp.instance = null;

// 디버그콘솔
const setupDebugConsole = () => {
  const g = globalThis;
  if (globalThis.uiDebug?.log) return globalThis.uiDebug;

  const consoleDiv = document.querySelector(".console");
  if (!consoleDiv) {
    globalThis.uiDebug = { log: () => {}, clear: () => {} };
    return globalThis.uiDebug;
  }

  const safeStringify = (value) => {
    if (typeof value === "string") return value;
    if (value instanceof Error) return `${value.name}: ${value.message}`;
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  };

  const appendLine = (line) => {
    consoleDiv.style.display = "block";
    consoleDiv.innerHTML += line + "<br>";
    consoleDiv.scrollTop = consoleDiv.scrollHeight;
  };

  globalThis.uiDebug = {
    clear() {
      consoleDiv.innerHTML = "";
    },
    log(...args) {
      const line = args.map(safeStringify).join(" ");
      appendLine(line);
      console.log(...args);
    },
  };

  return globalThis.uiDebug;
};

// setupDebugConsole();
const dpsApp = DpsApp.createInstance();
const debug = globalThis.uiDebug;

window.addEventListener("error", (event) => {
  debug?.log?.("window.error", {
    message: event.message,
    source: event.filename,
    line: event.lineno,
    column: event.colno,
  });
});

window.addEventListener("unhandledrejection", (event) => {
  debug?.log?.("unhandledrejection", event.reason);
});

const startApp = async () => {
  debug?.log?.("startApp", {
    readyState: document.readyState,
    hasDpsData: !!window.dpsData,
    hasJavaBridge: !!window.javaBridge,
  });
  try {
    await window.i18n?.init?.();
    window.lucide?.createIcons?.();
    dpsApp.start();
  } catch (err) {
    debug?.log?.("startApp.error", err);
  }
};

const waitForBridgeAndStart = () => {
  // JavaFX WebView injects these after loadWorker SUCCEEDED (slightly later than DOMContentLoaded)
  const ready = !!window.javaBridge && !!window.dpsData;

  debug?.log?.("waitForBridge", {
    readyState: document.readyState,
    hasDpsData: !!window.dpsData,
    hasJavaBridge: !!window.javaBridge,
  });

  if (ready) {
    startApp();
    return;
  }
  setTimeout(waitForBridgeAndStart, 50);
};

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", waitForBridgeAndStart, { once: true });
} else {
  waitForBridgeAndStart();
}
