/**
 * 蓝牙打印链路状态：只存本机 storage，不依赖 SDK。
 *
 * 单独拆一个文件的原因：工作台首页要显示「已连接 / 上次打印」，但首页绝不能
 * require printer.js —— 那会把 300 多 KB 的蓝牙 SDK 一起拉进首屏（见需求 7.5）。
 *
 * 状态是「展示用缓存」，不负责判断能不能打印：能不能打一律以 isConnected() 为准。
 */

const LINK_KEY = 'print_link_state';
/** 预览截图清理的日期戳，放在这里是为了让 link-state 之外的模块少一个 key */
const CLEAN_KEY = 'print_preview_clean_at';

/** 超过这个时间没更新，就不再声称「已连接」：进程可能被系统杀了，状态停在旧值上 */
const FRESH_MS = 10 * 60 * 1000;

function read() {
  try {
    const state = wx.getStorageSync(LINK_KEY);
    return state && typeof state === 'object' ? state : {};
  } catch (e) {
    return {};
  }
}

/** 合并写：只传要改的字段，其余保留（例如连接状态变化不能把 lastPrintAt 抹掉） */
function write(patch) {
  const next = Object.assign({}, read(), patch || {}, { updatedAt: Date.now() });
  try {
    wx.setStorageSync(LINK_KEY, next);
  } catch (e) {
    // 状态只影响展示，写不进去不能影响打印流程
  }
  return next;
}

function clear() {
  try {
    wx.removeStorageSync(LINK_KEY);
  } catch (e) {
    // 同上
  }
}

function isFresh(state) {
  const value = state || read();
  return !!(value.connected && value.updatedAt && Date.now() - value.updatedAt < FRESH_MS);
}

module.exports = {
  LINK_KEY,
  CLEAN_KEY,
  FRESH_MS,
  read,
  write,
  clear,
  isFresh,
};
