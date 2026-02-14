const createMeterUI = ({
  elList,
  dpsFormatter,
  getUserName,
  onClickUserRow,
  onHoverUserRow,
  onLeaveUserRow,
  getMetric,
  getSortDirection,
  getPinUserToTop,
}) => {
  const MAX_CACHE = 32;
  const cjkRegex = /[\u3400-\u9FFF\uF900-\uFAFF]/;
  const classIconSrcByJob = new Map();

  const rowViewById = new Map();
  let lastVisibleIds = new Set();
  let pendingRenderRows = null;
  let renderRowsRafId = 0;

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
      isVisible: false,
      lastNameText: "",
      lastIsCjk: false,
      lastMetricText: "",
      lastContributionText: "",
      lastFillRatio: -1,
      lastClassIconSrc: "",
      lastIsUser: false,
      lastIsIdentifying: false,
      hoverRipplePlayed: false,
      hoverRippleTimer: null,
    };

    rowEl.addEventListener("mouseenter", (event) => {
      if (!view.hoverRipplePlayed) {
        view.rowEl.classList.add("hoverRippleOnce");
        view.hoverRipplePlayed = true;
        if (view.hoverRippleTimer) {
          clearTimeout(view.hoverRippleTimer);
        }
        view.hoverRippleTimer = setTimeout(() => {
          view.rowEl.classList.remove("hoverRippleOnce");
          view.hoverRippleTimer = null;
        }, 950);
      }
      onHoverUserRow?.(view.currentRow, event);
    });


    rowEl.addEventListener("mousemove", (event) => {
      onHoverUserRow?.(view.currentRow, event);
    });
    rowEl.addEventListener("mouseleave", () => {
      view.hoverRipplePlayed = false;
      if (view.hoverRippleTimer) {
        clearTimeout(view.hoverRippleTimer);
        view.hoverRippleTimer = null;
      }
      view.rowEl.classList.remove("hoverRippleOnce");
      onLeaveUserRow?.(view.currentRow);
    });

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
    const pinUser = typeof getPinUserToTop === "function" && getPinUserToTop();
    if (pinUser) {
      return [user, ...top6.filter((row) => !row.isUser)];
    }
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
    const visibleTotalDamage = rows.reduce((sum, row) => sum + (Number(row?.totalDamage) || 0), 0);

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

      if (!view.isVisible) {
        view.rowEl.style.display = "";
        view.isVisible = true;
      }

      const isUser = !!row.isUser;
      if (view.lastIsUser !== isUser) {
        view.rowEl.classList.toggle("isUser", isUser);
        view.lastIsUser = isUser;
      }

      const isIdentifying = !!row.isIdentifying;
      if (view.lastIsIdentifying !== isIdentifying) {
        view.rowEl.classList.toggle("isIdentifying", isIdentifying);
        view.lastIsIdentifying = isIdentifying;
      }

      const rowId = row.id ?? row.name ?? "";
      const nameText = row.isIdentifying
        ? window.i18n?.format?.("meter.identifyingPlayer", { id: rowId }, `Player #${rowId}`) ??
          `Player #${rowId}`
        : row.name ?? "";
      if (view.lastNameText !== nameText) {
        view.nameEl.textContent = nameText;
        view.lastNameText = nameText;
      }

      const isCjk = cjkRegex.test(nameText);
      if (view.lastIsCjk !== isCjk) {
        view.nameEl.classList.toggle("isCjk", isCjk);
        view.lastIsCjk = isCjk;
      }

      if (row.job && !!row.job) {
        if (!classIconSrcByJob.has(row.job)) {
          classIconSrcByJob.set(row.job, `./assets/${row.job}.png`);
        }
        const src = classIconSrcByJob.get(row.job);
        if (view.lastClassIconSrc !== src) {
          view.classIconImg.src = src;
          view.lastClassIconSrc = src;
        }
        if (view.classIconImg.style.visibility !== "visible") {
          view.classIconImg.style.visibility = "visible";
        }
      } else {
        if (view.lastClassIconSrc) {
          view.lastClassIconSrc = "";
          view.classIconImg.removeAttribute("src");
        }
        if (view.classIconImg.style.visibility !== "hidden") {
          view.classIconImg.style.visibility = "hidden";
        }
      }

      // view.classIconEl.style.display = "";

      const metric = resolveMetric(row) || { value: 0, text: "-" };
      const metricValue = Number(metric.value) || 0;
      const damageContribution =
        visibleTotalDamage > 0 ? (Number(row.totalDamage) / visibleTotalDamage) * 100 : 0;

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

      const metricText = metric.text;
      if (view.lastMetricText !== metricText) {
        view.dpsNumber.textContent = metricText;
        view.lastMetricText = metricText;
      }

      const contributionText = `${damageContribution.toFixed(1)}%`;
      if (view.lastContributionText !== contributionText) {
        view.dpsContribution.textContent = contributionText;
        view.lastContributionText = contributionText;
      }

      const ratio = Math.max(0, Math.min(1, metricValue / topMetric));
      if (view.lastFillRatio !== ratio) {
        view.fillEl.style.transform = `scaleX(${ratio})`;
        view.lastFillRatio = ratio;
      }

      elList.appendChild(view.rowEl);
    }

    for (const id of lastVisibleIds) {
      if (nextVisibleIds.has(id)) continue;
      const view = rowViewById.get(id);
      if (view && view.isVisible) {
        view.rowEl.style.display = "none";
        view.isVisible = false;
      }
    }

    lastVisibleIds = nextVisibleIds;

    pruneCache(nextVisibleIds);
  };

  const flushPendingRows = () => {
    renderRowsRafId = 0;
    const rows = pendingRenderRows;
    pendingRenderRows = null;
    if (!rows) return;

    const arr = Array.isArray(rows) ? rows.slice() : [];
    const sortDirection = typeof getSortDirection === "function" ? getSortDirection() : "desc";
    arr.sort((a, b) => {
      const aMetric = Number(resolveMetric(a)?.value) || 0;
      const bMetric = Number(resolveMetric(b)?.value) || 0;
      return sortDirection === "asc" ? aMetric - bMetric : bMetric - aMetric;
    });
    renderRows(getDisplayRows(arr));
  };

  const updateFromRows = (rows) => {
    pendingRenderRows = rows;
    if (renderRowsRafId) return;
    renderRowsRafId = requestAnimationFrame(flushPendingRows);
  };
  const onResetMeterUi = () => {
    if (renderRowsRafId) {
      cancelAnimationFrame(renderRowsRafId);
      renderRowsRafId = 0;
    }
    pendingRenderRows = null;
    elList.classList.remove("hasRows");
    lastVisibleIds = new Set();

    const battleTimeEl = elList.querySelector(".battleTime");
    if (battleTimeEl) {
      elList.replaceChildren(battleTimeEl);
    } else {
      elList.replaceChildren();
    }

    rowViewById.clear();
    classIconSrcByJob.clear();
  };

  const getRowById = (id) => {
    if (id === null || id === undefined) return null;
    const view = rowViewById.get(id) || rowViewById.get(String(id));
    return view?.currentRow || null;
  };

  return { updateFromRows, onResetMeterUi, getRowById };
};
