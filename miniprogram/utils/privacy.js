/**
 * 隐私授权状态 + 弹窗调度。
 *
 * 只做三件事：
 * 1. 记录「用户在哪个政策版本下同意过」（版本一变旧同意即失效，必须重新告知）；
 * 2. 给页面一个闸门 `ensureAgreed()`：每个「收集动作」前 await 它，同意才继续；
 * 3. 把弹窗组件注册进来，保证全站同时只有一个弹窗实例（防叠层）。
 *
 * 不在这里判断业务：哪些动作算收集，由调用方决定。
 */

const STORAGE_KEY = 'meal_privacy_consent';

/** 政策版本号：政策全文有实质改动就改这里，用户会被重新询问一次。 */
const POLICY_VERSION = '2026-11-19';

/** 当前挂在页面上的弹窗组件（同一时刻只会有一个页面在前台） */
let modal = null;
/** 正在等待答复的那一次询问；重复调用复用它，不叠第二层 */
let pending = null;

function readConsent() {
  try {
    return wx.getStorageSync(STORAGE_KEY) || null;
  } catch (e) {
    return null;
  }
}

function hasAgreed() {
  const saved = readConsent();
  return !!(saved && saved.version === POLICY_VERSION && saved.agreedAt);
}

function markAgreed() {
  try {
    wx.setStorageSync(STORAGE_KEY, { version: POLICY_VERSION, agreedAt: Date.now() });
  } catch (e) {
    // 写不进去也放行本次；下次会再问一遍，不会少问
  }
}

function registerModal(component) {
  modal = component;
}

function unregisterModal(component) {
  if (modal === component) {
    modal = null;
  }
}

/**
 * 收集动作的前置闸门。
 * 已同意 → 立刻 resolve(true)；否则唤起弹窗，等用户点「同意/暂不」。
 *
 * @returns {Promise<boolean>} 同意为 true，拒绝为 false
 */
function ensureAgreed() {
  if (hasAgreed()) {
    return Promise.resolve(true);
  }
  if (pending) {
    return pending.promise;
  }

  const promise = new Promise((resolve) => {
    pending = { resolve };
    if (!modal) {
      // 页面漏挂组件：按拒绝处理。宁可挡住收集，也不能悄悄采集。
      pending = null;
      resolve(false);
      return;
    }
    modal.open();
  });
  if (pending) {
    pending.promise = promise;
  }
  return promise;
}

/** 弹窗里点了「同意并继续」 */
function agree() {
  markAgreed();
  settle(true);
}

/** 弹窗里点了「暂不」 */
function disagree() {
  settle(false);
}

function settle(value) {
  const current = pending;
  pending = null;
  if (current) {
    current.resolve(value);
  }
}

/** 跳政策全文页。页面是纯静态文本，正常不会失败；保留兜底提示，避免点了没有任何反馈 */
function openPolicy() {
  wx.navigateTo({
    url: '/pages/privacy/index',
    fail: () => {
      wx.showToast({ title: '政策页打开失败，请稍后再试', icon: 'none' });
    },
  });
}

module.exports = {
  POLICY_VERSION,
  hasAgreed,
  ensureAgreed,
  agree,
  disagree,
  registerModal,
  unregisterModal,
  openPolicy,
};
