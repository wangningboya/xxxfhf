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
    // 已准入的人误入本页时直接送回首页，不要让他卡在这儿。
    // 还没登记的人就停在本页填邀请码——这张码既是准入凭证，也是建号凭据。
    app
      .ensureLogin()
      .then((me) => {
        if (me && !me.registered) {
          // 明确没登记：停在本页填邀请码，这张码既是准入凭证也是建号凭据
          this.setData({ loginFailed: false });
          return null;
        }
        return app.refreshMe().then((fresh) => {
          if (fresh && fresh.accessGranted) {
            wx.reLaunch({ url: '/pages/index/index' });
          }
        });
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

    const me = app.globalData.me;
    // 已登记只是没准入：核销一张邀请码即可；还没登记：这张码同时把账号建出来
    const task =
      me && me.registered
        ? request({ url: '/auth/invite', method: 'POST', data: { code } }).then((state) => {
            app.globalData.me = Object.assign({}, app.globalData.me, state);
            return state;
          })
        : app.joinByInvite(code);

    task
      .then(() => {
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
