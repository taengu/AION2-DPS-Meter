const createI18n = ({
  defaultLanguage = "en",
  storageKey = "dpsMeter.language",
  supportedLanguages = ["en", "ko", "zh-Hant", "zh-Hans"],
} = {}) => {
  let currentLanguage = defaultLanguage;
  let uiStrings = {};
  let skillStrings = {};
  const listeners = new Set();

  const safeGetStorage = (key) => {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  };

  const safeSetStorage = (key, value) => {
    try {
      localStorage.setItem(key, value);
    } catch {
      // ignore
    }
  };

  const normalizeLanguage = (lang) =>
    supportedLanguages.includes(lang) ? lang : defaultLanguage;

  const loadJson = async (path) => {
    try {
      const res = await fetch(path, { cache: "no-store" });
      if (!res.ok) return {};
      const data = await res.json();
      return data && typeof data === "object" ? data : {};
    } catch {
      return {};
    }
  };

  const resolveKey = (obj, key) => {
    if (!obj || !key) return undefined;
    return key.split(".").reduce((acc, part) => (acc ? acc[part] : undefined), obj);
  };

  const t = (key, fallback = "") => {
    const value = resolveKey(uiStrings, key);
    if (typeof value === "string") return value;
    return fallback;
  };

  const format = (key, vars = {}, fallback = "") => {
    const template = t(key, fallback);
    if (!template) return fallback;
    return template.replace(/\{(\w+)\}/g, (_, varKey) => {
      const replacement = vars[varKey];
      return replacement === undefined || replacement === null ? "" : String(replacement);
    });
  };

  const getSkillName = (code, fallback = "") => {
    const value = skillStrings?.[String(code)];
    if (typeof value === "string" && value.trim()) return value;
    return fallback;
  };

  const applyTranslations = () => {
    document.querySelectorAll("[data-i18n]").forEach((el) => {
      const key = el.dataset.i18n;
      const text = t(key, el.textContent ?? "");
      if (text) el.textContent = text;
    });

    document.querySelectorAll("[data-i18n-placeholder]").forEach((el) => {
      const key = el.dataset.i18nPlaceholder;
      const text = t(key, el.getAttribute("placeholder") ?? "");
      if (text) el.setAttribute("placeholder", text);
    });

    document.querySelectorAll("[data-i18n-aria-label]").forEach((el) => {
      const key = el.dataset.i18nAriaLabel;
      const text = t(key, el.getAttribute("aria-label") ?? "");
      if (text) el.setAttribute("aria-label", text);
    });
  };

  const setLanguage = async (lang, { persist = true } = {}) => {
    const next = normalizeLanguage(lang || defaultLanguage);
    currentLanguage = next;

    if (persist) {
      safeSetStorage(storageKey, next);
    }

    const [ui, skills] = await Promise.all([
      loadJson(`./i18n/ui/${next}.json`),
      loadJson(`./i18n/skills/${next}.json`),
    ]);

    uiStrings = ui || {};
    skillStrings = skills || {};
    applyTranslations();
    listeners.forEach((listener) => listener(currentLanguage));
  };

  const init = async () => {
    const stored = safeGetStorage(storageKey);
    await setLanguage(stored || defaultLanguage, { persist: false });
  };

  const onChange = (listener) => {
    listeners.add(listener);
    return () => listeners.delete(listener);
  };

  return {
    init,
    setLanguage,
    t,
    format,
    getSkillName,
    getLanguage: () => currentLanguage,
    onChange,
  };
};

window.i18n = createI18n();
