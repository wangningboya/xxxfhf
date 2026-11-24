/**
 * 打印结果上报与失败暂存。
 *
 * 单独一个模块而不是塞进 print-runner，是为了**不把 SDK 带进来**：
 * 订单详情页也要在进入时补传暂存记录（需求 5.2），
 * 但详情页没必要为一个补传动作解析 300 多 KB 的 JCAPI。
 * 这里只依赖 `request`，任何页面都可以放心 require。
 */

const { request } = require('../request');

/** 上报失败暂存（需求 5.2）：最多 50 条、保留 7 天 */
const PENDING_KEY = 'print_pending_pages';
const PENDING_LIMIT = 50;
const PENDING_TTL_MS = 7 * 24 * 3600 * 1000;

function readPending() {
  const list = wx.getStorageSync(PENDING_KEY);
  if (!Array.isArray(list)) {
    return [];
  }
  const now = Date.now();
  return list.filter((item) => item && item.at && now - item.at < PENDING_TTL_MS);
}

function writePending(list) {
  wx.setStorageSync(PENDING_KEY, list.slice(-PENDING_LIMIT));
}

function postPage(record) {
  return request({ url: '/admin/print/page', method: 'POST', data: record });
}

/**
 * 上报一张的打印结果。
 *
 * 失败重试一次，仍失败就落本地暂存 —— 打印本身不能因为网络问题中断，
 * 但「谁打过」这条记录丢了会让另一台手机重复出纸，所以也不能静默丢。
 */
function reportPage(record) {
  return postPage(record).catch(() => postPage(record).catch(() => {
    const list = readPending();
    list.push(Object.assign({ at: Date.now() }, record));
    writePending(list);
  }));
}

/**
 * 补传暂存的记录。进打印页、订单详情页时各调一次即可。
 *
 * @return {Promise<{flushed: number, left: number}>} 补传成功几条、还剩几条
 *   —— 调用方靠 flushed 决定要不要重拉一次列表/详情，不用猜。
 */
function flushPending() {
  const list = readPending();
  if (!list.length) {
    return Promise.resolve({ flushed: 0, left: 0 });
  }
  const failed = [];
  let cursor = 0;
  function next() {
    if (cursor >= list.length) {
      // 成功的已经从队列里剔掉，只剩补传仍失败的
      writePending(failed);
      return Promise.resolve({ flushed: list.length - failed.length, left: failed.length });
    }
    const record = list[cursor++];
    return postPage(record).then(next, () => {
      // 这一条补传失败就留着，下次再来；后面的继续试，不因一条卡死整队
      failed.push(record);
      return next();
    });
  }
  return next();
}

module.exports = { reportPage, flushPending, readPending, PENDING_KEY };
