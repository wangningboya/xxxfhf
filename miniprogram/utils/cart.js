/**
 * 购物车（本地存储，不落服务端）。
 *
 * 为什么不做服务端购物车：购物车在「下单成功」那一刻就失去意义，
 * 为它建表、建接口、处理多端同步，成本远大于收益。份数真正的约束在下单时由服务端原子占用。
 *
 * 一条购物车只对应「明天」那份菜单：切供餐日不清空，跨 0 点（车里的日期 ≤ 今天）才算过期。
 *
 * 同一盒饭选了不同规格算两行（key 里带规格选项），否则改规格会把前一行覆盖掉。
 */

const { dateStr } = require('./format');

const KEY = 'meal_cart';

function empty() {
  return { serveDate: '', items: [] };
}

function load() {
  const cart = wx.getStorageSync(KEY);
  if (!cart || !Array.isArray(cart.items)) {
    return empty();
  }
  return cart;
}

function save(cart) {
  wx.setStorageSync(KEY, cart);
}

function clear() {
  wx.removeStorageSync(KEY);
}

/** 规格选项组合成稳定的行标识，顺序无关：['b','a'] 与 ['a','b'] 是同一行 */
function lineKey(comboId, optionIds) {
  const ids = (optionIds || []).slice().sort();
  return comboId + '|' + ids.join(',');
}

/**
 * 加一行（已存在则累加份数）。
 *
 * @param {object} cart  load() 出来的购物车
 * @param {object} line  { comboId, name, cover, price, qty, optionIds, specText }
 */
function addLine(cart, line) {
  const next = { serveDate: cart.serveDate, items: cart.items.slice() };
  const key = lineKey(line.comboId, line.optionIds);
  const index = next.items.findIndex((item) => lineKey(item.comboId, item.optionIds) === key);
  if (index >= 0) {
    const merged = Object.assign({}, next.items[index]);
    merged.qty = merged.qty + (line.qty || 1);
    next.items[index] = merged;
  } else {
    next.items.push(Object.assign({}, line, { qty: line.qty || 1 }));
  }
  return next;
}

/** 改份数；改到 0 就是删掉这一行 */
function setQty(cart, key, qty) {
  const next = { serveDate: cart.serveDate, items: [] };
  cart.items.forEach((item) => {
    if (lineKey(item.comboId, item.optionIds) !== key) {
      next.items.push(item);
      return;
    }
    if (qty > 0) {
      next.items.push(Object.assign({}, item, { qty }));
    }
  });
  return next;
}

function count(cart) {
  return cart.items.reduce((sum, item) => sum + item.qty, 0);
}

/** 商品金额合计（分）。不含配送费与规格差价 —— 差价在下单页由服务端算，避免两边算法不一致。 */
function goodsAmount(cart) {
  return cart.items.reduce((sum, item) => {
    const specDelta = (item.specDeltas || []).reduce((s, v) => s + Number(v || 0), 0);
    return sum + (Number(item.price || 0) + specDelta) * item.qty;
  }, 0);
}

/**
 * 购物车是否已过期：车里的供餐日 ≤ 今天。
 *
 * 加购时写入的 serveDate 恒为「明天」，过了 0 点它就变成今天，服务端只收明天，
 * 这一车已经不可能提交。日期是 yyyy-MM-dd，字符串序就是日期序，不必转 Date。
 */
function isStale(cart, today) {
  return !!cart && !!cart.serveDate && cart.serveDate <= (today || dateStr());
}

/** 过期就直接清车，返回是否真清了（页面据此决定要不要提示） */
function expireIfStale(today) {
  if (!isStale(load(), today)) {
    return false;
  }
  clear();
  return true;
}

module.exports = {
  load,
  save,
  clear,
  addLine,
  setQty,
  count,
  goodsAmount,
  lineKey,
  empty,
  isStale,
  expireIfStale,
};
