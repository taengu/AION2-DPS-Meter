class DpsApp {
  static instance;

  constructor() {
    if (DpsApp.instance) return DpsApp.instance;

    this.POLL_MS = 200;
    this.USER_NAME = "-------";

    this.dpsFormatter = new Intl.NumberFormat("ko-KR");
    this.lastJson = null;
    this.isCollapse = false;

    // 빈데이터 덮어쓰기 방지 스냅샷
    this.lastSnapshot = null;
    // reset 직후 서버가 구 데이터 계속 주는 현상 방지
    this.resetPending = false;

    this.BATTLE_TIME_BASIS = "render";
    this.GRACE_MS = 30000;
    this.GRACE_ARM_MS = 1000;

    this.prevDpsById = new Map();
    this.nextDpsById = new Map();

    // battleTime 캐시
    this._battleTimeVisible = false;

    DpsApp.instance = this;
  }

  static createInstance() {
    if (!DpsApp.instance) DpsApp.instance = new DpsApp();
    return DpsApp.instance;
  }

  start() {
    this.elList = document.querySelector(".list");
    this.elBossName = document.querySelector(".bossName");
    this.elBossName.textContent = "DPS METER";

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

    setInterval(() => this.fetchDps(), this.POLL_MS);
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

  fetchDps() {
    const now = this.nowMs();
    const raw = window.dpsData?.getDpsData?.();

    // 값이 없으면 활동 없음 + 타임 숨김
    if (typeof raw !== "string") {
      this.battleTime.update(now, false);
      this._battleTimeVisible = false;
      this.battleTime.setVisible(false);
      return;
    }

    // 값이 동일해도 타이머는 계속 업데이트 해야함 유예나 종료필요하니까
    if (raw === this.lastJson) {
      this.battleTime.update(now, false);
      this.battleTime.setVisible(this._battleTimeVisible);
      if (this._battleTimeVisible) {
        this.battleTime.render(now);
      }
      return;
    }

    this.lastJson = raw;

    // 파싱
    const { rows, targetName } = this.buildRowsFromPayload(raw);
    const showByServer = rows.length > 0;

    // 서버가 빈값 줄때까지 데이터 무시
    if (this.resetPending) {
      // 표시 숨김
      this._battleTimeVisible = false;
      this.battleTime.setVisible(false);

      if (rows.length === 0) {
        this.resetPending = false;
      }
      return;
    }

    // dps 기준 변화감지
    const isActivity = this.computedDps(rows);

    //타이머 업데이트
    this.battleTime.update(now, isActivity);

    // 빈값은 ui 안덮어씀
    let rowsToRender = rows;
    if (rows.length === 0) {
      if (this.lastSnapshot) {
        rowsToRender = this.lastSnapshot;
      } else {
        //타이머 숨김
        this._battleTimeVisible = false;
        this.battleTime.setVisible(false);
        return;
      }
    } else {
      this.lastSnapshot = rows;
    }

    // 타이머표시 여부
    const showByRender = rowsToRender.length > 0;
    const showBattleTime = this.BATTLE_TIME_BASIS === "server" ? showByServer : showByRender;
    const shouldBeVisible = showBattleTime && !this.isCollapse;

    this._battleTimeVisible = shouldBeVisible;
    this.battleTime.setVisible(shouldBeVisible);
    if (shouldBeVisible) {
      this.battleTime.render(now);
    }

    // 렌더
    this.elBossName.textContent = targetName ? targetName : "타겟 코드 미수집";
    this.meterUI.updateFromRows(rowsToRender);
  }

  buildRowsFromPayload(raw) {
    const payload = this.safeParseJSON(raw, {});
    const targetName = typeof payload?.targetName === "string" ? payload.targetName : "";

    const mapObj = payload?.map && typeof payload.map === "object" ? payload.map : {};
    const rows = this.buildRowsFromMapObject(mapObj);

    return { rows, targetName };
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

      // 소숫점 한자리
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

  // dps변화 기준
  computedDps(serverRows) {
    if (!Array.isArray(serverRows) || serverRows.length === 0) {
      return false;
    }

    this.nextDpsById.clear();

    let changed = false;

    for (const row of serverRows) {
      const id = row?.id ?? row?.name;
      if (!id) continue;

      const dps = Math.trunc(Number(row?.dps) || 0);
      this.nextDpsById.set(id, dps);

      const prev = this.prevDpsById.get(id);
      if (prev === undefined || prev !== dps) {
        changed = true;
      }
    }

    if (!changed) {
      if (this.prevDpsById.size !== this.nextDpsById.size) {
        changed = true;
      } else {
        for (const id of this.prevDpsById.keys()) {
          if (!this.nextDpsById.has(id)) {
            changed = true;
            break;
          }
        }
      }
    }

    const tmp = this.prevDpsById;
    this.prevDpsById = this.nextDpsById;
    this.nextDpsById = tmp;

    return changed;
  }

  async getDetails(row) {
    const raw = await window.dpsData?.getBattleDetail?.(row.id);
    globalThis.uiDebug?.log?.("getBattleDetail", raw);

    let detailObj = raw;
    if (typeof raw === "string") {
      detailObj = this.safeParseJSON(raw, {});
    }
    if (!detailObj || typeof detailObj !== "object") {
      detailObj = {};
    }

    const skills = [];
    let totalDmg = 0;

    for (const [code, value] of Object.entries(detailObj)) {
      if (!value || typeof value !== "object") {
        continue;
      }

      const dmg = Math.trunc(Number(value.damageAmount)) || 0;
      if (dmg <= 0) {
        continue;
      }

      totalDmg += dmg;

      const nameRaw = typeof value.skillName === "string" ? value.skillName.trim() : "";
      skills.push({
        code,
        name: nameRaw ? nameRaw : `스킬 ${code}`,
        time: Math.trunc(Number(value.times)) || 0,
        crit: Math.trunc(Number(value.critTimes)) || 0,
        dmg,
      });
    }

    const contrib = Number(row?.damageContribution);
    const percent = Number.isFinite(contrib) ? `${contrib.toFixed(1)}%` : "-";

    const combatTime = this.battleTime?.getCombatTimeText?.(this.nowMs()) ?? "00:00";

    return {
      totalDmg,
      percent,
      combatTime,
      skills,
    };
  }

  bindHeaderButtons() {
    this.collapseBtn?.addEventListener("click", () => {
      this.isCollapse = !this.isCollapse;
      this._battleTimeVisible = !this.isCollapse;
      this.battleTime?.setVisible?.(!this.isCollapse);

      this.elList.style.display = this.isCollapse ? "none" : "grid";

      const iconName = this.isCollapse ? "arrow-down-wide-narrow" : "arrow-up-wide-narrow";
      const iconEl =
        this.collapseBtn.querySelector("svg") || this.collapseBtn.querySelector("[data-lucide]");
      if (!iconEl) {
        return;
      }

      iconEl.setAttribute("data-lucide", iconName);
      lucide.createIcons({ root: this.collapseBtn });
    });

    this.resetBtn?.addEventListener("click", () => {
      this.resetPending = true;
      this.lastSnapshot = null;
      this.lastJson = null;

      this._battleTimeVisible = false;
      this.battleTime.reset();
      this.battleTime.setVisible(false);

      this.prevDpsById.clear();
      this.nextDpsById.clear();

      this.detailsUI?.close?.();
      this.meterUI?.onResetMeterUi?.();

      this.elBossName.textContent = "DPS METER";

      window.javaBridge?.resetDps?.();
    });
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

  globalThis.uiDebug = {
    log(tag, payload) {
      if (globalThis.dpsData?.isDebuggingMode?.() !== true) return;
      const time = new Date().toLocaleTimeString("ko-KR", { hour12: false });
      appendLine(`${time} ${tag} ${safeStringify(payload)}`);
    },
    clear() {
      consoleDiv.innerHTML = "";
    },
  };

  return globalThis.uiDebug;
};

setupDebugConsole();
const dpsApp = DpsApp.createInstance();
