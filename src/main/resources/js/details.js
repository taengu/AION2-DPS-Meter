const createDetailsUI = ({
  detailsPanel,
  detailsClose,
  detailsBackBtn,
  detailsFightTitleEl,
  detailsPartyListEl,
  detailsStatsEl,
  skillsListEl,
  dpsFormatter,
  getDetails,
  getDetailsContext,
  onPinnedRowChange,
  onBack,
}) => {
  let openedRowId = null;
  let pinnedRowId = null;
  let openSeq = 0;
  let lastRow = null;
  let lastDetails = null;
  let detailsContext = null;
  let detailsActors = new Map();
  let detailsTargets = [];
  let selectedTargetId = null;
  let selectedAttackerIds = null;
  let selectedAttackerLabel = "";
  let sortMode = "recent";
  let detectedJobByActorId = new Map();
  let skillSortKey = "dmg";
  let skillSortDir = "desc";
  let activeCompactMode = false;
  let historyRecord = null;
  let fightStartMs = 0;
  let fightBossName = "";
  let lastUnfilteredDetails = null;
  const detailsCacheByRowId = new Map();
  const COMPACT_MAX_SKILLS = 5;
  const cjkRegex = /[\u3400-\u9FFF\uF900-\uFAFF]/;

  const clamp01 = (v) => Math.max(0, Math.min(1, v));

  const formatNum = (v) => {
    const n = Number(v);
    if (!Number.isFinite(n)) return "-";
    return dpsFormatter.format(n);
  };
  const formatCount = (v) => {
    const n = Number(v);
    if (!Number.isFinite(n)) return "-";
    if (Math.abs(n) < 1000) {
      return `${Math.round(n)}`;
    }
    return dpsFormatter.format(n);
  };
  const pctText = (v) => {
    const n = Number(v);
    return Number.isFinite(n) ? `${n.toFixed(1)}%` : "-";
  };
  const formatCompactNumber = (v) => {
    const n = Number(v);
    if (!Number.isFinite(n)) return "-";
    if (n >= 1_000_000) {
      return `${(n / 1_000_000).toFixed(2)}m`;
    }
    if (n >= 1_000) {
      return `${(n / 1_000).toFixed(1)}k`;
    }
    return `${Math.round(n)}`;
  };
  const formatDamageCompact = (v) => {
    const n = Number(v);
    if (!Number.isFinite(n)) return "-";
    const abs = Math.abs(n);
    if (abs >= 1_000_000) {
      return `${(n / 1_000_000).toFixed(2)}m`;
    }
    if (abs >= 1_000) {
      return `${(n / 1_000).toFixed(2)}k`;
    }
    return `${Math.round(n)}`;
  };
  const formatMinutesSince = (timestampMs) => {
    const ts = Number(timestampMs);
    if (!Number.isFinite(ts) || ts <= 0) return "-";
    const minutes = (Date.now() - ts) / 60000;
    if (!Number.isFinite(minutes) || minutes < 0) return "-";
    return `${minutes.toFixed(1)}m`;
  };
  const formatBattleTime = (ms) => {
    const totalMs = Number(ms);
    if (!Number.isFinite(totalMs) || totalMs <= 0) return "00:00";
    const totalSeconds = Math.floor(totalMs / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  };
  const i18n = window.i18n;
  const labelText = (key, fallback) => i18n?.t?.(key, fallback) ?? fallback;

  const MONTHS_SHORT = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"];
  const formatFightDateTime = (ms) => {
    const d = new Date(Number(ms));
    if (isNaN(d.getTime())) return "";
    const yr = d.getFullYear();
    const mo = String(d.getMonth() + 1).padStart(2, "0");
    const day = String(d.getDate()).padStart(2, "0");
    const h24 = d.getHours();
    const mm = String(d.getMinutes()).padStart(2, "0");
    const lang = i18n?.lang ?? "en";
    if (lang === "ko") {
      const period = h24 < 12 ? "\uC624\uC804" : "\uC624\uD6C4"; // 오전/오후
      const h12 = h24 % 12 || 12;
      return `${yr}-${mo}-${day} @ ${period} ${h12}:${mm}`;
    }
    if (lang === "zh-Hans" || lang === "zh-Hant") {
      const period = h24 < 12 ? "\u4E0A\u5348" : "\u4E0B\u5348"; // 上午/下午
      const h12 = h24 % 12 || 12;
      return `${yr}-${mo}-${day} @ ${period} ${h12}:${mm}`;
    }
    const period = h24 < 12 ? "AM" : "PM";
    const h12 = h24 % 12 || 12;
    return `${yr}-${mo}-${day} @ ${h12}:${mm} ${period}`;
  };

  const renderFightTitle = () => {
    if (!detailsFightTitleEl) return;
    const target = getTargetById(selectedTargetId) || detailsTargets[0];
    const bossName = fightBossName || (target ? getTargetLabel(target) : "");
    const dateStr = fightStartMs > 0 ? formatFightDateTime(fightStartMs) : "";
    if (!bossName) { detailsFightTitleEl.innerHTML = ""; return; }
    const fightVs = labelText("details.fightVs", "Fight vs");
    const suffix = dateStr ? ` - ${dateStr}` : "";
    detailsFightTitleEl.innerHTML = `${fightVs} <span class="fightTitleBossName">${bossName}</span>${suffix}`;
  };

  const STATUS = [
    {
      key: "details.stats.totalDamage",
      fallback: "Total Damage",
      getValue: (d) => formatDamageCompact(d?.totalDmg),
    },
    { key: "details.stats.contribution", fallback: "Contribution", getValue: (d) => pctText(d?.contributionPct) },
    { key: "details.stats.combatTime", fallback: "Combat Time", getValue: (d) => d?.combatTime ?? "-" },
    { key: "details.skills.hits", fallback: "Hits", getValue: (d) => formatCount(d?.totalHits) },
    { key: "details.stats.multiHitHits", fallback: "Multi-Hits", getValue: (d) => pctText(d?.multiHitPct) },
    {
      key: "details.stats.multiHitDamage",
      fallback: "Multi-Hit Damage",
      getValue: (d) => formatDamageCompact(d?.multiHitDamage),
    },
    { key: "details.stats.critRate", fallback: "Crit Rate", getValue: (d) => pctText(d?.totalCritPct) },
    { key: "details.stats.perfectRate", fallback: "Perfect Rate", getValue: (d) => pctText(d?.totalPerfectPct) },
    { key: "details.stats.doubleRate", fallback: "Double Rate", getValue: (d) => pctText(d?.totalDoublePct) },
    { key: "details.stats.parryRate", fallback: "Parry Rate", getValue: (d) => pctText(d?.totalParryPct) },
    { key: "details.stats.empty", fallback: "", getValue: () => "", isPlaceholder: true },
  ];

  const createStatView = (labelKey, fallbackLabel, { isPlaceholder = false } = {}) => {
    const statEl = document.createElement("div");
    statEl.className = "stat";
    if (isPlaceholder) {
      statEl.classList.add("statEmpty");
    }

    const labelEl = document.createElement("p");
    labelEl.className = "label";
    labelEl.textContent = isPlaceholder ? "" : labelText(labelKey, fallbackLabel);

    const valueEl = document.createElement("p");
    valueEl.className = "value";
    valueEl.textContent = isPlaceholder ? "" : "-";

    statEl.appendChild(labelEl);
    statEl.appendChild(valueEl);

    return { statEl, labelEl, valueEl, labelKey, fallbackLabel };
  };

  const statSlots = STATUS.map((def) =>
    createStatView(def.key, def.fallback, { isPlaceholder: def.isPlaceholder })
  );
  statSlots.forEach((value) => detailsStatsEl.appendChild(value.statEl));

  const getTargetById = (targetId) =>
    detailsTargets.find((target) => Number(target?.targetId) === Number(targetId));

  const getActorDamage = (actorDamage, actorId) => {
    if (!actorDamage || typeof actorDamage !== "object") return 0;
    const byNumber = actorDamage[actorId];
    const byString = actorDamage[String(actorId)];
    return Number(byNumber ?? byString ?? 0) || 0;
  };

  const getTargetLabel = (target) => {
    if (!target) return "";
    const targetId = Number(target?.targetId);
    const targetName = typeof target.targetName === "string" ? target.targetName.trim() : "";
    const localizedName = Number.isFinite(targetId) && targetId > 0
      ? (i18n?.getNpcName?.(targetId, targetName) ?? targetName)
      : targetName;
    return localizedName || `Mob #${target.targetId}`;
  };

  const getTargetDamageForSelection = (target) => {
    if (!target) return 0;
    if (!Array.isArray(selectedAttackerIds) || selectedAttackerIds.length === 0) {
      return Number(target.totalDamage) || 0;
    }
    return selectedAttackerIds.reduce(
      (sum, actorId) => sum + getActorDamage(target.actorDamage, actorId),
      0
    );
  };

  const formatTargetSuffix = (target) => {
    if (!target) return "";
    if (sortMode === "recent") {
      return "";
    }
    if (sortMode === "time") {
      return formatBattleTime(target.battleTime);
    }
    return formatCompactNumber(getTargetDamageForSelection(target));
  };

  const jobColorMap = {
    정령성: "#E06BFF",
    Spiritmaster: "#E06BFF",
    궁성: "#41D98A",
    Ranger: "#41D98A",
    살성: "#7BE35A",
    Assassin: "#7BE35A",
    수호성: "#5F8CFF",
    Templar: "#5F8CFF",
    마도성: "#9A6BFF",
    Sorcerer: "#9A6BFF",
    호법성: "#FF9A3D",
    Chanter: "#FF9A3D",
    치유성: "#F2C15A",
    Cleric: "#F2C15A",
    검성: "#4FD1C5",
    Gladiator: "#4FD1C5",
  };

  const getJobColor = (job) => jobColorMap[job] || "";

  const getActorJob = (actorId) => {
    const numericId = Number(actorId);
    if (!Number.isFinite(numericId) || numericId <= 0) return "";
    const contextJob = detailsActors.get(numericId)?.job;
    if (contextJob) return contextJob;
    const detectedJob = detectedJobByActorId.get(numericId);
    if (detectedJob) return detectedJob;
    if (Number(lastRow?.id) === numericId) {
      return String(lastRow?.job || "");
    }
    return "";
  };

  const rememberJobsFromDetails = (details) => {
    if (!details || typeof details !== "object") return;
    const actorStats = Array.isArray(details.perActorStats) ? details.perActorStats : [];
    actorStats.forEach((entry) => {
      const actorId = Number(entry?.actorId);
      const job = String(entry?.job || "").trim();
      if (Number.isFinite(actorId) && actorId > 0 && job) {
        detectedJobByActorId.set(actorId, job);
      }
    });
    const skills = Array.isArray(details.skills) ? details.skills : [];
    skills.forEach((skill) => {
      const actorId = Number(skill?.actorId);
      const job = String(skill?.job || "").trim();
      if (Number.isFinite(actorId) && actorId > 0 && job) {
        detectedJobByActorId.set(actorId, job);
      }
    });
  };

  const updateHeaderText = () => {
    renderFightTitle();
  };

  const updateLabels = () => {
    for (let i = 0; i < statSlots.length; i++) {
      const slot = statSlots[i];
      if (slot.labelKey === "details.stats.empty") {
        slot.labelEl.textContent = "";
      } else {
        slot.labelEl.textContent = labelText(slot.labelKey, slot.fallbackLabel);
      }
    }
    updateHeaderText();
    updateGridColumns();
  };

  const resolveStatValue = (statKey, data) => {
    if (!data) return "-";
    switch (statKey) {
      case "details.stats.totalDamage":
        return formatDamageCompact(data.totalDmg);
      case "details.stats.maxHp":
        return data.maxHp > 0 ? formatDamageCompact(data.maxHp) : "-";
      case "details.stats.hits":
      case "details.skills.hits":
        return formatCount(data.totalHits);
      case "details.stats.multiHitDamage":
        return formatDamageCompact(data.multiHitDamage);
      case "details.stats.contribution":
        return pctText(data.contributionPct);
      case "details.stats.critRate":
        return pctText(data.totalCritPct);
      case "details.stats.perfectRate":
        return pctText(data.totalPerfectPct);
      case "details.stats.doubleRate":
        return pctText(data.totalDoublePct);
      case "details.stats.backRate":
        return pctText(data.totalBackPct);
      case "details.stats.parryRate":
        return pctText(data.totalParryPct);
      case "details.stats.empty":
        return "";
      case "details.stats.combatTime":
        return data.combatTime ?? "-";
      default:
        return STATUS.find((stat) => stat.key === statKey)?.getValue(data) ?? "-";
    }
  };

  const COMPACT_STAT_KEYS = new Set([
    "details.stats.totalDamage",
    "details.stats.contribution",
    "details.stats.combatTime",
    "details.skills.hits",
    "details.stats.critRate",
  ]);

  const renderStats = (details, { compact = false } = {}) => {
    const hasPlayerSelected = Array.isArray(selectedAttackerIds) && selectedAttackerIds.length > 0;
    const showSplit = hasPlayerSelected && !compact && lastUnfilteredDetails && lastUnfilteredDetails !== details;
    // Resolve the selected player's class colour for the X value
    const playerColor = showSplit && selectedAttackerIds.length === 1
      ? getJobColor(getActorJob(selectedAttackerIds[0]))
      : "";

    for (let i = 0; i < STATUS.length; i++) {
      const slot = statSlots[i];
      const statKey = STATUS[i].key;
      const shouldShow = !compact || COMPACT_STAT_KEYS.has(statKey);
      slot.statEl.style.display = shouldShow ? "" : "none";
      if (!shouldShow) continue;

      slot.valueEl.innerHTML = "";
      slot.valueEl.style.display = "";
      slot.valueEl.style.flexWrap = "";
      slot.valueEl.style.gap = "";
      slot.valueEl.style.justifyContent = "";
      slot.valueEl.style.alignItems = "";

      if (showSplit && statKey !== "details.stats.empty") {
        const playerVal = resolveStatValue(statKey, details);
        const totalVal = resolveStatValue(statKey, lastUnfilteredDetails);
        slot.valueEl.style.display = "flex";
        slot.valueEl.style.gap = "3px";
        slot.valueEl.style.justifyContent = "flex-end";
        slot.valueEl.style.alignItems = "baseline";

        const playerSpan = document.createElement("span");
        playerSpan.textContent = playerVal;
        playerSpan.style.fontWeight = "400";
        if (playerColor) playerSpan.style.color = playerColor;

        const sepSpan = document.createElement("span");
        sepSpan.textContent = " / ";
        sepSpan.style.fontWeight = "400";
        sepSpan.style.opacity = "0.5";

        const totalSpan = document.createElement("span");
        totalSpan.textContent = totalVal;
        totalSpan.style.fontWeight = "700";

        slot.valueEl.appendChild(playerSpan);
        slot.valueEl.appendChild(sepSpan);
        slot.valueEl.appendChild(totalSpan);
      } else {
        slot.valueEl.textContent = STATUS[i].getValue(details);
      }
    }
  };

  // Build party bar stats from detailsContext actorDamage (live fights).
  // Falls back to null if context has no actorDamage data (history fights use details.perActorStats).
  const buildPartyBarStats = () => {
    const targets = selectedTargetId
      ? (getTargetById(selectedTargetId) ? [getTargetById(selectedTargetId)] : getSelectableTargets())
      : getSelectableTargets();
    const combined = new Map();
    targets.forEach((target) => {
      const actorDmg = target?.actorDamage;
      if (!actorDmg || typeof actorDmg !== "object") return;
      Object.entries(actorDmg).forEach(([id, dmg]) => {
        const actorId = Number(id);
        if (!Number.isFinite(actorId) || actorId <= 0) return;
        combined.set(actorId, (combined.get(actorId) || 0) + (Number(dmg) || 0));
      });
    });
    if (combined.size === 0) return null;
    const total = [...combined.values()].reduce((s, v) => s + v, 0) || 1;
    return [...combined.entries()]
      .map(([actorId, dmg]) => ({
        actorId,
        job: detectedJobByActorId.get(actorId) || detailsActors.get(actorId)?.job || "",
        totalDmg: dmg,
        contributionPct: (dmg / total) * 100,
      }))
      .sort((a, b) => b.totalDmg - a.totalDmg);
  };

  const renderPartyBars = (stats) => {
    if (!detailsPartyListEl) return;
    detailsPartyListEl.innerHTML = "";
    const actors = Array.isArray(stats) ? stats : [];
    if (actors.length === 0) return;

    const topDmg = actors.reduce((m, a) => Math.max(m, Number(a.totalDmg) || 0), 1);

    const rerender = () => renderPartyBars(buildPartyBarStats() || lastDetails?.perActorStats);

    // "All" bar
    const allBar = document.createElement("div");
    allBar.className = "detailsPartyBar" + (!selectedAttackerIds || selectedAttackerIds.length === 0 ? " isSelected" : "");
    allBar.innerHTML = `<div class="detailsPartyBarFill" style="transform:scaleX(1)"></div><div class="detailsPartyBarContent"><span class="detailsPartyBarName">All</span></div>`;
    allBar.addEventListener("click", async () => {
      selectedAttackerIds = null;
      selectedAttackerLabel = labelText("details.all", "All");
      rerender();
      await refreshDetailsView();
    });
    detailsPartyListEl.appendChild(allBar);

    actors.forEach((actor) => {
      const actorId = Number(actor.actorId);
      const job = actor.job || getActorJob(actorId);
      const name = detailsActors.get(actorId)?.nickname || resolveActorLabel(actorId);
      const dmg = Number(actor.totalDmg) || 0;
      const pct = Number(actor.contributionPct) || 0;
      const ratio = topDmg > 0 ? dmg / topDmg : 0;
      const color = getJobColor(job);
      const isSelected = Array.isArray(selectedAttackerIds) && selectedAttackerIds.includes(actorId);

      const bar = document.createElement("div");
      bar.className = "detailsPartyBar" + (isSelected ? " isSelected" : "");

      const fillEl = document.createElement("div");
      fillEl.className = "detailsPartyBarFill";
      fillEl.style.transform = `scaleX(${Math.max(0, Math.min(1, ratio))})`;

      const contentEl = document.createElement("div");
      contentEl.className = "detailsPartyBarContent";

      if (job) {
        const iconEl = document.createElement("img");
        iconEl.className = "detailsPartyBarIcon";
        iconEl.src = `./assets/${job}.png`;
        iconEl.alt = job;
        iconEl.onerror = () => { iconEl.style.display = "none"; };
        contentEl.appendChild(iconEl);
      }

      const nameEl = document.createElement("span");
      nameEl.className = "detailsPartyBarName" + (cjkRegex.test(name) ? " isCjk" : "");
      nameEl.textContent = name;
      if (color) nameEl.style.color = color;

      const dmgEl = document.createElement("span");
      dmgEl.className = "detailsPartyBarDmg";
      dmgEl.textContent = `${formatDamageCompact(dmg)} [${pct.toFixed(1)}%]`;

      contentEl.appendChild(nameEl);
      contentEl.appendChild(dmgEl);
      bar.appendChild(fillEl);
      bar.appendChild(contentEl);

      bar.addEventListener("click", async () => {
        if (isSelected) {
          selectedAttackerIds = null;
          selectedAttackerLabel = labelText("details.all", "All");
        } else {
          selectedAttackerIds = [actorId];
          selectedAttackerLabel = name;
        }
        rerender();
        await refreshDetailsView();
      });

      detailsPartyListEl.appendChild(bar);
    });
  };

  const createSkillView = () => {
    const rowEl = document.createElement("div");
    rowEl.className = "skillRow";

    const rowFillEl = document.createElement("div");
    rowFillEl.className = "rowFill";
    rowEl.appendChild(rowFillEl);

    const nameEl = document.createElement("div");
    nameEl.className = "cell name";

    const iconEl = document.createElement("img");
    iconEl.className = "skillIcon";
    iconEl.alt = "";
    iconEl.loading = "lazy";
    iconEl.decoding = "async";
    iconEl.referrerPolicy = "no-referrer";
    iconEl.addEventListener("error", () => window.skillIcons?.handleImgError?.(iconEl));

    const nameTextEl = document.createElement("span");
    nameTextEl.className = "skillNameText";

    nameEl.appendChild(iconEl);
    nameEl.appendChild(nameTextEl);

    const hitEl = document.createElement("div");
    hitEl.className = "cell center hit";

    const dmgEl = document.createElement("div");
    dmgEl.className = "cell center dmg";

    const dmgPctEl = document.createElement("div");
    dmgPctEl.className = "cell center dmgpct";

    const multiHitEl = document.createElement("div");
    multiHitEl.className = "cell center mhit";

    const multiHitDamageEl = document.createElement("div");
    multiHitDamageEl.className = "cell center mdmg";

    const critEl = document.createElement("div");
    critEl.className = "cell center crit";

    const parryEl = document.createElement("div");
    parryEl.className = "cell center parry";

    const backEl = document.createElement("div");
    backEl.className = "cell center back";

    const perfectEl = document.createElement("div");
    perfectEl.className = "cell center perfect";

    const doubleEl = document.createElement("div");
    doubleEl.className = "cell center double";

    const minDmgEl = document.createElement("div");
    minDmgEl.className = "cell center mindmg";

    const avgDmgEl = document.createElement("div");
    avgDmgEl.className = "cell center avgdmg";

    const maxDmgEl = document.createElement("div");
    maxDmgEl.className = "cell center maxdmg";

    rowEl.appendChild(nameEl);
    rowEl.appendChild(dmgEl);
    rowEl.appendChild(dmgPctEl);
    rowEl.appendChild(multiHitEl);
    rowEl.appendChild(multiHitDamageEl);
    rowEl.appendChild(critEl);
    rowEl.appendChild(parryEl);
    rowEl.appendChild(perfectEl);
    rowEl.appendChild(doubleEl);
    rowEl.appendChild(backEl);
    rowEl.appendChild(hitEl);
    rowEl.appendChild(minDmgEl);
    rowEl.appendChild(avgDmgEl);
    rowEl.appendChild(maxDmgEl);

    return {
      rowEl,
      rowFillEl,
      nameEl,
      iconEl,
      nameTextEl,
      hitEl,
      dmgEl,
      dmgPctEl,
      multiHitEl,
      multiHitDamageEl,
      critEl,
      parryEl,
      backEl,
      perfectEl,
      doubleEl,
      minDmgEl,
      avgDmgEl,
      maxDmgEl,
    };
  };

  const skillSlots = [];
  const ensureSkillSlots = (n) => {
    while (skillSlots.length < n) {
      const v = createSkillView();
      skillSlots.push(v);
      skillsListEl.appendChild(v.rowEl);
    }
  };

  const getSkillSortValue = (skill, key) => {
    const hits = Number(skill?.time) || 0;
    const dmg = Number(skill?.dmg) || 0;
    switch (key) {
      case "name":
        return String(skill?.name || "").toLowerCase();
      case "hit":
        return hits;
      case "mhit": {
        const mhHits = Number(skill?.multiHitHits) || 0;
        return hits > 0 ? mhHits / hits : 0;
      }
      case "mdmg":
        return Number(skill?.multiHitDamage) || 0;
      case "dmgpct":
      case "dmg":
        return dmg;
      case "crit":
        return hits > 0 ? (Number(skill?.crit) || 0) / hits : 0;
      case "parry":
        return hits > 0 ? (Number(skill?.parry) || 0) / hits : 0;
      case "perfect":
        return hits > 0 ? (Number(skill?.perfect) || 0) / hits : 0;
      case "double":
        return hits > 0 ? (Number(skill?.double) || 0) / hits : 0;
      case "back":
        return hits > 0 ? (Number(skill?.back) || 0) / hits : 0;
      case "mindmg":
        return Number(skill?.minDmg) || 0;
      case "avgdmg":
        return hits > 0 ? dmg / hits : 0;
      case "maxdmg":
        return Number(skill?.maxDmg) || 0;
      default:
        return dmg;
    }
  };

  const compareSkillSort = (a, b) => {
    const key = skillSortKey;
    const dir = skillSortDir === "asc" ? 1 : -1;
    const aVal = getSkillSortValue(a, key);
    const bVal = getSkillSortValue(b, key);
    if (key === "name") {
      return aVal.localeCompare(bVal) * dir;
    }
    if (aVal === bVal) {
      return (Number(b?.dmg) || 0) - (Number(a?.dmg) || 0);
    }
    return (aVal - bVal) * dir;
  };

  const updateSkillHeaderSortState = () => {
    const headerCells = detailsPanel?.querySelectorAll?.(".detailsSkills .skillHeader .cell");
    headerCells?.forEach?.((cell) => {
      const key = cell?.dataset?.sortKey;
      if (!key) return;
      const isActive = key === skillSortKey;
      cell.classList.toggle("isSorted", isActive);
      if (isActive) {
        cell.setAttribute("data-sort-dir", skillSortDir);
      } else {
        cell.removeAttribute("data-sort-dir");
      }
    });
  };

  // Column definitions: name → grid track template fragment
  const GRID_COL_DEFS = {
    name: "minmax(90px, 3fr)",
    hit: "minmax(38px, 0.75fr)",
    dmg: "minmax(36px, 1.0fr)",
    dmgpct: "minmax(26px, 0.85fr)",
    mhit: "minmax(20px, 0.6fr)",
    mdmg: "minmax(30px, 0.9fr)",
    crit: "minmax(24px, 0.65fr)",
    parry: "minmax(20px, 0.6fr)",
    perfect: "minmax(22px, 0.65fr)",
    double: "minmax(22px, 0.65fr)",
    back: "minmax(18px, 0.6fr)",
    mindmg: "minmax(28px, 0.8fr)",
    avgdmg: "minmax(28px, 0.8fr)",
    maxdmg: "minmax(28px, 0.8fr)",
  };
  const GRID_COL_ORDER = ["name", "dmg", "dmgpct", "mhit", "mdmg", "crit", "parry", "perfect", "double", "back", "hit", "mindmg", "avgdmg", "maxdmg"];

  let lastMeasuredNameWidth = 0;
  const updateGridColumns = () => {
    if (!detailsPanel) return;
    const skillsContainer = detailsPanel.querySelector(".detailsSkills");
    if (!skillsContainer) return;

    // Measure actual scrollbar width so the header padding matches exactly
    const skillsEl = skillsContainer.querySelector(".skills");
    if (skillsEl) {
      const scrollbarW = skillsEl.offsetWidth - skillsEl.clientWidth;
      skillsContainer.style.setProperty("--scrollbar-w", `${scrollbarW}px`);
    }

    const visibleCols = GRID_COL_ORDER.filter((col) => !detailsPanel.classList.contains(`hide-col-${col}`));
    if (lastMeasuredNameWidth > 0) {
      const dataCols = visibleCols.filter((c) => c !== "name");
      const template = `${lastMeasuredNameWidth}px ${dataCols.map((col) => GRID_COL_DEFS[col]).join(" ")}`;
      skillsContainer.style.setProperty("--skill-grid-cols", template);
    } else {
      const cols = visibleCols.map((col) => GRID_COL_DEFS[col]);
      skillsContainer.style.setProperty("--skill-grid-cols", cols.join(" "));
    }
  };


  const bindSkillHeaderSorting = () => {
    const headerCells = detailsPanel?.querySelectorAll?.(".detailsSkills .skillHeader .cell[data-sort-key]");
    headerCells?.forEach?.((cell) => {
      cell.addEventListener("click", () => {
        const key = cell?.dataset?.sortKey;
        if (!key) return;
        if (skillSortKey === key) {
          skillSortDir = skillSortDir === "asc" ? "desc" : "asc";
        } else {
          skillSortKey = key;
          skillSortDir = key === "name" ? "asc" : "desc";
        }
        updateSkillHeaderSortState();
        if (lastDetails) {
          renderSkills(lastDetails, { compact: activeCompactMode });
        }
      });
    });
    updateSkillHeaderSortState();
  };

  bindSkillHeaderSorting();
  updateGridColumns();

  const renderSkills = (details, { compact = false } = {}) => {
    const skills = Array.isArray(details?.skills) ? details.skills : [];
    const groupedSkills = new Map();
    skills.forEach((skill) => {
      if (!skill) return;
      const name = String(skill.name ?? "");
      const key = `${name}::${skill.isDot ? "dot" : "hit"}`;
      const existing = groupedSkills.get(key);
      if (!existing) {
        groupedSkills.set(key, { ...skill });
        return;
      }
      const nextActorId = Number(existing.actorId);
      const skillActorId = Number(skill.actorId);
      const resolvedActorId =
        Number.isFinite(nextActorId) && Number.isFinite(skillActorId) && nextActorId === skillActorId
          ? nextActorId
          : null;
      const existMinDmg = Number(existing.minDmg) || 0;
      const skillMinDmg = Number(skill.minDmg) || 0;
      const mergedMin = existMinDmg > 0 && skillMinDmg > 0
        ? Math.min(existMinDmg, skillMinDmg)
        : existMinDmg || skillMinDmg;
      groupedSkills.set(key, {
        ...existing,
        actorId: resolvedActorId,
        job: existing.job || skill.job || "",
        time: (Number(existing.time) || 0) + (Number(skill.time) || 0),
        dmg: (Number(existing.dmg) || 0) + (Number(skill.dmg) || 0),
        crit: (Number(existing.crit) || 0) + (Number(skill.crit) || 0),
        parry: (Number(existing.parry) || 0) + (Number(skill.parry) || 0),
        back: (Number(existing.back) || 0) + (Number(skill.back) || 0),
        perfect: (Number(existing.perfect) || 0) + (Number(skill.perfect) || 0),
        double: (Number(existing.double) || 0) + (Number(skill.double) || 0),
        heal: (Number(existing.heal) || 0) + (Number(skill.heal) || 0),
        multiHitCount: (Number(existing.multiHitCount) || 0) + (Number(skill.multiHitCount) || 0),
        multiHitDamage: (Number(existing.multiHitDamage) || 0) + (Number(skill.multiHitDamage) || 0),
        multiHitHits: (Number(existing.multiHitHits) || 0) + (Number(skill.multiHitHits) || 0),
        minDmg: mergedMin,
        maxDmg: Math.max((Number(existing.maxDmg) || 0), (Number(skill.maxDmg) || 0)),
      });
    });
    const sortedSkills = [...groupedSkills.values()].sort(compareSkillSort);
    const topSkills = compact ? sortedSkills.slice(0, COMPACT_MAX_SKILLS) : sortedSkills;

    const totalDamage = Number(details?.totalDmg);
    const aggregatedDamage = topSkills.reduce((sum, skill) => sum + (Number(skill?.dmg) || 0), 0);
    const percentBaseTotal = totalDamage > 0 ? totalDamage : aggregatedDamage;

    ensureSkillSlots(topSkills.length);

    // Measure longest skill name and set name column width
    const iconSize = parseInt(getComputedStyle(document.documentElement).getPropertyValue("--details-skill-icon-size") || "36", 10);
    const iconMargin = 6;
    const namePad = 4;
    const measureCanvas = document.createElement("canvas").getContext("2d");
    const fontSize = parseFloat(getComputedStyle(skillsListEl).fontSize) * 0.88;
    measureCanvas.font = `700 ${fontSize}px ${getComputedStyle(skillsListEl).fontFamily}`;
    let maxNameWidth = 0;
    topSkills.forEach((skill) => {
      const w = measureCanvas.measureText(skill?.name ?? "").width;
      if (w > maxNameWidth) maxNameWidth = w;
    });
    lastMeasuredNameWidth = Math.ceil(iconSize + iconMargin + maxNameWidth + namePad);
    updateGridColumns();

    for (let i = 0; i < skillSlots.length; i++) {
      const view = skillSlots[i];
      const skill = topSkills[i];

      if (!skill) {
        view.rowEl.style.display = "none";
        view.rowFillEl.style.transform = "scaleX(0)";
        if (view.iconEl) view.iconEl.style.display = "none";
        continue;
      }

      view.rowEl.style.display = "";

      const damage = skill.dmg || 0;
      const barFillRatio = clamp01(damage / percentBaseTotal);
      const hits = skill.time || 0;
      const crits = skill.crit || 0;
      const parry = skill.parry || 0;
      const perfect = skill.perfect || 0;
      const double = skill.double || 0;
      const back = skill.back || 0;
      const multiHitHits = skill.multiHitHits || 0;
      const multiHitDamage = skill.multiHitDamage || 0;
      const rawMinDmg = Number(skill.minDmg) || 0;
      const minDmg = rawMinDmg >= 2147483647 ? 0 : rawMinDmg;
      const maxDmg = skill.maxDmg || 0;
      const avgDmg = hits > 0 ? Math.round(damage / hits) : 0;

      const pct = (num, den) => (den > 0 ? Math.round((num / den) * 100) : 0);

      const damageRate = percentBaseTotal > 0 ? (damage / percentBaseTotal) * 100 : 0;

      const critRate = pct(crits, hits);
      const parryRate = pct(parry, hits);
      const backRate = pct(back, hits);
      const perfectRate = pct(perfect, hits);
      const doubleRate = pct(double, hits);
      const multiHitRate = pct(multiHitHits, hits);

      view.nameTextEl.textContent = skill.name ?? "";
      const resolvedJob = skill.job || getActorJob(skill.actorId);
      window.skillIcons?.applyIconToImage?.(view.iconEl, { ...skill, job: resolvedJob });
      const theostoneNameColor = window.skillIcons?.getTheostoneNameColor?.(skill) || "";
      const skillColor = theostoneNameColor || (resolvedJob ? getJobColor(resolvedJob) : "");
      view.nameTextEl.style.color = skillColor || "";
      view.hitEl.textContent = `${hits}`;
      view.dmgEl.textContent = `${formatDamageCompact(damage)}`;
      view.dmgPctEl.textContent = `${damageRate.toFixed(1)}%`;
      view.critEl.textContent = `${critRate}%`;
      view.parryEl.textContent = `${parryRate}%`;
      view.backEl.textContent = `${backRate}%`;
      view.perfectEl.textContent = `${perfectRate}%`;
      view.doubleEl.textContent = `${doubleRate}%`;
      view.multiHitEl.textContent = `${multiHitRate}%`;
      view.multiHitDamageEl.textContent = `${formatDamageCompact(multiHitDamage)}`;
      view.minDmgEl.textContent = `${formatDamageCompact(minDmg)}`;
      view.avgDmgEl.textContent = `${formatDamageCompact(avgDmg)}`;
      view.maxDmgEl.textContent = `${formatDamageCompact(maxDmg)}`;

      view.rowFillEl.style.transform = `scaleX(${barFillRatio})`;
    }
  };

  // ── Collapsible section toggle ──
  const sectionHeaders = detailsPanel?.querySelectorAll?.(".detailsSectionHeader");
  sectionHeaders?.forEach?.((header) => {
    header.addEventListener("click", () => {
      const section = header.closest(".detailsSection");
      if (section) {
        section.classList.toggle("isExpanded");
        // Re-render charts when sections are expanded (canvas needs non-zero size)
        if (section.classList.contains("isExpanded") && lastDetails) {
          if (section.classList.contains("dpsChartSection")) {
            requestAnimationFrame(() => renderDpsChart(lastDetails));
          } else if (section.classList.contains("timelineSection")) {
            requestAnimationFrame(() => renderTimeline(lastDetails));
          }
        }
      }
    });
  });

  // ── DPS Chart ──
  const dpsChartCanvas = detailsPanel?.querySelector?.(".dpsChartCanvas");
  const dpsChartXAxis = detailsPanel?.querySelector?.(".dpsChartXAxis");
  const dpsChartLegend = detailsPanel?.querySelector?.(".dpsChartLegend");

  // ── Timeline chart ──
  const timelineCanvas = detailsPanel?.querySelector?.(".timelineCanvas");
  const timelineViewport = detailsPanel?.querySelector?.(".timelineViewport");
  const timelineLegend = detailsPanel?.querySelector?.(".timelineLegend");
  const timelineXAxis = detailsPanel?.querySelector?.(".timelineXAxis");
  const timelineSection = detailsPanel?.querySelector?.(".timelineSection");

  // Color palette for skill lanes
  const LANE_COLORS = [
    "#6aa6ff", "#ff7eb3", "#7be35a", "#ffc658", "#b580ff",
    "#5fcaff", "#ff6f4f", "#41d98a", "#e06bff", "#f2b94f",
    "#8fd3ff", "#ff4f7a", "#ffe062", "#7b6dff", "#52b35c",
  ];

  const timelineIconCache = new Map(); // skill key → { img, ready }
  let timelineRedrawScheduled = false;

  const scheduleTimelineRedraw = () => {
    if (timelineRedrawScheduled) return;
    timelineRedrawScheduled = true;
    requestAnimationFrame(() => {
      timelineRedrawScheduled = false;
      if (lastDetails) renderTimeline(lastDetails);
    });
  };

  const loadTimelineIcon = (skill) => {
    const key = `${skill.code}::${skill.isDot ? "dot" : "hit"}`;
    if (timelineIconCache.has(key)) return timelineIconCache.get(key);

    const candidates = window.skillIcons?.getIconCandidates?.(skill) || [];
    if (!candidates.length) {
      timelineIconCache.set(key, null);
      return null;
    }

    const entry = { img: new Image(), ready: false };
    entry.img.crossOrigin = "anonymous";

    const tryLoad = (idx) => {
      if (idx >= candidates.length) return;
      const url = candidates[idx];
      // For data: URIs, load directly
      if (!url.startsWith("http")) {
        entry.img.src = url;
        entry.img.onload = () => { entry.ready = true; scheduleTimelineRedraw(); };
        return;
      }
      // Use skillIcons blob cache (fetchAsBlob) if available, else fetch directly
      const fetchPromise = window.skillIcons?._fetchAsBlob
        ? window.skillIcons._fetchAsBlob(url)
        : fetch(url, { mode: "cors", credentials: "omit" })
            .then((r) => { if (!r.ok) throw new Error(r.status); return r.blob(); })
            .then((blob) => URL.createObjectURL(blob));
      fetchPromise
        .then((blobUrl) => {
          if (!blobUrl) throw new Error("no blob");
          entry.img.src = blobUrl;
          entry.img.onload = () => { entry.ready = true; scheduleTimelineRedraw(); };
        })
        .catch(() => tryLoad(idx + 1));
    };
    tryLoad(0);
    timelineIconCache.set(key, entry);
    return entry;
  };

  const renderDpsChart = (details) => {
    if (!dpsChartCanvas || !dpsChartXAxis || !dpsChartLegend) return;

    // Use unfiltered skills for DPS lines + boss health; filtered details for ping + battleTime
    const unfilteredDetails = lastUnfilteredDetails || details;
    const chartSkills = Array.isArray(unfilteredDetails?.skills) ? unfilteredDetails.skills : [];
    const battleTimeMs = Number(unfilteredDetails?.battleTimeMs) || Number(details?.battleTimeMs) || 0;
    if (battleTimeMs <= 0 || chartSkills.length === 0) {
      dpsChartCanvas.width = 0;
      dpsChartCanvas.height = 0;
      dpsChartXAxis.innerHTML = "";
      dpsChartLegend.innerHTML = "";
      return;
    }

    // Group hits by actor (from ALL actors, unfiltered)
    const actorHits = new Map(); // actorId -> [{ts, dmg}]
    const allHits = []; // flat list for boss cumulative damage
    chartSkills.forEach((skill) => {
      if (!skill) return;
      const actorId = Number(skill.actorId);
      if (!Number.isFinite(actorId) || actorId <= 0) return;
      const timestamps = Array.isArray(skill.hitTimestamps) ? skill.hitTimestamps : [];
      const dmg = Number(skill.dmg) || 0;
      const perHitDmg = timestamps.length > 0 ? dmg / timestamps.length : 0;
      if (!actorHits.has(actorId)) actorHits.set(actorId, []);
      const arr = actorHits.get(actorId);
      timestamps.forEach((ts) => {
        const hit = { ts: Number(ts), dmg: perHitDmg };
        arr.push(hit);
        allHits.push(hit);
      });
    });

    if (actorHits.size === 0) {
      dpsChartCanvas.width = 0;
      dpsChartCanvas.height = 0;
      dpsChartXAxis.innerHTML = "";
      dpsChartLegend.innerHTML = "";
      return;
    }

    // Sort hits per actor and all hits by time
    actorHits.forEach((hits) => hits.sort((a, b) => a.ts - b.ts));
    allHits.sort((a, b) => a.ts - b.ts);

    // Build actor list sorted by total damage descending
    const actorList = [...actorHits.entries()]
      .map(([actorId, hits]) => ({
        actorId,
        hits,
        totalDmg: hits.reduce((s, h) => s + h.dmg, 0),
        job: detectedJobByActorId.get(actorId) || detailsActors.get(actorId)?.job || "",
      }))
      .sort((a, b) => b.totalDmg - a.totalDmg);

    // Determine rolling window: ~10% of fight, clamped 5s–15s
    const windowMs = Math.max(5000, Math.min(15000, battleTimeMs * 0.1));

    // Sample DPS at N evenly-spaced time points
    const SAMPLES = 100;
    const sampleDps = (hits, t) => {
      const lo = t - windowMs;
      let total = 0;
      for (const h of hits) {
        if (h.ts > t) break;
        if (h.ts > lo) total += h.dmg;
      }
      return total / (windowMs / 1000);
    };

    // Canvas dimensions
    const CHART_HEIGHT = 120;
    const PAD_TOP = 8;
    const PAD_BOTTOM = 4;
    const PAD_LEFT = 0;
    const PAD_RIGHT = 0;
    const plotHeight = CHART_HEIGHT - PAD_TOP - PAD_BOTTOM;
    const dpr = window.devicePixelRatio || 1;
    const chartWidth = dpsChartCanvas.parentElement
      ? dpsChartCanvas.parentElement.clientWidth
      : 300;

    dpsChartCanvas.width = Math.ceil(chartWidth * dpr);
    dpsChartCanvas.height = Math.ceil(CHART_HEIGHT * dpr);
    dpsChartCanvas.style.width = `${chartWidth}px`;
    dpsChartCanvas.style.height = `${CHART_HEIGHT}px`;

    const ctx = dpsChartCanvas.getContext("2d");
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, chartWidth, CHART_HEIGHT);

    const xAt = (t) => PAD_LEFT + (t / battleTimeMs) * (chartWidth - PAD_LEFT - PAD_RIGHT);

    // Compute DPS samples for all actors
    const allSamples = actorList.map((actor) => {
      const pts = [];
      for (let i = 0; i <= SAMPLES; i++) {
        const t = (i / SAMPLES) * battleTimeMs;
        pts.push(sampleDps(actor.hits, t));
      }
      return { actor, pts };
    });

    const globalMax = allSamples.reduce((mx, { pts }) => Math.max(mx, ...pts), 0) || 1;

    // Determine time grid interval
    const durationSec = battleTimeMs / 1000;
    const gridIntervalSec = durationSec > 300 ? 60 : durationSec > 60 ? 30 : 10;

    // Draw grid lines (horizontal)
    const gridCount = 4;
    ctx.strokeStyle = "rgba(255,255,255,0.06)";
    ctx.lineWidth = 1;
    for (let g = 0; g <= gridCount; g++) {
      const y = Math.round(PAD_TOP + (g / gridCount) * plotHeight) + 0.5;
      ctx.beginPath();
      ctx.moveTo(PAD_LEFT, y);
      ctx.lineTo(chartWidth - PAD_RIGHT, y);
      ctx.stroke();
    }

    // Draw vertical grid lines
    for (let sec = gridIntervalSec; sec < durationSec; sec += gridIntervalSec) {
      const x = Math.round(xAt(sec * 1000)) + 0.5;
      ctx.beginPath();
      ctx.moveTo(x, PAD_TOP);
      ctx.lineTo(x, PAD_TOP + plotHeight);
      ctx.stroke();
    }

    // ── Boss cumulative damage (grey, dashed) ──
    const totalBossDmg = allHits.reduce((s, h) => s + h.dmg, 0) || 1;
    if (allHits.length > 0) {
      ctx.beginPath();
      ctx.strokeStyle = "rgba(220,60,60,0.85)";
      ctx.lineWidth = 1.5;
      ctx.setLineDash([3, 4]);
      let cumDmg = 0;
      let hitIdx = 0;
      for (let i = 0; i <= SAMPLES; i++) {
        const t = (i / SAMPLES) * battleTimeMs;
        while (hitIdx < allHits.length && allHits[hitIdx].ts <= t) {
          cumDmg += allHits[hitIdx].dmg;
          hitIdx++;
        }
        const healthFraction = Math.max(0, 1 - cumDmg / totalBossDmg);
        const x = xAt(t);
        const y = PAD_TOP + (1 - healthFraction) * plotHeight;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.stroke();
      ctx.setLineDash([]);
    }

    // ── Actor DPS lines ──
    const isSelectedActor = (actorId) =>
      !Array.isArray(selectedAttackerIds) || selectedAttackerIds.includes(actorId);

    const drawActor = ({ actor, pts }, selected) => {
      const fallbackColor = LANE_COLORS[actorList.indexOf(actor) % LANE_COLORS.length];
      const color = getJobColor(actor.job) || fallbackColor;
      ctx.beginPath();
      ctx.strokeStyle = selected ? color : color + "bb";
      ctx.lineWidth = selected ? 2 : 1.5;
      ctx.setLineDash(selected ? [] : [5, 3]);
      pts.forEach((dps, i) => {
        const x = xAt((i / SAMPLES) * battleTimeMs);
        const y = PAD_TOP + (1 - dps / globalMax) * plotHeight;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      });
      ctx.stroke();
      ctx.setLineDash([]);
    };

    allSamples.forEach((s) => { if (!isSelectedActor(s.actor.actorId)) drawActor(s, false); });
    allSamples.forEach((s) => { if (isSelectedActor(s.actor.actorId)) drawActor(s, true); });

    // ── Ping line (white dotted, Catmull-Rom smoothed) ──
    const pingHistory = Array.isArray(details?.pingHistory) ? details.pingHistory : [];
    if (pingHistory.length >= 1) {
      const maxPing = pingHistory.reduce((mx, p) => Math.max(mx, p.pingMs), 0) || 1;
      const pingPts = pingHistory.map((p) => ({
        x: xAt(Number(p.tsMs)),
        y: PAD_TOP + (Number(p.pingMs) / maxPing) * plotHeight,
      }));
      ctx.beginPath();
      ctx.strokeStyle = "rgba(255,255,255,0.9)";
      ctx.lineWidth = 1.5;
      ctx.setLineDash([2, 4]);
      ctx.moveTo(pingPts[0].x, pingPts[0].y);
      if (pingPts.length === 1) {
        ctx.lineTo(pingPts[0].x, pingPts[0].y);
      } else {
        // Catmull-Rom → cubic bezier, tension 0.5
        const tension = 0.5;
        for (let i = 0; i < pingPts.length - 1; i++) {
          const p0 = pingPts[Math.max(0, i - 1)];
          const p1 = pingPts[i];
          const p2 = pingPts[i + 1];
          const p3 = pingPts[Math.min(pingPts.length - 1, i + 2)];
          const cp1x = p1.x + (p2.x - p0.x) * tension / 2;
          const cp1y = p1.y + (p2.y - p0.y) * tension / 2;
          const cp2x = p2.x - (p3.x - p1.x) * tension / 2;
          const cp2y = p2.y - (p3.y - p1.y) * tension / 2;
          ctx.bezierCurveTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y);
        }
      }
      ctx.stroke();
      ctx.setLineDash([]);
    }

    // ── Legend (HTML, above chart, right-aligned) ──
    const legendItems = [
      { dash: "none",    color: "rgba(255,255,255,0.9)",  label: "DPS" },
      { dash: "3,4",     color: "rgba(220,60,60,0.9)",    label: "Boss HP" },
    ];
    if (pingHistory.length >= 1) {
      legendItems.push({ dash: "2,4", color: "rgba(255,255,255,0.9)", label: "Ping" });
    }
    dpsChartLegend.innerHTML = legendItems.map(({ dash, color, label }) => {
      const svgLine = dash === "none"
        ? `<line x1="0" y1="6" x2="22" y2="6" stroke="${color}" stroke-width="2"/>`
        : `<line x1="0" y1="6" x2="22" y2="6" stroke="${color}" stroke-width="2" stroke-dasharray="${dash}"/>`;
      return `<span class="legendItem">
        <svg width="22" height="12" viewBox="0 0 22 12" xmlns="http://www.w3.org/2000/svg">${svgLine}</svg>
        <span style="color:${color}">${label}</span>
      </span>`;
    }).join("");

    // X axis labels
    dpsChartXAxis.innerHTML = "";
    const tickCount = Math.min(12, Math.ceil(durationSec / gridIntervalSec) + 1);
    for (let i = 0; i <= tickCount; i++) {
      const sec = Math.round((i / tickCount) * durationSec);
      const minutes = Math.floor(sec / 60);
      const seconds = sec % 60;
      const span = document.createElement("span");
      span.textContent = `${minutes}:${String(seconds).padStart(2, "0")}`;
      dpsChartXAxis.appendChild(span);
    }
  };

  const renderTimeline = (details) => {
    if (!timelineCanvas || !timelineViewport || !timelineLegend || !timelineXAxis) return;

    const skills = Array.isArray(details?.skills) ? details.skills : [];
    const battleTimeMs = Number(lastUnfilteredDetails?.battleTimeMs) || Number(details?.battleTimeMs) || 0;
    if (battleTimeMs <= 0 || skills.length === 0) {
      timelineCanvas.width = 0;
      timelineCanvas.height = 0;
      timelineLegend.innerHTML = "";
      timelineXAxis.innerHTML = "";
      return;
    }

    // Group skills and collect all hit timestamps
    const laneMap = new Map();
    skills.forEach((skill) => {
      if (!skill) return;
      const timestamps = Array.isArray(skill.hitTimestamps) ? skill.hitTimestamps : [];
      if (timestamps.length === 0) return;
      const name = String(skill.name ?? "");
      const key = `${name}::${skill.isDot ? "dot" : "hit"}`;
      const existing = laneMap.get(key);
      if (existing) {
        existing.timestamps.push(...timestamps);
        existing.totalDmg += Number(skill.dmg) || 0;
      } else {
        laneMap.set(key, {
          name,
          code: skill.code,
          job: skill.job || getActorJob(skill.actorId),
          isDot: !!skill.isDot,
          timestamps: [...timestamps],
          totalDmg: Number(skill.dmg) || 0,
          skill,
        });
      }
    });

    // Sort lanes by total damage (highest at top)
    const lanes = [...laneMap.values()]
      .sort((a, b) => b.totalDmg - a.totalDmg)
      .slice(0, 15); // max 15 lanes

    if (lanes.length === 0) {
      timelineCanvas.width = 0;
      timelineCanvas.height = 0;
      timelineLegend.innerHTML = "";
      timelineXAxis.innerHTML = "";
      return;
    }

    // Sort timestamps within each lane
    lanes.forEach((lane) => lane.timestamps.sort((a, b) => a - b));

    // Dimensions
    const ICON_SIZE = 22;
    const LANE_HEIGHT = 30;
    const LANE_PAD = 4;
    const LEFT_MARGIN = 0;
    const durationSec = battleTimeMs / 1000;
    const minCanvasWidth = timelineViewport.clientWidth - 24;
    const pixelsPerSecond = Math.max(8, minCanvasWidth / durationSec);
    const chartWidth = Math.max(minCanvasWidth, Math.ceil(durationSec * pixelsPerSecond));
    const chartHeight = lanes.length * LANE_HEIGHT;
    const dpr = window.devicePixelRatio || 1;

    timelineCanvas.width = Math.ceil(chartWidth * dpr);
    timelineCanvas.height = Math.ceil(chartHeight * dpr);
    timelineCanvas.style.width = `${chartWidth}px`;
    timelineCanvas.style.height = `${chartHeight}px`;

    const ctx = timelineCanvas.getContext("2d");
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, chartWidth, chartHeight);

    // Draw lane backgrounds
    lanes.forEach((lane, i) => {
      const y = i * LANE_HEIGHT;
      ctx.fillStyle = i % 2 === 0 ? "rgba(255,255,255,0.03)" : "rgba(0,0,0,0.1)";
      ctx.fillRect(0, y, chartWidth, LANE_HEIGHT);
    });

    // Draw vertical grid lines (every 30s)
    const gridIntervalSec = durationSec > 300 ? 60 : durationSec > 60 ? 30 : 10;
    ctx.strokeStyle = "rgba(255,255,255,0.08)";
    ctx.lineWidth = 1;
    for (let sec = gridIntervalSec; sec < durationSec; sec += gridIntervalSec) {
      const x = Math.round((sec / durationSec) * chartWidth) + 0.5;
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, chartHeight);
      ctx.stroke();
    }

    // Draw skill icons on each lane
    const pendingDraws = [];
    lanes.forEach((lane, laneIdx) => {
      const y = laneIdx * LANE_HEIGHT + LANE_PAD;
      const color = LANE_COLORS[laneIdx % LANE_COLORS.length];
      const entry = loadTimelineIcon({
        code: lane.code,
        job: lane.job,
        isDot: lane.isDot,
      });

      lane.timestamps.forEach((ts) => {
        const x = LEFT_MARGIN + (ts / battleTimeMs) * (chartWidth - LEFT_MARGIN);
        const iconX = x - ICON_SIZE / 2;
        const iconY = y;

        if (entry && entry.ready && entry.img.complete && entry.img.naturalWidth > 0) {
          ctx.drawImage(entry.img, iconX, iconY, ICON_SIZE, ICON_SIZE);
        } else if (entry) {
          // Draw placeholder, queue redraw
          ctx.fillStyle = color;
          ctx.globalAlpha = 0.6;
          ctx.fillRect(iconX, iconY, ICON_SIZE, ICON_SIZE);
          ctx.globalAlpha = 1.0;
          pendingDraws.push({ entry, iconX, iconY });
        } else {
          // No icon - draw colored dot
          ctx.fillStyle = color;
          ctx.globalAlpha = 0.8;
          ctx.beginPath();
          ctx.arc(x, y + ICON_SIZE / 2, 5, 0, Math.PI * 2);
          ctx.fill();
          ctx.globalAlpha = 1.0;
        }
      });
    });

    // Icons that weren't ready will trigger scheduleTimelineRedraw via onload

    // Render legend
    timelineLegend.innerHTML = "";
    lanes.forEach((lane, i) => {
      const item = document.createElement("div");
      item.className = "legendItem";
      const iconImg = document.createElement("img");
      iconImg.alt = "";
      iconImg.loading = "lazy";
      iconImg.referrerPolicy = "no-referrer";
      window.skillIcons?.applyIconToImage?.(iconImg, {
        code: lane.code,
        job: lane.job,
        isDot: lane.isDot,
      });
      const label = document.createElement("span");
      label.textContent = lane.name || `Skill ${lane.code}`;
      item.appendChild(iconImg);
      item.appendChild(label);
      timelineLegend.appendChild(item);
    });

    // Render X axis labels
    timelineXAxis.innerHTML = "";
    const tickCount = Math.min(12, Math.ceil(durationSec / gridIntervalSec) + 1);
    for (let i = 0; i <= tickCount; i++) {
      const sec = Math.round((i / tickCount) * durationSec);
      const minutes = Math.floor(sec / 60);
      const seconds = sec % 60;
      const span = document.createElement("span");
      span.textContent = `${minutes}:${String(seconds).padStart(2, "0")}`;
      timelineXAxis.appendChild(span);
    }
  };

  const loadDetailsContext = () => {
    const nextContext = typeof getDetailsContext === "function" ? getDetailsContext() : null;
    if (!nextContext) {
      detailsContext = null;
      detailsActors = new Map();
      detailsTargets = [];
      selectedTargetId = null;
      return null;
    }
    detailsContext = nextContext;
    detailsActors = new Map();
    detailsTargets = Array.isArray(nextContext.targets) ? nextContext.targets : [];
    const actorList = Array.isArray(nextContext.actors) ? nextContext.actors : [];
    actorList.forEach((actor) => {
      const numericId = Number(actor.actorId);
      if (!Number.isFinite(numericId) || numericId <= 0) return;
      detailsActors.set(numericId, actor);
    });
    return nextContext;
  };

  const getTargetActorIds = (target) => {
    if (!target || typeof target.actorDamage !== "object") return [];
    return Object.keys(target.actorDamage)
      .map((id) => Number(id))
      .filter((id) => Number.isFinite(id) && id > 0);
  };

  const targetMatchesSelectedAttackers = (target) => {
    if (!target) return false;
    if (!Array.isArray(selectedAttackerIds) || selectedAttackerIds.length === 0) return true;
    return selectedAttackerIds.some((actorId) => getActorDamage(target.actorDamage, actorId) > 0);
  };

  const getSelectableTargets = () =>
    detailsTargets.filter(
      (target) => Number(target?.targetId) > 0 && targetMatchesSelectedAttackers(target)
    );

  const resolveActorLabel = (actorId) => {
    const actor = detailsActors.get(Number(actorId));
    if (actor?.nickname && actor.nickname !== String(actorId)) return actor.nickname;
    return `Player #${actorId}`;
  };

  const getActorIdsByLabel = (label) => {
    const normalizedLabel = String(label ?? "").trim().toLowerCase();
    if (!normalizedLabel) return [];
    const ids = [];
    detailsActors.forEach((actor, actorId) => {
      const nickname = String(actor?.nickname ?? "").trim().toLowerCase();
      if (!nickname) return;
      if (nickname === normalizedLabel) {
        ids.push(actorId);
      }
    });
    return ids;
  };

  const syncSelectedAttackersFromLabel = () => {
    if (!Array.isArray(selectedAttackerIds) || selectedAttackerIds.length === 0) return;
    if (!detailsActors.size) return;
    const matchingActorIds = getActorIdsByLabel(selectedAttackerLabel);
    if (!matchingActorIds.length) return;
    selectedAttackerIds = matchingActorIds;
  };

  const resolveRowLabel = (row) => {
    if (!row) return "-";
    if (row.isIdentifying) {
      return `Player #${row.id ?? row.name ?? ""}`.trim();
    }
    return String(row.name ?? "-");
  };

  const applyCjkClass = (element, text) => {
    if (!element) return;
    element.classList.toggle("isCjk", cjkRegex.test(String(text || "")));
  };

  const getTargetSortValue = (target) => {
    if (!target) return 0;
    if (sortMode === "recent") {
      return Number(target.lastDamageTime) || 0;
    }
    if (sortMode === "time") {
      return Number(target.battleTime) || 0;
    }
    return getTargetDamageForSelection(target);
  };

  const syncSortButtons = () => { /* no-op: sort buttons removed */ };

  const applyTargetSelection = async (targetId) => {
    if (targetId === "all") {
      selectedTargetId = null;
    } else {
      selectedTargetId = Number(targetId) || null;
    }
    if (selectedAttackerIds && selectedAttackerIds.length === 1) {
      selectedAttackerLabel = resolveActorLabel(selectedAttackerIds[0]);
    }
    updateHeaderText();
    await refreshDetailsView();
  };

  const applyAttackerSelection = async (actorId) => {
    if (actorId === "all") {
      selectedAttackerIds = null;
      selectedAttackerLabel = labelText("details.all", "All");
    } else {
      const numericId = Number(actorId);
      selectedAttackerIds = Number.isFinite(numericId) ? [numericId] : null;
      selectedAttackerLabel = selectedAttackerIds ? resolveActorLabel(numericId) : "All";
    }
    const selectedTarget = getTargetById(selectedTargetId);
    if (selectedTargetId !== null && !targetMatchesSelectedAttackers(selectedTarget)) {
      selectedTargetId = null;
    }
    updateHeaderText();
    await refreshDetailsView();
  };

  const combinePerActorStats = (detailsList = []) => {
    const totals = new Map();
    detailsList.forEach((details) => {
      const stats = Array.isArray(details?.perActorStats) ? details.perActorStats : [];
      stats.forEach((entry) => {
        const actorId = Number(entry?.actorId);
        if (!Number.isFinite(actorId)) return;
        const next = totals.get(actorId) || {
          actorId,
          job: entry?.job || "",
          totalDmg: 0,
          totalTimes: 0,
          totalCrit: 0,
          totalParry: 0,
          totalBack: 0,
          totalPerfect: 0,
          totalDouble: 0,
          totalHits: 0,
          totalHeal: 0,
          multiHitCount: 0,
          multiHitDamage: 0,
        };
        next.totalDmg += Number(entry?.totalDmg) || 0;
        if (!next.job && entry?.job) next.job = entry.job;
        next.totalTimes += Number(entry?.totalTimes) || 0;
        next.totalCrit += Number(entry?.totalCrit) || 0;
        next.totalParry += Number(entry?.totalParry) || 0;
        next.totalBack += Number(entry?.totalBack) || 0;
        next.totalPerfect += Number(entry?.totalPerfect) || 0;
        next.totalDouble += Number(entry?.totalDouble) || 0;
        next.totalHits += Number(entry?.totalHits) || 0;
        next.totalHeal += Number(entry?.totalHeal) || 0;
        next.multiHitCount += Number(entry?.multiHitCount) || 0;
        next.multiHitDamage += Number(entry?.multiHitDamage) || 0;
        totals.set(actorId, next);
      });
    });
    return [...totals.values()].sort((a, b) => b.totalDmg - a.totalDmg);
  };

  const buildCombinedDetails = (detailsList = [], totalTargetDamage = 0, showSkillIcons = true) => {
    const skills = detailsList.flatMap((details) => (Array.isArray(details?.skills) ? details.skills : []));
    let totalDmg = 0;
    let totalTimes = 0;
    let totalCrit = 0;
    let totalParry = 0;
    let totalBack = 0;
    let totalPerfect = 0;
    let totalDouble = 0;
    let totalMultiHitCount = 0;
    let totalMultiHitDamage = 0;
    let totalMultiHitHits = 0;
    let totalHeal = 0;

    skills.forEach((skill) => {
      const dmg = Number(skill?.dmg) || 0;
      totalDmg += dmg;
      totalHeal += Number(skill?.heal) || 0;
      totalMultiHitCount += Number(skill?.multiHitCount) || 0;
      totalMultiHitDamage += Number(skill?.multiHitDamage) || 0;
      totalMultiHitHits += Number(skill?.multiHitHits) || 0;
      if (!skill?.isDot) {
        totalTimes += Number(skill?.time) || 0;
        totalCrit += Number(skill?.crit) || 0;
        totalParry += Number(skill?.parry) || 0;
        totalBack += Number(skill?.back) || 0;
        totalPerfect += Number(skill?.perfect) || 0;
        totalDouble += Number(skill?.double) || 0;
      }
    });

    const pct = (num, den) => (den > 0 ? Math.round((num / den) * 1000) / 10 : 0);
    const battleTimeMs = detailsList.reduce((sum, details) => sum + (Number(details?.battleTimeMs) || 0), 0);

    return {
      totalDmg,
      totalHits: totalTimes,
      maxHp: detailsList.reduce((max, d) => Math.max(max, Number(d?.maxHp) || 0), 0),
      contributionPct: totalTargetDamage > 0 ? (totalDmg / totalTargetDamage) * 100 : 0,
      totalCritPct: pct(totalCrit, totalTimes),
      totalParryPct: pct(totalParry, totalTimes),
      totalBackPct: pct(totalBack, totalTimes),
      totalPerfectPct: pct(totalPerfect, totalTimes),
      totalDoublePct: pct(totalDouble, totalTimes),
      multiHitCount: totalMultiHitCount,
      multiHitDamage: totalMultiHitDamage,
      multiHitPct: pct(totalMultiHitHits, totalTimes),
      totalHeal,
      combatTime: formatBattleTime(battleTimeMs),
      battleTimeMs,
      skills,
      showSkillIcons,
      perActorStats: combinePerActorStats(detailsList),
      showCombinedTotals: !selectedAttackerIds || selectedAttackerIds.length === 0,
    };
  };

  // A synthetic null row used when opening Details for all players via boss name click
  const NULL_ROW = { id: null, job: "", name: "" };

  const refreshDetailsView = async (seq) => {
    const row = lastRow ?? NULL_ROW;
    if (activeCompactMode) {
      const details = await getDetails(row, {
        targetId: null,
        attackerIds: null,
        totalTargetDamage: null,
        showSkillIcons: false,
        maxSkills: COMPACT_MAX_SKILLS,
      });
      if (typeof seq === "number" && seq !== openSeq) return;
      render(details, row);
      return;
    }

    if (!detailsContext) {
      const details = await getDetails(row);
      if (typeof seq === "number" && seq !== openSeq) return;
      render(details, row);
      return;
    }

    const showSkillIcons = !selectedAttackerIds || selectedAttackerIds.length === 0;
    if (selectedTargetId === null) {
      const targetList = getSelectableTargets();
      if (!targetList.length) {
        const details = await getDetails(row, {
          targetId: null,
          attackerIds: selectedAttackerIds,
          totalTargetDamage: null,
          showSkillIcons,
        });
        if (typeof seq === "number" && seq !== openSeq) return;
        render(details, row);
        return;
      }

      const [firstTarget, ...restTargets] = targetList;
      const firstDetails = await getDetails(row, {
        targetId: firstTarget.targetId,
        attackerIds: selectedAttackerIds,
        totalTargetDamage: firstTarget.totalDamage,
        showSkillIcons,
      });
      if (typeof seq === "number" && seq !== openSeq) return;
      render(firstDetails, row);

      const restDetails = await Promise.all(
        restTargets.map((target) =>
          getDetails(row, {
            targetId: target.targetId,
            attackerIds: selectedAttackerIds,
            totalTargetDamage: target.totalDamage,
            showSkillIcons,
          })
        )
      );
      const detailsList = [firstDetails, ...restDetails];
      const totalTargetDamage = targetList.reduce(
        (sum, target) => sum + (Number(target?.totalDamage) || 0),
        0
      );
      const mergedDetails = buildCombinedDetails(detailsList, totalTargetDamage, showSkillIcons);
      if (typeof seq === "number" && seq !== openSeq) return;
      render(mergedDetails, row);
      return;
    }

    const target = getTargetById(selectedTargetId);
    const totalTargetDamage = target ? target.totalDamage : null;
    const details = await getDetails(row, {
      targetId: selectedTargetId,
      attackerIds: selectedAttackerIds,
      totalTargetDamage,
      showSkillIcons,
    });
    if (typeof seq === "number" && seq !== openSeq) return;
    render(details, row);
  };

  /**
   * Fetch "All players" details using the same multi-target merge logic
   * as refreshDetailsView but with attackerIds: null.
   */
  const fetchUnfilteredDetails = async (row) => {
    if (!detailsContext) {
      return await getDetails(row);
    }
    if (selectedTargetId === null) {
      const targetList = getSelectableTargets();
      if (!targetList.length) {
        return await getDetails(row, {
          targetId: null,
          attackerIds: null,
          totalTargetDamage: null,
          showSkillIcons: true,
        });
      }
      const allTargetDetails = await Promise.all(
        targetList.map((target) =>
          getDetails(row, {
            targetId: target.targetId,
            attackerIds: null,
            totalTargetDamage: target.totalDamage,
            showSkillIcons: true,
          })
        )
      );
      const totalTargetDamage = targetList.reduce(
        (sum, target) => sum + (Number(target?.totalDamage) || 0),
        0
      );
      return buildCombinedDetails(allTargetDetails, totalTargetDamage, true);
    }
    const target = getTargetById(selectedTargetId);
    return await getDetails(row, {
      targetId: selectedTargetId,
      attackerIds: null,
      totalTargetDamage: target ? target.totalDamage : null,
      showSkillIcons: true,
    });
  };

  const render = (details, row) => {
    if (row?.id && row?.job) {
      const rowActorId = Number(row.id);
      if (Number.isFinite(rowActorId) && rowActorId > 0) {
        detectedJobByActorId.set(rowActorId, String(row.job));
      }
    }
    rememberJobsFromDetails(details);
    if (!selectedAttackerIds || selectedAttackerIds.length === 0) {
      lastUnfilteredDetails = details;
    }
    selectedAttackerLabel = selectedAttackerLabel || String(row.name ?? "");
    updateHeaderText();
    renderPartyBars(buildPartyBarStats() || details?.perActorStats);
    renderStats(details, { compact: activeCompactMode });
    renderSkills(details, { compact: activeCompactMode });
    renderDpsChart(details);
    renderTimeline(details);
    lastRow = row;
    lastDetails = details;

    // If a player is selected but we don't have "All" totals yet,
    // fetch them in the background using the same multi-target merge
    // logic that refreshDetailsView uses, then re-render stats.
    if (!lastUnfilteredDetails && Array.isArray(selectedAttackerIds) && selectedAttackerIds.length > 0) {
      const capturedSeq = openSeq;
      fetchUnfilteredDetails(row).then((allDetails) => {
        if (capturedSeq !== openSeq || !allDetails) return;
        lastUnfilteredDetails = allDetails;
        renderStats(lastDetails, { compact: activeCompactMode });
      }).catch(() => {});
    }
    const cacheRowId = String(row?.id ?? "").trim();
    if (cacheRowId) {
      detailsCacheByRowId.set(cacheRowId, details);
    }
  };

  const getCachedDetails = (rowId) => {
    const cacheRowId = String(rowId ?? "").trim();
    if (!cacheRowId) return null;
    return detailsCacheByRowId.get(cacheRowId) || null;
  };

  const isOpen = () => detailsPanel.classList.contains("open");

  const open = async (
    row,
    { force = false, restartOnSwitch = true, defaultTargetAll = false, defaultTargetId = null, pin = true, compact = false } = {}
  ) => {
    const rowId = row?.id ?? null;
    // if (!rowId) return;

    const isOpen = detailsPanel.classList.contains("open");
    const isSame = isOpen && openedRowId === rowId;
    const isSwitch = isOpen && openedRowId && openedRowId !== rowId;

    const requestedCompact = !!compact;
    const requestedPin = !!pin;
    const isSameCompactMode = activeCompactMode === requestedCompact;
    const isAlreadyPinnedForRow = pinnedRowId === rowId;

    if (!force && isSame && isSameCompactMode && (!requestedPin || isAlreadyPinnedForRow)) return;

    if (isSwitch && restartOnSwitch) {
      close();
      requestAnimationFrame(() => {
        open(row, { force: true, restartOnSwitch: false, defaultTargetAll, defaultTargetId, pin, compact });
      });
      return;
    }

    openedRowId = rowId;
    if (pin) {
      pinnedRowId = rowId;
      onPinnedRowChange?.(pinnedRowId);
    }
    lastRow = row;

    activeCompactMode = requestedCompact;
    selectedAttackerLabel = resolveRowLabel(row);
    const rowIdNum = Number(rowId);
    selectedAttackerIds = (rowId !== null && rowId !== undefined && Number.isFinite(rowIdNum) && rowIdNum > 0) ? [rowIdNum] : null;
    if (activeCompactMode) {
      detailsContext = null;
      detailsActors = new Map();
      detailsTargets = [];
    } else {
      loadDetailsContext();
    }
    syncSelectedAttackersFromLabel();
    if (activeCompactMode) {
      selectedTargetId = null;
    } else if (defaultTargetAll) {
      selectedTargetId = null;
    } else if (Number.isFinite(Number(defaultTargetId)) && Number(defaultTargetId) > 0) {
      selectedTargetId = Number(defaultTargetId);
    } else if (detailsContext && detailsContext.currentTargetId) {
      selectedTargetId = detailsContext.currentTargetId;
    } else {
      selectedTargetId = detailsTargets[0]?.targetId ?? null;
    }
    if (selectedAttackerIds && selectedAttackerIds.length === 1) {
      selectedAttackerLabel = resolveActorLabel(selectedAttackerIds[0]);
    }
    // Compute fight start time from target context
    const firstTarget = getTargetById(selectedTargetId) || detailsTargets[0];
    fightStartMs = firstTarget
      ? Math.max(0, (Number(firstTarget.lastDamageTime) || 0) - (Number(firstTarget.battleTime) || 0))
      : 0;
    fightBossName = firstTarget ? getTargetLabel(firstTarget) : (row?.name ?? "");
    updateHeaderText();
    detailsPanel.classList.add("open");
    detailsPanel.style.removeProperty("width");
    updateGridColumns();

    const cachedDetails = getCachedDetails(rowId);
    if (cachedDetails) {
      render(cachedDetails, row);
    } else {
      // 이전 값 비우기
      for (let i = 0; i < statSlots.length; i++) statSlots[i].valueEl.textContent = "-";
      for (let i = 0; i < skillSlots.length; i++) {
        skillSlots[i].rowEl.style.display = "none";
        skillSlots[i].rowFillEl.style.transform = "scaleX(0)";
      }
    }

    const seq = ++openSeq;

    try {
      await refreshDetailsView(seq);

      if (seq !== openSeq) return;
    } catch (e) {
      if (seq !== openSeq) return;
      // uiDebug?.log("getDetails:error", { id: rowId, message: e?.message });
    }
  };
  const close = ({ keepPinned = false } = {}) => {
    openSeq++;

    openedRowId = null;
    if (!keepPinned) {
      pinnedRowId = null;
      onPinnedRowChange?.(null);
    }
    lastRow = null;
    lastDetails = null;
    lastUnfilteredDetails = null;
    activeCompactMode = false;
    lastMeasuredNameWidth = 0;
    detailsPanel.style.removeProperty("width");
    for (let i = 0; i < statSlots.length; i++) {
      statSlots[i].statEl.style.display = "";
    }
    detailsPanel.classList.remove("open");
    historyRecord = null;
    window._historyDetailsOverride = null;
    fightStartMs = 0;
    fightBossName = "";
    window._resumeFpsMonitor?.();
  };
  detailsClose?.addEventListener("click", close);

  detailsBackBtn?.addEventListener("click", () => {
    close({ keepPinned: false });
    onBack?.();
  });

  const openHistoryFight = async (record) => {
    historyRecord = record;
    openSeq++;
    const seq = openSeq;

    openedRowId = null;
    pinnedRowId = null;
    onPinnedRowChange?.(null);
    lastRow = null;
    activeCompactMode = false;
    lastMeasuredNameWidth = 0;

    // Build detailsActors map from stored actors
    detailsActors = new Map();
    const actorList = Array.isArray(record.actors) ? record.actors : [];
    actorList.forEach((actor) => {
      const numericId = Number(actor.actorId);
      if (Number.isFinite(numericId) && numericId > 0) {
        detailsActors.set(numericId, actor);
        if (actor.job) detectedJobByActorId.set(numericId, actor.job);
      }
    });

    // Build actorDamage from stored skills so player filtering works on history fights
    const historyActorDamage = {};
    if (Array.isArray(record.details?.skills)) {
      record.details.skills.forEach((skill) => {
        const id = Number(skill.actorId);
        if (id > 0) historyActorDamage[id] = (historyActorDamage[id] || 0) + (Number(skill.dmg) || 0);
      });
    }

    // Build a synthetic detailsTargets entry
    const bossTargetSummary = {
      targetId: record.targetId,
      targetName: record.bossName,
      totalDamage: record.totalDamage,
      battleTime: record.durationMs,
      lastDamageTime: record.startTimeMs + record.durationMs,
      actorDamage: historyActorDamage,
    };
    detailsTargets = [bossTargetSummary];
    detailsContext = { currentTargetId: record.targetId, targets: detailsTargets, actors: actorList };

    selectedTargetId = record.targetId;
    selectedAttackerIds = null;
    selectedAttackerLabel = labelText("details.history.allPlayers", "All Players");

    fightStartMs = Number(record.startTimeMs) || 0;
    fightBossName = record.bossName || "";
    updateHeaderText();
    detailsPanel.classList.add("open");
    detailsPanel.style.removeProperty("width");
    updateGridColumns();

    // Clear stats/skills while loading
    for (let i = 0; i < statSlots.length; i++) statSlots[i].valueEl.textContent = "-";
    for (let i = 0; i < skillSlots.length; i++) {
      skillSlots[i].rowEl.style.display = "none";
      skillSlots[i].rowFillEl.style.transform = "scaleX(0)";
    }

    if (seq !== openSeq) return;
    const fakeRow = { id: null, job: "", name: record.bossName };
    window._historyDetailsOverride = record.details;
    const processedDetails = await getDetails(fakeRow, {
      targetId: record.targetId,
      totalTargetDamage: record.totalDamage,
      showSkillIcons: true,
    });
    if (seq !== openSeq) return;
    if (processedDetails) render(processedDetails, fakeRow);
  };

  const refresh = async () => {
    if (!detailsPanel.classList.contains("open")) return;
    if (historyRecord) return; // history view doesn't refresh from backend
    const previousTargetId = selectedTargetId;
    const previousAttackerIds = Array.isArray(selectedAttackerIds) ? [...selectedAttackerIds] : null;
    const wasCompact = activeCompactMode;
    const seq = ++openSeq;
    if (!wasCompact) {
      loadDetailsContext();
    }
    selectedTargetId = previousTargetId;
    activeCompactMode = wasCompact;
    selectedAttackerIds = previousAttackerIds;
    syncSelectedAttackersFromLabel();
    syncSortButtons();
    updateHeaderText();
    await refreshDetailsView(seq);
  };

  const isPinned = () => pinnedRowId !== null;

  return { open, close, isOpen, isPinned, render, updateLabels, refresh, updateGridColumns, openHistoryFight };
};
