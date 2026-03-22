// battleTime.js
const createBattleTimeUI = ({
  rootEl,
  tickSelector,
  statusSelector,
  analysisSelector,
  getAnalysisText,
  graceMs,
  graceArmMs,
  idleMs = 60000,
  visibleClass,
} = {}) => {
  if (!rootEl) return null;

  const tickEl = rootEl.querySelector(tickSelector);
  const statusEl = statusSelector ? rootEl.querySelector(statusSelector) : null;
  const analysisEl = analysisSelector ? rootEl.querySelector(analysisSelector) : null;

  let lastBattleTimeMs = null;

  let currentState = "";

  let lastChangedAt = 0;

  let lastSeenAt = 0;

  let analysisTextProvider = getAnalysisText;

  const formatMMSS = (ms) => {
    const v = Math.max(0, Math.floor(Number(ms) || 0));
    const sec = Math.floor(v / 1000);
    const mm = String(Math.floor(sec / 60)).padStart(2, "0");
    const ss = String(sec % 60).padStart(2, "0");
    return `${mm}:${ss}`;
  };

  const setState = (state) => {
    const next = state || "";
    if (currentState === next) return;
    rootEl.classList.remove("state-fighting", "state-grace", "state-ended", "state-idle");
    if (state) rootEl.classList.add(state);
    currentState = next;

    if (statusEl) statusEl.dataset.state = state || "";
    if (analysisEl) {
      const shouldShow =
        state === "state-grace" || state === "state-ended" || state === "state-idle";
      analysisEl.style.display = shouldShow ? "" : "none";
      if (shouldShow) {
        const text = analysisTextProvider ? analysisTextProvider() : "Analysing data...";
        analysisEl.textContent = text;
      }
    }
  };

  const setVisible = (visible) => {
    rootEl.classList.toggle(visibleClass, !!visible);
    if (!visible) {
      setState("");
    }
  };

  const reset = () => {
    lastBattleTimeMs = null;
    lastChangedAt = 0;
    lastSeenAt = 0;

    if (tickEl) tickEl.textContent = "00:00";
    setState("");
  };

  const update = (now, battleTimeMs) => {
    lastSeenAt = now;

    const bt = Number(battleTimeMs);
    if (!Number.isFinite(bt)) return;

    const formatted = formatMMSS(bt);
    if (tickEl && tickEl.textContent !== formatted) tickEl.textContent = formatted;

    if (lastBattleTimeMs === null) {
      lastBattleTimeMs = bt;
      lastChangedAt = now;
      setState("state-fighting");
      return;
    }

    if (bt !== lastBattleTimeMs) {
      lastBattleTimeMs = bt;
      lastChangedAt = now;
      setState("state-fighting");
      return;
    }

    const frozenMs = Math.max(0, now - lastChangedAt);

    if (frozenMs >= idleMs) setState("state-idle");
    else if (frozenMs >= graceMs) setState("state-ended");
    else if (frozenMs >= graceArmMs) setState("state-grace");
    else setState("state-fighting");
  };

  const render = (now) => {
    if (lastBattleTimeMs === null) return;

    const frozenMs = Math.max(0, now - lastChangedAt);
    if (frozenMs >= idleMs) setState("state-idle");
    else if (frozenMs >= graceMs) setState("state-ended");
    else if (frozenMs >= graceArmMs) setState("state-grace");
    else setState("state-fighting");
  };

  const getCombatTimeText = () => formatMMSS(lastBattleTimeMs ?? 0);

  const getState = () => currentState;
  const isEnded = () => currentState === "state-ended";

  const setAnalysisTextProvider = (provider) => {
    analysisTextProvider = provider;
    if (analysisEl) {
      const shouldShow =
        currentState === "state-grace" ||
        currentState === "state-ended" ||
        currentState === "state-idle";
      if (shouldShow) {
        const text = analysisTextProvider ? analysisTextProvider() : "Analysing data...";
        analysisEl.textContent = text;
      }
    }
  };

  return {
    setVisible,
    update,
    render,
    reset,
    getCombatTimeText,
    getState,
    isEnded,
    setAnalysisTextProvider,
  };
};
