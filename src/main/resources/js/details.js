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
}) => {
  let openedRowId = null;
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

  const clamp01 = (v) => Math.max(0, Math.min(1, v));

  const formatNum = (v) => {
    const n = Number(v);
    if (!Number.isFinite(n)) return "-";
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
      return `${(n / 1_000_000).toFixed(3)}m`;
    }
    if (abs >= 1_000) {
      return `${(n / 1_000).toFixed(3)}k`;
    }
    return `${n.toFixed(3)}`;
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
    { key: "details.stats.totalDamage", fallback: "Total Damage", getValue: (d) => formatNum(d?.totalDmg) },
    { key: "details.stats.contribution", fallback: "Contribution", getValue: (d) => pctText(d?.contributionPct) },
    { key: "details.stats.multiHitHits", fallback: "Multi-Hits", getValue: (d) => formatNum(d?.multiHitCount) },
    { key: "details.stats.multiHitDamage", fallback: "Multi-Hit Damage", getValue: (d) => formatNum(d?.multiHitDamage) },
    // { label: "보스 막기비율", getValue: (d) => d?.parry ?? "-" },
    // { label: "보스 회피비율", getValue: (d) => d?.eva ?? "-" },
    { key: "details.stats.critRate", fallback: "Crit Rate", getValue: (d) => pctText(d?.totalCritPct) },
    { key: "details.stats.perfectRate", fallback: "Perfect Rate", getValue: (d) => pctText(d?.totalPerfectPct) },
    { key: "details.stats.doubleRate", fallback: "Double Rate", getValue: (d) => pctText(d?.totalDoublePct) },
    { key: "details.stats.backRate", fallback: "Back Attack Rate", getValue: (d) => pctText(d?.totalBackPct) },
    { key: "details.stats.parryRate", fallback: "Parry Rate", getValue: (d) => pctText(d?.totalParryPct) },
    { key: "details.stats.combatTime", fallback: "Combat Time", getValue: (d) => d?.combatTime ?? "-" },
  ];

  const createStatView = (labelKey, fallbackLabel) => {
    const statEl = document.createElement("div");
    statEl.className = "stat";

    const labelEl = document.createElement("p");
    labelEl.className = "label";
    labelEl.textContent = labelText(labelKey, fallbackLabel);

    const valueEl = document.createElement("p");
    valueEl.className = "value";
    valueEl.textContent = "-";

    statEl.appendChild(labelEl);
    statEl.appendChild(valueEl);

    return { statEl, labelEl, valueEl, labelKey, fallbackLabel };
  };

  const statSlots = STATUS.map((def) => createStatView(def.key, def.fallback));
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
      slot.labelEl.textContent = labelText(slot.labelKey, slot.fallbackLabel);
    }
    updateHeaderText();
  };

  const resolveStatValue = (statKey, data) => {
    if (!data) return "-";
    switch (statKey) {
      case "details.stats.totalDamage":
        return formatNum(data.totalDmg);
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
      if (statKey !== "details.stats.combatTime") {
        actorStats.forEach((actor) => {
          const span = document.createElement("span");
          if (statKey === "details.stats.totalDamage") {
            span.textContent = formatCompactNumber(actor.totalDmg);
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
    const critEl = document.createElement("div");
    hitEl.className = "cell center hit";
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

    const multiHitEl = document.createElement("div");
    multiHitEl.className = "cell center mhit";

    const multiHitDamageEl = document.createElement("div");
    multiHitDamageEl.className = "cell center mdmg";

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
    rowEl.appendChild(critEl);
    rowEl.appendChild(parryEl);
    rowEl.appendChild(perfectEl);
    rowEl.appendChild(doubleEl);
    rowEl.appendChild(backEl);
    rowEl.appendChild(healEl);
    rowEl.appendChild(multiHitEl);
    rowEl.appendChild(multiHitDamageEl);

    rowEl.appendChild(dmgEl);

    return {
      rowEl,
      nameEl,
      nameTextEl,
      hitEl,
      critEl,
      parryEl,
      backEl,
      perfectEl,
      doubleEl,
      healEl,
      multiHitEl,
      multiHitDamageEl,
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
    const topSkills = [...groupedSkills.values()].sort(
      (a, b) => (Number(b?.dmg) || 0) - (Number(a?.dmg) || 0)
    );
    // .slice(0, 12);

    const totalDamage = Number(details?.totalDmg);
    if (!Number.isFinite(totalDamage) || totalDamage <= 0) {
      // uiDebug?.log("details:invalidTotalDmg", details);
      return;
    }
    const percentBaseTotal = totalDamage;

    ensureSkillSlots(topSkills.length);

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
      view.healEl.textContent = `${formatNum(heal)}`;
      view.multiHitEl.textContent = `${formatNum(multiHitCount)}`;
      view.multiHitDamageEl.textContent = `${formatNum(multiHitDamage)}`;

      view.dmgTextEl.textContent = `${formatDamageCompact(damage)} (${damageRate.toFixed(1)}%)`;
      view.dmgFillEl.style.transform = `scaleX(${barFillRatio})`;
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

  const resolveActorLabel = (actorId) => {
    const actor = detailsActors.get(Number(actorId));
    if (actor?.nickname && actor.nickname !== String(actorId)) return actor.nickname;
    return `Player #${actorId}`;
  };

  const resolveRowLabel = (row) => {
    if (!row) return "-";
    if (row.isIdentifying) {
      return `Player #${row.id ?? row.name ?? ""}`.trim();
    }
    return String(row.name ?? "-");
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
        };
        next.totalDmg += Number(entry?.totalDmg) || 0;
        if (!next.job && entry?.job) next.job = entry.job;
        next.totalTimes += Number(entry?.totalTimes) || 0;
        next.totalCrit += Number(entry?.totalCrit) || 0;
        next.totalParry += Number(entry?.totalParry) || 0;
        next.totalBack += Number(entry?.totalBack) || 0;
        next.totalPerfect += Number(entry?.totalPerfect) || 0;
        next.totalDouble += Number(entry?.totalDouble) || 0;
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

    skills.forEach((skill) => {
      const dmg = Number(skill?.dmg) || 0;
      totalDmg += dmg;
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
      contributionPct: totalTargetDamage > 0 ? (totalDmg / totalTargetDamage) * 100 : 0,
      totalCritPct: pct(totalCrit, totalTimes),
      totalParryPct: pct(totalParry, totalTimes),
      totalBackPct: pct(totalBack, totalTimes),
      totalPerfectPct: pct(totalPerfect, totalTimes),
      totalDoublePct: pct(totalDouble, totalTimes),
      multiHitCount: totalMultiHitCount,
      multiHitDamage: totalMultiHitDamage,
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

  const open = async (row, { force = false, restartOnSwitch = true, defaultTargetAll = false } = {}) => {
    const rowId = row?.id ?? null;
    // if (!rowId) return;

    const isOpen = detailsPanel.classList.contains("open");
    const isSame = isOpen && openedRowId === rowId;
    const isSwitch = isOpen && openedRowId && openedRowId !== rowId;

    if (!force && isSame) return;

    if (isSwitch && restartOnSwitch) {
      close();
      requestAnimationFrame(() => {
        open(row, { force: true, restartOnSwitch: false });
      });
      return;
    }

    openedRowId = rowId;
    lastRow = row;

    selectedAttackerLabel = resolveRowLabel(row);
    const rowIdNum = Number(rowId);
    selectedAttackerIds = Number.isFinite(rowIdNum) ? [rowIdNum] : null;
    loadDetailsContext();
    if (defaultTargetAll) {
      selectedTargetId = null;
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
  const close = () => {
    openSeq++;

    openedRowId = null;
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
    renderNicknameMenu();
    renderTargetMenu();
    syncSortButtons();
    updateHeaderText();
    await refreshDetailsView(seq);
  };

  return { open, close, isOpen, render, updateLabels, refresh };
};
