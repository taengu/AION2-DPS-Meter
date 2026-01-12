const createMeterUI = ({ elList, dpsFormatter, getUserName, onClickUserRow }) => {
  const createRowView = () => {
    const rowEl = document.createElement("div");
    rowEl.className = "item";
    rowEl.style.display = "none";

    const fillEl = document.createElement("div");
    fillEl.className = "fill";

    const contentEl = document.createElement("div");
    contentEl.className = "content";

    const classIconEl = document.createElement("div");
    classIconEl.className = "classIcon";

    const nameEl = document.createElement("div");
    nameEl.className = "name";

    const dpsEl = document.createElement("div");
    dpsEl.className = "dps";

    contentEl.appendChild(classIconEl);
    contentEl.appendChild(nameEl);
    contentEl.appendChild(dpsEl);

    rowEl.appendChild(fillEl);
    rowEl.appendChild(contentEl);

    const view = { rowEl, nameEl, dpsEl, fillEl, currentRow: null };

    rowEl.addEventListener("click", () => {
      if (view.currentRow?.isUser) onClickUserRow?.(view.currentRow);
    });

    return view;
  };

  const rowSlots = Array.from({ length: 7 }, createRowView);
  rowSlots.forEach((v) => elList.appendChild(v.rowEl));

  // 상위 6개 + 유저(유저가 top6 밖이면 7개)
  const getDisplayRows = (sortedAll) => {
    const top6 = sortedAll.slice(0, 6);
    const isUser = sortedAll.find((x) => x.isUser);

    if (!isUser) return top6;
    if (top6.some((x) => x.isUser)) return top6;
    return [...top6, isUser];
  };

  const buildRowsFromJson = (jsonStr) => {
    const obj = JSON.parse(jsonStr);
    const userName = getUserName();

    const rows = [];
    for (const [name, value] of Object.entries(obj)) {
      const dps = Math.trunc(Number(value));
      if (!Number.isFinite(dps)) continue;

      rows.push({
        id: name,
        name,
        dps,
        isUser: name === userName,
      });
    }
    return rows;
  };

  const renderRows = (rows) => {
    elList.classList.toggle("hasRows", rows.length > 0);
    
    let topDps = 1;

    for (const row of rows) topDps = Math.max(topDps, Number(row?.dps) || 0);

    for (let i = 0; i < rowSlots.length; i++) {
      const view = rowSlots[i];
      const row = rows[i];

      if (!row) {
        view.currentRow = null;
        view.rowEl.style.display = "none";
        view.fillEl.style.transform = "scaleX(0)";
        continue;
      }

      view.currentRow = row;
      view.rowEl.style.display = "";
      view.rowEl.classList.toggle("isUser", !!row.isUser);

      view.nameEl.textContent = row.name ?? "";

      const dps = Number(row.dps) || 0;
      view.dpsEl.textContent = `${dpsFormatter.format(dps)}/초`;

      const ratio = Math.max(0, Math.min(1, dps / topDps));
      view.fillEl.style.transform = `scaleX(${ratio})`;
    }
  };

  const updateFromJson = (jsonStr) => {
    const rows = buildRowsFromJson(jsonStr);
    rows.sort((a, b) => (Number(b.dps) || 0) - (Number(a.dps) || 0));
    renderRows(getDisplayRows(rows));
  };

  const setOnClickUserRow = (fn) => {
    onClickUserRow = fn;
  };

  return { updateFromJson, setOnClickUserRow };
};
