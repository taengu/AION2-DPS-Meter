(function initSkillIcons(global) {
  const BASE_URL = "https://assets.playnccdn.com/static-aion2-gamedata/resources";
  const TRANSPARENT_PIXEL = "data:image/gif;base64,R0lGODlhAQABAAAAACw=";

  // Lucide "swords" icon for basic attacks
  const SWORDS_ICON = "data:image/svg+xml," + encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#c0c8d8" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">` +
    `<polyline points="14.5 17.5 3 6 3 3 6 3 17.5 14.5"/>` +
    `<line x1="13" x2="19" y1="19" y2="13"/>` +
    `<line x1="16" x2="20" y1="16" y2="20"/>` +
    `<line x1="19" x2="21" y1="21" y2="19"/>` +
    `<polyline points="14.5 6.5 18 3 21 3 21 6 17.5 9.5"/>` +
    `<line x1="5" x2="9" y1="14" y2="18"/>` +
    `<line x1="7" x2="4" y1="17" y2="20"/>` +
    `<line x1="3" x2="5" y1="19" y2="21"/>` +
    `</svg>`
  );

  // Lucide "wand" icon for missing/unknown skills
  const WAND_ICON = "data:image/svg+xml," + encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#8890a4" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">` +
    `<path d="M15 4V2"/>` +
    `<path d="M15 16v-2"/>` +
    `<path d="M8 9h2"/>` +
    `<path d="M20 9h2"/>` +
    `<path d="M17.8 11.8 19 13"/>` +
    `<path d="M15 9h.01"/>` +
    `<path d="M17.8 6.2 19 5"/>` +
    `<path d="m3 21 9-9"/>` +
    `<path d="M12.2 6.2 11 5"/>` +
    `</svg>`
  );

  // Lookup table: first 4 digits of 8-digit skill code -> icon filename (from game data)
  let SKILL_ICON_MAP = null;

  const getSkillIconMap = () => {
    if (SKILL_ICON_MAP) return SKILL_ICON_MAP;
    try {
      const raw = window.javaBridge?.readResource?.("/data/skill_icons.json");
      if (typeof raw === "string" && raw.length) {
        SKILL_ICON_MAP = JSON.parse(raw);
      }
    } catch (_) { /* ignore */ }
    return SKILL_ICON_MAP || {};
  };

  const classCodeByJob = {
    Gladiator: "GL",
    검성: "GL",
    Templar: "TE",
    수호성: "TE",
    Assassin: "AS",
    살성: "AS",
    Ranger: "RA",
    궁성: "RA",
    Sorcerer: "SO",
    마도성: "SO",
    Spiritmaster: "EL",
    Elementalist: "EL",
    정령성: "EL",
    Cleric: "CL",
    치유성: "CL",
    Chanter: "CH",
    호법성: "CH",
  };

  const classCodeByPrefix = {
    "11": "GL",
    "12": "TE",
    "13": "AS",
    "14": "RA",
    "15": "SO",
    "16": "EL",
    "17": "CL",
    "18": "CH",
  };

  const THEOSTONE_PREFIX = "30";
  const THEOSTONE_ICON_BASE = "Icon_Item_Usable_Godstone_WP_r_";
  const THEOSTONE_NAME_COLOR_BY_CODE = {
    0: "#52b35c",
    1: "#3d94d8",
    2: "#e9a43a",
  };

  const pad3 = (value) => String(Math.max(0, Number(value) || 0)).padStart(3, "0");

  const normalizeCode = (rawCode) => {
    const digits = String(rawCode ?? "").replace(/\D/g, "");
    if (!digits) return "";
    return digits.length >= 8 ? digits.slice(0, 8) : digits.padEnd(8, "0");
  };

  const resolveClassCode = (skill = {}) => {
    const code = normalizeCode(skill.code);
    const prefix = code.slice(0, 2);
    return classCodeByPrefix[prefix] || classCodeByJob[String(skill.job || "")] || "";
  };

  const getSkillSubCode = (skill = {}) => {
    const code = normalizeCode(skill.code);
    if (!code || code.length < 4) return null;
    const sub = Number(code.slice(2, 4));
    return Number.isFinite(sub) ? sub : null;
  };

  const isPassiveSkill = (skill = {}) => {
    if (skill?.isDot) return false;
    const sub = getSkillSubCode(skill);
    if (!Number.isFinite(sub)) return false;
    return sub >= 70;
  };

  const buildIconUrl = (classCode, idx, passive = false) => {
    const suffix = passive ? "_Passive_" : "_";
    return `${BASE_URL}/ICON_${classCode}_SKILL${suffix}${pad3(idx)}.png`;
  };

  const parseTheostone = (skill = {}) => {
    const code = normalizeCode(skill.code);
    if (!code.startsWith(THEOSTONE_PREFIX) || code.length < 7) return null;

    const qualityCode = Number(code.charAt(4));
    const iconCode = Number(code.slice(5, 7));
    if (!Number.isFinite(iconCode) || iconCode <= 0) return null;

    const iconHex = iconCode.toString(16).padStart(3, "0");
    return {
      qualityCode,
      nameColor: THEOSTONE_NAME_COLOR_BY_CODE[qualityCode] || "",
      iconUrl: `${BASE_URL}/${THEOSTONE_ICON_BASE}${iconHex}.png`,
    };
  };

  const getTheostoneNameColor = (skill = {}) => parseTheostone(skill)?.nameColor || "";

  const getIconCandidates = (skill = {}) => {
    const theostone = parseTheostone(skill);
    if (theostone) {
      return [theostone.iconUrl];
    }

    const code = normalizeCode(skill.code);
    if (!code) return [SWORDS_ICON];

    // Basic attacks: xx0000xx class autos (not xx000000), 10000xxx elementalist autos, 1699xxxx spirit autos
    const prefix2 = code.slice(0, 2);
    const mid4 = code.slice(2, 6);
    if (prefix2 >= "11" && prefix2 <= "18" && mid4 === "0000" && code.slice(6) !== "00") {
      return [SWORDS_ICON];
    }
    if (code.startsWith("1000") || code.startsWith("1699")) {
      return [SWORDS_ICON];
    }

    // Use lookup table from game data (keyed by first 4 digits of skill code)
    const base4 = code.slice(0, 4);
    const iconName = getSkillIconMap()[base4];
    if (iconName) {
      return [`${BASE_URL}/${iconName}.png`, WAND_ICON];
    }

    // Fallback: algorithmic approach for skills not in the table
    const classCode = resolveClassCode(skill);
    if (!classCode) return [WAND_ICON];

    const sub = Number(code.slice(2, 4));
    if (!Number.isFinite(sub)) return [WAND_ICON];

    return [buildIconUrl(classCode, sub, false), WAND_ICON];
  };

  // In-memory blob URL cache: CDN url → blob URL (instant, no decode cost)
  const blobCache = new Map();    // url → blobUrl
  const blobPending = new Map();  // url → Promise<blobUrl|null>

  // Derive a safe filename from a CDN URL for disk caching
  const urlToCacheKey = (url) => {
    const match = url.match(/\/([^/]+\.png)$/i);
    return match ? match[1] : null;
  };

  const fetchAsBlob = (url) => {
    if (blobCache.has(url)) return Promise.resolve(blobCache.get(url));
    if (blobPending.has(url)) return blobPending.get(url);
    // Only cache CDN URLs (not data: URIs)
    if (!url.startsWith("http")) return Promise.resolve(null);

    const cacheKey = urlToCacheKey(url);
    const bridge = window.javaBridge;

    const p = Promise.resolve()
      .then(() => {
        // Try disk cache first
        if (cacheKey && bridge?.readCachedIcon) {
          const b64 = bridge.readCachedIcon(cacheKey);
          if (b64) {
            const binary = atob(b64);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
            return new Blob([bytes], { type: "image/png" });
          }
        }
        return null;
      })
      .then((diskBlob) => {
        if (diskBlob) return diskBlob;
        // Fetch from CDN and persist to disk cache
        return fetch(url, { mode: "cors", credentials: "omit" })
          .then((r) => {
            if (!r.ok) throw new Error(r.status);
            return r.blob();
          })
          .then((blob) => {
            // Write to disk cache in background
            if (cacheKey && bridge?.writeCachedIcon) {
              blob.arrayBuffer().then((buf) => {
                const bytes = new Uint8Array(buf);
                let b64 = "";
                for (let i = 0; i < bytes.length; i += 8192) {
                  b64 += String.fromCharCode(...bytes.subarray(i, i + 8192));
                }
                bridge.writeCachedIcon(cacheKey, btoa(b64));
              }).catch(() => {});
            }
            return blob;
          });
      })
      .then((blob) => {
        const blobUrl = URL.createObjectURL(blob);
        blobCache.set(url, blobUrl);
        return blobUrl;
      })
      .catch(() => null)
      .finally(() => blobPending.delete(url));
    blobPending.set(url, p);
    return p;
  };

  const applyIconToImage = (imgEl, skill = {}) => {
    if (!imgEl) return;
    const candidates = getIconCandidates(skill);
    if (!candidates.length) {
      imgEl.dataset.iconCandidates = "[]";
      imgEl.dataset.iconIndex = "0";
      imgEl.dataset.iconUrl = WAND_ICON;
      imgEl.classList.remove("isPlaceholder");
      imgEl.src = WAND_ICON;
      imgEl.style.display = "";
      return;
    }

    const primaryUrl = candidates[0];

    // Fast path: element already shows this icon — skip entirely
    if (imgEl.dataset.iconUrl === primaryUrl) {
      imgEl.style.display = "";
      return;
    }

    imgEl.dataset.iconUrl = primaryUrl;
    imgEl.dataset.iconCandidates = JSON.stringify(candidates);
    imgEl.dataset.iconIndex = "0";
    imgEl.style.display = "";

    // Try cached blob first (instant, no flash)
    const cached = blobCache.get(primaryUrl);
    if (cached) {
      imgEl.classList.remove("isPlaceholder");
      imgEl.src = cached;
      return;
    }

    // For data: URIs (SVG fallbacks), apply directly
    if (!primaryUrl.startsWith("http")) {
      imgEl.classList.remove("isPlaceholder");
      imgEl.src = primaryUrl;
      return;
    }

    // Show wand placeholder while fetching, then swap in blob
    imgEl.classList.remove("isPlaceholder");
    imgEl.src = WAND_ICON;
    fetchAsBlob(primaryUrl).then((blobUrl) => {
      // Only apply if this element still wants this icon
      if (imgEl.dataset.iconUrl !== primaryUrl) return;
      if (blobUrl) {
        imgEl.src = blobUrl;
      } else {
        // CDN fetch failed — fall through to next candidate
        handleImgError(imgEl);
      }
    });
  };

  const handleImgError = (imgEl) => {
    if (!imgEl) return;
    let candidates = [];
    try {
      const raw = imgEl.dataset.iconCandidates || "[]";
      const decoded = raw.includes("%") ? decodeURIComponent(raw) : raw;
      candidates = JSON.parse(decoded);
    } catch (_) {
      candidates = [];
    }
    const idx = Number(imgEl.dataset.iconIndex || 0) + 1;
    if (!Array.isArray(candidates) || idx >= candidates.length) {
      imgEl.classList.remove("isPlaceholder");
      imgEl.src = WAND_ICON;
      imgEl.style.display = "";
      return;
    }
    imgEl.dataset.iconIndex = String(idx);
    const nextUrl = candidates[idx];

    // Use blob cache for CDN fallbacks too
    const cached = blobCache.get(nextUrl);
    if (cached) {
      imgEl.classList.remove("isPlaceholder");
      imgEl.src = cached;
      return;
    }

    imgEl.classList.remove("isPlaceholder");
    imgEl.src = nextUrl;
  };

  global.skillIcons = {
    getIconCandidates,
    getTheostoneNameColor,
    applyIconToImage,
    handleImgError,
    _fetchAsBlob: fetchAsBlob,
  };
})(window);
