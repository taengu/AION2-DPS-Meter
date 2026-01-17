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
    this.callDebugWindow();
  }

  fetchDps() {
    const raw = window.dpsData?.getDpsData?.();
    if (typeof raw !== "string") {
      return;
    }
    if (raw === this.lastJson) {
      return;
    }
    this.lastJson = raw;

    const { rows, targetName } = this.buildRowsFromPayload(raw);

    //UI 초기화 안되는 버그 있어서 주석처리
    // if (rows.length > 0) {
    //   this.lastNonEmptyRows = rows;
    // } else if (this.lastNonEmptyRows) {
    //   rows = this.lastNonEmptyRows;
    // }

    this.elBossName.textContent = targetName;

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

    for (const [name, value] of Object.entries(mapObj || {})) {
      const isObj = value && typeof value === "object";

      const job = isObj ? value.job ?? "" : "";
      const dpsRaw = isObj ? value.dps : value;
      const dps = Math.trunc(Number(dpsRaw));
      const damageContribution = isObj ? Number(value.damageContribution).toFixed(1) : "";

      if (!Number.isFinite(dps)) {
        continue;
      }

      rows.push({
        id: name,
        name,
        job,
        dps,
        damageContribution,
        isUser: name === this.USER_NAME,
      });
    }

    return rows;
  }

  getDetails(row) {
    const skills = [
      {
        code: 24,
        name: "내려찍기",
        time: 324,
        crit: 300,
        dmg: Math.round(Math.random() * 1000000),
      },
      {
        code: 6765,
        name: "맹렬한 일격",
        time: 32,
        crit: 20,
        dmg: Math.round(Math.random() * 500000),
      },
      {
        code: 954,
        name: "파멸의 맹타",
        time: 324,
        crit: 300,
        dmg: Math.round(Math.random() * 400000),
      },
      { code: 9981, name: "단죄", time: 14, crit: 3, dmg: Math.round(Math.random() * 900000) },
      {
        code: 12345,
        name: "비호의 일격",
        time: 4,
        crit: 2,
        dmg: Math.round(Math.random() * 200000),
      },
      { code: 4332, name: "심판", time: 54, crit: 1, dmg: Math.round(Math.random() * 400000) },
    ];

    const sumSkills = skills.reduce((acc, s) => acc + (Number(s.dmg) || 0), 0);
    const totalDmg = sumSkills + Math.round(sumSkills * 0.12);

    return {
      totalDmg,
      percent: `${Math.round(Math.random() * 30) + 10}%`,
      parry: `${Math.round(Math.random() * 50)}%`,
      eva: `${Math.round(Math.random() * 30)}%`,
      combatTime: "2분 36초",
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
  callDebugWindow() {
    if (!window.dpsData) {
      setTimeout(() => this.callDebugWindow(), 100);
      return;
    }

    const consoleDiv = document.querySelector(".console");

    if (window.dpsData.isDebuggingMode()) {
      consoleDiv.style.display = "block";
      const originalLog = console.log;

      console.log = function (...args) {
        originalLog.apply(console, args);
        consoleDiv.innerHTML += args.join(" ") + "<br>";
        consoleDiv.scrollTop = consoleDiv.scrollHeight;
      };
      console.log("콘솔 활성화됨");
      setInterval(() => {
        const data = window.dpsData.getDpsData();
        console.log(data);
      }, 500);
    }
  }
}

const dpsApp = DpsApp.createInstance();
