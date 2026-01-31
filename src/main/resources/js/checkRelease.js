(() => {
  const API = "https://api.github.com/repos/taengu/Aion2-Dps-Meter/releases?per_page=10";
  const URL = "https://github.com/taengu/Aion2-Dps-Meter/releases";
  const START_DELAY = 800,
    RETRY = 500,
    LIMIT = 5;

  const n = (v) => {
    const [a = 0, b = 0, c = 0] = String(v || "")
      .trim()
      .replace(/^v/i, "")
      .split(".")
      .map(Number);
    return a * 1e6 + b * 1e3 + c;
  };

  let modal;
  let text;
  let once = false;

  const start = () =>
    setTimeout(async () => {
      if (once) {
        return;
      }
      once = true;

      modal = document.querySelector("#updateModal");
      text = document.querySelector("#updateModalText");

      document.querySelector(".updateModalBtn").onclick = () => {
        modal.classList.remove("isOpen");
        window.javaBridge.openBrowser(URL);
        window.javaBridge.exitApp();
      };

      for (
        let i = 0;
        i < LIMIT && !(window.dpsData?.getVersion && window.javaBridge?.openBrowser);
        i++
      ) {
        await new Promise((r) => setTimeout(r, RETRY));
      }
      if (!(window.dpsData?.getVersion && window.javaBridge?.openBrowser)) {
        return;
      }

      const current = String(window.dpsData.getVersion() || "").trim();
      const res = await fetch(API, {
        headers: { Accept: "application/vnd.github+json" },
        cache: "no-store",
      });
      if (!res.ok) {
        return;
      }

      const releases = await res.json();
      const latest = releases.find((release) => {
        const tag = String(release?.tag_name || "").trim().toLowerCase();
        if (tag.startsWith("pre")) {
          return false;
        }
        return !release?.draft && !release?.prerelease;
      })?.tag_name;
      if (latest && n(latest) > n(current)) {
        const fallback = `A new update is available!\n\nCurrent version: v.${current}\nLatest version: v.${latest}\n\nPlease update before continuing.`;
        text.textContent =
          window.i18n?.format?.("update.text", { current, latest }, fallback) || fallback;
        modal.classList.add("isOpen");
      }
    }, START_DELAY);

  window.ReleaseChecker = { start };
})();
