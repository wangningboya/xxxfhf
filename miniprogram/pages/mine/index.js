const app = getApp();
const { request, setToken } = require('../../utils/request');
const privacy = require('../../utils/privacy');

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
    // 未登记的微信没有令牌，拉 /auth/me 只会 401，先把人送到填邀请码的页面
    app
      .ensureLogin()
      .then((me) => {
        if (me && !me.registered) {
          wx.reLaunch({ url: '/pages/no-access/index' });
          return null;
        }
        return app.refreshMe();
      })
      .then((me) => {
        if (me) {
          this.apply(me);
        }
      })
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
    // 昵称属于个人信息，第一次填写前要先让用户看明白收集用途
    privacy.ensureAgreed().then((ok) => {
      if (!ok) {
        wx.showToast({ title: '同意后才能填写昵称，你可以先逛逛', icon: 'none' });
        return;
      }
      this.setData({ nickEditing: true, nickInput: (this.data.me && this.data.me.nickName) || '' });
    });
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
    // 手机号是敏感个人信息：先弹隐私提示，同意后才打开输入框
    privacy.ensureAgreed().then((ok) => {
      if (!ok) {
        wx.showToast({ title: '同意后才能修改手机号，你可以先逛逛', icon: 'none' });
        return;
      }
      // 当前号只回显掩码（me.phone 来自 /auth/me，服务端已用 MaskUtil.phone 打码），
      // 仅作展示不可编辑；修改走「填新号」路径，掩码值不可能被当成号码存回去。
      // 不再调 /auth/my-phone 取明文回填。
      this.setData({ phoneEditing: true, phoneInput: '' });
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
   * 本机存着同一个商家账号的登录态就直接进；没有缓存时不再逼他重输账密 ——
   * 商家令牌本来就是按 user_type 签发的，/admin/* 拦截器只看库里的类型与角色，
   * 不区分令牌从哪条登录路径来（决策记录 D-27）。静默调 /auth/admin/me
   * 把 username / permissions 补进本地缓存后直达；接口失败（掉线、权限被收回）
   * 才退回账密登录页。
   */
  openMerchant() {
    const me = this.data.me || {};
    const saved = wx.getStorageSync(MERCHANT_KEY);
    if (saved && saved.userId && saved.userId === me.userId) {
      wx.navigateTo({ url: '/pages/merchant/home' });
      return;
    }
    request({ url: '/auth/admin/me', method: 'GET' })
      .then((result) => {
        wx.setStorageSync(MERCHANT_KEY, {
          userId: result.userId,
          username: result.username,
          displayName: result.displayName,
          permissions: result.permissions || [],
        });
        wx.navigateTo({ url: '/pages/merchant/home' });
      })
      .catch(() => {
        // 拿不到商家登录态：令牌过期 / 类型不是 merchant / 没权限，回登录页兜底
        wx.navigateTo({ url: '/pages/merchant/login' });
      });
  },

  /* ---------------- 隐私与注销 ---------------- */

  /** 证照公示：营业执照与食品经营许可证，法规要求公示在经营主页面 */
  onOpenLicense() {
    wx.navigateTo({
      url: '/pages/license/index',
      fail: () => wx.showToast({ title: '证照页打开失败，请稍后再试', icon: 'none' }),
    });
  },

  onOpenPrivacy() {
    wx.navigateTo({
      url: '/pages/privacy/index',
      fail: () => wx.showToast({ title: '政策页打开失败，请稍后再试', icon: 'none' }),
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
        // 令牌当场作废，本地状态一并清掉；然后去「已注销」页而不是回首页——
        // 首页会把注销后的微信当成一个没登记的新用户，立刻冲到填邀请码的页面
        setToken('');
        app.globalData.me = null;
        wx.reLaunch({ url: '/pages/account-deleted/index' });
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ deleting: false }));
  },
});
