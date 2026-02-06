class DpsApp {
  constructor() {
    if (DpsApp.instance) return DpsApp.instance;

    this.POLL_MS = 200;
    this.WINDOW_TITLE_POLL_MS = 30000;
    this.USER_NAME = "";
    this.onlyShowUser = false;
    this.debugLoggingEnabled = false;
    this.pinMeToTop = false;
    this.includeMainMeterScreenshot = false;
    this.saveScreenshotToFolder = false;
    this.screenshotFolder = "";
    this.storageKeys = {
      userName: "dpsMeter.userName",
      onlyShowUser: "dpsMeter.onlyShowUser",
      allTargetsWindowMs: "dpsMeter.allTargetsWindowMs",
      trainSelectionMode: "dpsMeter.trainSelectionMode",
      targetSelectionWindowMs: "dpsMeter.targetSelectionWindowMs",
      meterFillOpacity: "dpsMeter.meterFillOpacity",
      detailsBackgroundOpacity: "dpsMeter.detailsBackgroundOpacity",
      detailsIncludeMeterScreenshot: "dpsMeter.detailsIncludeMeterScreenshot",
      detailsSaveScreenshotToFolder: "dpsMeter.detailsSaveScreenshotToFolder",
      detailsScreenshotFolder: "dpsMeter.detailsScreenshotFolder",
      targetSelection: "dpsMeter.targetSelection",
      displayMode: "dpsMeter.displayMode",
      language: "dpsMeter.language",
      debugLogging: "dpsMeter.debugLoggingEnabled",
      pinMeToTop: "dpsMeter.pinMeToTop",
      theme: "dpsMeter.theme",
    };

    this.dpsFormatter = new Intl.NumberFormat("en-US");
    this.lastJson = null;
    this.isCollapse = false;
    this.displayMode = "dps";
    this.theme = "aion2";
    this.availableThemes = [
      "aion2",
      "asmodian",
      "cogni",
      "elyos",
      "ember",
      "fera",
      "frost",
      "natura",
      "obsidian",
      "varian",
    ];

    // 빈데이터 덮어쓰기 방지 스냅샷
    this.lastSnapshot = null;
    // reset 직후 서버가 구 데이터 계속 주는 현상 방지
    this.resetPending = false;
    this.refreshPending = false;

    this.BATTLE_TIME_BASIS = "render";
    this.GRACE_MS = 30000;
    this.GRACE_ARM_MS = 3000;


    // battleTime 캐시
    this._battleTimeVisible = false;
    this._lastBattleTimeMs = null;

    this._pollTimer = null;
    this._windowTitleTimer = null;

    this.i18n = window.i18n;
    this.targetSelection = "lastHitByMe";
    this.listSortDirection = "desc";
    this.lastTargetMode = "";
    this.lastTargetName = "";
    this.lastTargetId = 0;
    this._lastRenderedListSignature = "";
    this._lastRenderedTargetLabel = "";
    this._lastTargetSelection = this.targetSelection;
    this._lastRenderedRowsSummary = null;
    this.localPlayerId = null;
    this.trainSelectionMode = "all";
    this._detailsFlashTimer = null;
    this._meterFlashTimer = null;
    this._recentLocalIdByName = new Map();

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

  safeGetSetting(key) {
    try {
      const bridgeValue = window.javaBridge?.getSetting?.(key);
      if (bridgeValue !== undefined && bridgeValue !== null) {
        return bridgeValue;
      }
    } catch (e) {
      globalThis.uiDebug?.log?.("getSetting blocked", { key, error: String(e) });
    }
    return this.safeGetStorage(key);
  }

  safeSetSetting(key, value) {
    try {
      window.javaBridge?.setSetting?.(key, value);
    } catch (e) {
      globalThis.uiDebug?.log?.("setSetting blocked", { key, error: String(e) });
    }
    this.safeSetStorage(key, value);
  }


  static createInstance() {
    if (!DpsApp.instance) DpsApp.instance = new DpsApp();
    return DpsApp.instance;
  }

  start() {
    this.elList = document.querySelector(".list");
    this.elBossName = document.querySelector(".bossName");
    this.elBossName.textContent = this.getDefaultTargetLabel();
    this._lastRenderedTargetLabel = this.elBossName.textContent;
    this.battleTimeRoot = document.querySelector(".battleTime");
    this.analysisStatusEl = document.querySelector(".analysisStatus");
    this.aionRunning = false;
    this.isDetectingPort = false;
    this._connectionStatusOverride = false;

    this.resetBtn = document.querySelector(".resetBtn");
    this.targetModeBtn = document.querySelector(".footerBtns .targetModeBtn");
    this.collapseBtn = document.querySelector(".collapseBtn");
    this.metricToggleBtn = document.querySelector(".metricToggleBtn");

    this.bindHeaderButtons();
    this.bindDragToMoveWindow();
    this.bindResizeHandle();

    this.meterUI = createMeterUI({
      elList: this.elList,
      dpsFormatter: this.dpsFormatter,
      getUserName: () => this.USER_NAME,
      getMetric: (row) => this.getMetricForRow(row),
      getSortDirection: () => this.listSortDirection,
      getPinUserToTop: () => this.pinMeToTop,
      onClickUserRow: (row) => {
        const fallbackAllTargets =
          this.lastTargetMode === "lastHitByMe" &&
          (!Number(this.lastTargetId) || Number(this.lastTargetId) <= 0) &&
          !this.lastTargetName;
        this.detailsUI.open(row, {
          defaultTargetAll: this.lastTargetMode === "allTargets" || fallbackAllTargets,
        });
      },
    });

    const withBacklog = (text) => {
      if (!window.javaBridge?.isRunningFromIde?.()) return text;
      const backlog = window.javaBridge?.getParsingBacklog?.();
      if (!Number.isFinite(backlog)) return text;
      return `${text} (backlog: ${backlog})`;
    };

    const getBattleTimeStatusText = () => {
      if (!this.aionRunning) {
        const text = this.i18n?.t("battleTime.notRunning", "AION2 not running") ?? "AION2 not running";
        return withBacklog(text);
      }
      if (this.isDetectingPort) {
        const text = this.i18n?.t("connection.detecting", "Detecting AION2 connection...") ??
          "Detecting AION2 connection...";
        return withBacklog(text);
      }
      if (this.battleTime?.getState?.() === "state-idle") {
        const text = this.i18n?.t("battleTime.idle", "Idle") ?? "Idle";
        return withBacklog(text);
      }
      const text = this.i18n?.t("battleTime.analysing", "Monitoring data...") ?? "Monitoring data...";
      return withBacklog(text);
    };

    this.battleTime = createBattleTimeUI({
      rootEl: document.querySelector(".battleTime"),
      tickSelector: ".tick",
      statusSelector: ".status",
      analysisSelector: ".analysisStatus",
      getAnalysisText: getBattleTimeStatusText,
      graceMs: this.GRACE_MS,
      graceArmMs: this.GRACE_ARM_MS,
      idleMs: 60000,
      visibleClass: "isVisible",
    });
    this.battleTime.setVisible(false);
    this.updateConnectionStatusUi();

    this.detailsPanel = document.querySelector(".detailsPanel");
    this.detailsClose = document.querySelector(".detailsClose");
    this.detailsTitle = document.querySelector(".detailsTitle");
    this.detailsNicknameBtn = document.querySelector(".detailsNicknameBtn");
    this.detailsNicknameMenu = document.querySelector(".detailsNicknameMenu");
    this.detailsTargetBtn = document.querySelector(".detailsTargetBtn");
    this.detailsTargetMenu = document.querySelector(".detailsTargetMenu");
    this.detailsSortButtons = document.querySelectorAll(".detailsSortBtn");
    this.detailsScreenshotBtn = document.querySelector(".detailsScreenshotBtn");
    this.detailsScreenshotNote = document.querySelector(".detailsScreenshotNote");
    this.detailsRefreshBtn = document.querySelector(".detailsRefreshBtn");
    this.detailsStatsEl = document.querySelector(".detailsStats");
    this.skillsListEl = document.querySelector(".skills");

    this.detailsUI = createDetailsUI({
      detailsPanel: this.detailsPanel,
      detailsClose: this.detailsClose,
      detailsTitle: this.detailsTitle,
      detailsNicknameBtn: this.detailsNicknameBtn,
      detailsNicknameMenu: this.detailsNicknameMenu,
      detailsTargetBtn: this.detailsTargetBtn,
      detailsTargetMenu: this.detailsTargetMenu,
      detailsSortButtons: this.detailsSortButtons,
      detailsStatsEl: this.detailsStatsEl,
      skillsListEl: this.skillsListEl,
      dpsFormatter: this.dpsFormatter,
      getDetails: (row, options) => this.getDetails(row, options),
      getDetailsContext: () => this.getDetailsContext(),
    });
    this.detailsRefreshBtn?.addEventListener("click", () => {
      this.detailsUI?.refresh?.();
    });
    if (this.detailsScreenshotBtn) {
      let screenshotNoteTimer = null;
      this.detailsScreenshotBtn.addEventListener("click", () => {
        const tooltipText =
          this.i18n?.t("details.screenshot.captured", "Captured Screenshot") ?? "Captured Screenshot";
        const meterRect = document.querySelector(".meter")?.getBoundingClientRect?.();
        const detailsRect = this.detailsPanel?.classList?.contains("open")
          ? this.detailsPanel.getBoundingClientRect()
          : null;
        const includeMeter = !!this.includeMainMeterScreenshot;
        const baseRect = includeMeter ? meterRect || detailsRect : detailsRect;
        if (!baseRect) return;
        const minX = includeMeter && meterRect && detailsRect
          ? Math.min(meterRect.left, detailsRect.left)
          : baseRect.left;
        const minY = includeMeter && meterRect && detailsRect
          ? Math.min(meterRect.top, detailsRect.top)
          : baseRect.top;
        const maxX = includeMeter && meterRect && detailsRect
          ? Math.max(meterRect.right, detailsRect.right)
          : baseRect.right;
        const maxY = includeMeter && meterRect && detailsRect
          ? Math.max(meterRect.bottom, detailsRect.bottom)
          : baseRect.bottom;
        const rectWidth = Math.max(1, maxX - minX);
        const rectHeight = Math.max(1, maxY - minY);
        const scale = window.devicePixelRatio || 1;
        const clipboardSuccess = window.javaBridge?.captureScreenshotToClipboard?.(
          minX,
          minY,
          rectWidth,
          rectHeight,
          scale
        );
        let fileSuccess = false;
        if (this.saveScreenshotToFolder && this.screenshotFolder) {
          const filename = this.buildScreenshotFilename();
          fileSuccess = !!window.javaBridge?.captureScreenshotToFile?.(
            minX,
            minY,
            rectWidth,
            rectHeight,
            scale,
            this.screenshotFolder,
            filename
          );
        }
        if ((!clipboardSuccess && !fileSuccess) || !this.detailsScreenshotNote) return;
        this.detailsScreenshotBtn.setAttribute("title", tooltipText);
        if (clipboardSuccess && fileSuccess) {
          this.detailsScreenshotNote.textContent = "Saved to clipboard + file";
        } else if (fileSuccess) {
          this.detailsScreenshotNote.textContent = "Saved to file";
        } else {
          this.detailsScreenshotNote.textContent = "Saved to clipboard";
        }
        this.detailsScreenshotNote.classList.add("isVisible");
        if (this.detailsPanel) {
          this.triggerDetailsFlash();
        }
        if (includeMeter && meterRect) {
          this.triggerMeterFlash();
        }
        if (screenshotNoteTimer) window.clearTimeout(screenshotNoteTimer);
        screenshotNoteTimer = window.setTimeout(() => {
          this.detailsScreenshotNote?.classList.remove("isVisible");
          this.detailsScreenshotNote.textContent = "";
        }, 2000);
      });
    }
    this.setupDetailsPanelSettings();
    this.setupSettingsPanel();
    this.detailsUI?.updateLabels?.();
    this.i18n?.onChange?.((lang) => {
      this.settingsSelections.language = lang;
      this.initializeSettingsDropdowns();
      this.detailsUI?.updateLabels?.();
      this.detailsUI?.refresh?.();
      this.updateDisplayToggleLabel();
      if (this.battleTime?.setAnalysisTextProvider) {
        this.battleTime.setAnalysisTextProvider(getBattleTimeStatusText);
      }
      this.refreshConnectionInfo();
      this.refreshBossLabel();
    });
    window.ReleaseChecker?.start?.();
    this.setupConsoleDebugging();

    const storedDisplayMode = this.safeGetStorage(this.storageKeys.displayMode);
    this.setDisplayMode(storedDisplayMode || this.displayMode, { persist: false });

    this.startPolling();
    this.startWindowTitlePolling();
    this.fetchDps();
  }

  nowMs() {
    return typeof performance !== "undefined" ? performance.now() : Date.now();
  }

  formatBattleTime(ms) {
    const totalMs = Number(ms);
    if (!Number.isFinite(totalMs) || totalMs <= 0) return "00:00";
    const totalSeconds = Math.floor(totalMs / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
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

  startWindowTitlePolling() {
    if (this._windowTitleTimer) return;
    this._windowTitleTimer = setInterval(
      () => this.checkAion2WindowTitle(),
      this.WINDOW_TITLE_POLL_MS
    );
    this.checkAion2WindowTitle();
  }

  parseCharacterNameFromWindowTitle(title) {
    const trimmed = String(title ?? "").trim();
    if (!trimmed) return "";
    if (!trimmed.toLowerCase().startsWith("aion2")) return "";
    const remainder = trimmed.slice(5).trim();
    if (!remainder) return "";
    return remainder.replace(/^[|l:-]+/i, "").trim();
  }

  checkAion2WindowTitle() {
    const title = window.javaBridge?.getAion2WindowTitle?.();
    const running = typeof title === "string" && title.trim().length > 0;
    if (running !== this.aionRunning) {
      this.aionRunning = running;
      this.refreshConnectionInfo();
      this.updateConnectionStatusUi();
    }
    if (!running) return;
    const detectedName = this.parseCharacterNameFromWindowTitle(title);
    if (!detectedName || detectedName === this.USER_NAME) return;
    this.setUserName(detectedName, { persist: true, syncBackend: true });
    if (this.characterNameInput && document.activeElement !== this.characterNameInput) {
      this.characterNameInput.value = detectedName;
    }
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
    this.lastTargetId = 0;
    this._lastRenderedListSignature = "";
    this._lastRenderedTargetLabel = "";
    this._lastRenderedRowsSummary = null;

    this._battleTimeVisible = false;
    this._lastBattleTimeMs = null;
    this.battleTime?.reset?.();
    this.battleTime?.setVisible?.(false);

    this.detailsUI?.close?.();
    this.meterUI?.onResetMeterUi?.();

    if (this.elBossName) {
      this.elBossName.textContent = this.getDefaultTargetLabel();
    }
    if (this.battleTimeRoot) {
      this.battleTimeRoot.classList.add("isVisible");
    }
    if (this.analysisStatusEl) {
      this.analysisStatusEl.textContent =
        this.i18n?.t("battleTime.analysing", "Monitoring data...") ?? "Monitoring data...";
      this.analysisStatusEl.style.display = "";
    }
    this.updateConnectionStatusUi();
    this.logDebug("Target label reset: resetAll invoked.");
    this.logDebug("Meter list reset: resetAll invoked.");
    if (callBackend) {
      window.javaBridge?.resetDps?.();
    }
  }




  fetchDps() {
    if (this.isCollapse) return;
    const now = this.nowMs();
    if (this.settingsPanel?.classList.contains("isOpen")) {
      this.refreshConnectionInfo({ skipSettingsRefresh: true });
    }
    const raw = window.dpsData?.getDpsData?.();
    // globalThis.uiDebug?.log?.("getBattleDetail", raw);

    // 값이 없으면 타이머 숨김
    if (typeof raw !== "string") {
      this._rawLastChangedAt = now;

      this._lastBattleTimeMs = null;
      this._battleTimeVisible = false;
      this.battleTime.setVisible(false);
      this.updateConnectionStatusUi();
      return;
    }

    if (raw === this.lastJson && !this.refreshPending) {
      const shouldBeVisible = this._battleTimeVisible && !this.isCollapse;

      this.battleTime.setVisible(shouldBeVisible);
      if (shouldBeVisible) {
        this.battleTime.update(now, this._lastBattleTimeMs);
      }

      this.updateConnectionStatusUi();
      return;
    }

    const previousTargetName = this.lastTargetName;
    const previousTargetMode = this.lastTargetMode;
    const previousTargetId = this.lastTargetId;
    const { rows, targetName, targetMode, battleTimeMs, targetId, localPlayerId } =
      this.buildRowsFromPayload(raw);
    if (this.refreshPending) {
      if (rows.length > 0) {
        return;
      }
      this.refreshPending = false;
      this.lastJson = raw;
      this.lastSnapshot = [];
      this._lastRenderedListSignature = "";
      this._lastRenderedRowsSummary = null;
      this.meterUI?.onResetMeterUi?.();
      return;
    }

    this.lastJson = raw;
    this.applyLocalPlayerIdUpdate(localPlayerId, "backend local id update");
    this.updateLocalPlayerIdentity(rows);
    this._lastBattleTimeMs = battleTimeMs;
    this.lastTargetMode = targetMode;
    this.lastTargetName = targetName;
    this.lastTargetId = targetId;

    if (
      targetId !== this._lastLoggedTargetId ||
      targetMode !== this._lastLoggedTargetMode ||
      targetName !== this._lastLoggedTargetName
    ) {
      const reasons = [];
      if (targetId !== this._lastLoggedTargetId) reasons.push("targetId changed");
      if (targetMode !== this._lastLoggedTargetMode) reasons.push("mode changed");
      if (targetName !== this._lastLoggedTargetName) reasons.push("name changed");
      console.log("[Target Lock]", {
        targetId,
        targetName,
        targetMode,
        reason: reasons.join(", ") || "initial",
      });
      this._lastLoggedTargetId = targetId;
      this._lastLoggedTargetMode = targetMode;
      this._lastLoggedTargetName = targetName;
    }


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
    const isOutOfCombat = this.isOutOfCombatState();
    // 빈값은 ui 안덮어씀
    let rowsToRender = rows;
    const listReasons = [];
    if (rows.length === 0) {
      if (this.lastSnapshot) rowsToRender = this.lastSnapshot;
      else {
        this._battleTimeVisible = false;
        this.battleTime.setVisible(false);
        return;
      }
    } else if (!isOutOfCombat) {
      this.lastSnapshot = rows;
    } else if (this.lastSnapshot) {
      const updatedSnapshot = this.updateSnapshotNicknameForUser(rows, this.lastSnapshot);
      this.lastSnapshot = updatedSnapshot;
      rowsToRender = updatedSnapshot;
    } else {
      this.lastSnapshot = rows;
      rowsToRender = rows;
      listReasons.push("idle baseline captured");
    }

    // 타이머 표시 여부
    const showByRender = rowsToRender.length > 0;
    const showBattleTime = this.BATTLE_TIME_BASIS === "server" ? showByServer : showByRender;

    let nextBattleTimeMs = battleTimeMs;
    if (
      Number.isFinite(Number(nextBattleTimeMs)) &&
      Number.isFinite(Number(this._lastBattleTimeMs)) &&
      targetId === previousTargetId &&
      Number(nextBattleTimeMs) < Number(this._lastBattleTimeMs)
    ) {
      nextBattleTimeMs = this._lastBattleTimeMs;
    }

    const eligible = showBattleTime && Number.isFinite(Number(nextBattleTimeMs));

    this._battleTimeVisible = eligible;
    const shouldBeVisible = eligible && !this.isCollapse;

    this.battleTime.setVisible(shouldBeVisible);

    if (shouldBeVisible) {
      this.battleTime.update(now, nextBattleTimeMs);
    }

    this.updateConnectionStatusUi();
    if (targetMode === "trainTargets" && this.USER_NAME) {
      rowsToRender = rowsToRender.filter((row) => row.name === this.USER_NAME);
    }
    // render
    const nextTargetLabel = this.getTargetLabel({ targetId, targetName, targetMode });
    if (this.elBossName) {
      this.elBossName.textContent = nextTargetLabel;
      this.elBossName.classList.toggle("isAllTargets", targetMode === "allTargets");
    }
    if (
      nextTargetLabel !== this._lastRenderedTargetLabel ||
      previousTargetName !== targetName ||
      previousTargetMode !== targetMode
    ) {
      const reasons = [];
      if (previousTargetName !== targetName || previousTargetMode !== targetMode) {
        reasons.push("payload target changed");
      }
      if (!targetName) {
        reasons.push(`default label for mode ${targetMode || "unknown"}`);
      }
      this.logDebug(
        `Target label changed: "${this._lastRenderedTargetLabel}" -> "${nextTargetLabel}" (reason: ${reasons.join(
          "; "
        )}).`
      );
      this._lastRenderedTargetLabel = nextTargetLabel;
    }
    const rowsSummary = this.getRowsSummary(rowsToRender);
    if (rowsSummary.listSignature !== this._lastRenderedListSignature) {
      const changeReasons = this.describeRowsChange(rowsSummary, this._lastRenderedRowsSummary);
      const reasonText = [...changeReasons, ...listReasons].filter(Boolean).join("; ");
      this.logDebug(
        `Meter list changed (${rowsToRender.length} rows). reason: ${
          reasonText || "list membership changed"
        }.`
      );
      this._lastRenderedListSignature = rowsSummary.listSignature;
      this._lastRenderedRowsSummary = rowsSummary;
    }
    this.meterUI.updateFromRows(rowsToRender);
  }

  buildRowsFromPayload(raw) {
    const payload = this.safeParseJSON(raw, {});
    const targetName = typeof payload?.targetName === "string" ? payload.targetName : "";
    const targetMode = typeof payload?.targetMode === "string" ? payload.targetMode : "";
    const targetIdRaw = payload?.targetId;
    const targetId = Number.isFinite(Number(targetIdRaw)) ? Number(targetIdRaw) : 0;
    const localPlayerIdRaw = payload?.localPlayerId;
    const localPlayerId = Number.isFinite(Number(localPlayerIdRaw))
      ? Number(localPlayerIdRaw)
      : null;

    const mapObj = payload?.map && typeof payload.map === "object" ? payload.map : {};
    const rows = this.buildRowsFromMapObject(mapObj);

    const battleTimeMsRaw = payload?.battleTime;
    const battleTimeMs = Number.isFinite(Number(battleTimeMsRaw)) ? Number(battleTimeMsRaw) : null;

    return { rows, targetName, targetMode, battleTimeMs, targetId, localPlayerId };
  }

  buildRowsFromMapObject(mapObj) {
    const rows = [];

    for (const [id, value] of Object.entries(mapObj || {})) {
      const numericId = Number(id);
      if (Number.isFinite(numericId) && numericId <= 0) {
        continue;
      }
      const isObj = value && typeof value === "object";

      const job = isObj ? (value.job ?? "") : "";
      const nickname = isObj ? (value.nickname ?? "") : "";
      const idText = String(id);
      const hasNickname = !!nickname && nickname !== idText;
      const isIdentifying = !hasNickname;
      const name = hasNickname ? nickname : idText;

      const dpsRaw = isObj ? value.dps : value;
      const dps = Math.trunc(Number(dpsRaw));
      const totalDamage = Math.trunc(Number(isObj ? value.amount : 0));

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
        totalDamage,
        damageContribution,
        isUser: name === this.USER_NAME,
        isIdentifying,
      });
    }

    const dedupedByName = new Map();
    for (const row of rows) {
      const key = String(row.name ?? "");
      if (!key) continue;
      const existing = dedupedByName.get(key);
      if (!existing) {
        dedupedByName.set(key, row);
        continue;
      }
      const scoreRow = (candidate) => {
        let score = 0;
        if (candidate.job) score += 2;
        if (!candidate.isIdentifying) score += 1;
        return score;
      };
      const existingScore = scoreRow(existing);
      const nextScore = scoreRow(row);
      if (nextScore > existingScore) {
        dedupedByName.set(key, row);
        continue;
      }
      if (nextScore < existingScore) {
        continue;
      }
      const existingId = Number(existing.id);
      const nextId = Number(row.id);
      if (!Number.isFinite(existingId) || (Number.isFinite(nextId) && nextId > existingId)) {
        dedupedByName.set(key, row);
      }
    }

    return Array.from(dedupedByName.values());
  }

  isOutOfCombatState() {
    const state = this.battleTime?.getState?.();
    return state === "state-idle" || state === "state-ended";
  }

  updateSnapshotNicknameForUser(rows, snapshot) {
    if (!Array.isArray(snapshot) || snapshot.length === 0) return snapshot;
    const localId = Number(this.localPlayerId);
    if (!Number.isFinite(localId) || localId <= 0) return snapshot;
    const incoming = Array.isArray(rows)
      ? rows.find((row) => Number(row?.id) === localId && !row.isIdentifying)
      : null;
    if (!incoming) return snapshot;
    return snapshot.map((row) => {
      if (Number(row?.id) !== localId) return row;
      return {
        ...row,
        name: incoming.name,
        isIdentifying: false,
        isUser: incoming.isUser,
      };
    });
  }

  getDefaultMeterFillOpacity() {
    const raw = getComputedStyle(document.documentElement)
      .getPropertyValue("--meter-fill-opacity")
      .trim();
    const value = Number.parseFloat(raw);
    if (!Number.isFinite(value)) return 100;
    return Math.round(value * 100);
  }

  normalizeMeterOpacity(value, fallback = 100) {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return fallback;
    return Math.min(100, Math.max(10, Math.round(numeric)));
  }

  applyMeterFillOpacity(percent, { persist } = {}) {
    const normalized = this.normalizeMeterOpacity(percent, 100);
    document.documentElement.style.setProperty("--meter-fill-opacity", String(normalized / 100));
    if (persist) {
      this.safeSetSetting(this.storageKeys.meterFillOpacity, String(normalized));
    }
  }

  triggerDetailsFlash() {
    if (!this.detailsPanel) return;
    this.detailsPanel.classList.remove("flash");
    void this.detailsPanel.offsetWidth;
    this.detailsPanel.classList.add("flash");
    if (this._detailsFlashTimer) window.clearTimeout(this._detailsFlashTimer);
    this._detailsFlashTimer = window.setTimeout(() => {
      this.detailsPanel?.classList.remove("flash");
    }, 1000);
  }

  triggerMeterFlash() {
    const meterEl = document.querySelector(".meter");
    if (!meterEl) return;
    meterEl.classList.remove("flash");
    void meterEl.offsetWidth;
    meterEl.classList.add("flash");
    if (this._meterFlashTimer) window.clearTimeout(this._meterFlashTimer);
    this._meterFlashTimer = window.setTimeout(() => {
      meterEl.classList.remove("flash");
    }, 1000);
  }

  captureMainMeterScreenshot() {
    const meterRect = document.querySelector(".meter")?.getBoundingClientRect?.();
    if (!meterRect) return;
    const scale = window.devicePixelRatio || 1;
    const success = window.javaBridge?.captureScreenshotToClipboard?.(
      meterRect.left,
      meterRect.top,
      meterRect.width,
      meterRect.height,
      scale
    );
    if (success) {
      this.triggerMeterFlash();
    }
  }

  reinitTargetSelection(reason) {
    this.resetTargetTrackingState();
    window.javaBridge?.restartTargetSelection?.();
    this.refreshPending = false;
    this.resetPending = false;
    this.lastJson = null;
    this.lastSnapshot = null;
    this._lastRenderedListSignature = "";
    this._lastRenderedRowsSummary = null;
    this.setTargetSelection(this.targetSelection, { persist: false, syncBackend: true, reason });
    if (!this.isCollapse) {
      this.fetchDps();
    }
  }

  resetTargetTrackingState() {
    this.lastTargetMode = "";
    this.lastTargetName = "";
    this.lastTargetId = 0;
    this._lastRenderedTargetLabel = "";
    this._lastLoggedTargetId = null;
    this._lastLoggedTargetMode = null;
    this._lastLoggedTargetName = null;
  }

  updateLocalPlayerIdentity(rows = []) {
    if (!Array.isArray(rows) || !rows.length || !this.USER_NAME) {
      return;
    }
    const matched = rows.find((row) => row?.name === this.USER_NAME);
    if (!matched) {
      return;
    }
    const actorId = Number(matched.id);
    this.applyLocalPlayerIdUpdate(actorId, "local id update");
  }

  applyLocalPlayerIdUpdate(actorId, reason) {
    if (!Number.isFinite(actorId) || actorId <= 0) {
      return;
    }
    if (this.localPlayerId === actorId) {
      return;
    }
    this.localPlayerId = actorId;
    window.javaBridge?.bindLocalActorId?.(String(actorId));
    window.javaBridge?.setLocalPlayerId?.(String(actorId));
    if (this.USER_NAME) {
      window.javaBridge?.bindLocalNickname?.(String(actorId), this.USER_NAME);
      this.setUserName(this.USER_NAME, { persist: true, syncBackend: true });
      this.rememberLocalIdForName(this.USER_NAME, actorId);
    }
    if (this.localActorIdInput && document.activeElement !== this.localActorIdInput) {
      this.localActorIdInput.value = String(actorId);
    }
    this.refreshConnectionInfo({ skipSettingsRefresh: true });
    this.reinitTargetSelection(reason || "local id update");
  }

  rememberLocalIdForName(name, actorId) {
    const key = String(name ?? "").trim().toLowerCase();
    if (!key || !Number.isFinite(actorId) || actorId <= 0) return;
    this._recentLocalIdByName.set(key, { actorId, timestamp: Date.now() });
  }

  getRecentLocalIdForName(name) {
    const key = String(name ?? "").trim().toLowerCase();
    if (!key) return null;
    const entry = this._recentLocalIdByName.get(key);
    if (!entry) return null;
    if (Date.now() - entry.timestamp > 120000) {
      this._recentLocalIdByName.delete(key);
      return null;
    }
    return entry.actorId;
  }

  getDetailsContext() {
    const raw = window.dpsData?.getDetailsContext?.();
    if (!raw) return null;
    if (typeof raw === "string") {
      return this.safeParseJSON(raw, null);
    }
    return raw;
  }

  async getDetails(row, { targetId = null, attackerIds = null, totalTargetDamage = null, showSkillIcons = false } = {}) {
    let raw = null;
    if (targetId && window.dpsData?.getTargetDetails) {
      const payload = Array.isArray(attackerIds) ? JSON.stringify(attackerIds) : "";
      raw = await window.dpsData.getTargetDetails(targetId, payload);
    } else {
      raw = await window.dpsData?.getBattleDetail?.(row.id);
    }
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
      heal = 0,
      countForTotals = true,
      job = "",
      actorId = null,
      isDot = false,
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
        heal: Number(heal) || 0,
        dmg: dmgInt,
        job,
        actorId,
        isDot,
      });
    };

    const detailSkills = Array.isArray(detailObj?.skills) ? detailObj.skills : null;
    if (detailSkills) {
      for (const value of detailSkills) {
        if (!value || typeof value !== "object") continue;
        const code = String(value.code ?? "");
        const nameRaw = typeof value.name === "string" ? value.name.trim() : "";
        const translatedName = this.i18n?.getSkillName?.(code, nameRaw) ?? nameRaw;
        const baseName =
          translatedName ||
          this.i18n?.format?.("skills.fallback", { code }, `Skill ${code}`) ||
          `Skill ${code}`;
        const dotName =
          this.i18n?.format?.("skills.dot", { name: baseName }, `${baseName} - DOT`) ||
          `${baseName} - DOT`;
        const isDot = !!value.isDot;
        const actorId = Number(value.actorId);

        pushSkill({
          codeKey: isDot ? `${code}-dot` : code,
          name: isDot ? dotName : baseName,
          time: value.time,
          dmg: value.dmg,
          crit: value.crit,
          parry: value.parry,
          back: value.back,
          perfect: value.perfect,
          double: value.double,
          heal: value.heal,
          job: value.job ?? "",
          countForTotals: !isDot,
          actorId: Number.isFinite(actorId) ? actorId : null,
          isDot,
        });
      }
    } else {
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
          heal: value.healAmount,
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
    }

    const pct = (num, den) => {
      if (den <= 0) return 0;
      return Math.round((num / den) * 1000) / 10;
    };
    const perActorStatsMap = new Map();
    for (const skill of skills) {
      if (!Number.isFinite(Number(skill.actorId))) continue;
      const actorId = Number(skill.actorId);
      const entry =
        perActorStatsMap.get(actorId) || {
          actorId,
          job: skill.job ?? "",
          totalDmg: 0,
          totalTimes: 0,
          totalCrit: 0,
          totalParry: 0,
          totalBack: 0,
          totalPerfect: 0,
          totalDouble: 0,
        };
      entry.totalDmg += Number(skill.dmg) || 0;
      if (!skill.isDot) {
        entry.totalTimes += Number(skill.time) || 0;
        entry.totalCrit += Number(skill.crit) || 0;
        entry.totalParry += Number(skill.parry) || 0;
        entry.totalBack += Number(skill.back) || 0;
        entry.totalPerfect += Number(skill.perfect) || 0;
        entry.totalDouble += Number(skill.double) || 0;
      }
      if (!entry.job && skill.job) {
        entry.job = skill.job;
      }
      perActorStatsMap.set(actorId, entry);
    }
    const fallbackContribution = Number(row?.damageContribution);
    const baseTotalDamage = Number.isFinite(Number(totalTargetDamage))
      ? Number(totalTargetDamage)
      : Number(detailObj?.totalTargetDamage);
    const contributionPct =
      Number.isFinite(baseTotalDamage) && baseTotalDamage > 0
        ? (totalDmg / baseTotalDamage) * 100
        : fallbackContribution;
    const battleTimeMsRaw = Number(detailObj?.battleTime);
    const combatTime = Number.isFinite(battleTimeMsRaw)
      ? this.formatBattleTime(battleTimeMsRaw)
      : this.battleTime?.getCombatTimeText?.() ?? "00:00";

    const perActorStats = [...perActorStatsMap.values()]
      .map((entry) => ({
        actorId: entry.actorId,
        job: entry.job,
        totalDmg: entry.totalDmg,
        totalTimes: entry.totalTimes,
        totalCrit: entry.totalCrit,
        totalParry: entry.totalParry,
        totalBack: entry.totalBack,
        totalPerfect: entry.totalPerfect,
        totalDouble: entry.totalDouble,
        contributionPct:
          Number.isFinite(baseTotalDamage) && baseTotalDamage > 0
            ? (entry.totalDmg / baseTotalDamage) * 100
            : 0,
        totalCritPct: pct(entry.totalCrit, entry.totalTimes),
        totalParryPct: pct(entry.totalParry, entry.totalTimes),
        totalBackPct: pct(entry.totalBack, entry.totalTimes),
        totalPerfectPct: pct(entry.totalPerfect, entry.totalTimes),
        totalDoublePct: pct(entry.totalDouble, entry.totalTimes),
        combatTime,
      }))
      .sort((a, b) => b.totalDmg - a.totalDmg);

    return {
      totalDmg,
      contributionPct,
      totalCritPct: pct(totalCrit, totalTimes),
      totalParryPct: pct(totalParry, totalTimes),
      totalBackPct: pct(totalBack, totalTimes),
      totalPerfectPct: pct(totalPerfect, totalTimes),
      totalDoublePct: pct(totalDouble, totalTimes),
      combatTime,
      battleTimeMs: Number.isFinite(battleTimeMsRaw) ? battleTimeMsRaw : 0,

      skills,
      showSkillIcons,
      perActorStats,
      showCombinedTotals: !attackerIds || attackerIds.length === 0,
    };
  }

  bindHeaderButtons() {
    this.logoBtn = document.querySelector(".bossIcon");
    this.collapseBtn?.addEventListener("click", () => {
      this.listSortDirection = this.listSortDirection === "asc" ? "desc" : "asc";
      this.renderCurrentRows();

      const iconName =
        this.listSortDirection === "asc" ? "arrow-down-wide-narrow" : "arrow-up-wide-narrow";
      const iconEl =
        this.collapseBtn.querySelector("svg") || this.collapseBtn.querySelector("[data-lucide]");
      if (!iconEl) {
        return;
      }

      iconEl.setAttribute("data-lucide", iconName);
      window.lucide?.createIcons?.({ root: this.collapseBtn });
    });
    this.resetBtn?.addEventListener("click", () => {
      this.refreshDamageData({ reason: "manual refresh" });
    });
    this.targetModeBtn?.addEventListener("click", () => {
      const modes = ["lastHitByMe", "allTargets", "trainTargets"];
      const currentIndex = modes.indexOf(this.targetSelection);
      const nextMode = modes[(currentIndex + 1) % modes.length];
      console.log("[Target Mode Toggle]", {
        from: this.targetSelection,
        to: nextMode,
      });
      this.setTargetSelection(nextMode, {
        persist: true,
        syncBackend: true,
        reason: "header toggle",
      });
      if (!this.isCollapse) {
        this.fetchDps();
      }
    });
    this.metricToggleBtn?.addEventListener("click", () => {
      const nextMode = this.displayMode === "totalDamage" ? "dps" : "totalDamage";
      this.setDisplayMode(nextMode, { persist: true });
      this.renderCurrentRows();
    });
    this.logoBtn?.addEventListener("click", () => {
      this.captureMainMeterScreenshot();
    });
  }

  setupSettingsPanel() {
    this.settingsPanel = document.querySelector(".settingsPanel");
    this.settingsClose = document.querySelector(".settingsClose");
    this.settingsBtn = document.querySelector(".settingsBtn");
    this.lockedIp = document.querySelector(".lockedIp");
    this.lockedPort = document.querySelector(".lockedPort");
    this.localActorIdInput = document.querySelector(".localActorIdInput");
    this.allTargetsWindowDropdownBtn = document.querySelector(".allTargetsWindowDropdownBtn");
    this.allTargetsWindowDropdownMenu = document.querySelector(".allTargetsWindowDropdownMenu");
    this.targetWindowDropdownBtn = document.querySelector(".targetWindowDropdownBtn");
    this.targetWindowDropdownMenu = document.querySelector(".targetWindowDropdownMenu");
    this.trainSelectionModeDropdownBtn = document.querySelector(".trainSelectionModeDropdownBtn");
    this.trainSelectionModeDropdownMenu = document.querySelector(".trainSelectionModeDropdownMenu");
    this.resetDetectBtn = document.querySelector(".resetDetectBtn");
    this.characterNameInput = document.querySelector(".characterNameInput");
    this.debugLoggingCheckbox = document.querySelector(".debugLoggingCheckbox");
    this.pinMeToTopCheckbox = document.querySelector(".pinMeToTopCheckbox");
    this.meterOpacityInput = document.querySelector(".meterOpacityInput");
    this.meterOpacityValue = document.querySelector(".meterOpacityValue");
    this.discordButton = document.querySelector(".discordButton");
    this.quitButton = document.querySelector(".quitButton");
    this.languageDropdownBtn = document.querySelector(".languageDropdownBtn");
    this.languageDropdownMenu = document.querySelector(".languageDropdownMenu");
    this.themeDropdownBtn = document.querySelector(".themeDropdownBtn");
    this.themeDropdownMenu = document.querySelector(".themeDropdownMenu");
    this.settingsSelections = {
      language: "en",
      theme: this.theme,
      allTargetsWindowMs: "120000",
      trainSelectionMode: "all",
      targetSelectionWindowMs: "5000",
    };

    const storedName = this.safeGetStorage(this.storageKeys.userName) || "";
    const storedAllTargetsWindowMs = this.safeGetSetting(this.storageKeys.allTargetsWindowMs) ||
      this.safeGetStorage(this.storageKeys.allTargetsWindowMs) ||
      "120000";
    const storedTrainSelectionMode = this.safeGetSetting(this.storageKeys.trainSelectionMode) ||
      this.safeGetStorage(this.storageKeys.trainSelectionMode) ||
      "all";
    const storedTargetSelectionWindowMs = this.safeGetSetting(this.storageKeys.targetSelectionWindowMs) ||
      this.safeGetStorage(this.storageKeys.targetSelectionWindowMs) ||
      "5000";
    const storedMeterOpacity = this.safeGetSetting(this.storageKeys.meterFillOpacity) ||
      this.safeGetStorage(this.storageKeys.meterFillOpacity);
    const storedDebugLogging = this.safeGetSetting(this.storageKeys.debugLogging) === "true";
    const storedPinMeToTop = this.safeGetSetting(this.storageKeys.pinMeToTop) === "true";
    const storedTargetSelection = this.safeGetStorage(this.storageKeys.targetSelection);
    const storedLanguage = this.safeGetStorage(this.storageKeys.language);
    const storedTheme = this.safeGetSetting(this.storageKeys.theme);

    this.setUserName(storedName, { persist: false, syncBackend: true });
    this.setOnlyShowUser(false, { persist: false });
    this.setDebugLogging(storedDebugLogging, { persist: false, syncBackend: true });
    this.setPinMeToTop(storedPinMeToTop, { persist: false });
    const normalizedTargetSelection =
      storedTargetSelection === "allTargets" || storedTargetSelection === "trainTargets"
        ? storedTargetSelection
        : "lastHitByMe";
    this.setTargetSelection(normalizedTargetSelection, {
      persist: false,
      syncBackend: true,
      reason: storedTargetSelection ? "restore from storage" : "default selection",
    });
    this.applyTheme(storedTheme || this.theme, { persist: false });
    if (storedLanguage) {
      this.i18n?.setLanguage?.(storedLanguage, { persist: false });
    }

    if (this.characterNameInput) {
      this.characterNameInput.value = this.USER_NAME;
    }
    if (this.localActorIdInput) {
      this.localActorIdInput.value = this.localPlayerId ? String(this.localPlayerId) : "";
      this.localActorIdInput.addEventListener("input", (event) => {
        const digits = String(event.target?.value || "").replace(/[^0-9]/g, "");
        event.target.value = digits;
      });
      this.localActorIdInput.addEventListener("change", (event) => {
        const value = String(event.target?.value || "").trim();
        if (!value) return;
        this.localPlayerId = Number(value);
        window.javaBridge?.bindLocalActorId?.(value);
        if (this.USER_NAME) {
          window.javaBridge?.bindLocalNickname?.(value, this.USER_NAME);
        }
        this.setUserName(this.USER_NAME, { persist: true, syncBackend: true });
      });
    }

    const allowedWindows = ["30000", "60000", "120000", "180000", "300000"];
    const selectedWindow = allowedWindows.includes(String(storedAllTargetsWindowMs))
      ? String(storedAllTargetsWindowMs)
      : "120000";
    this.settingsSelections.allTargetsWindowMs = selectedWindow;
    this.safeSetSetting(this.storageKeys.allTargetsWindowMs, selectedWindow);
    window.javaBridge?.setAllTargetsWindowMs?.(selectedWindow);

    const allowedTargetWindows = ["5000", "10000", "15000", "20000", "30000"];
    const selectedTargetWindow = allowedTargetWindows.includes(String(storedTargetSelectionWindowMs))
      ? String(storedTargetSelectionWindowMs)
      : "5000";
    this.settingsSelections.targetSelectionWindowMs = selectedTargetWindow;
    this.safeSetSetting(this.storageKeys.targetSelectionWindowMs, selectedTargetWindow);
    window.javaBridge?.setTargetSelectionWindowMs?.(selectedTargetWindow);

    const allowedModes = ["all", "highestDamage"];
    const selectedMode = allowedModes.includes(String(storedTrainSelectionMode))
      ? String(storedTrainSelectionMode)
      : "all";
    this.trainSelectionMode = selectedMode;
    this.settingsSelections.trainSelectionMode = selectedMode;
    this.safeSetSetting(this.storageKeys.trainSelectionMode, selectedMode);
    window.javaBridge?.setTrainSelectionMode?.(selectedMode);

    if (this.debugLoggingCheckbox) {
      this.debugLoggingCheckbox.checked = this.debugLoggingEnabled;
      this.debugLoggingCheckbox.addEventListener("change", (event) => {
        const isChecked = !!event.target?.checked;
        this.setDebugLogging(isChecked, { persist: true, syncBackend: true });
      });
    }
    if (this.pinMeToTopCheckbox) {
      this.pinMeToTopCheckbox.checked = this.pinMeToTop;
      this.pinMeToTopCheckbox.addEventListener("change", (event) => {
        const isChecked = !!event.target?.checked;
        this.setPinMeToTop(isChecked, { persist: true });
      });
    }
    if (this.meterOpacityInput && this.meterOpacityValue) {
      const defaultOpacity = this.getDefaultMeterFillOpacity();
      const resolvedOpacity = this.normalizeMeterOpacity(storedMeterOpacity, defaultOpacity);
      this.applyMeterFillOpacity(resolvedOpacity, { persist: false });
      this.meterOpacityInput.value = String(resolvedOpacity);
      this.meterOpacityValue.textContent = `${resolvedOpacity}%`;
      const stopDrag = (event) => event.stopPropagation();
      this.meterOpacityInput.addEventListener("mousedown", stopDrag);
      this.meterOpacityInput.addEventListener("touchstart", stopDrag, { passive: true });
      this.meterOpacityInput.addEventListener("input", (event) => {
        const value = Number(event.target?.value);
        const next = this.normalizeMeterOpacity(value, defaultOpacity);
        this.meterOpacityValue.textContent = `${next}%`;
        this.applyMeterFillOpacity(next, { persist: true });
      });
    }

    const currentLanguage = this.i18n?.getLanguage?.() || storedLanguage || "en";
    this.settingsSelections.language = currentLanguage;
    this.settingsSelections.theme = this.theme;

    this.initializeSettingsDropdowns();

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

    this.quitButton?.addEventListener("click", () => {
      window.javaBridge?.exitApp?.();
    });
  }

  initializeSettingsDropdowns() {
    const previewThemeVars = (themeId) => {
      const root = document.documentElement;
      const previous = root.dataset.theme;
      root.dataset.theme = themeId;
      const computed = getComputedStyle(root);
      const textColor = computed.getPropertyValue("--text-color").trim() || "#ffffff";
      const nameShadow = computed.getPropertyValue("--player-name-shadow").trim() || "none";
      const rowFill = computed.getPropertyValue("--row-fill").trim() || "#2f2f2f";
      root.dataset.theme = previous || "aion2";
      return { textColor, nameShadow, rowFill };
    };

    const closeAll = () => {
      document.querySelectorAll(".settingsDropdownMenu.isOpen").forEach((menu) => {
        menu.classList.remove("isOpen");
      });
    };

    const setupDropdown = (
      button,
      menu,
      options,
      currentValue,
      onSelect,
      { decorateItem = null, decorateButton = null } = {}
    ) => {
      if (!button || !menu) return;
      const optionList = Array.isArray(options) ? options : [];
      menu.innerHTML = "";

      optionList.forEach((opt) => {
        const item = document.createElement("button");
        item.type = "button";
        item.className = "settingsDropdownItem";
        item.dataset.value = opt.value;
        item.textContent = opt.label;
        if (String(opt.value) === String(currentValue)) {
          item.classList.add("isActive");
        }
        decorateItem?.(item, opt.value);
        item.addEventListener("click", () => {
          onSelect?.(opt.value);
          menu.classList.remove("isOpen");
          this.initializeSettingsDropdowns();
        });
        menu.appendChild(item);
      });

      const selected = optionList.find((opt) => String(opt.value) === String(currentValue)) || optionList[0];
      const textEl = button.querySelector(".settingsDropdownText");
      if (textEl) {
        textEl.textContent = selected?.label || "-";
      }
      button.style.background = "";
      button.style.color = "";
      decorateButton?.(button, selected?.value);
      button.onclick = (event) => {
        event.stopPropagation();
        const wasOpen = menu.classList.contains("isOpen");
        closeAll();
        menu.classList.toggle("isOpen", !wasOpen);
      };
    };

    if (!this._settingsDropdownOutsideBound) {
      document.addEventListener("click", (event) => {
        if (!event.target?.closest?.(".settingsDropdownWrapper")) {
          closeAll();
        }
      });
      this._settingsDropdownOutsideBound = true;
    }

    const languageOptions = [
      { value: "en", label: "English" },
      { value: "ko", label: "한국어" },
      { value: "zh-Hant", label: "繁體中文" },
      { value: "zh-Hans", label: "简体中文" },
    ];

    const themeOptions = [
      { value: "aion2", label: this.i18n?.t("settings.theme.options.aion2", "AION2") },
      { value: "asmodian", label: this.i18n?.t("settings.theme.options.asmodian", "Asmodian") },
      { value: "cogni", label: this.i18n?.t("settings.theme.options.cogni", "Cogni") },
      { value: "elyos", label: this.i18n?.t("settings.theme.options.elyos", "Elyos") },
      { value: "ember", label: this.i18n?.t("settings.theme.options.ember", "Ember") },
      { value: "fera", label: this.i18n?.t("settings.theme.options.fera", "Fera") },
      { value: "frost", label: this.i18n?.t("settings.theme.options.frost", "Frost") },
      { value: "natura", label: this.i18n?.t("settings.theme.options.natura", "Natura") },
      { value: "obsidian", label: this.i18n?.t("settings.theme.options.obsidian", "Obsidian") },
      { value: "varian", label: this.i18n?.t("settings.theme.options.varian", "Varian") },
    ];

    themeOptions.sort((a, b) => a.label.localeCompare(b.label));

    const targetWindowOptions = [
      { value: "5000", label: this.i18n?.t("settings.targetWindow.options.5s", "5 seconds") },
      { value: "10000", label: this.i18n?.t("settings.targetWindow.options.10s", "10 seconds") },
      { value: "15000", label: this.i18n?.t("settings.targetWindow.options.15s", "15 seconds") },
      { value: "20000", label: this.i18n?.t("settings.targetWindow.options.20s", "20 seconds") },
      { value: "30000", label: this.i18n?.t("settings.targetWindow.options.30s", "30 seconds") },
    ];

    const allTargetsWindowOptions = [
      { value: "30000", label: this.i18n?.t("settings.allTargetsWindow.options.30s", "30 seconds") },
      { value: "60000", label: this.i18n?.t("settings.allTargetsWindow.options.1m", "1 minute") },
      { value: "120000", label: this.i18n?.t("settings.allTargetsWindow.options.2m", "2 minutes") },
      { value: "180000", label: this.i18n?.t("settings.allTargetsWindow.options.3m", "3 minutes") },
      { value: "300000", label: this.i18n?.t("settings.allTargetsWindow.options.5m", "5 minutes") },
    ];

    const trainModeOptions = [
      { value: "all", label: this.i18n?.t("settings.trainingMode.options.all", "All") },
      {
        value: "highestDamage",
        label: this.i18n?.t("settings.trainingMode.options.highestDamage", "Highest Damage"),
      },
    ];

    setupDropdown(
      this.languageDropdownBtn,
      this.languageDropdownMenu,
      languageOptions,
      this.settingsSelections.language,
      (value) => {
        if (!value) return;
        this.settingsSelections.language = value;
        this.safeSetStorage(this.storageKeys.language, value);
        this.i18n?.setLanguage?.(value, { persist: true });
      }
    );

    setupDropdown(
      this.themeDropdownBtn,
      this.themeDropdownMenu,
      themeOptions,
      this.settingsSelections.theme,
      (value) => {
        if (!value) return;
        this.settingsSelections.theme = value;
        this.applyTheme(value, { persist: true });
      },
      {
        decorateItem: (item, value) => {
          const colors = previewThemeVars(value);
          item.style.background = colors.rowFill;
          item.style.opacity = "1";
          item.style.color = colors.textColor;
          item.style.textShadow = colors.nameShadow;
          item.style.fontWeight = "500";
          item.style.fontSize = "18px";
        },
        decorateButton: (button, value) => {
          const colors = previewThemeVars(value);
          button.style.background = colors.rowFill;
          button.style.opacity = "1";
          button.style.color = colors.textColor;
          button.style.textShadow = colors.nameShadow;
          button.style.fontWeight = "500";
          button.style.fontSize = "18px";
          const textEl = button.querySelector(".settingsDropdownText");
          if (textEl) {
            textEl.style.textShadow = colors.nameShadow;
          }
        },
      }
    );

    setupDropdown(
      this.targetWindowDropdownBtn,
      this.targetWindowDropdownMenu,
      targetWindowOptions,
      this.settingsSelections.targetSelectionWindowMs,
      (value) => {
        if (!value) return;
        this.settingsSelections.targetSelectionWindowMs = value;
        this.safeSetSetting(this.storageKeys.targetSelectionWindowMs, value);
        window.javaBridge?.setTargetSelectionWindowMs?.(value);
        if (!this.isCollapse) this.fetchDps();
      }
    );

    setupDropdown(
      this.allTargetsWindowDropdownBtn,
      this.allTargetsWindowDropdownMenu,
      allTargetsWindowOptions,
      this.settingsSelections.allTargetsWindowMs,
      (value) => {
        if (!value) return;
        this.settingsSelections.allTargetsWindowMs = value;
        this.safeSetSetting(this.storageKeys.allTargetsWindowMs, value);
        window.javaBridge?.setAllTargetsWindowMs?.(value);
        if (!this.isCollapse) this.fetchDps();
      }
    );

    setupDropdown(
      this.trainSelectionModeDropdownBtn,
      this.trainSelectionModeDropdownMenu,
      trainModeOptions,
      this.settingsSelections.trainSelectionMode,
      (value) => {
        if (!value) return;
        this.settingsSelections.trainSelectionMode = value;
        this.trainSelectionMode = value;
        this.safeSetSetting(this.storageKeys.trainSelectionMode, value);
        window.javaBridge?.setTrainSelectionMode?.(value);
        if (!this.isCollapse) this.fetchDps();
      }
    );
  }

  setupDetailsPanelSettings() {
    this.detailsOpacityInput = document.querySelector(".detailsOpacityInput");
    this.detailsOpacityValue = document.querySelector(".detailsOpacityValue");
    this.detailsSettingsBtn = document.querySelector(".detailsSettingsBtn");
    this.detailsSettingsMenu = document.querySelector(".detailsSettingsMenu");
    this.detailsIncludeMeterCheckbox = document.querySelector(".detailsIncludeMeterCheckbox");
    this.detailsSaveScreenshotCheckbox = document.querySelector(".detailsSaveScreenshotCheckbox");
    this.detailsScreenshotFolderRow = document.querySelector(".detailsSettingsFolder");
    this.detailsScreenshotFolderPath = document.querySelector(".detailsSettingsFolderPath");
    this.detailsScreenshotFolderBtn = document.querySelector(".detailsSettingsFolderBtn");

    const storedOpacity = this.safeGetStorage(this.storageKeys.detailsBackgroundOpacity);
    const initialOpacity = this.parseDetailsOpacity(storedOpacity);
    this.setDetailsBackgroundOpacity(initialOpacity, { persist: false });

    const storedIncludeMeter =
      this.safeGetStorage(this.storageKeys.detailsIncludeMeterScreenshot) === "true";
    const storedSaveToFolder =
      this.safeGetStorage(this.storageKeys.detailsSaveScreenshotToFolder) === "true";
    const storedFolder = this.safeGetStorage(this.storageKeys.detailsScreenshotFolder);
    this.includeMainMeterScreenshot = storedIncludeMeter;
    this.saveScreenshotToFolder = storedSaveToFolder;
    this.screenshotFolder = storedFolder || this.getDefaultScreenshotFolder();

    if (this.detailsIncludeMeterCheckbox) {
      this.detailsIncludeMeterCheckbox.checked = this.includeMainMeterScreenshot;
      this.detailsIncludeMeterCheckbox.addEventListener("change", (event) => {
        this.includeMainMeterScreenshot = !!event.target?.checked;
        this.safeSetStorage(
          this.storageKeys.detailsIncludeMeterScreenshot,
          String(this.includeMainMeterScreenshot)
        );
      });
    }
    if (this.detailsSaveScreenshotCheckbox) {
      this.detailsSaveScreenshotCheckbox.checked = this.saveScreenshotToFolder;
      this.detailsSaveScreenshotCheckbox.addEventListener("change", (event) => {
        this.saveScreenshotToFolder = !!event.target?.checked;
        if (this.saveScreenshotToFolder && !this.screenshotFolder) {
          this.screenshotFolder = this.getDefaultScreenshotFolder();
        }
        this.safeSetStorage(
          this.storageKeys.detailsSaveScreenshotToFolder,
          String(this.saveScreenshotToFolder)
        );
        if (this.screenshotFolder) {
          this.safeSetStorage(this.storageKeys.detailsScreenshotFolder, this.screenshotFolder);
        }
        this.updateScreenshotFolderDisplay();
      });
    }
    if (this.detailsScreenshotFolderBtn) {
      this.detailsScreenshotFolderBtn.addEventListener("click", () => {
        const selected = window.javaBridge?.chooseScreenshotFolder?.(this.screenshotFolder);
        if (!selected || typeof selected !== "string") return;
        this.screenshotFolder = selected;
        this.safeSetStorage(this.storageKeys.detailsScreenshotFolder, this.screenshotFolder);
        this.updateScreenshotFolderDisplay();
      });
    }
    this.updateScreenshotFolderDisplay();

    if (this.detailsOpacityInput) {
      this.detailsOpacityInput.value = String(Math.round(initialOpacity * 100));
      const stopDrag = (event) => event.stopPropagation();
      this.detailsOpacityInput.addEventListener("mousedown", stopDrag);
      this.detailsOpacityInput.addEventListener("touchstart", stopDrag, { passive: true });
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

  setupConsoleDebugging() {
    if (this._consoleDebuggingEnabled) {
      return;
    }
    this._consoleDebuggingEnabled = true;

    window.addEventListener("error", (event) => {
      console.error("[UI Error]", event.error || event.message, event);
    });

    window.addEventListener("unhandledrejection", (event) => {
      console.error("[UI Promise Rejection]", event.reason || event);
    });

    document.addEventListener("click", (event) => {
      const target = event.target;
      if (!target || typeof target.closest !== "function") return;
      const menuTarget =
        target.closest("[role='menu']") ||
        target.closest(".detailsSettingsMenu") ||
        target.closest(".detailsDropdownMenu") ||
        target.closest(".settingsPanel");
      if (!menuTarget) return;
      const menuClass = menuTarget.className || menuTarget.getAttribute?.("role") || "menu";
      const targetLabel =
        target.getAttribute?.("aria-label") ||
        target.getAttribute?.("data-i18n") ||
        target.textContent?.trim() ||
        target.tagName;
      console.log("[UI Menu Click]", { menu: menuClass, target: targetLabel });
    });
  }

  parseDetailsOpacity(value) {
    if (value === null || value === undefined || value === "") {
      return 0.65;
    }
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) {
      return 0.65;
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

  getDefaultScreenshotFolder() {
    return (
      window.javaBridge?.getDefaultScreenshotFolder?.() ||
      this.safeGetStorage(this.storageKeys.detailsScreenshotFolder) ||
      ""
    );
  }

  updateScreenshotFolderDisplay() {
    if (!this.detailsScreenshotFolderRow) return;
    this.detailsScreenshotFolderRow.classList.toggle("isHidden", !this.saveScreenshotToFolder);
    if (this.detailsScreenshotFolderPath) {
      this.detailsScreenshotFolderPath.textContent = this.screenshotFolder || "-";
    }
  }

  buildScreenshotFilename() {
    const now = new Date();
    const pad = (value) => String(value).padStart(2, "0");
    const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(
      now.getHours()
    )}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
    return `AION2_DPS_${stamp}.png`;
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
    const previousName = this.USER_NAME;
    const trimmed = String(name ?? "").trim();
    this.USER_NAME = trimmed;
    if (this.characterNameInput && document.activeElement !== this.characterNameInput) {
      this.characterNameInput.value = trimmed;
    }
    if (persist) {
      localStorage.setItem(this.storageKeys.userName, trimmed);
    }
    if (syncBackend) {
      window.javaBridge?.setCharacterName?.(trimmed);
    }
    if (previousName && previousName !== trimmed) {
      const cachedId = this.getRecentLocalIdForName(trimmed);
      if (cachedId) {
        this.refreshDamageData({ reason: "local name update" });
        this.applyLocalPlayerIdUpdate(cachedId, "local name update cached id");
        return;
      }
      this.localPlayerId = null;
      if (this.localActorIdInput && document.activeElement !== this.localActorIdInput) {
        this.localActorIdInput.value = "";
      }
      this.refreshDamageData({ reason: "local name update" });
      this.reinitTargetSelection("local name update");
    }
    if (!this.isCollapse) {
      this.fetchDps();
    }
    this.refreshSettingsPanelIfOpen();
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

  setDebugLogging(enabled, { persist = false, syncBackend = false } = {}) {
    this.debugLoggingEnabled = !!enabled;
    if (this.debugLoggingCheckbox && document.activeElement !== this.debugLoggingCheckbox) {
      this.debugLoggingCheckbox.checked = this.debugLoggingEnabled;
    }
    if (persist) {
      this.safeSetSetting(this.storageKeys.debugLogging, String(this.debugLoggingEnabled));
    }
    if (syncBackend) {
      window.javaBridge?.setDebugLoggingEnabled?.(this.debugLoggingEnabled);
    }
  }

  setPinMeToTop(enabled, { persist = false } = {}) {
    this.pinMeToTop = !!enabled;
    if (this.pinMeToTopCheckbox && document.activeElement !== this.pinMeToTopCheckbox) {
      this.pinMeToTopCheckbox.checked = this.pinMeToTop;
    }
    if (persist) {
      this.safeSetSetting(this.storageKeys.pinMeToTop, String(this.pinMeToTop));
    }
    this.renderCurrentRows();
  }

  setTargetSelection(mode, { persist = false, syncBackend = false, reason = "update" } = {}) {
    const previousSelection = this.targetSelection;
    this.targetSelection = ["allTargets", "trainTargets"].includes(mode)
      ? mode
      : "lastHitByMe";
    if (persist) {
      this.safeSetStorage(this.storageKeys.targetSelection, String(this.targetSelection));
    }
    if (syncBackend) {
      window.javaBridge?.setTargetSelection?.(this.targetSelection);
    }
    if (previousSelection !== this.targetSelection) {
      this.logDebug(
        `Target selection changed: "${previousSelection}" -> "${this.targetSelection}" (reason: ${reason}).`
      );
      this._lastTargetSelection = this.targetSelection;
    }
    this.updateTargetModeButton();
  }

  applyTheme(themeId, { persist = false } = {}) {
    const normalized = this.availableThemes.includes(themeId) ? themeId : this.availableThemes[0];
    this.theme = normalized;
    document.documentElement.dataset.theme = normalized;
    if (this.settingsSelections) {
      this.settingsSelections.theme = normalized;
    }
    if (persist) {
      this.safeSetSetting(this.storageKeys.theme, normalized);
    }
  }

  setDisplayMode(mode, { persist = false } = {}) {
    this.displayMode = mode === "totalDamage" ? "totalDamage" : "dps";
    if (persist) {
      this.safeSetStorage(this.storageKeys.displayMode, this.displayMode);
    }
    this.updateDisplayToggleLabel();
  }

  updateDisplayToggleLabel() {
    if (!this.metricToggleBtn) return;
    const label =
      this.displayMode === "totalDamage"
        ? this.i18n?.t("header.display.total", "DMG") ?? "DMG"
        : this.i18n?.t("header.display.dps", "DPS") ?? "DPS";
    const ariaLabel =
      this.displayMode === "totalDamage"
        ? this.i18n?.t("header.display.ariaDamage", "Showing total damage")
        : this.i18n?.t("header.display.ariaDps", "Showing DPS");
    this.metricToggleBtn.textContent = label;
    this.metricToggleBtn.setAttribute("aria-label", ariaLabel);
  }

  formatAbbreviatedNumber(value) {
    const n = Number(value);
    if (!Number.isFinite(n)) return "-";
    const abs = Math.abs(n);
    const units = [
      { value: 1e12, suffix: "t" },
      { value: 1e9, suffix: "b" },
      { value: 1e6, suffix: "m" },
      { value: 1e3, suffix: "k" },
    ];
    for (const unit of units) {
      if (abs >= unit.value) {
        const scaled = (n / unit.value).toFixed(2);
        const trimmed = scaled.replace(/\.?0+$/, "");
        return `${trimmed}${unit.suffix}`;
      }
    }
    return this.dpsFormatter.format(n);
  }

  refreshDamageData({ reason = "refresh" } = {}) {
    this.refreshPending = true;
    this.lastSnapshot = null;
    this.lastJson = null;
    this.lastTargetMode = "";
    this.lastTargetName = "";
    this.lastTargetId = 0;
    this._lastRenderedListSignature = "";
    this._lastRenderedTargetLabel = "";
    this._lastRenderedRowsSummary = null;
    this.detailsUI?.close?.();
    this.lastSnapshot = [];
    this._lastRenderedRowsSummary = null;
    this._lastRenderedListSignature = "";
    this.meterUI?.onResetMeterUi?.();
    this.renderCurrentRows();

    if (this.elBossName) {
      this.elBossName.textContent = this.getDefaultTargetLabel(this.targetSelection);
    }

    const lastParsedAtMs = Number(window.javaBridge?.getLastParsedAtMs?.());
    if (Number.isFinite(lastParsedAtMs) && lastParsedAtMs > 0) {
      const idleMs = Date.now() - lastParsedAtMs;
      if (idleMs > 30_000) {
        window.javaBridge?.resetAutoDetection?.();
      }
    }

    window.javaBridge?.resetDps?.();
    this.logDebug(`Damage data refreshed (${reason}).`);
  }

  getMetricForRow(row) {
    if (this.displayMode === "totalDamage") {
      const totalDamage = Number(row?.totalDamage) || 0;
      return {
        value: totalDamage,
        text: this.formatAbbreviatedNumber(totalDamage),
      };
    }
    const dps = Number(row?.dps) || 0;
    return {
      value: dps,
      text: `${this.dpsFormatter.format(dps)}/s`,
    };
  }

  renderCurrentRows() {
    if (this.isCollapse) return;
    let rowsToRender = Array.isArray(this.lastSnapshot) ? this.lastSnapshot : [];
    if (this.lastTargetMode === "trainTargets" && this.USER_NAME) {
      rowsToRender = rowsToRender.filter((row) => row.name === this.USER_NAME);
    }
    const rowsSummary = this.getRowsSummary(rowsToRender);
    if (rowsSummary.listSignature !== this._lastRenderedListSignature) {
      const reasons = this.describeRowsChange(rowsSummary, this._lastRenderedRowsSummary);
      if (!Array.isArray(this.lastSnapshot) || this.lastSnapshot.length === 0) {
        reasons.push("no snapshot available");
      } else {
        reasons.push("renderCurrentRows refresh");
      }
      this.logDebug(
        `Meter list changed (${rowsToRender.length} rows). reason: ${
          reasons.join("; ") || "list membership changed"
        }.`
      );
      this._lastRenderedListSignature = rowsSummary.listSignature;
      this._lastRenderedRowsSummary = rowsSummary;
    }
    this.meterUI?.updateFromRows?.(rowsToRender);
  }

  refreshConnectionInfo({ skipSettingsRefresh = false } = {}) {
    if (!this.lockedIp || !this.lockedPort) return;
    const raw = window.javaBridge?.getConnectionInfo?.();
    if (typeof raw !== "string") {
      this.lockedIp.textContent = "-";
      this.lockedPort.textContent = "-";
      if (this.localActorIdInput && document.activeElement !== this.localActorIdInput) {
        this.localActorIdInput.value = "";
      }
      this.isDetectingPort = this.aionRunning;
      this.updateConnectionStatusUi();
      if (!skipSettingsRefresh) {
        this.refreshSettingsPanelIfOpen();
      }
      return;
    }
    const info = this.safeParseJSON(raw, {});
    const previousLocalId = this.localPlayerId;
    const deviceName = typeof info?.device === "string" && info.device.trim() ? info.device : "";
    const rawIp = info?.ip || "-";
    const ip =
      deviceName ||
      (rawIp === "127.0.0.1" || rawIp === "::1"
        ? this.i18n?.t("connection.loopback", "Local Loopback") ?? "Local Loopback"
        : rawIp);
    const hasPort = Number.isFinite(Number(info?.port));
    this.isDetectingPort = this.aionRunning && !hasPort;
    const port = hasPort
      ? String(info.port)
      : this.isDetectingPort
        ? this.i18n?.t("connection.detecting", "Detecting AION2 connection...")
        : this.i18n?.t("connection.auto", "Auto");
    this.lockedIp.textContent = ip;
    this.lockedPort.textContent = port;
    const localPlayerId = Number(info?.localPlayerId);
    this.localPlayerId = Number.isFinite(localPlayerId) && localPlayerId > 0
      ? Math.trunc(localPlayerId)
      : null;
    if (this.localActorIdInput && document.activeElement !== this.localActorIdInput) {
      this.localActorIdInput.value = this.localPlayerId ? String(this.localPlayerId) : "";
    }
    if (this.characterNameInput) {
      const nickname = String(info?.characterName || this.USER_NAME || "").trim();
      this.characterNameInput.value = nickname;
    }
    if (this.localPlayerId && this.localPlayerId !== previousLocalId) {
      this.reinitTargetSelection("local id update");
    }
    this.updateConnectionStatusUi();
    if (!skipSettingsRefresh) {
      this.refreshSettingsPanelIfOpen();
    }
  }

  refreshSettingsPanelIfOpen() {
    if (!this.settingsPanel?.classList.contains("isOpen")) return;
    this.refreshConnectionInfo({ skipSettingsRefresh: true });
  }

  updateConnectionStatusUi() {
    if (!this.battleTimeRoot || !this.analysisStatusEl) return;
    if (!this.aionRunning) {
      this.applyConnectionStatusOverride(
        this.i18n?.t("battleTime.notRunning", "AION2 not running") ?? "AION2 not running"
      );
      return;
    }
    if (this.isDetectingPort) {
      this.applyConnectionStatusOverride(
        this.i18n?.t("connection.detecting", "Detecting AION2 connection...") ??
          "Detecting AION2 connection..."
      );
      return;
    }
    this.clearConnectionStatusOverride();
  }

  applyConnectionStatusOverride(text) {
    this._connectionStatusOverride = true;
    this.battleTimeRoot.classList.add("isVisible", "state-idle");
    this.analysisStatusEl.textContent = text;
    this.analysisStatusEl.style.display = "";
  }

  clearConnectionStatusOverride() {
    if (!this._connectionStatusOverride) return;
    this._connectionStatusOverride = false;
    this.battleTimeRoot.classList.remove("state-idle");
    if (!this._battleTimeVisible) {
      this.battleTimeRoot.classList.remove("isVisible");
    }
    this.analysisStatusEl.textContent =
      this.i18n?.t("battleTime.analysing", "Ready - monitoring combat...") ??
      "Ready - monitoring combat...";
    this.analysisStatusEl.style.removeProperty("display");
  }

  getRowsSummary(rows) {
    const safeRows = Array.isArray(rows) ? rows : [];
    const ids = safeRows.map((row) => String(row?.id ?? "")).sort();
    const names = safeRows.map((row) => String(row?.name ?? "")).sort();
    return {
      count: safeRows.length,
      ids,
      names,
      listSignature: ids.join("|"),
    };
  }

  describeRowsChange(nextSummary, previousSummary) {
    const reasons = [];
    if (!previousSummary) {
      reasons.push("initial meter render");
      return reasons;
    }
    if (previousSummary.count !== nextSummary.count) {
      reasons.push(`row count ${previousSummary.count} -> ${nextSummary.count}`);
    }
    const idsChanged =
      previousSummary.ids.length !== nextSummary.ids.length ||
      previousSummary.ids.some((id, index) => id !== nextSummary.ids[index]);
    if (idsChanged) {
      reasons.push("row ids changed");
    }
    const namesChanged =
      previousSummary.names.length !== nextSummary.names.length ||
      previousSummary.names.some((name, index) => name !== nextSummary.names[index]);
    if (namesChanged && !idsChanged) {
      reasons.push("row names changed");
    }
    return reasons;
  }

  logDebug(message) {
    if (!message) return;
    try {
      window.javaBridge?.logDebug?.(String(message));
    } catch (e) {
      globalThis.uiDebug?.log?.("logDebug blocked", { message: String(message), error: String(e) });
    }
  }

  getDefaultTargetLabel(targetMode = "") {
    if (targetMode === "allTargets") {
      return this.i18n?.t("target.all", "All Targets") ?? "All Targets";
    }
    if (targetMode === "trainTargets") {
      return this.i18n?.t("target.train", "Training Scarecrow") ?? "Training Scarecrow";
    }
    return this.i18n?.t("header.title", "DPS METER") ?? "DPS METER";
  }

  getTargetLabel({ targetId = 0, targetName = "", targetMode = "" } = {}) {
    if (targetMode === "allTargets" || targetMode === "trainTargets") {
      return this.getDefaultTargetLabel(targetMode);
    }
    if (targetMode === "lastHitByMe" && (!Number(targetId) || Number(targetId) <= 0) && !targetName) {
      return this.getDefaultTargetLabel("allTargets");
    }
    if (Number.isFinite(Number(targetId)) && Number(targetId) > 0) {
      return `Mob #${Number(targetId)}`;
    }
    if (targetName) {
      return targetName;
    }
    return this.getDefaultTargetLabel(targetMode);
  }

  updateTargetModeButton() {
    if (!this.targetModeBtn) return;
    const isAllTargets = this.targetSelection === "allTargets";
    const isTrainTargets = this.targetSelection === "trainTargets";
    this.targetModeBtn.classList.toggle("isAllTargets", isAllTargets);
    this.targetModeBtn.classList.toggle("isTrainTargets", isTrainTargets);
    this.targetModeBtn.textContent = isAllTargets ? "ALL" : isTrainTargets ? "TRAIN" : "TARGET";
    const ariaLabel = isAllTargets
      ? "All targets mode"
      : isTrainTargets
        ? "Train targets mode"
        : "Target mode";
    this.targetModeBtn.setAttribute("aria-label", ariaLabel);
  }

  refreshBossLabel() {
    if (!this.elBossName) return;
    if (this.lastTargetName || this.lastTargetId) {
      return;
    }
    this.elBossName.textContent = this.getTargetLabel({
      targetMode: this.lastTargetMode,
      targetId: this.lastTargetId,
      targetName: this.lastTargetName,
    });
    this.elBossName.classList.toggle("isAllTargets", this.lastTargetMode === "allTargets");
  }

  bindDragToMoveWindow() {
    let isDragging = false;
    let startX = 0,
      startY = 0;
    let initialStageX = 0,
      initialStageY = 0;

    document.addEventListener("mousedown", (e) => {
      const targetEl = e.target?.nodeType === Node.TEXT_NODE ? e.target.parentElement : e.target;
      if (targetEl?.closest?.(".resizeHandle")) {
        return;
      }
      if (targetEl?.closest?.(".headerBtn, .footerBtn")) {
        return;
      }
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

  bindResizeHandle() {
    this.resizeHandle = document.querySelector(".resizeHandle");
    this.meterEl = document.querySelector(".meter");
    if (!this.resizeHandle || !this.meterEl) return;

    let isResizing = false;
    let startX = 0;
    let startY = 0;
    let startWidth = 0;
    let startHeight = 0;
    const minWidth = 300;
    const minHeight = 30;

    const onMouseMove = (event) => {
      if (!isResizing) return;
      const nextWidth = Math.max(minWidth, startWidth + (event.clientX - startX));
      const nextHeight = Math.max(minHeight, startHeight + (event.clientY - startY));
      this.meterEl.style.width = `${nextWidth}px`;
      this.meterEl.style.height = `${nextHeight}px`;
    };

    const onMouseUp = () => {
      if (!isResizing) return;
      isResizing = false;
    };

    this.resizeHandle.addEventListener("mousedown", (event) => {
      event.preventDefault();
      event.stopPropagation();
      const rect = this.meterEl.getBoundingClientRect();
      startWidth = rect.width;
      startHeight = rect.height;
      startX = event.clientX;
      startY = event.clientY;
      isResizing = true;
    });

    document.addEventListener("mousemove", onMouseMove);
    document.addEventListener("mouseup", onMouseUp);
  }
}

DpsApp.instance = null;

// 디버그콘솔
const setupDebugConsole = () => {
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

  const originalConsole = {
    log: console.log.bind(console),
    info: console.info.bind(console),
    warn: console.warn.bind(console),
    error: console.error.bind(console),
  };

  const printToPanel = (level, args) => {
    const line = args.map(safeStringify).join(" ");
    appendLine(`[${level}] ${line}`);
  };

  if (!globalThis.__uiConsolePatched) {
    ["log", "info", "warn", "error"].forEach((level) => {
      console[level] = (...args) => {
        originalConsole[level](...args);
        printToPanel(level, args);
      };
    });
    globalThis.__uiConsolePatched = true;
  }

  globalThis.uiDebug = {
    clear() {
      consoleDiv.innerHTML = "";
    },
    log(...args) {
      printToPanel("debug", args);
    },
  };

  return globalThis.uiDebug;
};

// Keep JavaFX overlay console hidden unless explicitly re-enabled for troubleshooting.
// setupDebugConsole();
const dpsApp = DpsApp.createInstance();
window.dpsApp = dpsApp;
const debug = globalThis.uiDebug || { log: () => {}, clear: () => {} };

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

let appStarted = false;
const startApp = async ({ forced = false } = {}) => {
  if (appStarted) return;
  appStarted = true;
  debug?.log?.("startApp", {
    readyState: document.readyState,
    hasDpsData: !!window.dpsData,
    hasJavaBridge: !!window.javaBridge,
    forced,
  });
  try {
    await window.i18n?.init?.();
    window.lucide?.createIcons?.();
    dpsApp.start();
    window.javaBridge?.notifyUiReady?.();
  } catch (err) {
    debug?.log?.("startApp.error", err);
  }
};

const waitForBridgeAndStart = (attempt = 0) => {
  // JavaFX WebView injects these after loadWorker SUCCEEDED (slightly later than DOMContentLoaded)
  const ready = !!window.javaBridge && !!window.dpsData;

  debug?.log?.("waitForBridge", {
    attempt,
    readyState: document.readyState,
    hasDpsData: !!window.dpsData,
    hasJavaBridge: !!window.javaBridge,
  });

  if (ready) {
    startApp();
    return;
  }

  if (attempt >= 200) {
    debug?.log?.("waitForBridge.timeout", "Bridge not ready after 10s; forcing UI startup.");
    startApp({ forced: true });
    return;
  }

  setTimeout(() => waitForBridgeAndStart(attempt + 1), 50);
};

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", waitForBridgeAndStart, { once: true });
} else {
  waitForBridgeAndStart();
}
