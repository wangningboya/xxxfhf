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

/**
 * 解析服务端的 LocalDateTime。
 *
 * iOS 对 `2026-11-23T12:00:00` 的解析不稳定（可能直接 NaN），必须换成空格、
 * 并把 `-` 换成 `/` —— 这是本项目里所有时间处理都要走一遍的原因，
 * 不要在页面里各写一份。
 */
function parseTime(value) {
  if (!value) {
    return null;
  }
  if (value instanceof Date) {
    return value;
  }
  const d = new Date(String(value).replace('T', ' ').replace(/-/g, '/'));
  return isNaN(d.getTime()) ? null : d;
}

function pad2(n) {
  return String(n).padStart(2, '0');
}

/**
 * 相对时间：刚刚 / X 分钟前 / X 小时前 / 昨天 / MM-DD。
 *
 * 「昨天」按自然日判断而不是「24 小时前」：晚上 23:50 的消息，
 * 第二天早上 8 点看到时应当说「昨天」，说「8 小时前」会让人算不清是哪天。
 */
function relativeTime(value) {
  const d = parseTime(value);
  if (!d) {
    return '';
  }
  const diff = Date.now() - d.getTime();
  if (diff < 60000) {
    return '刚刚';
  }
  if (diff < 3600000) {
    return Math.floor(diff / 60000) + ' 分钟前';
  }
  const todayZero = new Date();
  todayZero.setHours(0, 0, 0, 0);
  const thatZero = new Date(d.getTime());
  thatZero.setHours(0, 0, 0, 0);
  const days = Math.round((todayZero.getTime() - thatZero.getTime()) / 86400000);
  if (days === 0) {
    return Math.floor(diff / 3600000) + ' 小时前';
  }
  if (days === 1) {
    return '昨天';
  }
  if (d.getFullYear() === todayZero.getFullYear()) {
    return pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
}

/** 时间点：11-23 12:05。时间轴用，只到分钟 —— 到秒反而看不清。 */
function timeLabel(value) {
  const d = parseTime(value);
  if (!d) {
    return '';
  }
  return (
    pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes())
  );
}

/**
 * 两个时间点之间的耗时：不到 1 分钟 / X 分钟 / X 小时 Y 分钟 / X 天 Y 小时。
 *
 * 只看分钟及以上，不算秒：接单耗时「2 分 13 秒」对商家没有额外信息，
 * 而秒级数字会让同一笔单刷新两次看到不同结果，像在跳动。
 */
function durationText(from, to) {
  const a = parseTime(from);
  const b = parseTime(to);
  if (!a || !b) {
    return '';
  }
  const minutes = Math.max(0, Math.floor((b.getTime() - a.getTime()) / 60000));
  if (minutes < 1) {
    return '不到 1 分钟';
  }
  if (minutes < 60) {
    return minutes + ' 分钟';
  }
  const hours = Math.floor(minutes / 60);
  const restMinutes = minutes % 60;
  if (hours < 24) {
    return restMinutes ? hours + ' 小时 ' + restMinutes + ' 分钟' : hours + ' 小时';
  }
  const days = Math.floor(hours / 24);
  const restHours = hours % 24;
  return restHours ? days + ' 天 ' + restHours + ' 小时' : days + ' 天';
}

module.exports = {
  yuan,
  parseList,
  dayLabel,
  dateStr,
  addDays,
  delta,
  assetUrl,
  parseTime,
  relativeTime,
  timeLabel,
  durationText,
};
