const createHistoryUI = ({ onOpenFight } = {}) => {
  const panel = document.querySelector(".historyPanel");
  if (!panel) return null;

  const listEl = panel.querySelector(".historyList");
  const closeBtn = panel.querySelector(".historyClose");
  const emptyEl = panel.querySelector(".historyEmpty");
  const trainToggleBtn = panel.querySelector(".historyTrainToggle");
  const deleteToggleBtn = panel.querySelector(".historyDeleteToggle");
  const filterBossEl = panel.querySelector(".historyFilterBoss");
  const filterPlayerEl = panel.querySelector(".historyFilterPlayer");
  const filterPlayerTrigger = filterPlayerEl?.querySelector(".historyClassDropdownTrigger");
  const filterPlayerLabel = filterPlayerEl?.querySelector(".historyClassDropdownLabel");
  const filterPlayerMenu = filterPlayerEl?.querySelector(".historyClassDropdownMenu");
  const filterDateEl = panel.querySelector(".historyFilterDate");

  // Map from the Korean class name stored in fight records → stable enum key used for i18n
  const JOB_KEY_MAP = {
    "검성": "GLADIATOR",
    "수호성": "TEMPLAR",
    "궁성": "RANGER",
    "살성": "ASSASSIN",
    "마도성": "SORCERER",
    "치유성": "CLERIC",
    "정령성": "ELEMENTALIST",
    "호법성": "CHANTER",
  };

  let showDeleteMode = false;
  let filterBoss = "";
  let filterPlayer = "";
  let filterDate = "";
  let classDropdownOpen = false;

  const syncDeleteToggle = () => {
    if (!deleteToggleBtn) return;
    deleteToggleBtn.classList.toggle("active", showDeleteMode);
    panel.classList.toggle("deleteMode", showDeleteMode);
  };

  const i18n = window.i18n;
  const t = (key, fallback) => i18n?.t?.(key, fallback) ?? fallback;

  const STORAGE_KEY = "historyShowTraining";
  let showTraining = (() => {
    try { return localStorage.getItem(STORAGE_KEY) !== "0"; } catch { return true; }
  })();

  const syncTrainToggle = () => {
    if (!trainToggleBtn) return;
    trainToggleBtn.classList.toggle("active", showTraining);
    trainToggleBtn.title = t(
      showTraining ? "history.hideTrainingBattles" : "history.showTrainingBattles",
      showTraining ? "Hide Training Battles" : "Show Training Battles"
    );
  };

  const formatTime = (ms) => {
    const totalMs = Number(ms);
    if (!Number.isFinite(totalMs) || totalMs <= 0) return "00:00";
    const totalSeconds = Math.floor(totalMs / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  };

  const formatDate = (ms) => {
    const d = new Date(Number(ms));
    if (isNaN(d.getTime())) return "-";
    const pad = (n) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };

  const formatDamage = (v) => {
    const n = Number(v);
    if (!Number.isFinite(n)) return "-";
    if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}m`;
    if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
    return `${Math.round(n)}`;
  };

  const getJobLabel = (job) => {
    const key = JOB_KEY_MAP[job];
    if (!key) return job;
    return t(`classes.${key}`, key);
  };

  const setClassDropdownOpen = (open) => {
    classDropdownOpen = open;
    filterPlayerEl?.classList.toggle("open", open);
  };

  const setClassFilter = (job) => {
    filterPlayer = job;
    if (filterPlayerLabel) {
      if (job) {
        const img = `<img src="./assets/${job}.png" alt="" class="historyClassDropdownIcon" onerror="this.style.display='none'">`;
        filterPlayerLabel.innerHTML = `${img}${getJobLabel(job)}`;
      } else {
        filterPlayerLabel.textContent = t("history.filterPlayer", "All classes");
      }
    }
    filterPlayerMenu?.querySelectorAll(".historyClassOption").forEach((opt) => {
      opt.classList.toggle("selected", opt.dataset.job === job);
    });
    setClassDropdownOpen(false);
    renderList(allFights);
  };

  let allFights = [];

  const populateDropdowns = (fights) => {
    if (!filterBossEl || !filterPlayerEl || !filterDateEl) return;
    const allOption = (label) => `<option value="">${label}</option>`;

    const bossNames = [...new Set(fights.map((f) => f.bossName || "").filter(Boolean))].sort();
    filterBossEl.innerHTML = allOption(t("history.filterBoss", "All bosses"));
    bossNames.forEach((name) => {
      const opt = document.createElement("option");
      opt.value = name;
      opt.textContent = name;
      if (name === filterBoss) opt.selected = true;
      filterBossEl.appendChild(opt);
    });

    // Custom class dropdown
    if (filterPlayerMenu) {
      filterPlayerMenu.innerHTML = "";
      const allOpt = document.createElement("div");
      allOpt.className = "historyClassOption" + (filterPlayer === "" ? " selected" : "");
      allOpt.dataset.job = "";
      allOpt.textContent = t("history.filterPlayer", "All classes");
      allOpt.addEventListener("click", () => setClassFilter(""));
      filterPlayerMenu.appendChild(allOpt);

      const jobs = [...new Set(fights.flatMap((f) => Array.isArray(f.jobs) ? f.jobs : []).filter(Boolean))].sort(
        (a, b) => getJobLabel(a).localeCompare(getJobLabel(b))
      );
      jobs.forEach((job) => {
        const opt = document.createElement("div");
        opt.className = "historyClassOption" + (job === filterPlayer ? " selected" : "");
        opt.dataset.job = job;
        const img = document.createElement("img");
        img.src = `./assets/${job}.png`;
        img.alt = "";
        img.className = "historyClassDropdownIcon";
        img.onerror = () => { img.style.display = "none"; };
        opt.appendChild(img);
        opt.appendChild(document.createTextNode(getJobLabel(job)));
        opt.addEventListener("click", () => setClassFilter(job));
        filterPlayerMenu.appendChild(opt);
      });

      // Sync label
      if (!filterPlayer) {
        if (filterPlayerLabel) filterPlayerLabel.textContent = t("history.filterPlayer", "All classes");
      }
    }

    const dates = [...new Set(fights.map((f) => formatDate(f.startTimeMs).slice(0, 10)).filter((d) => d !== "-"))].sort().reverse();
    filterDateEl.innerHTML = allOption(t("history.filterDate", "All dates"));
    dates.forEach((date) => {
      const opt = document.createElement("option");
      opt.value = date;
      opt.textContent = date;
      if (date === filterDate) opt.selected = true;
      filterDateEl.appendChild(opt);
    });
  };

  const applyFilters = (fights) => {
    return fights.filter((f) => {
      if (f.isTrain && !showTraining) return false;
      if (filterBoss && (f.bossName || "") !== filterBoss) return false;
      if (filterPlayer) {
        const jobs = Array.isArray(f.jobs) ? f.jobs : [];
        if (!jobs.includes(filterPlayer)) return false;
      }
      if (filterDate && formatDate(f.startTimeMs).slice(0, 10) !== filterDate) return false;
      return true;
    });
  };

  const renderList = (fights) => {
    if (!listEl) return;
    listEl.innerHTML = "";

    const visible = applyFilters(fights);

    if (!visible || visible.length === 0) {
      if (emptyEl) emptyEl.style.display = "";
      return;
    }
    if (emptyEl) emptyEl.style.display = "none";

    visible.forEach((fight) => {
      const row = document.createElement("div");
      row.className = "historyRow";
      row.dataset.fightId = fight.id;

      const infoEl = document.createElement("div");
      infoEl.className = "historyRowInfo";

      const nameEl = document.createElement("div");
      nameEl.className = "historyRowName";
      nameEl.textContent = fight.bossName || `Boss #${fight.targetId}`;
      if (fight.isLive) {
        const badge = document.createElement("span");
        badge.className = "historyLiveBadge";
        badge.textContent = t("history.liveBadge", "Live");
        nameEl.appendChild(badge);
      }
      if (fight.isTrain) {
        const badge = document.createElement("span");
        badge.className = "historyTrainBadge";
        badge.textContent = t("history.trainBadge", "Training");
        nameEl.appendChild(badge);
      }

      const metaEl = document.createElement("div");
      metaEl.className = "historyRowMeta";

      const timeEl = document.createElement("span");
      timeEl.className = "historyRowTime";
      timeEl.textContent = formatDate(fight.startTimeMs);

      const durEl = document.createElement("span");
      durEl.className = "historyRowDuration";
      durEl.textContent = formatTime(fight.durationMs);

      const dmgEl = document.createElement("span");
      dmgEl.className = "historyRowDamage";
      dmgEl.textContent = formatDamage(fight.totalDamage);

      metaEl.appendChild(timeEl);
      metaEl.appendChild(durEl);
      metaEl.appendChild(dmgEl);

      const iconsEl = document.createElement("div");
      iconsEl.className = "historyRowIcons";
      const allJobs = (Array.isArray(fight.jobs) ? fight.jobs : []).slice(0, 12);
      allJobs.forEach((job) => {
        if (!job) return;
        const wrap = document.createElement("span");
        wrap.className = "historyIconWrap";
        wrap.setAttribute("data-tip", getJobLabel(job));
        const img = document.createElement("img");
        img.src = `./assets/${job}.png`;
        img.alt = job;
        img.className = "historyRowClassIcon";
        img.onerror = () => { wrap.style.display = "none"; };
        wrap.appendChild(img);
        iconsEl.appendChild(wrap);
      });

      infoEl.appendChild(nameEl);
      infoEl.appendChild(metaEl);

      const actionsEl = document.createElement("div");
      actionsEl.className = "historyRowActions";

      if (!fight.isLive) {
        const deleteBtn = document.createElement("button");
        deleteBtn.className = "historyDeleteBtn";
        deleteBtn.type = "button";
        deleteBtn.setAttribute("aria-label", t("history.delete", "Delete"));
        deleteBtn.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="15" height="15"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/><path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/></svg>`;
        deleteBtn.addEventListener("click", (e) => {
          e.stopPropagation();
          if (window.javaBridge?.deleteFight?.(fight.id)) {
            row.remove();
            if (!listEl.querySelector(".historyRow")) {
              if (emptyEl) emptyEl.style.display = "";
            }
          }
        });
        actionsEl.appendChild(deleteBtn);
      }

      row.appendChild(infoEl);
      row.appendChild(iconsEl);
      row.appendChild(actionsEl);

      row.addEventListener("click", () => {
        const rawRecord = window.javaBridge?.getFightDetails?.(fight.id);
        if (!rawRecord) return;
        let record;
        try {
          record = typeof rawRecord === "string" ? JSON.parse(rawRecord) : rawRecord;
        } catch {
          return;
        }
        onOpenFight?.(record);
      });

      listEl.appendChild(row);
    });
  };

  const open = () => {
    panel.classList.add("open");
    syncTrainToggle();
    const raw = window.javaBridge?.getFightHistory?.();
    try {
      allFights = typeof raw === "string" ? JSON.parse(raw) : (Array.isArray(raw) ? raw : []);
    } catch {
      allFights = [];
    }
    populateDropdowns(allFights);
    renderList(allFights);
  };

  filterBossEl?.addEventListener("change", () => {
    filterBoss = filterBossEl.value;
    renderList(allFights);
  });
  filterPlayerTrigger?.addEventListener("click", () => {
    setClassDropdownOpen(!classDropdownOpen);
  });
  document.addEventListener("click", (e) => {
    if (classDropdownOpen && filterPlayerEl && !filterPlayerEl.contains(e.target)) {
      setClassDropdownOpen(false);
    }
  });
  filterDateEl?.addEventListener("change", () => {
    filterDate = filterDateEl.value;
    renderList(allFights);
  });

  trainToggleBtn?.addEventListener("click", () => {
    showTraining = !showTraining;
    try { localStorage.setItem(STORAGE_KEY, showTraining ? "1" : "0"); } catch {}
    syncTrainToggle();
    renderList(allFights);
  });

  deleteToggleBtn?.addEventListener("click", () => {
    showDeleteMode = !showDeleteMode;
    syncDeleteToggle();
  });

  const close = () => {
    panel.classList.remove("open");
    showDeleteMode = false;
    syncDeleteToggle();
    filterBoss = "";
    filterPlayer = "";
    filterDate = "";
    if (filterBossEl) filterBossEl.selectedIndex = 0;
    if (filterPlayerLabel) filterPlayerLabel.textContent = t("history.filterPlayer", "All classes");
    setClassDropdownOpen(false);
    if (filterDateEl) filterDateEl.selectedIndex = 0;
  };

  const isOpen = () => panel.classList.contains("open");

  closeBtn?.addEventListener("click", close);

  return { open, close, isOpen };
};
