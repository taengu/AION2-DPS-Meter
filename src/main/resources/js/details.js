const createDetailsUI = ({
  detailsPanel,
  detailsClose,
  detailsTitle,
  detailsNicknameBtn,
  detailsNicknameMenu,
  detailsTargetBtn,
  detailsTargetMenu,
  detailsSortButtons,
  detailsStatsEl,
  skillsListEl,
  dpsFormatter,
  getDetails,
  getDetailsContext,
  onPinnedRowChange,
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
  let lastSkillNameColumnWidth = 0;
  const skillNameMeasureCtx = document.createElement("canvas").getContext("2d");
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
  const detailsTitleLabel = detailsTitle?.querySelector?.(".detailsTitleLabel");
  const detailsTitleSeparator = detailsTitle?.querySelector?.(".detailsTitleSeparator");
  const detailsTitleVs = detailsTitle?.querySelector?.(".detailsTitleVs");
  const detailsTargetSuffix = detailsTitle?.querySelector?.(".detailsTargetSuffix");

  const STATUS = [
    {
      key: "details.stats.totalDamage",
      fallback: "Total Damage",
      getValue: (d) => formatDamageCompact(d?.totalDmg),
    },
    { key: "details.stats.contribution", fallback: "Contribution", getValue: (d) => pctText(d?.contributionPct) },
    { key: "details.stats.combatTime", fallback: "Combat Time", getValue: (d) => d?.combatTime ?? "-" },
    { key: "details.skills.hits", fallback: "Hits", getValue: (d) => formatCount(d?.totalHits) },
    { key: "details.stats.multiHitHits", fallback: "Multi-Hits", getValue: (d) => formatCount(d?.multiHitCount) },
    {
      key: "details.stats.multiHitDamage",
      fallback: "Multi-Hit Damage",
      getValue: (d) => formatDamageCompact(d?.multiHitDamage),
    },
    { key: "details.stats.critRate", fallback: "Crit Rate", getValue: (d) => pctText(d?.totalCritPct) },
    { key: "details.stats.perfectRate", fallback: "Perfect Rate", getValue: (d) => pctText(d?.totalPerfectPct) },
    { key: "details.stats.doubleRate", fallback: "Double Rate", getValue: (d) => pctText(d?.totalDoublePct) },
    { key: "details.stats.parryRate", fallback: "Parry Rate", getValue: (d) => pctText(d?.totalParryPct) },
    { key: "details.skills.heal", fallback: "Heal", getValue: (d) => formatCount(d?.totalHeal) },
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
    const nicknameTextEl = detailsNicknameBtn?.querySelector?.(".detailsDropdownText");
    const targetTextEl = detailsTargetBtn?.querySelector?.(".detailsDropdownText");
    if (detailsTitleLabel) {
      detailsTitleLabel.textContent = labelText("details.header", "Details");
    }
    if (detailsTitleSeparator) {
      detailsTitleSeparator.textContent = labelText("details.titleFor", "for");
    }
    if (detailsTitleVs) {
      detailsTitleVs.textContent = labelText("details.titleVs", "vs");
    }
    if (detailsNicknameBtn) {
      if (nicknameTextEl) {
        nicknameTextEl.textContent = selectedAttackerLabel || "-";
      } else {
        detailsNicknameBtn.textContent = selectedAttackerLabel || "-";
      }
      applyCjkClass(detailsNicknameBtn, selectedAttackerLabel || "");
      const actorId = Array.isArray(selectedAttackerIds) && selectedAttackerIds.length === 1
        ? selectedAttackerIds[0]
        : null;
      const actorJob = actorId ? getActorJob(actorId) : "";
      const color = actorJob ? getJobColor(actorJob) : "";
      detailsNicknameBtn.style.color = color || "";
    }
    if (detailsTargetBtn) {
      const targetLabel = selectedTargetId ? `Mob #${selectedTargetId}` : labelText("details.all", "All");
      if (targetTextEl) {
        targetTextEl.textContent = targetLabel;
      } else {
        detailsTargetBtn.textContent = targetLabel;
      }
    }
    if (detailsTargetSuffix) {
      const target = getTargetById(selectedTargetId);
      const suffix = formatTargetSuffix(target);
      if (!suffix) {
        detailsTargetSuffix.textContent = "";
      } else if (sortMode === "recent") {
        detailsTargetSuffix.textContent = suffix;
      } else {
        detailsTargetSuffix.textContent = `(${suffix})`;
      }
    }
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
    syncSkillColumnMinWidths();
  };

  const resolveStatValue = (statKey, data) => {
    if (!data) return "-";
    switch (statKey) {
      case "details.stats.totalDamage":
        return formatDamageCompact(data.totalDmg);
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
      case "details.stats.selfHealing":
      case "details.skills.heal":
        return formatCount(data.totalHeal);
      case "details.stats.empty":
        return "";
      case "details.stats.combatTime":
        return data.combatTime ?? "-";
      default:
        return STATUS.find((stat) => stat.key === statKey)?.getValue(data) ?? "-";
    }
  };

  const renderStats = (details) => {
    const showCombinedTotals = details?.showCombinedTotals && Array.isArray(details?.perActorStats);
    for (let i = 0; i < STATUS.length; i++) {
      const slot = statSlots[i];
      const statKey = STATUS[i].key;
      if (!showCombinedTotals) {
        slot.valueEl.style.display = "";
        slot.valueEl.style.flexWrap = "";
        slot.valueEl.style.gap = "";
        slot.valueEl.style.justifyContent = "";
        slot.valueEl.style.alignItems = "";
        slot.valueEl.innerHTML = "";
        slot.valueEl.textContent = STATUS[i].getValue(details);
        continue;
      }

      slot.valueEl.innerHTML = "";
      slot.valueEl.style.display = "flex";
      slot.valueEl.style.flexWrap = "wrap";
      slot.valueEl.style.gap = "6px";
      slot.valueEl.style.justifyContent = "flex-end";
      slot.valueEl.style.alignItems = "center";

      const actorStats = details.perActorStats || [];
      if (statKey === "details.stats.empty") {
        slot.valueEl.textContent = "";
        continue;
      }
      if (statKey !== "details.stats.combatTime") {
        actorStats.forEach((actor) => {
          const span = document.createElement("span");
          if (statKey === "details.stats.totalDamage") {
            span.textContent = formatDamageCompact(actor.totalDmg);
          } else if (statKey === "details.stats.hits" || statKey === "details.skills.hits") {
            span.textContent = formatCount(actor.totalHits);
          } else if (statKey === "details.stats.multiHitDamage") {
            span.textContent = formatDamageCompact(actor.multiHitDamage);
          } else {
            span.textContent = resolveStatValue(statKey, actor);
          }
          span.style.fontWeight = "400";
          const color = getJobColor(actor.job || getActorJob(actor.actorId));
          if (color) {
            span.style.color = color;
          }
          slot.valueEl.appendChild(span);
        });
      }

      const totalSpan = document.createElement("span");
      totalSpan.textContent = resolveStatValue(statKey, details);
      totalSpan.style.fontWeight = "700";
      slot.valueEl.appendChild(totalSpan);
    }
  };

  const createSkillView = () => {
    const rowEl = document.createElement("div");
    rowEl.className = "skillRow";

    const nameEl = document.createElement("div");
    nameEl.className = "cell name";

    const nameTextEl = document.createElement("span");
    nameTextEl.className = "skillNameText";

    nameEl.appendChild(nameTextEl);

    const hitEl = document.createElement("div");
    hitEl.className = "cell center hit";

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

    const healEl = document.createElement("div");
    healEl.className = "cell center heal";

    const dmgEl = document.createElement("div");
    dmgEl.className = "cell dmg right";

    const dmgFillEl = document.createElement("div");
    dmgFillEl.className = "dmgFill";

    const dmgTextEl = document.createElement("div");
    dmgTextEl.className = "dmgText";

    dmgEl.appendChild(dmgFillEl);
    dmgEl.appendChild(dmgTextEl);

    rowEl.appendChild(nameEl);
    rowEl.appendChild(hitEl);
    rowEl.appendChild(multiHitEl);
    rowEl.appendChild(multiHitDamageEl);
    rowEl.appendChild(critEl);
    rowEl.appendChild(parryEl);
    rowEl.appendChild(perfectEl);
    rowEl.appendChild(doubleEl);
    rowEl.appendChild(backEl);
    rowEl.appendChild(healEl);

    rowEl.appendChild(dmgEl);

    return {
      rowEl,
      nameEl,
      nameTextEl,
      hitEl,
      multiHitEl,
      multiHitDamageEl,
      critEl,
      parryEl,
      backEl,
      perfectEl,
      doubleEl,
      healEl,
      dmgFillEl,
      dmgTextEl,
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
    switch (key) {
      case "name":
        return String(skill?.name || "").toLowerCase();
      case "hit":
        return hits;
      case "mhit":
        return Number(skill?.multiHitCount) || 0;
      case "mdmg":
        return Number(skill?.multiHitDamage) || 0;
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
      case "heal":
        return Number(skill?.heal) || 0;
      case "dmg":
      default:
        return Number(skill?.dmg) || 0;
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

  const syncSkillColumnMinWidths = () => {
    const headerEl = detailsPanel?.querySelector?.(".detailsSkills .skillHeader");
    const headerCells = detailsPanel?.querySelectorAll?.(".detailsSkills .skillHeader .cell");
    if (!headerEl || !headerCells?.length) return;

    const compactColumns = ["hit", "mhit", "mdmg", "crit", "parry", "perfect", "double", "back", "heal"];
    const compactSet = new Set(compactColumns);
    const columnOrder = ["name", ...compactColumns, "dmg"];

    const compactColumnMaxWidths = {
      hit: 56,
      mhit: 66,
      mdmg: 78,
      crit: 64,
      parry: 64,
      perfect: 68,
      double: 66,
      back: 62,
      heal: 72,
    };

    const compactColumnMinWidths = {
      hit: 40,
      mhit: 48,
      mdmg: 56,
      crit: 46,
      parry: 46,
      perfect: 50,
      double: 48,
      back: 44,
      heal: 50,
    };

    const isVisibleCell = (cell) => {
      if (!cell) return false;
      const style = window.getComputedStyle(cell);
      return style.display !== "none";
    };

    const measuredWidths = new Map();
    const headerMinWidths = new Map();

    columnOrder.forEach((columnClass) => {
      const headerCell = [...headerCells].find(
        (cell) => cell.classList.contains(columnClass) && isVisibleCell(cell)
      );
      if (!headerCell) return;

      const columnCells = detailsPanel?.querySelectorAll?.(`.detailsSkills .cell.${columnClass}`) || [];
      const visibleCells = [...columnCells].filter((cell) => {
        if (!isVisibleCell(cell)) return false;
        const row = cell?.closest?.(".skillRow");
        return !row || row.style.display !== "none";
      });
      if (!visibleCells.length) return;

      const headerWidth = Math.ceil(headerCell.scrollWidth || 0);
      if (!Number.isFinite(headerWidth) || headerWidth <= 0) return;

      headerMinWidths.set(columnClass, headerWidth + 4);

      let targetWidth = headerWidth + 4;
      if (columnClass === "name") {
        const currentNameWidth = parseFloat(
          detailsPanel?.style?.getPropertyValue?.("--details-skill-name-width") || "180"
        );
        if (Number.isFinite(currentNameWidth) && currentNameWidth > 0) {
          targetWidth = Math.max(targetWidth, Math.ceil(currentNameWidth));
        }
        targetWidth = Math.min(Math.max(120, targetWidth), 220);
      } else if (compactSet.has(columnClass)) {
        visibleCells.forEach((cell) => {
          targetWidth = Math.max(targetWidth, Math.ceil(cell.scrollWidth || 0) + 4);
        });
        targetWidth = Math.min(targetWidth, compactColumnMaxWidths[columnClass] || 74);
      } else if (columnClass === "dmg") {
        visibleCells.forEach((cell) => {
          targetWidth = Math.max(targetWidth, Math.ceil(cell.scrollWidth || 0) + 4);
        });
        targetWidth = Math.min(Math.max(120, targetWidth), 170);
      }

      measuredWidths.set(columnClass, Math.ceil(targetWidth));
    });

    const visibleColumns = columnOrder.filter((columnClass) => measuredWidths.has(columnClass));
    if (!visibleColumns.length) return;

    const gap = 6;
    const totalGap = Math.max(0, visibleColumns.length - 1) * gap;
    const availableWidth = Math.max(300, Math.floor(headerEl.clientWidth - 4));
    const getTotalWidth = () => visibleColumns.reduce((sum, key) => sum + (measuredWidths.get(key) || 0), 0) + totalGap;

    let overflow = getTotalWidth() - availableWidth;

    if (overflow > 0 && measuredWidths.has("name")) {
      const current = measuredWidths.get("name") || 0;
      const minName = Math.max(headerMinWidths.get("name") || 84, 108);
      const reducible = Math.max(0, current - minName);
      const delta = Math.min(reducible, overflow);
      measuredWidths.set("name", current - delta);
      overflow -= delta;
    }

    if (overflow > 0 && measuredWidths.has("dmg")) {
      const current = measuredWidths.get("dmg") || 0;
      const minDmg = Math.max(headerMinWidths.get("dmg") || 100, 108);
      const reducible = Math.max(0, current - minDmg);
      const delta = Math.min(reducible, overflow);
      measuredWidths.set("dmg", current - delta);
      overflow -= delta;
    }

    if (overflow > 0) {
      const shrinkable = compactColumns.filter((columnClass) => measuredWidths.has(columnClass));
      let safety = 2000;
      while (overflow > 0 && safety > 0) {
        safety -= 1;
        let changed = false;
        for (let i = 0; i < shrinkable.length && overflow > 0; i++) {
          const key = shrinkable[i];
          const current = measuredWidths.get(key) || 0;
          const minWidth = Math.max(compactColumnMinWidths[key] || 40, headerMinWidths.get(key) || 40);
          if (current <= minWidth) continue;
          measuredWidths.set(key, current - 1);
          overflow -= 1;
          changed = true;
        }
        if (!changed) break;
      }
    }

    visibleColumns.forEach((columnClass) => {
      const width = Math.max(36, Math.floor(measuredWidths.get(columnClass) || 0));
      const columnCells = detailsPanel?.querySelectorAll?.(`.detailsSkills .cell.${columnClass}`);
      if (!columnCells?.length) return;

      columnCells.forEach((cell) => {
        if (!isVisibleCell(cell)) return;
        cell.style.minWidth = `${width}px`;
        cell.style.width = `${width}px`;
        cell.style.maxWidth = `${width}px`;
        cell.style.flex = `0 0 ${width}px`;
      });

      if (columnClass === "name") {
        detailsPanel?.style?.setProperty?.("--details-skill-name-width", `${width}px`);
      }
    });

    const panelRect = detailsPanel?.getBoundingClientRect?.();
    const currentWidth = Math.ceil(panelRect?.width || 0);
    const maxAllowedWidth = Math.max(520, Math.floor(window.innerWidth - Math.max(0, panelRect?.left || 0) - 12));
    if (currentWidth > maxAllowedWidth) {
      detailsPanel.style.width = `${maxAllowedWidth}px`;
    }
  };

  const syncSkillNameColumnWidth = (skills = []) => {
    if (!skillNameMeasureCtx) return;
    const fontSource =
      detailsPanel?.querySelector?.(".detailsSkills .skills .skillRow .skillNameText") ||
      detailsPanel?.querySelector?.(".detailsSkills .skillHeader .cell.name");
    if (!fontSource) return;

    const computed = window.getComputedStyle(fontSource);
    const font = computed?.font || `${computed.fontWeight} ${computed.fontSize} ${computed.fontFamily}`;
    if (!font) return;

    skillNameMeasureCtx.font = font;
    let widest = 0;
    for (let i = 0; i < skills.length; i++) {
      const width = skillNameMeasureCtx.measureText(String(skills[i]?.name ?? "")).width;
      if (width > widest) widest = width;
    }

    const panelRect = detailsPanel?.getBoundingClientRect?.();
    const panelWidth = Math.ceil(panelRect?.width || detailsPanel?.clientWidth || 0);
    const maxNameColumnWidth = Math.max(140, Math.floor(panelWidth * 0.28));
    const nextWidth = Math.min(Math.max(96, Math.ceil(widest + 20)), maxNameColumnWidth);
    if (Math.abs(nextWidth - lastSkillNameColumnWidth) <= 1) return;
    lastSkillNameColumnWidth = nextWidth;
    detailsPanel?.style?.setProperty?.("--details-skill-name-width", `${nextWidth}px`);
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
          renderSkills(lastDetails);
        }
      });
    });
    updateSkillHeaderSortState();
  };

  bindSkillHeaderSorting();
  syncSkillColumnMinWidths();

  const renderSkills = (details) => {
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
      });
    });
    const topSkills = [...groupedSkills.values()].sort(compareSkillSort);
    // .slice(0, 12);

    const totalDamage = Number(details?.totalDmg);
    const aggregatedDamage = topSkills.reduce((sum, skill) => sum + (Number(skill?.dmg) || 0), 0);
    const percentBaseTotal = totalDamage > 0 ? totalDamage : aggregatedDamage;

    ensureSkillSlots(topSkills.length);
    syncSkillNameColumnWidth(topSkills);

    for (let i = 0; i < skillSlots.length; i++) {
      const view = skillSlots[i];
      const skill = topSkills[i];

      if (!skill) {
        view.rowEl.style.display = "none";
        view.dmgFillEl.style.transform = "scaleX(0)";
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
      const heal = skill.heal || 0;
      const multiHitCount = skill.multiHitCount || 0;
      const multiHitDamage = skill.multiHitDamage || 0;

      const pct = (num, den) => (den > 0 ? Math.round((num / den) * 100) : 0);

      const damageRate = percentBaseTotal > 0 ? (damage / percentBaseTotal) * 100 : 0;

      const critRate = pct(crits, hits);
      const parryRate = pct(parry, hits);
      const backRate = pct(back, hits);
      const perfectRate = pct(perfect, hits);
      const doubleRate = pct(double, hits);

      view.nameTextEl.textContent = skill.name ?? "";
      const resolvedJob = skill.job || getActorJob(skill.actorId);
      const skillColor = resolvedJob ? getJobColor(resolvedJob) : "";
      view.nameTextEl.style.color = skillColor || "";
      view.hitEl.textContent = `${hits}`;
      view.critEl.textContent = `${critRate}%`;

      view.parryEl.textContent = `${parryRate}%`;
      view.backEl.textContent = `${backRate}%`;
      view.perfectEl.textContent = `${perfectRate}%`;
      view.doubleEl.textContent = `${doubleRate}%`;
      view.healEl.textContent = `${formatCount(heal)}`;
      view.multiHitEl.textContent = `${formatCount(multiHitCount)}`;
      view.multiHitDamageEl.textContent = `${formatDamageCompact(multiHitDamage)}`;

      view.dmgTextEl.textContent = `${formatDamageCompact(damage)} (${damageRate.toFixed(1)}%)`;
      view.dmgFillEl.style.transform = `scaleX(${barFillRatio})`;
    }

    syncSkillColumnMinWidths();
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

  const renderNicknameMenu = () => {
    if (!detailsNicknameMenu) return;
    detailsNicknameMenu.innerHTML = "";
    const allItem = document.createElement("button");
    allItem.type = "button";
    allItem.className = "detailsDropdownItem";
    allItem.dataset.value = "all";
    allItem.textContent = labelText("details.all", "All");
    if (!selectedAttackerIds || selectedAttackerIds.length === 0) {
      allItem.classList.add("isActive");
    }
    detailsNicknameMenu.appendChild(allItem);

    const target = getTargetById(selectedTargetId);
    const actorIds = getTargetActorIds(target);
    const actorEntries = actorIds
      .map((id) => ({
        id,
        damage: getActorDamage(target?.actorDamage, id),
        label: resolveActorLabel(id),
      }))
      .sort((a, b) => b.damage - a.damage);

    actorEntries.forEach((entry) => {
      const item = document.createElement("button");
      item.type = "button";
      item.className = "detailsDropdownItem";
      item.dataset.value = String(entry.id);
      item.textContent = entry.label;
      applyCjkClass(item, entry.label);
      const color = getJobColor(getActorJob(entry.id));
      if (color) {
        item.style.color = color;
      }
      if (selectedAttackerIds?.includes?.(entry.id)) {
        item.classList.add("isActive");
      }
      detailsNicknameMenu.appendChild(item);
    });
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

  const renderTargetMenu = () => {
    if (!detailsTargetMenu) return;
    detailsTargetMenu.innerHTML = "";
    const targetsSorted = [...detailsTargets].sort((a, b) => getTargetSortValue(b) - getTargetSortValue(a));

    const allItem = document.createElement("button");
    allItem.type = "button";
    allItem.className = "detailsDropdownItem";
    allItem.dataset.value = "all";
    allItem.textContent = labelText("details.all", "All");
    if (!selectedTargetId) {
      allItem.classList.add("isActive");
    }
    detailsTargetMenu.appendChild(allItem);

    targetsSorted.forEach((target) => {
      const item = document.createElement("button");
      item.type = "button";
      item.className = "detailsDropdownItem";
      item.dataset.value = String(target.targetId);
      const suffix = formatTargetSuffix(target);
      if (!suffix) {
        item.textContent = `Mob #${target.targetId}`;
      } else if (sortMode === "recent") {
        item.textContent = `Mob #${target.targetId} ${suffix}`;
      } else {
        item.textContent = `Mob #${target.targetId} (${suffix})`;
      }
      if (Number(target.targetId) === Number(selectedTargetId)) {
        item.classList.add("isActive");
      }
      detailsTargetMenu.appendChild(item);
    });
  };

  const syncSortButtons = () => {
    if (!detailsSortButtons) return;
    detailsSortButtons.forEach((button) => {
      const mode = button?.dataset?.sort;
      button.classList.toggle("isActive", mode === sortMode);
    });
  };

  const applyTargetSelection = async (targetId) => {
    if (targetId === "all") {
      selectedTargetId = null;
    } else {
      selectedTargetId = Number(targetId) || null;
    }
    const target = getTargetById(selectedTargetId);
    const actorIds = getTargetActorIds(target);
    if (targetId !== "all" && selectedAttackerIds && selectedAttackerIds.length > 0) {
      const stillValid = selectedAttackerIds.some((id) => actorIds.includes(id));
      if (!stillValid) {
        selectedAttackerIds = null;
        selectedAttackerLabel = "All";
      }
    }
    if (selectedAttackerIds && selectedAttackerIds.length === 1) {
      selectedAttackerLabel = resolveActorLabel(selectedAttackerIds[0]);
    }
    renderNicknameMenu();
    renderTargetMenu();
    updateHeaderText();
    await refreshDetailsView();
  };

  const applyAttackerSelection = async (actorId) => {
    if (actorId === "all") {
      selectedAttackerIds = null;
      selectedAttackerLabel = "All";
    } else {
      const numericId = Number(actorId);
      selectedAttackerIds = Number.isFinite(numericId) ? [numericId] : null;
      selectedAttackerLabel = selectedAttackerIds ? resolveActorLabel(numericId) : "All";
    }
    renderNicknameMenu();
    renderTargetMenu();
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
    let totalHeal = 0;

    skills.forEach((skill) => {
      const dmg = Number(skill?.dmg) || 0;
      totalDmg += dmg;
      totalHeal += Number(skill?.heal) || 0;
      totalMultiHitCount += Number(skill?.multiHitCount) || 0;
      totalMultiHitDamage += Number(skill?.multiHitDamage) || 0;
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
      contributionPct: totalTargetDamage > 0 ? (totalDmg / totalTargetDamage) * 100 : 0,
      totalCritPct: pct(totalCrit, totalTimes),
      totalParryPct: pct(totalParry, totalTimes),
      totalBackPct: pct(totalBack, totalTimes),
      totalPerfectPct: pct(totalPerfect, totalTimes),
      totalDoublePct: pct(totalDouble, totalTimes),
      multiHitCount: totalMultiHitCount,
      multiHitDamage: totalMultiHitDamage,
      totalHeal,
      combatTime: formatBattleTime(battleTimeMs),
      battleTimeMs,
      skills,
      showSkillIcons,
      perActorStats: combinePerActorStats(detailsList),
      showCombinedTotals: !selectedAttackerIds || selectedAttackerIds.length === 0,
    };
  };

  const refreshDetailsView = async (seq) => {
    if (!lastRow) return;
    if (!detailsContext) {
      const details = await getDetails(lastRow);
      if (typeof seq === "number" && seq !== openSeq) return;
      render(details, lastRow);
      return;
    }

    const showSkillIcons = !selectedAttackerIds || selectedAttackerIds.length === 0;
    if (selectedTargetId === null) {
      const targetList = detailsTargets.filter((target) => Number(target?.targetId) > 0);
      if (!targetList.length) {
        const details = await getDetails(lastRow, {
          targetId: null,
          attackerIds: selectedAttackerIds,
          totalTargetDamage: null,
          showSkillIcons,
        });
        if (typeof seq === "number" && seq !== openSeq) return;
        render(details, lastRow);
        return;
      }

      const detailsList = await Promise.all(
        targetList.map((target) =>
          getDetails(lastRow, {
            targetId: target.targetId,
            attackerIds: selectedAttackerIds,
            totalTargetDamage: target.totalDamage,
            showSkillIcons,
          })
        )
      );
      const totalTargetDamage = targetList.reduce(
        (sum, target) => sum + (Number(target?.totalDamage) || 0),
        0
      );
      const mergedDetails = buildCombinedDetails(detailsList, totalTargetDamage, showSkillIcons);
      if (typeof seq === "number" && seq !== openSeq) return;
      render(mergedDetails, lastRow);
      return;
    }

    const target = getTargetById(selectedTargetId);
    const totalTargetDamage = target ? target.totalDamage : null;
    const details = await getDetails(lastRow, {
      targetId: selectedTargetId,
      attackerIds: selectedAttackerIds,
      totalTargetDamage,
      showSkillIcons,
    });
    if (typeof seq === "number" && seq !== openSeq) return;
    render(details, lastRow);
  };

  detailsNicknameBtn?.addEventListener("click", (event) => {
    event.stopPropagation();
    detailsNicknameMenu?.classList.toggle("isOpen");
    detailsTargetMenu?.classList.remove("isOpen");
  });

  detailsTargetBtn?.addEventListener("click", (event) => {
    event.stopPropagation();
    detailsTargetMenu?.classList.toggle("isOpen");
    detailsNicknameMenu?.classList.remove("isOpen");
  });

  detailsNicknameMenu?.addEventListener("click", async (event) => {
    const button = event.target?.closest?.(".detailsDropdownItem");
    if (!button) return;
    const value = button.dataset.value;
    detailsNicknameMenu?.classList.remove("isOpen");
    await applyAttackerSelection(value);
  });

  detailsTargetMenu?.addEventListener("click", async (event) => {
    const button = event.target?.closest?.(".detailsDropdownItem");
    if (!button) return;
    const value = button.dataset.value;
    detailsTargetMenu?.classList.remove("isOpen");
    await applyTargetSelection(value);
  });

  detailsSortButtons?.forEach?.((button) => {
    button.addEventListener("click", async () => {
      const mode = button?.dataset?.sort;
      if (!mode || sortMode === mode) return;
      sortMode = mode;
      syncSortButtons();
      renderTargetMenu();
      updateHeaderText();
    });
  });

  document.addEventListener("click", (event) => {
    if (event.target?.closest?.(".detailsDropdownWrapper")) return;
    detailsNicknameMenu?.classList.remove("isOpen");
    detailsTargetMenu?.classList.remove("isOpen");
  });

  const render = (details, row) => {
    if (row?.id && row?.job) {
      const rowActorId = Number(row.id);
      if (Number.isFinite(rowActorId) && rowActorId > 0) {
        detectedJobByActorId.set(rowActorId, String(row.job));
      }
    }
    rememberJobsFromDetails(details);
    selectedAttackerLabel = selectedAttackerLabel || String(row.name ?? "");
    updateHeaderText();
    renderStats(details);
    renderSkills(details);
    lastRow = row;
    lastDetails = details;
  };

  const isOpen = () => detailsPanel.classList.contains("open");

  const open = async (
    row,
    { force = false, restartOnSwitch = true, defaultTargetAll = false, defaultTargetId = null, pin = true } = {}
  ) => {
    const rowId = row?.id ?? null;
    // if (!rowId) return;

    const isOpen = detailsPanel.classList.contains("open");
    const isSame = isOpen && openedRowId === rowId;
    const isSwitch = isOpen && openedRowId && openedRowId !== rowId;

    if (!force && isSame) return;

    if (isSwitch && restartOnSwitch) {
      close();
      requestAnimationFrame(() => {
        open(row, { force: true, restartOnSwitch: false, defaultTargetAll, defaultTargetId, pin });
      });
      return;
    }

    openedRowId = rowId;
    if (pin) {
      pinnedRowId = rowId;
      onPinnedRowChange?.(pinnedRowId);
    }
    lastRow = row;

    selectedAttackerLabel = resolveRowLabel(row);
    const rowIdNum = Number(rowId);
    selectedAttackerIds = Number.isFinite(rowIdNum) ? [rowIdNum] : null;
    loadDetailsContext();
    syncSelectedAttackersFromLabel();
    if (defaultTargetAll) {
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
    renderNicknameMenu();
    renderTargetMenu();
    syncSortButtons();
    updateHeaderText();
    detailsPanel.classList.add("open");

    // 이전 값 비우기
    for (let i = 0; i < statSlots.length; i++) statSlots[i].valueEl.textContent = "-";
    for (let i = 0; i < skillSlots.length; i++) {
      skillSlots[i].rowEl.style.display = "none";
      skillSlots[i].dmgFillEl.style.transform = "scaleX(0)";
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
    detailsPanel.classList.remove("open");
  };
  detailsClose?.addEventListener("click", close);

  const refresh = async () => {
    if (!detailsPanel.classList.contains("open") || !lastRow) return;
    const previousTargetId = selectedTargetId;
    const previousAttackerIds = Array.isArray(selectedAttackerIds) ? [...selectedAttackerIds] : null;
    const seq = ++openSeq;
    loadDetailsContext();
    selectedTargetId = previousTargetId;
    selectedAttackerIds = previousAttackerIds;
    syncSelectedAttackersFromLabel();
    renderNicknameMenu();
    renderTargetMenu();
    syncSortButtons();
    updateHeaderText();
    await refreshDetailsView(seq);
  };

  const isPinned = () => pinnedRowId !== null;

  return { open, close, isOpen, isPinned, render, updateLabels, refresh };
};
