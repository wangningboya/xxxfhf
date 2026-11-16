const app = getApp();
const { request, toastError } = require('../../utils/request');

Page({
  data: {
    code: '',
    submitting: false,
    /** 登录本身失败时的提示：连 token 都拿不到就不用谈邀请码了 */
    loginFailed: false,
  },

  onShow() {
    // 已经准入的人误入本页时直接送回首页，不要让他卡在这儿
    app
      .ensureLogin()
      .then(() => app.refreshMe())
      .then((me) => {
        if (me && me.accessGranted) {
          wx.reLaunch({ url: '/pages/index/index' });
        }
      })
      .catch(() => {
        this.setData({ loginFailed: true });
      });
  },

  onCodeInput(e) {
    // 邀请码统一大写，用户手输常是小写
    this.setData({ code: (e.detail.value || '').toUpperCase() });
  },

  onSubmit() {
    const code = this.data.code.trim();
    if (!code) {
      wx.showToast({ title: '请输入邀请码', icon: 'none' });
      return;
    }
    if (this.data.submitting) {
      return;
    }
    this.setData({ submitting: true });

    request({ url: '/auth/invite', method: 'POST', data: { code } })
      .then((state) => {
        app.globalData.me = Object.assign({}, app.globalData.me, state);
        wx.showToast({ title: '验证成功', icon: 'success' });
        wx.reLaunch({ url: '/pages/index/index' });
      })
      .catch((err) => {
        toastError(err);
      })
      .then(() => {
        this.setData({ submitting: false });
      });
  },
});
