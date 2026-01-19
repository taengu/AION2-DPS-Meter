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

  const STATUS = [
    { label: "누적 피해량", getValue: (d) => formatNum(d?.totalDmg) },
    { label: "피해량 기여도", getValue: (d) => d?.percent ?? "-" },
    // { label: "보스 막기비율", getValue: (d) => d?.parry ?? "-" },
    // { label: "보스 회피비율", getValue: (d) => d?.eva ?? "-" },
    { label: "전투시간", getValue: (d) => d?.combatTime ?? "-" },
  ];

  const createStatView = (labelText) => {
    const statEl = document.createElement("div");
    statEl.className = "stat";

    const labelEl = document.createElement("p");
    labelEl.className = "label";
    labelEl.textContent = labelText;

    const valueEl = document.createElement("p");
    valueEl.className = "value";
    valueEl.textContent = "-";

    statEl.appendChild(labelEl);
    statEl.appendChild(valueEl);

    return { statEl, valueEl };
  };

  const statSlots = STATUS.map((def) => createStatView(def.label));
  statSlots.forEach((value) => detailsStatsEl.appendChild(value.statEl));

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

    const castEl = document.createElement("div");
    castEl.className = "cell cast";
    const hitEl = document.createElement("span");
    hitEl.className = "castHit";

    const critEl = document.createElement("span");
    critEl.className = "castCrit";
    castEl.appendChild(hitEl);
    castEl.appendChild(critEl);

    const dmgEl = document.createElement("div");
    dmgEl.className = "cell dmg right";

    const dmgFillEl = document.createElement("div");
    dmgFillEl.className = "dmgFill";

    const dmgTextEl = document.createElement("div");
    dmgTextEl.className = "dmgText";

    dmgEl.appendChild(dmgFillEl);
    dmgEl.appendChild(dmgTextEl);

    rowEl.appendChild(nameEl);
    rowEl.appendChild(castEl);
    rowEl.appendChild(dmgEl);

    return { rowEl, nameEl, castEl, hitEl, critEl, dmgFillEl, dmgTextEl };
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
    const topSkills = [...skills]
      .sort((a, b) => (Number(b?.dmg) || 0) - (Number(a?.dmg) || 0))
      .slice(0, 12);

    const totalDamage = Number(details?.totalDmg);
    if (!Number.isFinite(totalDamage) || totalDamage <= 0) {
      uiDebug?.log("details:invalidTotalDmg", details);
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

      const damage = Number(skill.dmg) || 0;
      const damagePercent = (damage / percentBaseTotal) * 100;
      const damagePercentRounded = Math.round(damagePercent);
      const barFillRatio = clamp01(damage / percentBaseTotal);
      const hits = Number(skill.time) || 0;
      const crits = Number(skill.crit) || 0;

      // 0으로 나누기 방지
      const critRate = hits > 0 ? Math.floor((crits / hits) * 100) : 0;

      view.nameEl.textContent = skill.name ?? "";
      view.hitEl.textContent = `${hits}회`;
      view.critEl.textContent = `(${critRate}%)`;
      view.dmgTextEl.textContent = `${formatNum(damage)} (${damagePercentRounded}%)`;
      view.dmgFillEl.style.transform = `scaleX(${barFillRatio})`;
    }
  };

  const render = (details, row) => {
    detailsTitle.textContent = `${String(row.name)} 상세내역`;
    renderStats(details);
    renderSkills(details);
  };

  const isOpen = () => detailsPanel.classList.contains("open");

  const open = async (row, { force = false, restartOnSwitch = true } = {}) => {
    const rowId = row?.id ?? null;
    if (!rowId) return;

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

    detailsTitle.textContent = `${row.name} 상세내역`;
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
      uiDebug?.log("getDetails:error", { id: rowId, message: e?.message });
    }
  };
  const close = () => {
    openSeq++;

    openedRowId = null;
    detailsPanel.classList.remove("open");
  };
  detailsClose?.addEventListener("click", close);

  return { open, close, isOpen, render };
};
