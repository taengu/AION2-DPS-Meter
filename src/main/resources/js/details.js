const createDetailsUI = ({
  detailsPanel,
  detailsClose,
  detailsTitle,
  detailsStatsEl,
  skillsListEl,
  dpsFormatter,
  getDetails,
}) => {
  let openedRowId = null;
  let openSeq = 0;

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
  const i18n = window.i18n;
  const labelText = (key, fallback) => i18n?.t?.(key, fallback) ?? fallback;
  const formatTitle = (name) =>
    i18n?.format?.("details.title", { name }, `${name} Details`) ?? `${name} Details`;

  const STATUS = [
    { key: "details.stats.totalDamage", fallback: "Total Damage", getValue: (d) => formatNum(d?.totalDmg) },
    { key: "details.stats.contribution", fallback: "Contribution", getValue: (d) => pctText(d?.contributionPct) },
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

  const updateLabels = () => {
    for (let i = 0; i < statSlots.length; i++) {
      const slot = statSlots[i];
      slot.labelEl.textContent = labelText(slot.labelKey, slot.fallbackLabel);
    }
    if (!detailsPanel.classList.contains("open")) {
      detailsTitle.textContent = labelText("details.header", "Details");
    } else if (currentRowName) {
      detailsTitle.textContent = formatTitle(currentRowName);
    }
  };

  const renderStats = (details) => {
    for (let i = 0; i < STATUS.length; i++) {
      statSlots[i].valueEl.textContent = STATUS[i].getValue(details);
    }
  };

  const createSkillView = () => {
    const rowEl = document.createElement("div");
    rowEl.className = "skillRow";

    const nameEl = document.createElement("div");
    nameEl.className = "cell name";

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

    rowEl.appendChild(dmgEl);

    return {
      rowEl,
      nameEl,
      hitEl,
      critEl,
      parryEl,
      backEl,
      perfectEl,
      doubleEl,
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
    const topSkills = [...skills].sort((a, b) => (Number(b?.dmg) || 0) - (Number(a?.dmg) || 0));
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

      const pct = (num, den) => (den > 0 ? Math.round((num / den) * 100) : 0);

      const damageRate = percentBaseTotal > 0 ? (damage / percentBaseTotal) * 100 : 0;

      const critRate = pct(crits, hits);
      const parryRate = pct(parry, hits);
      const backRate = pct(back, hits);
      const perfectRate = pct(perfect, hits);
      const doubleRate = pct(double, hits);

      view.nameEl.textContent = skill.name ?? "";
      view.hitEl.textContent = `${hits}`;
      view.critEl.textContent = `${critRate}%`;

      view.parryEl.textContent = `${parryRate}%`;
      view.backEl.textContent = `${backRate}%`;
      view.perfectEl.textContent = `${perfectRate}%`;
      view.doubleEl.textContent = `${doubleRate}%`;

      view.dmgTextEl.textContent = `${formatNum(damage)} (${damageRate.toFixed(1)}%)`;
      view.dmgFillEl.style.transform = `scaleX(${barFillRatio})`;
    }
  };

  let currentRowName = "";

  const render = (details, row) => {
    currentRowName = String(row.name);
    detailsTitle.textContent = formatTitle(currentRowName);
    renderStats(details);
    renderSkills(details);
  };

  const isOpen = () => detailsPanel.classList.contains("open");

  const open = async (row, { force = false, restartOnSwitch = true } = {}) => {
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

    currentRowName = String(row.name);
    detailsTitle.textContent = formatTitle(currentRowName);
    detailsPanel.classList.add("open");

    // 이전 값 비우기
    for (let i = 0; i < statSlots.length; i++) statSlots[i].valueEl.textContent = "-";
    for (let i = 0; i < skillSlots.length; i++) {
      skillSlots[i].rowEl.style.display = "none";
      skillSlots[i].dmgFillEl.style.transform = "scaleX(0)";
    }

    const seq = ++openSeq;

    try {
      const details = await getDetails(row);

      if (seq !== openSeq) return;

      render(details, row);
    } catch (e) {
      if (seq !== openSeq) return;
      // uiDebug?.log("getDetails:error", { id: rowId, message: e?.message });
    }
  };
  const close = () => {
    openSeq++;

    openedRowId = null;
    detailsPanel.classList.remove("open");
  };
  detailsClose?.addEventListener("click", close);

  return { open, close, isOpen, render, updateLabels };
};
