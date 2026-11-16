const { request, setToken } = require('../../utils/request');

const MERCHANT_KEY = 'meal_merchant';

Page({
  data: {
    username: '',
    password: '',
    submitting: false,
  },

  onLoad() {
    const saved = wx.getStorageSync(MERCHANT_KEY);
    if (saved && saved.username) {
      this.setData({ username: saved.username });
    }
  },

  onInput(e) {
    this.setData({ [e.currentTarget.dataset.field]: e.detail.value });
  },

  onLogin() {
    const username = (this.data.username || '').trim();
    const password = this.data.password || '';
    if (!username || !password) {
      wx.showToast({ title: '请输入账号和密码', icon: 'none' });
      return;
    }
    if (this.data.submitting) {
      return;
    }
    this.setData({ submitting: true });

    request({
      url: '/auth/admin/login',
      method: 'POST',
      auth: false,
      data: { username, password },
    })
      .then((result) => {
        setToken(result.token);
        wx.setStorageSync(MERCHANT_KEY, {
          userId: result.userId,
          username: result.username,
          displayName: result.displayName,
          permissions: result.permissions || [],
        });
        this.bindOpenid();
        wx.redirectTo({ url: '/pages/merchant/home' });
      })
      .catch((err) => {
        wx.showToast({ title: (err && err.message) || '登录失败', icon: 'none' });
        this.setData({ submitting: false });
      });
  },

  /**
   * 登录成功后顺手把收通知的微信绑上。
   *
   * 商家是账密登录，服务端拿不到 openid，不绑的话「有新订单」这类订阅消息发不出来。
   * 绑失败不打扰用户：可能是这个微信已经绑过别的商家账号，也可能是网络问题，
   * 都不该影响他进去接单。真正的绑定状态在「我的」里能重试。
   */
  bindOpenid() {
    wx.login({
      success: (res) => {
        if (!res.code) {
          return;
        }
        request({ url: '/admin/bind-openid', method: 'POST', data: { code: res.code } }).catch(
          () => {}
        );
      },
    });
  },

  onBackToCustomer() {
    wx.reLaunch({ url: '/pages/index/index' });
  },
});
