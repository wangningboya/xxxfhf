const { request, setToken, getToken, toastError } = require('./utils/request');
const { getGroupEncrypted } = require('./utils/group-entry');

App({
  globalData: {
    /** 当前用户的准入与审核状态，结构同服务端 /auth/me */
    me: null,
    /** 店铺配置：名称、公告、截单时间等 */
    config: {},
    configLoaded: false,
    /** 登录中的 Promise，避免多个页面同时触发重复 wx.login */
    loginPromise: null,
  },

  onLaunch() {
    this.ensureLogin().catch(() => {
      // 启动期登录失败不阻塞渲染，页面会再试一次并给出提示
    });
  },

  /**
   * 确保已登录。并发调用共享同一个 Promise。
   *
   * 只有 wx.login 拿到的 code 能换 openid，没有本地兜底方案，所以失败就是失败。
   * 不能假装已登录——那会让后续所有接口都 401，现象更难定位。
   */
  ensureLogin() {
    if (getToken()) {
      return Promise.resolve(this.globalData.me);
    }
    if (this.globalData.loginPromise) {
      return this.globalData.loginPromise;
    }

    const promise = new Promise((resolve, reject) => {
      wx.login({
        success: (res) => {
          if (!res.code) {
            reject({ message: '微信登录失败，请重新进入小程序' });
            return;
          }
          request({ url: '/auth/login', method: 'POST', data: { code: res.code }, auth: false })
            .then((data) => {
              setToken(data.token);
              this.globalData.me = {
                userId: data.userId,
                nickName: data.nickName,
                avatarUrl: data.avatarUrl,
                accessGranted: data.accessGranted,
                accessVia: data.accessVia,
                auditStatus: data.auditStatus,
                rejectReason: data.rejectReason,
                phone: data.phone,
              };
              resolve(this.globalData.me);
            })
            .catch(reject);
        },
        fail: () => reject({ message: '微信登录失败，请重新进入小程序' }),
      });
    });

    this.globalData.loginPromise = promise;
    // 成功或失败都清空，否则失败后会一直返回同一个 rejected Promise
    promise
      .catch(() => {})
      .then(() => {
        this.globalData.loginPromise = null;
      });

    return promise;
  },

  /** 刷新当前用户状态（商家审核后用户无需重登即可看到变化）。 */
  refreshMe() {
    return request({ url: '/auth/me' }).then((data) => {
      this.globalData.me = data;
      return data;
    });
  },

  /**
   * 进入首页的统一前置：登录 → 刷新状态 → 尝试群绑定。
   *
   * 令牌 7 天到期是必然发生的事，所以这里遇到 401 会清掉本地令牌重新 wx.login 一次，
   * 而不是把「登录已过期」甩给用户让他自己猜怎么办。
   *
   * @param {object} options 页面 onLoad 的 options（含 shareTicket）
   * @return {Promise<object>} 最新的用户状态
   */
  bootstrap(options) {
    return this.ensureLogin()
      .then(() =>
        this.refreshMe().catch((err) => {
          if (err && err.code === 40101) {
            setToken('');
            return this.ensureLogin().then(() => this.refreshMe());
          }
          throw err;
        })
      )
      .then((me) => (me && me.accessGranted ? me : this.tryBindGroup(options, me)));
  },

  /**
   * 用群卡片带的加密数据绑定 openGId。
   *
   * 失败不弹错：这里是「自动尝试」，拿不到群标识的用户会落到引导页走邀请码，
   * 在这条路径上弹红字只会让用户困惑。
   */
  tryBindGroup(options, currentMe) {
    return getGroupEncrypted(options).then((encrypted) => {
      if (!encrypted) {
        return currentMe;
      }
      return request({
        url: '/auth/bind-group',
        method: 'POST',
        data: { encryptedData: encrypted.encryptedData, iv: encrypted.iv },
      })
        .then((state) => {
          const merged = Object.assign({}, currentMe, state);
          this.globalData.me = merged;
          return merged;
        })
        .catch(() => currentMe);
    });
  },

  /** 加载店铺配置，只在首次成功时缓存。 */
  loadConfig() {
    if (this.globalData.configLoaded) {
      return Promise.resolve(this.globalData.config);
    }
    return request({ url: '/config' })
      .then((data) => {
        this.globalData.config = data || {};
        this.globalData.configLoaded = true;
        return this.globalData.config;
      })
      .catch(() => this.globalData.config);
  },

  toastError,
});
