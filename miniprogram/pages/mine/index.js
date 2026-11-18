const app = getApp();
const { request, setToken } = require('../../utils/request');

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
    /** 注销进行中：防止连点两次 */
    deleting: false,
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
      // 商家看不到昵称就没法认人，也就不能审核：先把「卡在哪」说清楚
      const hasNick = !!(me && me.nickName && me.nickName.trim());
      title = '审核中';
      desc = hasNick ? '商家通过后就能下单了' : '请先点上方昵称填写，商家才能审核';
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
    // me.phone 是掩码（138****1234），直接回填会把掩码当成号码再存一遍。
    // 回填明文走 /auth/my-phone：只返回本人的那一条，不接收 userId
    this.setData({ phoneEditing: true, phoneInput: '' });
    request({ url: '/auth/my-phone' })
      .then((phone) => {
        if (this.data.phoneEditing) {
          this.setData({ phoneInput: phone || '' });
        }
      })
      .catch(() => {
        // 取不到就留空让用户重填，不要因此挡住改号
      });
  },

  onPhoneInput(e) {
    this.setData({ phoneInput: (e.detail && e.detail.value) || '' });
  },

  onCancelPhone() {
    this.setData({ phoneEditing: false });
  },

  onSavePhone() {
    const phone = (this.data.phoneInput || '').trim();
    if (!/^1[3-9]\d{9}$/.test(phone)) {
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

  /* ---------------- 隐私与注销 ---------------- */

  onOpenPrivacy() {
    wx.navigateTo({
      url: '/pages/privacy/index',
      fail: () => wx.showToast({ title: '隐私政策页即将上线', icon: 'none' }),
    });
  },

  /**
   * 注销账号：先把后果摆清楚，再要一次确认。
   *
   * 有订单的账号不会被删行（订单是对账与售后凭证），只做匿名化：
   * 昵称改「已注销」、手机号 / 头像 / openid 清空、令牌当场失效。
   * 后果文案来自服务端 preview，不在前端写死数字。
   */
  onDeleteAccount() {
    if (this.data.deleting) {
      return;
    }
    request({ url: '/auth/delete-preview' })
      .then((preview) => this.confirmDelete(preview))
      .catch((err) => app.toastError(err));
  },

  confirmDelete(preview) {
    const hasOrders = (preview.orderCount || 0) > 0;
    const lines = [
      hasOrders
        ? '你有 ' + preview.orderCount + ' 笔订单，会保留 3 年用于交易与售后，其中的联系电话改成掩码。'
        : '你没有历史订单，账号会被直接删除。',
      preview.addressCount > 0 ? '名下 ' + preview.addressCount + ' 条收货地址会一并删除。' : '',
      '昵称、头像、手机号会立即清除，注销后无法恢复。',
    ].filter(Boolean);
    wx.showModal({
      title: '注销账号',
      content: lines.join('\n'),
      confirmText: '仍要注销',
      confirmColor: '#c0392b',
      success: (first) => {
        if (!first.confirm) {
          return;
        }
        wx.showModal({
          title: '最后确认',
          content: '注销后不能再下单，也找不回这个账号，确定吗？',
          confirmText: '确定注销',
          confirmColor: '#c0392b',
          success: (second) => {
            if (second.confirm) {
              this.doDeleteAccount();
            }
          },
        });
      },
    });
  },

  doDeleteAccount() {
    this.setData({ deleting: true });
    request({ url: '/auth/delete', method: 'POST' })
      .then(() => {
        setToken('');
        wx.showToast({ title: '已注销', icon: 'success' });
        setTimeout(() => wx.reLaunch({ url: '/pages/index/index' }), 800);
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ deleting: false }));
  },
});
