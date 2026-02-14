(function initSkillIcons(global) {
  const BASE_URL = "https://assets.playnccdn.com/static-aion2-gamedata/resources";

  const classCodeByPrefix = {
    "11": "GL",
    "12": "TE",
    "13": "AS",
    "14": "RG",
    "15": "SO",
    "16": "EL",
    "17": "CL",
    "18": "CH",
  };

  const classCodeByJob = {
    Gladiator: "GL",
    검성: "GL",
    Templar: "TE",
    수호성: "TE",
    Assassin: "AS",
    살성: "AS",
    Ranger: "RG",
    궁성: "RG",
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

  const getIconCandidates = (skill = {}) => {
    const classCode = resolveClassCode(skill);
    if (!classCode) return [];

    const sub = getSkillSubCode(skill);
    if (!Number.isFinite(sub)) return [];

    if (!isPassiveSkill(skill)) {
      return [buildIconUrl(classCode, sub, false)];
    }

    const indices = [sub - 70, sub - 71, sub - 69].filter((v) => Number.isFinite(v) && v > 0 && v <= 999);
    const unique = [...new Set(indices)];
    return unique.map((idx) => buildIconUrl(classCode, idx, true));
  };

  const applyIconToImage = (imgEl, skill = {}) => {
    if (!imgEl) return;
    const candidates = getIconCandidates(skill);
    if (!candidates.length) {
      imgEl.style.display = "none";
      imgEl.removeAttribute("src");
      return;
    }
    imgEl.dataset.iconCandidates = JSON.stringify(candidates);
    imgEl.dataset.iconIndex = "0";
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
      imgEl.style.display = "none";
      return;
    }
    imgEl.dataset.iconIndex = String(idx);
    imgEl.src = candidates[idx];
  };

  global.skillIcons = {
    getIconCandidates,
    applyIconToImage,
    handleImgError,
  };
})(window);
