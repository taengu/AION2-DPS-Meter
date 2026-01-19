const createBattleTimeUI = ({
  rootEl,
  tickSelector,
  statusSelector,
  graceMs,
  graceArmMs,
  visibleClass,
} = {}) => {
  if (!rootEl) return null;
  const tickEl = rootEl.querySelector(tickSelector);
  const statusEl = statusSelector ? rootEl.querySelector(statusSelector) : null;

  let fightOn = false;
  let fightStartAt = 0;
  let lastActiveAt = 0;
  let fightEndAt = 0;

  const formatMMSS = (ms) => {
    const sec = Math.max(0, Math.floor(ms / 1000));
    const mm = String(Math.floor(sec / 60)).padStart(2, "0");
    const ss = String(sec % 60).padStart(2, "0");
    return `${mm}:${ss}`;
  };

  const setState = (state) => {
    rootEl.classList.remove("state-fighting", "state-grace", "state-ended");
    if (state) {
      rootEl.classList.add(state);
    }
    if (statusEl) {
      statusEl.dataset.state = state || "";
    }
  };

  const getFightMs = () => {
    if (!fightStartAt) {
      return 0;
    }
    const endAt = fightOn ? lastActiveAt : fightEndAt || lastActiveAt;
    return Math.max(0, endAt - fightStartAt);
  };

  const setVisible = (visible) => {
    rootEl.classList.toggle(visibleClass, !!visible);
  };

  const reset = () => {
    fightOn = false;
    fightStartAt = 0;
    lastActiveAt = 0;
    fightEndAt = 0;

    if (tickEl) {
      tickEl.textContent = "00:00";
    }
    setState("");
  };

  const update = (now, isActivity) => {
    if (fightStartAt === 0) {
      if (isActivity) {
        fightOn = true;
        fightStartAt = now;
        lastActiveAt = now;
        fightEndAt = 0;
      }
      return;
    }
    //전투중
    if (fightOn) {
      if (isActivity) {
        lastActiveAt = now;
        return;
      }

      // 유예 초과면 전투 종료
      const inactiveMs = now - lastActiveAt;
      if (inactiveMs >= graceMs) {
        fightOn = false;
        fightEndAt = lastActiveAt; // 마지막 전투시간
      }
      return;
    }
    //전투 종료 후 boolean오면 재시작
    if (isActivity) {
      fightOn = true;
      fightStartAt = now;
      lastActiveAt = now;
      fightEndAt = 0;
    }
  };

  const render = (now) => {
    if (tickEl) {
      tickEl.textContent = formatMMSS(getFightMs());
    }
    if (fightOn) {
      const inactiveMs = Math.max(0, now - lastActiveAt);
      if (inactiveMs >= graceArmMs) {
        setState("state-grace");
      } else {
        setState("state-fighting");
      }
      return;
    }

    if (fightStartAt) {
      setState("state-ended");
    } else {
      setState("");
    }
  };

  const getCombatTimeText = () => formatMMSS(getFightMs());

  return { setVisible, update, render, reset, getCombatTimeText };
};
