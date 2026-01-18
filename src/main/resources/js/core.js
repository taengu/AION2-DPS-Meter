class DpsApp {
  static instance;

  constructor() {
    if (DpsApp.instance) {
      return DpsApp.instance;
    }

    this.POLL_MS = 200;
    this.USER_NAME = "승찬";
    this.lastJson = null;
    this.isCollapse = false;

    this.dpsFormatter = new Intl.NumberFormat("ko-KR");
    // 빈값으로 덮어씌우게 하지 않도록 스냅샷
    this.lastSnapshot = null;
    // reset 직후 잠깐 이전값으로 덮어씌우는 ui 버그떄문에 팬딩추가
    this.resetPending = false;

    DpsApp.instance = this;
  }

  static createInstance() {
    if (!DpsApp.instance) {
      DpsApp.instance = new DpsApp();
    }
    return DpsApp.instance;
  }

  start() {
    this.elList = document.querySelector(".list");
    this.elBossName = document.querySelector(".bossName");

    this.detailsPanel = document.querySelector(".detailsPanel");
    this.detailsClose = document.querySelector(".detailsClose");
    this.detailsTitle = document.querySelector(".detailsTitle");
    this.detailsStatsEl = document.querySelector(".detailsStats");
    this.skillsListEl = document.querySelector(".skills");

    this.resetBtn = document.querySelector(".resetBtn");
    this.collapseBtn = document.querySelector(".collapseBtn");

    this.detailsUI = createDetailsUI({
      detailsPanel: this.detailsPanel,
      detailsClose: this.detailsClose,
      detailsTitle: this.detailsTitle,
      detailsStatsEl: this.detailsStatsEl,
      skillsListEl: this.skillsListEl,
      dpsFormatter: this.dpsFormatter,
      getDetails: (row) => this.getDetails(row),
    });

    this.meterUI = createMeterUI({
      elList: this.elList,
      dpsFormatter: this.dpsFormatter,
      getUserName: () => this.USER_NAME,
      onClickUserRow: (row) => this.detailsUI.open(row),
    });

    this.bindHeaderButtons();
    this.bindDragToMoveWindow();

    this.elBossName.textContent = "DPS METER";
    this.fetchDps();
    setInterval(() => this.fetchDps(), this.POLL_MS);
  }

  fetchDps() {
    const raw = window.dpsData?.getDpsData?.();
    if (typeof raw !== "string") return;
    if (raw === this.lastJson) return;
    this.lastJson = raw;

    let { rows, targetName } = this.buildRowsFromPayload(raw);

    // 리셋 누르면 빈값이 올때까지 렌더 안함
    if (this.resetPending) {
      if (rows.length > 0) {
        // 서버에서 예전데이터 주는거 무시
        return;
      }
      // 빈값이 오면 reset 확인 완료
      this.resetPending = false;
      // UI는 onResetMeterUi로 이미 비움
      return;
    }

    // 빈 데이터는 UI를 덮어씌우지 않음
    if (rows.length === 0) {
      if (this.lastSnapshot) {
        rows = this.lastSnapshot;
      } else {
        return;
      }
    } else {
      this.lastSnapshot = rows; // 정상 데이터면 스냅샷 갱신
    }
    if (!!targetName) {
      this.elBossName.textContent = targetName;
    } else {
      this.elBossName.textContent = "타겟 코드 미수집";
    }
    this.meterUI.updateFromRows(rows);
  }
  buildRowsFromPayload(raw) {
    const payload = JSON.parse(raw);
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
      const dpsRaw = isObj ? value.dps : value;
      const dps = Math.trunc(Number(dpsRaw));
      const nickname = isObj ? (value.nickname ?? "") : "";
      const name = nickname || String(id);
      const damageContribution = isObj ? Number(value.damageContribution).toFixed(1) : "";

      if (!Number.isFinite(dps)) {
        continue;
      }

      rows.push({
        id: id,
        name: name,
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
    uiDebug?.log("getBattleDetail", raw);

    let detailObj = raw;
    if (typeof raw === "string") {
      try {
        detailObj = JSON.parse(raw);
      } catch {
        detailObj = {};
      }
    }
    if (!detailObj || typeof detailObj !== "object") detailObj = {};

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

    //혹시 나중에  전투시간을 줄수도 있으니 남김
    const combatTime = detailObj.combatTime ? detailObj.combatTime : "-";

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

      this.detailsUI?.close?.();
      this.meterUI?.onResetMeterUi?.();

      this.lastJson = null;
      this.elBossName.textContent = "DPS METER";

      window.javaBridge?.resetDps?.();
    });
  }

  bindDragToMoveWindow() {
    let isDragging = false;
    let startX, startY;
    let initialStageX, initialStageY;

    document.addEventListener("mousedown", (e) => {
      isDragging = true;
      startX = e.screenX;
      startY = e.screenY;
      initialStageX = window.screenX;
      initialStageY = window.screenY;
    });

    document.addEventListener("mousemove", (e) => {
      if (isDragging && window.javaBridge) {
        const deltaX = e.screenX - startX;
        const deltaY = e.screenY - startY;
        window.javaBridge.moveWindow(initialStageX + deltaX, initialStageY + deltaY);
      }
    });

    document.addEventListener("mouseup", () => {
      isDragging = false;
    });
  }
}

const setupDebugConsole = () => {
  if (globalThis.uiDebug?.log) {
    return globalThis.uiDebug;
  }

  const consoleDiv = document.querySelector(".console");

  if (!consoleDiv) {
    globalThis.uiDebug = { log: () => {}, clear: () => {} };
    return globalThis.uiDebug;
  }

  const safeStringify = (value) => {
    if (typeof value === "string") {
      return value;
    }
    if (value instanceof Error) {
      return `${value.name}: ${value.message}`;
    }
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
      if (globalThis.dpsData?.isDebuggingMode?.() !== true) {
        return;
      }

      const time = new Date().toLocaleTimeString("ko-KR", { hour12: false });
      const line = `${time} ${tag} ${safeStringify(payload)}`;

      appendLine(line);
    },

    clear() {
      consoleDiv.innerHTML = "";
    },
  };

  return globalThis.uiDebug;
};
setupDebugConsole();

const dpsApp = DpsApp.createInstance();
