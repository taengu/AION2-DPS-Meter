(function initSkillIcons(global) {
  const BASE_URL = "https://assets.playnccdn.com/static-aion2-gamedata/resources";
  const TRANSPARENT_PIXEL = "data:image/gif;base64,R0lGODlhAQABAAAAACw=";

  // Crossed swords SVG for basic attacks and fallback
  const CROSSED_SWORDS_ICON = "data:image/svg+xml," + encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" fill="none" stroke="#c0c8d8" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">` +
    // left blade
    `<path d="M14 6l20 20" stroke-width="3"/>` +
    `<path d="M10 4l6 0 22 22-3 3-22-22z" fill="#c0c8d8" stroke="#8890a4"/>` +
    // right blade
    `<path d="M50 6L30 26" stroke-width="3"/>` +
    `<path d="M54 4l-6 0-22 22 3 3 22-22z" fill="#c0c8d8" stroke="#8890a4"/>` +
    // left guard
    `<path d="M27 31l-8 2 2-8" fill="none" stroke="#a0a8b8" stroke-width="2.5"/>` +
    // right guard
    `<path d="M37 31l8 2-2-8" fill="none" stroke="#a0a8b8" stroke-width="2.5"/>` +
    // left handle
    `<path d="M21 35l-8 8" stroke="#a0a8b8" stroke-width="3"/>` +
    `<path d="M11 45l-1-3 3 1" stroke="#a0a8b8" stroke-width="2"/>` +
    `<path d="M9 47l-1-2 2 1" stroke="#a0a8b8" stroke-width="2"/>` +
    // right handle
    `<path d="M43 35l8 8" stroke="#a0a8b8" stroke-width="3"/>` +
    `<path d="M53 45l1-3-3 1" stroke="#a0a8b8" stroke-width="2"/>` +
    `<path d="M55 47l1-2-2 1" stroke="#a0a8b8" stroke-width="2"/>` +
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
    if (!code) return [CROSSED_SWORDS_ICON];

    // Basic attacks: xx0000xx class autos (not xx000000), 10000xxx elementalist autos, 1699xxxx spirit autos
    const prefix2 = code.slice(0, 2);
    const mid4 = code.slice(2, 6);
    if (prefix2 >= "11" && prefix2 <= "18" && mid4 === "0000" && code.slice(6) !== "00") {
      return [CROSSED_SWORDS_ICON];
    }
    if (code.startsWith("1000") || code.startsWith("1699")) {
      return [CROSSED_SWORDS_ICON];
    }

    // Use lookup table from game data (keyed by first 4 digits of skill code)
    const base4 = code.slice(0, 4);
    const iconName = getSkillIconMap()[base4];
    if (iconName) {
      return [`${BASE_URL}/${iconName}.png`, CROSSED_SWORDS_ICON];
    }

    // Fallback: algorithmic approach for skills not in the table
    const classCode = resolveClassCode(skill);
    if (!classCode) return [CROSSED_SWORDS_ICON];

    const sub = Number(code.slice(2, 4));
    if (!Number.isFinite(sub)) return [CROSSED_SWORDS_ICON];

    return [buildIconUrl(classCode, sub, false), CROSSED_SWORDS_ICON];
  };

  const applyIconToImage = (imgEl, skill = {}) => {
    if (!imgEl) return;
    const candidates = getIconCandidates(skill);
    if (!candidates.length) {
      imgEl.dataset.iconCandidates = "[]";
      imgEl.dataset.iconIndex = "0";
      imgEl.classList.add("isPlaceholder");
      imgEl.src = TRANSPARENT_PIXEL;
      imgEl.style.display = "";
      return;
    }
    imgEl.dataset.iconCandidates = JSON.stringify(candidates);
    imgEl.dataset.iconIndex = "0";
    imgEl.classList.remove("isPlaceholder");
    imgEl.src = candidates[0];
    imgEl.style.display = "";
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
      imgEl.classList.add("isPlaceholder");
      imgEl.src = TRANSPARENT_PIXEL;
      imgEl.style.display = "";
      return;
    }
    imgEl.dataset.iconIndex = String(idx);
    imgEl.classList.remove("isPlaceholder");
    imgEl.src = candidates[idx];
  };

  global.skillIcons = {
    getIconCandidates,
    getTheostoneNameColor,
    applyIconToImage,
    handleImgError,
  };
})(window);
