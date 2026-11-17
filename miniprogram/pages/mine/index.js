const app = getApp();
const { request } = require('../../utils/request');

/** 商家登录态键名，与 pages/merchant/login.js 保持一致 */
const MERCHANT_KEY = 'meal_merchant';

/**
 * 宫格全部格位定义（顺序即展示顺序，不足一行靠左排）。
 *
 * `key` 同时是 `data-action` 的取值，点击时按它分发；手机号格位用 `editPhone`
 * 而不是 `phone`，与「点了就弹输入框改手机号」这个动作对齐，免得读成一个页面路由。
 */
const CELL_DEFS = {
  orders: { key: 'orders', label: '我的订单', icon: '/assets/icons/orders.png' },
  address: { key: 'address', label: '收货地址', icon: '/assets/icons/address.png' },
  phone: { key: 'editPhone', label: '手机号', icon: '/assets/icons/phone.png' },
  merchant: { key: 'merchant', label: '商家工作台', icon: '/assets/icons/merchant.png' },
};

Page({
  data: {
    me: {},
    auditTitle: '',
    auditDesc: '',
    /** 宫格：我的订单 / 收货地址 / 手机号 / 商家工作台（仅商家账号） */
    cells: [],
    nickEditing: false,
    nickInput: '',
    phoneEditing: false,
    phoneInput: '',
    saving: false,
  },

  onShow() {
    app
      .ensureLogin()
      .then(() => app.refreshMe())
      .then((me) => this.apply(me))
      .catch((err) => {
        app.toastError(err);
        this.buildCells(this.data.me);
      });
  },

  apply(me) {
    const status = (me && me.auditStatus) || 'pending';
    let title = '';
    let desc = '';
    if (status === 'pending') {
      title = '审核中';
      desc = '商家通过后就能下单了';
    } else if (status === 'approved') {
      title = '已通过审核';
      desc = '可以正常下单';
    } else if (status === 'rejected') {
      title = '审核未通过';
      desc = (me && me.rejectReason) || '请联系群管理员';
    } else {
      title = '账号已停用';
      desc = '请联系商家';
    }
    this.setData({ me, auditTitle: title, auditDesc: desc });
    this.buildCells(me);
  },

  /**
   * 按身份拼宫格。
   *
   * 商家工作台只对 user_type = merchant 的账号出现；手机号格位常驻——
   * 不再有「微信授权开关」，人人都能自己手填。
   */
  buildCells(me) {
    const cells = [CELL_DEFS.orders, CELL_DEFS.address, CELL_DEFS.phone];
    if (me && me.userType === 'merchant') {
      cells.push(CELL_DEFS.merchant);
    }
    this.setData({ cells });
  },

  /* ---------------- 昵称 ---------------- */

  onEditNick() {
    this.setData({ nickEditing: true, nickInput: (this.data.me && this.data.me.nickName) || '' });
  },

  onNickInput(e) {
    this.setData({ nickInput: (e.detail && e.detail.value) || '' });
  },

  onCancelNick() {
    this.setData({ nickEditing: false });
  },

  onSaveNick() {
    const nickName = (this.data.nickInput || '').trim();
    if (!nickName) {
      wx.showToast({ title: '请填写昵称', icon: 'none' });
      return;
    }
    if (this.data.saving) {
      return;
    }
    this.setData({ saving: true });
    request({ url: '/auth/nickname', method: 'POST', data: { nickName } })
      .then(() => app.refreshMe())
      .then((me) => {
        this.apply(me);
        this.setData({ nickEditing: false });
        wx.showToast({ title: '已保存', icon: 'success' });
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ saving: false }));
  },

  /* ---------------- 手机号 ---------------- */

  onEditPhone() {
    this.setData({ phoneEditing: true, phoneInput: (this.data.me && this.data.me.phone) || '' });
  },

  onPhoneInput(e) {
    this.setData({ phoneInput: (e.detail && e.detail.value) || '' });
  },

  onCancelPhone() {
    this.setData({ phoneEditing: false });
  },

  onSavePhone() {
    const phone = (this.data.phoneInput || '').trim();
    if (!/^1\d{10}$/.test(phone)) {
      wx.showToast({ title: '请填写正确的手机号', icon: 'none' });
      return;
    }
    if (this.data.saving) {
      return;
    }
    this.setData({ saving: true });
    request({ url: '/auth/phone', method: 'POST', data: { phone } })
      .then(() => app.refreshMe())
      .then((me) => {
        this.apply(me);
        this.setData({ phoneEditing: false });
        wx.showToast({ title: '已保存', icon: 'success' });
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ saving: false }));
  },

  /* ---------------- 宫格导航 ---------------- */

  onTapCell(e) {
    const action = e.currentTarget.dataset.action;
    if (action === 'orders') {
      wx.navigateTo({ url: '/pages/order/list' });
      return;
    }
    if (action === 'address') {
      wx.navigateTo({ url: '/pages/address/list' });
      return;
    }
    if (action === 'editPhone') {
      this.onEditPhone();
      return;
    }
    if (action === 'merchant') {
      this.openMerchant();
    }
  },

  /**
   * 进商家工作台。
   *
   * 本机存着同一个商家账号的登录态就直接进；换了手机（本地没登录态）先进登录页。
   */
  openMerchant() {
    const me = this.data.me || {};
    const saved = wx.getStorageSync(MERCHANT_KEY);
    if (saved && saved.userId && saved.userId === me.userId) {
      wx.navigateTo({ url: '/pages/merchant/home' });
      return;
    }
    wx.navigateTo({ url: '/pages/merchant/login' });
  },
});
