/**
 * 隐私授权状态 + 弹窗调度。
 *
 * 做四件事：
 * 1. 记录「用户在哪个政策版本下同意过」（版本一变旧同意即失效，必须重新告知）；
 * 2. 给页面一个闸门 `ensureAgreed()`：每个「收集动作」前 await 它，同意才继续；
 * 3. 把弹窗组件注册进来，保证全站同时只有一个弹窗实例（防叠层）；
 * 4. 接收微信侧的授权请求（`onWechatAuthNeeded`），把弹窗的答复 resolve 回微信。
 *
 * 第 4 条是「微信侧同步」：本地的 consent 只对自己的闸门有效，
 * 微信判断隐私接口能不能用，看的是微信自己的授权状态，两者必须一起给。
 *
 * 不在这里判断业务：哪些动作算收集，由调用方决定。
 */

const STORAGE_KEY = 'meal_privacy_consent';
/** 拒绝记录：只用于「不主动打扰」的冷却判断，不参与授权判断 */
const DECLINE_KEY = 'meal_privacy_declined';

/** 政策版本号：政策全文有实质改动就改这里，用户会被重新询问一次。 */
const POLICY_VERSION = '2026-11-26';

/** 拒绝后的主动打扰冷却期（7 天）。只约束我们主动弹，不约束用户自己点控件。 */
const DECLINE_COOLDOWN_MS = 7 * 24 * 60 * 60 * 1000;

/**
 * 回传微信授权时引用的按钮 id。
 * 必须与 components/privacy-modal/index.wxml 里同意按钮的 id 完全一致，
 * 否则 resolve 会因「找不到对应按钮」被微信判为无效。
 */
const AGREE_BTN_ID = 'privacy-agree-btn';

/** 当前挂在页面上的弹窗组件（同一时刻只会有一个页面在前台） */
let modal = null;
/** 正在等待答复的那一次询问；重复调用复用它，不叠第二层 */
let pending = null;
/** 微信侧待回传的 resolve；同一时刻只会有一次待答复的授权请求 */
let authResolve = null;
/** 本次弹窗是否已给出答复，防「官方按钮」与「点击事件」双触发时重复 resolve */
let answered = false;

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
    // 已同意，拒绝记录立刻作废；留着会让下次政策变更时误判成「刚拒过」
    wx.removeStorageSync(DECLINE_KEY);
  } catch (e) {
    // 写不进去也放行本次；下次会再问一遍，不会少问
  }
}

/**
 * 记下「用户刚点过暂不」。
 * 政策版本一并存：政策改了就是要重新告知，旧的拒绝不能拿来挡新版本。
 */
function markDeclined() {
  try {
    wx.setStorageSync(DECLINE_KEY, { version: POLICY_VERSION, declinedAt: Date.now() });
  } catch (e) {
    // 记不下就退化成「下次还弹」，宁多问一次也不少告知
  }
}

/**
 * 是否处于「拒绝后的冷却期」。**只给主动弹的调用方用**（如首页昵称浮层露出时）。
 *
 * 用户自己去点昵称框、点保存手机号这一类，属于用户发起的动作，
 * 不受本函数影响 —— 那种情况必须弹，否则接口用不了。
 */
function declinedRecently() {
  let saved = null;
  try {
    saved = wx.getStorageSync(DECLINE_KEY) || null;
  } catch (e) {
    return false;
  }
  if (!saved || !saved.declinedAt) {
    return false;
  }
  if (saved.version !== POLICY_VERSION) {
    return false;
  }
  return Date.now() - saved.declinedAt < DECLINE_COOLDOWN_MS;
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
 * 唤起弹窗，并把「本次已答复」复位。
 * 页面没挂组件时返回 false —— 调用方据此决定降级，不能假装弹过了。
 */
function openModal() {
  answered = false;
  if (!modal) {
    return false;
  }
  modal.open();
  return true;
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
  // 页面漏挂组件：按拒绝处理。宁可挡住收集，也不能悄悄采集。
  if (!openModal()) {
    return Promise.resolve(false);
  }

  const promise = new Promise((resolve) => {
    pending = { promise: null, resolve };
  });
  pending.promise = promise;
  return promise;
}

/**
 * 微信侧的隐私授权请求入口 —— 由 app.js 的 `wx.onNeedPrivacyAuthorization` 转进来。
 *
 * 这是 P0-06 的核心：本地存了「同意」不代表微信侧也同意，
 * 只要用户是在点隐私接口（`type="nickname"`、`wx.chooseMedia` 等）时被微信拦下的，
 * 就必须把答复通过 resolve 回传给微信，接口才会继续。
 *
 * @param {Function} resolve 微信给的答复函数
 */
function onWechatAuthNeeded(resolve) {
  // 这行日志是给真机/工具排查用的：看不到它就说明微信根本没发起授权请求，
  // 此时「弹窗没出现」不是本模块的 bug，而是当前环境没做隐私校验。
  console.info('[隐私] 微信要求授权，弹出弹窗等待用户答复');
  // 上一个授权请求还没答复又被新请求顶掉时，先把旧的按「拒绝」结掉，
  // 否则那一次隐私接口会一直等不到 resolve（现象就是点了毫无反应）。
  if (authResolve) {
    replyToWechat('disagree');
  }
  authResolve = typeof resolve === 'function' ? resolve : null;
  if (!openModal()) {
    // 当前页面没挂弹窗：不能让微信把用户的接口吊死在那里，按拒绝回传并让调用方降级
    console.warn('[privacy] 微信请求授权，但当前页面未挂 privacy-modal');
    replyToWechat('disagree');
  }
}

/**
 * 把答复回传给微信。
 *
 * `buttonId` 指向弹窗里 `open-type="agreePrivacyAuthorization"` 的按钮：
 * 走官方按钮路线时微信已经自行完成授权，这次 resolve 是幂等的；
 * 基础库不认 buttonId 时 resolve 会抛错，吞掉即可 —— 页面流程不该因此中断。
 */
function replyToWechat(event) {
  const resolve = authResolve;
  authResolve = null;
  if (!resolve) {
    return;
  }
  try {
    resolve(event === 'agree' ? { event: 'agree', buttonId: AGREE_BTN_ID } : { event: 'disagree' });
  } catch (e) {
    console.warn('[privacy] 微信授权回传失败，按当前基础库不支持该形态处理', e);
  }
}

/** 弹窗里点了「同意并继续」 */
function agree() {
  if (answered) {
    return;
  }
  answered = true;
  markAgreed();
  replyToWechat('agree');
  settle(true);
}

/** 弹窗里点了「暂不」 */
function disagree() {
  if (answered) {
    return;
  }
  answered = true;
  markDeclined();
  replyToWechat('disagree');
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
  declinedRecently,
  ensureAgreed,
  agree,
  disagree,
  onWechatAuthNeeded,
  registerModal,
  unregisterModal,
  openPolicy,
};
