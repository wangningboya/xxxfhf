const app = getApp();
const { request, setToken } = require('../../utils/request');
const privacy = require('../../utils/privacy');

/** 商家登录态键名，与 pages/merchant/login.js 保持一致 */
const MERCHANT_KEY = 'meal_merchant';

/**
 * 等微信昵称审核结论的上限。
 *
 * `bindnicknamereview` 是客户端异步回调，点保存那一刻通常还没结果；超过这个时间仍没回调，
 * 按 `timeout === true` 同一口径放行（检测没跑完不该惩罚用户），后端还有一道 msg_sec_check。
 */
const NICK_REVIEW_WAIT_MS = 3000;

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
    /** 微信审核判过违规的那段昵称：保存时比对，同一段不再提交 */
    nickRejectedText: '',
    /** 微信判过「通过」的那段昵称：再次保存直接提交，不必白等一次审核 */
    nickApprovedText: '',
    /** 已点保存、正在等微信审核结论的昵称；非空时按钮显示「保存中…」 */
    nickPending: '',
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
      this.clearNickWait();
      this.setData({
        nickEditing: true,
        nickInput: (this.data.me && this.data.me.nickName) || '',
        nickRejectedText: '',
        nickApprovedText: '',
        nickPending: '',
      });
    });
  },

  onNickInput(e) {
    const value = (e.detail && e.detail.value) || '';
    // 等审核期间用户又改了字：这次挂起的提交作废，按新文本重新点保存
    if (this.data.nickPending && value.trim() !== this.data.nickPending) {
      this.clearNickWait();
      this.setData({ nickPending: '' });
    }
    this.setData({ nickInput: value });
  },

  /**
   * 微信侧的昵称审核结果（基础库 2.29.1+，`detail = { pass, timeout }`）。
   *
   * 审核是客户端异步回调，点保存那一刻往往还没有结论，所以保存不能抢在它前面提交：
   * 先挂起（`nickPending`），等这里给出结论再决定发不发请求。
   * `pass === false` 时微信自己会弹「无法使用该昵称」，这里不再叠一条提示。
   * `timeout === true` 按放行处理（检测没跑完不该惩罚用户）。
   */
  onNickReview(e) {
    const detail = e.detail || {};
    const text = (this.data.nickInput || '').trim();
    const pending = this.data.nickPending;

    if (detail.timeout) {
      if (pending && pending === text) {
        this.submitNick(pending);
      }
      return;
    }
    if (detail.pass === false) {
      this.setData({ nickRejectedText: pending || text, nickApprovedText: '' });
      // 挂起的那次不提交；微信自己的提示已经告诉用户为什么没存上
      if (pending) {
        this.clearNickWait();
        this.setData({ nickPending: '' });
      }
      return;
    }
    this.setData({ nickApprovedText: text, nickRejectedText: '' });
    if (pending && pending === text) {
      this.submitNick(pending);
    }
  },

  onCancelNick() {
    this.clearNickWait();
    this.setData({ nickEditing: false, nickPending: '' });
  },

  /**
   * 保存昵称。
   *
   * 取值走 form 的 submit，而不是 `data.nickInput`：微信在审核不通过、或
   * `reviewNickname` 上传超时时会**清空输入框视图**，受控的 `data` 不会跟着变，
   * 读 data 等于把已被微信否掉的文本原样提交（官方文档也建议用 form 收集）。
   */
  onSubmitNick(e) {
    if (this.data.saving || this.data.nickPending) {
      return;
    }
    const nickName = ((e.detail && e.detail.value && e.detail.value.nickName) || '').trim();
    // 视图已被微信清空（审核判废 / 上传超时）：data 也要跟着清，
    // 否则受控 value 会把旧文本写回输入框，下一次保存又把它提交上去。
    if (!nickName) {
      if (this.data.nickInput) {
        this.setData({ nickInput: '' });
      }
      wx.showToast({ title: '请填写昵称', icon: 'none' });
      return;
    }
    // 微信审核判过违规的同一段文本不再提交；改过字就放行，由服务端兜底
    if (nickName === this.data.nickRejectedText) {
      wx.showToast({ title: '该昵称无法使用，请换一个', icon: 'none' });
      return;
    }
    // 已有「通过」结论就直接提交；还没有结论先挂起，等 bindnicknamereview 回来再决定
    if (nickName === this.data.nickApprovedText) {
      this.submitNick(nickName);
      return;
    }
    this.waitNickReview(nickName);
  },

  /**
   * 微信侧还没有这段文本的结论：先挂起，等审核回调。
   *
   * 点「保存」本身会让输入框失焦、触发审核，正常流程下这里几百毫秒内就能拿到结论；
   * 等满 {@link NICK_REVIEW_WAIT_MS} 仍没有回调，按 timeout 口径放行提交。
   */
  waitNickReview(nickName) {
    this.setData({ nickPending: nickName });
    this.clearNickWait();
    this.nickWaitTimer = setTimeout(() => {
      // 核对挂起期间没被改字作废。① ② 读的都是 data，判不出「视图被微信清空」；
      // 实测受控 value 也写不回被清空的视图，所以这里只保证旧文本不被再次提交。
      const current = (this.data.nickInput || '').trim();
      if (this.data.nickPending === nickName && current === nickName) {
        this.submitNick(nickName);
      } else {
        this.setData({ nickPending: '' });
      }
    }, NICK_REVIEW_WAIT_MS);
  },

  clearNickWait() {
    if (this.nickWaitTimer) {
      clearTimeout(this.nickWaitTimer);
      this.nickWaitTimer = null;
    }
  },

  /** 真正的保存请求：只有审核通过、或等满超时之后才会走到这里 */
  submitNick(nickName) {
    this.clearNickWait();
    this.setData({ nickPending: '', saving: true });
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
