const createMeterUI = ({ elList, dpsFormatter, getUserName, onClickUserRow, getMetric }) => {
  const MAX_CACHE = 32;

  const rowViewById = new Map();
  let lastVisibleIds = new Set();

  const nowMs = () => (typeof performance !== "undefined" ? performance.now() : Date.now());

  const createRowView = (id) => {
    const rowEl = document.createElement("div");
    rowEl.className = "item";
    rowEl.style.display = "none";
    rowEl.dataset.rowId = String(id);

    const fillEl = document.createElement("div");
    fillEl.className = "fill";

    const contentEl = document.createElement("div");
    contentEl.className = "content";

    const classIconEl = document.createElement("div");
    classIconEl.className = "classIcon";

    const classIconImg = document.createElement("img");
    classIconImg.className = "classIconImg";
    classIconImg.style.visibility = "hidden";

    classIconImg.draggable = false;

    classIconEl.appendChild(classIconImg);

    const nameEl = document.createElement("div");
    nameEl.className = "name";

    const dpsContainer = document.createElement("div");
    const dpsNumber = document.createElement("p");
    dpsContainer.className = "dps";
    const dpsContribution = document.createElement("p");
    dpsContribution.className = "dpsContribution";

    dpsContainer.appendChild(dpsNumber);
    dpsContainer.appendChild(dpsContribution);

    contentEl.appendChild(classIconEl);
    contentEl.appendChild(nameEl);
    contentEl.appendChild(dpsContainer);
    rowEl.appendChild(fillEl);
    rowEl.appendChild(contentEl);

    const view = {
      id,
      rowEl,
      prevContribClass: "",
      nameEl,
      dpsContainer,
      classIconEl,
      classIconImg,
      dpsNumber,
      dpsContribution,
      fillEl,
      currentRow: null,
      lastSeenAt: 0,
    };

    rowEl.addEventListener("click", () => {
      // if (view.currentRow?.isUser)
      onClickUserRow?.(view.currentRow);
    });

    return view;
  };

  const getRowView = (id) => {
    let view = rowViewById.get(id);
    if (!view) {
      view = createRowView(id);
      rowViewById.set(id, view);
      elList.appendChild(view.rowEl);
    }
    return view;
  };

  // 상위 6개 + 유저(유저가 top6 밖이면 7개)
  const getDisplayRows = (sortedAll) => {
    const top6 = sortedAll.slice(0, 6);
    const user = sortedAll.find((x) => x.isUser);

    if (!user) return top6;
    if (top6.some((x) => x.isUser)) return top6;
    return [...top6, user];
  };

  const pruneCache = (keepIds) => {
    if (rowViewById.size <= MAX_CACHE) return;

    const candidates = [];
    for (const [id, view] of rowViewById) {
      if (keepIds.has(id)) {
        continue;
      }
      candidates.push({ id, t: view.lastSeenAt || 0 });
    }

    candidates.sort((a, b) => a.t - b.t); // 오래된거 제거

    for (let i = 0; rowViewById.size > MAX_CACHE && i < candidates.length; i++) {
      const id = candidates[i].id;
      const view = rowViewById.get(id);
      if (!view) continue;
      view.rowEl.remove();
      rowViewById.delete(id);
    }
  };

  const resolveMetric = (row) => {
    if (typeof getMetric === "function") {
      return getMetric(row);
    }
    const dps = Number(row?.dps) || 0;
    return { value: dps, text: `${dpsFormatter.format(dps)}/s` };
  };

  const renderRows = (rows) => {
    const now = nowMs();
    const nextVisibleIds = new Set();

    elList.classList.toggle("hasRows", rows.length > 0);

    let topMetric = 1;
    for (const row of rows) {
      const metricValue = Number(resolveMetric(row)?.value) || 0;
      topMetric = Math.max(topMetric, metricValue);
    }

    for (const row of rows) {
      if (!row) {
        continue;
      }

      const id = row.id ?? row.name;
      if (!id) {
        continue;
      }

      nextVisibleIds.add(id);

      const view = getRowView(id);
      view.currentRow = row;
      view.lastSeenAt = now;

      view.rowEl.style.display = "";
      view.rowEl.classList.toggle("isUser", !!row.isUser);

      view.nameEl.textContent = row.name ?? "";
      if (row.job && !!row.job) {
        const src = `./assets/${row.job}.png`;
        view.classIconImg.src = src;
        view.classIconImg.style.visibility = "visible";
      } else {
        view.classIconImg.style.visibility = "hidden";
      }

      // view.classIconEl.style.display = "";

      const metric = resolveMetric(row) || { value: 0, text: "-" };
      const metricValue = Number(metric.value) || 0;
      const damageContribution = Number(row.damageContribution) || 0;

      let contributionClass = "";
      if (damageContribution < 3) {
        contributionClass = "error";
      } else if (damageContribution < 5) {
        contributionClass = "warning";
      }
      if (view.prevContribClass !== contributionClass) {
        if (view.prevContribClass) {
          view.rowEl.classList.remove(view.prevContribClass);
        }
        if (contributionClass) {
          view.rowEl.classList.add(contributionClass);
        }
        view.prevContribClass = contributionClass;
      }

      view.dpsNumber.textContent = metric.text;
      view.dpsContribution.textContent = `${damageContribution.toFixed(1)}%`;
      const ratio = Math.max(0, Math.min(1, metricValue / topMetric));
      view.fillEl.style.transform = `scaleX(${ratio})`;

      elList.appendChild(view.rowEl);
    }

    for (const id of lastVisibleIds) {
      if (nextVisibleIds.has(id)) continue;
      const view = rowViewById.get(id);
      if (view) view.rowEl.style.display = "none";
    }

    lastVisibleIds = nextVisibleIds;

    pruneCache(nextVisibleIds);
  };

  const updateFromRows = (rows) => {
    const arr = Array.isArray(rows) ? rows.slice() : [];
    arr.sort((a, b) => {
      const aMetric = Number(resolveMetric(a)?.value) || 0;
      const bMetric = Number(resolveMetric(b)?.value) || 0;
      return bMetric - aMetric;
    });
    renderRows(getDisplayRows(arr));
  };
  const onResetMeterUi = () => {
    elList.classList.remove("hasRows");
    lastVisibleIds = new Set();

    const battleTimeEl = elList.querySelector(".battleTime");
    if (battleTimeEl) {
      elList.replaceChildren(battleTimeEl);
    } else {
      elList.replaceChildren();
    }

    rowViewById.clear();
  };
  return { updateFromRows, onResetMeterUi };
};
