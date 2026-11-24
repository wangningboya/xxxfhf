const app = getApp();
const { request } = require('../../utils/request');

/** 商家身份缓存键，与 pages/merchant/home.js 是同一份 */
const MERCHANT_KEY = 'meal_merchant';

/**
 * 兜底计时：超过这个时间还没跳走，就不再让用户干看转圈。
 *
 * 正常路径是一条接口的时间（几百毫秒），8 秒是「网络差 + 冷启动」的宽松上限。
 * 没有这个计时的话，接口既不 success 也不 fail（弱网挂起）时页面会永远转圈，
 * 用户只能杀小程序 —— 有个「重试」按钮至少能自己救一次。
 */
const GUARD_MS = 8000;

/**
 * 取小程序码里的 scene。
 *
 * `getUnlimited` 是把 scene 原样编进码里，微信把参数传上来时会对它做一次 URL 编码，
 * 所以必须 decodeURIComponent。当前订单号是纯字母数字，编码前后长得一样，
 * 将来单号带上任何符号就会静默变成一个「不存在的订单号」—— 那时候再来查这个 bug 很贵。
 *
 * @param {object} options 页面 onLoad 的 options
 * @return {string} 订单号，取不到返回空串
 */
function sceneOf(options) {
  const raw = (options && options.scene) || '';
  if (!raw) {
    return '';
  }
  try {
    return decodeURIComponent(raw).trim();
  } catch (e) {
    // 已经是明文（或者编码不合法）：按原样用，总比空着强
    return raw.trim();
  }
}

/**
 * 小程序码落地页。
 *
 * <p>这个页面不渲染订单，只做两件事：把 scene 里的订单号换成订单 ID，然后跳详情。
 * 为什么不在落地页直接渲染详情：详情页两侧（用户端 / 商家端）各有一套渲染逻辑，
 * 复制一份到扫码入口，将来改版一定漏改。
 *
 * <p><b>路径必须与后端 `getUnlimited` 的 `page` 参数完全一致</b>：
 * `pages/scan/entry` 少一个字符，微信就直接报「页面不存在」，连这个页面都进不来。
 *
 * <p>身份分叉只看本机有没有商家缓存：一台手机要么是顾客号、要么是商家号，
 * 同一个微信同时是两种身份的情况在这个业务里不存在。
 */
Page({
  data: {
    failed: false,
    message: '',
  },

  onLoad(options) {
    const opts = options || {};
    this.entryOptions = opts;
    // options.orderNo 是给真机调试用的：开发者工具里手填 orderNo 比造一张码快
    this.orderNo = sceneOf(opts) || opts.orderNo || '';
    this.left = false;

    if (!this.orderNo) {
      this.fail('没有读到订单号，请重新扫码');
      return;
    }
    this.startGuard();
    this.open();
  },

  onUnload() {
    // 跳走后本页销毁，后面的回调不能再 setData（会报「页面已销毁」）
    this.left = true;
    clearTimeout(this.guard);
  },

  onRetry() {
    this.setData({ failed: false, message: '' });
    this.left = false;
    this.startGuard();
    this.open();
  },

  /** 兜底页的「回首页」：按当前身份回各自的主页，不要把人送回商家登录页 */
  onHome() {
    const saved = wx.getStorageSync(MERCHANT_KEY);
    const url = saved && saved.userId ? '/pages/merchant/home' : '/pages/home/index';
    wx.reLaunch({ url });
  },

  startGuard() {
    clearTimeout(this.guard);
    this.guard = setTimeout(() => this.fail('打开超时，请检查网络后重试'), GUARD_MS);
  },

  open() {
    const saved = wx.getStorageSync(MERCHANT_KEY);
    if (saved && saved.userId) {
      this.openMerchant();
    } else {
      this.openUser();
    }
  },

  /**
   * 商家分支：只要订单 ID，不带详情。
   *
   * `/admin/order/by-no` 只回一个 Long，与 `/detail` 同一个权限口径（order.view / order.accept），
   * 所以没权限的账号在这里就会被 403 挡住，不会出现「跳过去了才报错」。
   */
  openMerchant() {
    request({ url: '/admin/order/by-no?orderNo=' + encodeURIComponent(this.orderNo) })
      .then((orderId) => this.go('/pages/merchant/order-detail?orderId=' + orderId))
      .catch((err) => {
        if (err && err.code === 40101) {
          // 商家令牌 7 天到期。不在这里清 meal_merchant：登录页还要用它回填账号，
          // 清了用户得重新输一遍账号。代价是这次扫码看不到订单 —— 登录后自己从列表里点。
          this.go('/pages/merchant/login');
          return;
        }
        this.fail((err && err.message) || '打不开这个订单');
      });
  },

  /**
   * 顾客分支：先过身份，再换 ID。
   *
   * 走 `app.bootstrap()` 而不是 `app.ensureLogin()`：扫码多半是冷启动，
   * 此时 `globalData.me` 还是空的，`ensureLogin` 在有令牌时会直接返回空值 ——
   * 那就等于没判断准入。bootstrap 会去拉 `/auth/me`，并处理「未登记 / 未准入」两个分支。
   */
  openUser() {
    app
      .bootstrap(this.entryOptions)
      .then((me) => {
        if (!me || !me.accessGranted) {
          // 与首页同一口径：不是本店群里的人一律去引导页
          this.left = true;
          clearTimeout(this.guard);
          wx.reLaunch({ url: '/pages/no-access/index' });
          return null;
        }
        return request({ url: '/order/by-no?orderNo=' + encodeURIComponent(this.orderNo) });
      })
      .then((order) => {
        if (order) {
          this.go('/pages/order/detail?orderId=' + order.orderId);
        }
      })
      .catch((err) => this.fail((err && err.message) || '打不开这个订单'));
  },

  go(url) {
    if (this.left) {
      return;
    }
    this.left = true;
    clearTimeout(this.guard);
    // redirectTo 而不是 navigateTo：落地页不该留在栈里，否则用户从详情返回会回到一个只转圈的页面
    wx.redirectTo({ url });
  },

  fail(message) {
    if (this.left) {
      return;
    }
    clearTimeout(this.guard);
    this.setData({ failed: true, message });
  },
});
