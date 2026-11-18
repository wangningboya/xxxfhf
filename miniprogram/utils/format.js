/**
 * 展示层格式化。
 *
 * 服务端金额一律是「分」，界面上要的是「元」；这个转换只在展示时做一次，
 * 计算永远在分上算，避免 0.1 + 0.2 这类浮点误差。
 */

const { apiBase } = require('../config');

/** 分 → 元，两位小数。25 → '0.25' */
function yuan(cents) {
  const value = Number(cents || 0);
  const sign = value < 0 ? '-' : '';
  const abs = Math.abs(value);
  return sign + Math.floor(abs / 100) + '.' + String(abs % 100).padStart(2, '0');
}

/** 配置里的 JSON 数组（如自取时段）。解析失败按空数组，页面自然降级为「没得选」。 */
function parseList(raw) {
  if (!raw) {
    return [];
  }
  if (Array.isArray(raw)) {
    return raw;
  }
  try {
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? arr : [];
  } catch (e) {
    return [];
  }
}

/** 2026-11-17 → 11-17；今天 / 明天用文字，别让用户自己算日子 */
function dayLabel(dateStr, todayStr, tomorrowStr) {
  if (!dateStr) {
    return '';
  }
  if (dateStr === todayStr) {
    return '今天';
  }
  if (dateStr === tomorrowStr) {
    return '明天';
  }
  return dateStr.slice(5);
}

/** 本地日期 yyyy-MM-dd。不能用 toISOString：那是 UTC，早上 8 点前会差一天。 */
function dateStr(date) {
  const d = date || new Date();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return d.getFullYear() + '-' + m + '-' + day;
}

/** 日期加减天数 */
function addDays(dateStr0, days) {
  const parts = (dateStr0 || dateStr()).split('-');
  const d = new Date(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2]));
  d.setDate(d.getDate() + days);
  return dateStr(d);
}

/** 规格差价：0 不显示，正数显示 +1.00 */
function delta(deltaCents) {
  const value = Number(deltaCents || 0);
  if (value === 0) {
    return '';
  }
  return (value > 0 ? '+' : '-') + yuan(Math.abs(value));
}

/**
 * 图片地址补全。
 *
 * 服务端只存相对路径（`/upload/2026/11/x.jpg`）——换域名、换机器都不用改库。
 * 但小程序的 `<image src>` 对 `/upload/...` 的理解是「小程序包内路径」，
 * 它会去代码包里找这个文件，找不到就白着一块、控制台也不报错。
 * 所以进 setData 之前必须拼上 apiBase。
 *
 * 已经是完整地址的原样返回：上传后的临时文件是 `http://tmp/...` / `wxfile://...`，
 * 历史数据里也可能存过绝对地址，重复拼会把地址拼坏。
 */
function assetUrl(path) {
  if (!path) {
    return '';
  }
  if (/^[a-zA-Z][a-zA-Z\d+.-]*:\/\//.test(path)) {
    return path;
  }
  const base = apiBase.endsWith('/') ? apiBase.slice(0, -1) : apiBase;
  return base + (path.charAt(0) === '/' ? path : '/' + path);
}

module.exports = { yuan, parseList, dayLabel, dateStr, addDays, delta, assetUrl };
