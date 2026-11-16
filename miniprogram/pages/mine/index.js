const app = getApp();
const { request } = require('../../utils/request');

Page({
  data: {
    me: {},
    auditTitle: '',
    auditDesc: '',
    phoneAuthEnabled: false,
    nickEditing: false,
    nickInput: '',
    phoneEditing: false,
    phoneInput: '',
    saving: false,
  },

  onShow() {
    app
      .loadConfig()
      .then((config) => {
        this.setData({ phoneAuthEnabled: String(config.phoneAuthEnabled) === 'true' });
      })
      .catch(() => {});
    app
      .ensureLogin()
      .then(() => app.refreshMe())
      .then((me) => this.apply(me))
      .catch((err) => app.toastError(err));
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

  /**
   * 微信手机号一键授权。
   *
   * 新版基础库只给 code，不给 encryptedData；服务端目前按 encryptedData 解，
   * 所以拿不到就直接引导他手填 —— 别让用户卡在一个「点了没反应」的按钮上。
   */
  onGetPhoneNumber(e) {
    const detail = e.detail || {};
    if (!detail.encryptedData || !detail.iv) {
      wx.showToast({ title: '请手动填写手机号', icon: 'none' });
      this.onEditPhone();
      return;
    }
    request({
      url: '/auth/phone/wx',
      method: 'POST',
      data: { encryptedData: detail.encryptedData, iv: detail.iv },
    })
      .then(() => app.refreshMe())
      .then((me) => {
        this.apply(me);
        wx.showToast({ title: '已绑定', icon: 'success' });
      })
      .catch((err) => app.toastError(err));
  },

  /* ---------------- 导航 ---------------- */

  onOpenOrders() {
    wx.navigateTo({ url: '/pages/order/list' });
  },

  onOpenAddress() {
    wx.navigateTo({ url: '/pages/address/list' });
  },

  onOpenMerchant() {
    wx.navigateTo({ url: '/pages/merchant/login' });
  },
});
