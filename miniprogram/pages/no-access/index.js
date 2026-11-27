const app = getApp();
const { toastError } = require('../../utils/request');

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
    // 6 位纯数字，没有大小写问题；只去空格
    this.setData({ code: (e.detail.value || '').trim() });
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

    // 准入只有一个入口：/auth/join（建号与核销在同一事务里完成）。
    // 已登记的行会被服务端按 openid 直接复用，所以这里不再区分「有没有账号」——
    // 原来那条「已登记走 /auth/invite」的分支对应的状态在库里不存在，接口已删。
    app
      .joinByInvite(code)
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
