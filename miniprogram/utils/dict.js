const { request } = require('./request');

/**
 * 数据字典（`GET /dict`）。
 *
 * 字典是「值固定、显示名会变」的展示配置：商家在后台把「待接单」改成「待处理」，
 * 前端不该为此发版。所以进小程序时取一次全量，本地缓存，之后同步读。
 *
 * 三条约定：
 * 1. **同步读**。页面在 `onShow` 里直接拿，不 await；没加载完时返回 fallback，
 *    页面照旧用代码里的旧文案 —— 绝不出现空白标签。
 * 2. **一次全量**。类型只有 7 个、项不到 40 条，一次拿全比按类型逐个取省事。
 * 3. **过期不阻塞**。缓存超过 12 小时视为陈旧，下次进前台静默重取一次，
 *    但陈旧数据仍然可用（先渲染再刷新，不闪白）。
 */

const STORAGE_KEY = 'meal_dict_v1';
const STALE_MS = 12 * 60 * 60 * 1000;

/** { typeCode: [{ itemValue, itemLabel, tagType, sort }] } */
let data = null;
let fetchedAt = 0;
let loading = null;

function readCache() {
  try {
    const raw = wx.getStorageSync(STORAGE_KEY);
    if (raw && raw.data && typeof raw.data === 'object') {
      data = raw.data;
      fetchedAt = Number(raw.fetchedAt || 0);
    }
  } catch (e) {
    data = null;
  }
}

function writeCache() {
  try {
    wx.setStorageSync(STORAGE_KEY, { data, fetchedAt });
  } catch (e) {
    // 缓存写不进去不影响本次会话，忽略
  }
}

function normalize(raw) {
  const result = {};
  if (!raw || typeof raw !== 'object') {
    return result;
  }
  Object.keys(raw).forEach((typeCode) => {
    const list = raw[typeCode];
    if (!Array.isArray(list)) {
      return;
    }
    result[typeCode] = list
      .filter((item) => item && item.itemValue)
      .map((item) => ({
        itemValue: item.itemValue,
        itemLabel: item.itemLabel || item.itemValue,
        tagType: item.tagType || 'default',
        sort: Number(item.sort || 0),
      }))
      .sort((a, b) => a.sort - b.sort);
  });
  return result;
}

/**
 * 拉一次字典。并发调用共用同一个请求；失败静默（退回代码里的静态文案）。
 * @param {boolean} force 忽略缓存强制重取
 */
function load(force) {
  if (!force && data && Date.now() - fetchedAt < STALE_MS) {
    return Promise.resolve(data);
  }
  if (loading) {
    return loading;
  }
  // 首次调用先把本地缓存读出来，期间页面同步读不会空
  if (!data) {
    readCache();
  }
  loading = request({ url: '/dict' })
    .then((raw) => {
      data = normalize(raw);
      fetchedAt = Date.now();
      writeCache();
      return data;
    })
    .catch(() => data || {})
    .then((result) => {
      loading = null;
      return result;
    });
  return loading;
}

/** 某类型的全部启用项；没加载到就返回空数组，调用方用自己的兜底 */
function items(typeCode) {
  if (!data) {
    readCache();
  }
  return (data && data[typeCode]) || [];
}

/**
 * 字典是否已经在内存里（含从 storage 读出的旧缓存）。
 *
 * 页面用它决定「要不要在 onShow 里等一次」：已经就绪时直接同步渲染，
 * 不必再挂一个 then 去重复 setData 和重复拉列表。
 */
function ready() {
  if (!data) {
    readCache();
  }
  return !!data;
}

/** 存值 → 显示名。缺项回退 fallback，绝不返回空 */
function label(typeCode, value, fallback) {
  if (!value) {
    return fallback || '';
  }
  const found = items(typeCode).find((item) => item.itemValue === value);
  return (found && found.itemLabel) || fallback || value;
}

/** 存值 → 配色（default / success / warning / danger / info） */
function tagType(typeCode, value) {
  const found = items(typeCode).find((item) => item.itemValue === value);
  return (found && found.tagType) || 'default';
}

/**
 * 按字典生成筛选页签。
 *
 * 字典里停用的项不会出现；顺序按 sort。`allLabel` 有值时在最前面插一项空值用于「全部」。
 */
function tabs(typeCode, allLabel, fallbackItems) {
  const list = items(typeCode);
  const source = list.length
    ? list.map((item) => ({ key: item.itemValue, label: item.itemLabel }))
    : fallbackItems || [];
  return allLabel ? [{ key: '', label: allLabel }].concat(source) : source;
}

module.exports = { load, ready, items, label, tagType, tabs };
