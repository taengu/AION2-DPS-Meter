(() => {
  const GITHUB_API = "https://api.github.com/repos/taengu/AION2-DPS-Meter/releases?per_page=10";
  const R2_MANIFEST = "https://pub-cab14a472a79408cad24a89801c89db1.r2.dev/latest.json";
  const RELEASES_URL = "https://github.com/taengu/AION2-DPS-Meter/releases";
  const FETCH_TIMEOUT = 6000;
  const START_DELAY = 800,
    RETRY = 500,
    LIMIT = 5;

  const parseVersion = (v) => {
    const cleaned = String(v || "").trim().replace(/^v/i, "");
    const [base, prerelease] = cleaned.split("-", 2);
    const [a = 0, b = 0, c = 0] = String(base || "")
      .split(".")
      .map(Number);
    return {
      base,
      prerelease: Boolean(prerelease),
      value: a * 1e6 + b * 1e3 + c,
    };
  };

  let modal, textEl, actionsEl, progressSection, progressBar, progressText, statusText;
  let once = false;
  let msiUrl = null;
  let releaseUrl = null;

  const $ = (sel) => document.querySelector(sel);

  const showActions = () => {
    actionsEl.style.display = "flex";
    progressSection.style.display = "none";
    statusText.style.display = "none";
  };

  const showProgress = () => {
    actionsEl.style.display = "none";
    progressSection.style.display = "block";
    statusText.style.display = "none";
    progressBar.style.width = "0%";
    progressText.textContent = "0%";
  };

  const showStatus = (msg, isError) => {
    actionsEl.style.display = "none";
    progressSection.style.display = "none";
    statusText.style.display = "block";
    statusText.textContent = msg;
    statusText.style.color = isError ? "#ff5252" : "#4caf50";
  };

  const startDownload = () => {
    if (!msiUrl || !window.javaBridge?.startUpdate) return;
    showProgress();
    window.javaBridge.startUpdate(msiUrl);
  };

  // Callbacks invoked from Kotlin via executeScript
  window.onDownloadProgress = (percent) => {
    if (progressBar && progressText) {
      progressBar.style.width = percent + "%";
      progressText.textContent = percent + "%";
    }
  };

  window.onDownloadComplete = () => {
    const msg = window.i18n?.t?.("update.installing") || "Installing update...";
    showStatus(msg, false);
  };

  window.onDownloadError = () => {
    const msg = window.i18n?.t?.("update.downloadError") || "Download failed. Please try again or install manually.";
    showStatus(msg, true);
    // Show actions again after a moment so user can retry or go manual
    setTimeout(showActions, 2000);
  };

  window.onDownloadCancelled = () => {
    showActions();
  };

  const start = () =>
    setTimeout(async () => {
      if (once) return;
      once = true;

      modal = $("#updateModal");
      textEl = $("#updateModalText");
      actionsEl = $("#updateModalActions");
      progressSection = $("#updateProgress");
      progressBar = $("#updateProgressBar");
      progressText = $("#updateProgressText");
      statusText = $("#updateStatusText");

      // Install Now — download and install MSI directly
      $(".updateInstallBtn").onclick = startDownload;

      // Release Notes — open release page in browser
      document.querySelectorAll(".updateNotesBtn").forEach((btn) => {
        btn.onclick = () => {
          if (releaseUrl) window.javaBridge?.openBrowser?.(releaseUrl);
        };
      });

      // Cancel — abort in-progress download
      $(".updateCancelBtn").onclick = () => {
        window.javaBridge?.cancelUpdate?.();
      };

      // Manual Install — open releases page in browser
      $(".updateManualBtn").onclick = () => {
        window.javaBridge?.openBrowser?.(RELEASES_URL);
      };

      // Update Later — dismiss
      $(".updateLaterBtn").onclick = () => {
        modal.classList.remove("isOpen");
      };

      // Wait for bridges
      for (
        let i = 0;
        i < LIMIT && !(window.dpsData?.getVersion && window.javaBridge?.openBrowser);
        i++
      ) {
        await new Promise((r) => setTimeout(r, RETRY));
      }
      if (!(window.dpsData?.getVersion && window.javaBridge?.openBrowser)) return;
      if (window.javaBridge?.isRunningViaGradle?.()) return;

      const rawCurrent = String(window.dpsData.getVersion() || "").trim();
      const current = rawCurrent.startsWith("v") ? rawCurrent : "v" + rawCurrent;

      const fetchWithTimeout = (url, opts = {}) => {
        const ctrl = new AbortController();
        const timer = setTimeout(() => ctrl.abort(), FETCH_TIMEOUT);
        return fetch(url, { ...opts, signal: ctrl.signal }).finally(() => clearTimeout(timer));
      };

      const fetchGitHub = async () => {
        const res = await fetchWithTimeout(GITHUB_API, {
          headers: { Accept: "application/vnd.github+json" },
          cache: "no-store",
        });
        if (!res.ok) throw new Error("GitHub " + res.status);
        const releases = await res.json();
        const release = releases.find((r) => {
          const tag = String(r?.tag_name || "").trim().toLowerCase();
          if (tag.startsWith("pre")) return false;
          return !r?.draft && !r?.prerelease;
        });
        if (!release) throw new Error("no release");
        const msiAsset = (release.assets || []).find(
          (a) => a.name && a.name.toLowerCase().endsWith(".msi")
        );
        return {
          latest: release.tag_name,
          msi: msiAsset?.browser_download_url || null,
          notes: release.html_url || RELEASES_URL,
        };
      };

      const fetchR2 = async () => {
        const res = await fetchWithTimeout(R2_MANIFEST, { cache: "no-store" });
        if (!res.ok) throw new Error("R2 " + res.status);
        const m = await res.json();
        const v = m.version?.startsWith("v") ? m.version : "v" + m.version;
        return {
          latest: v,
          msi: m.msiUrl || null,
          notes: m.releaseNotesUrl || RELEASES_URL,
        };
      };

      // Try GitHub first, fall back to R2 mirror
      const [primary, fallback] = [fetchGitHub, fetchR2];
      let result;
      try {
        result = await primary();
      } catch {
        try { result = await fallback(); } catch { return; }
      }

      const latest = result.latest;
      const latestInfo = parseVersion(latest);
      msiUrl = result.msi;
      releaseUrl = result.notes;

      const currentInfo = parseVersion(current);
      const hasUpdate =
        latestInfo.value > currentInfo.value ||
        (latestInfo.value === currentInfo.value &&
          currentInfo.prerelease &&
          !latestInfo.prerelease);
      if (!hasUpdate) return;

      // Show/hide install button based on whether MSI is available
      const installBtn = $(".updateInstallBtn");
      if (msiUrl && window.javaBridge?.startUpdate) {
        installBtn.style.display = "block";
      } else {
        installBtn.style.display = "none";
      }

      const fallback = `A new update is available!\n\nCurrent version: ${current}\nLatest version: ${latest}`;
      textEl.textContent =
        window.i18n?.format?.("update.text", { current, latest }, fallback) || fallback;
      showActions();
      modal.classList.add("isOpen");
    }, START_DELAY);

  window.ReleaseChecker = { start };
})();
