const { request, setToken, getToken, toastError } = require('./utils/request');
const { getGroupEncrypted } = require('./utils/group-entry');
const dict = require('./utils/dict');

/** 微信登录拿不到 code 时的统一文案：两种情况对用户是同一件事 */
const LOGIN_FAILED = '微信登录失败，请重新进入小程序';

/**
 * 尚未登记的微信用户。
 *
 * 服务端 /auth/login 不再「查不到就建号」，这个微信第一次进来时拿到的就是这份状态：
 * 没有令牌、没有 userId、也没有审核状态。前端据此去要准入凭证（邀请码或群卡片），
 * 凭证核验通过才调 /auth/join 建号。
 */
function unregistered() {
  return { registered: false, accessGranted: false, accessVia: null };
}

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

  /** 上次全局错误提示的时间戳，用于节流，避免连续报错时 toast 刷屏 */
  lastGlobalErrorToastAt: 0,

  /**
   * 全局错误兜底：页面 JS 未捕获的异常会走到这里。
   *
   * 错误细节对用户无意义，只弹一句友好提示；完整错误留在控制台方便开发排查。
   */
  onError(err) {
    console.error('[小程序全局错误]', err);
    this.toastGlobalError('系统开小差了，请稍后重试');
  },

  /**
   * 未处理的 Promise 拒绝兜底。
   *
   * request.js 产生的 rejection 自带面向用户的 message（如「网络异常，请检查网络后重试」），
   * 直接展示；其他来源没有可读 message，用统一文案兜住。
   */
  onUnhandledRejection(res) {
    const reason = res && res.reason;
    console.error('[未处理的 Promise 拒绝]', reason);
    this.toastGlobalError(reason && reason.message ? reason.message : '系统开小差了，请稍后重试');
  },

  /** 带节流的全局错误提示：2 秒内只弹一条 */
  toastGlobalError(message) {
    const now = Date.now();
    if (now - this.lastGlobalErrorToastAt < 2000) {
      return;
    }
    this.lastGlobalErrorToastAt = now;
    wx.showToast({ title: message, icon: 'none', duration: 2500 });
  },

  onLaunch() {
    this.ensureLogin()
      .then(() => {
        // 字典与登录无关（接口不鉴权），但排在登录后发，少一次并发首屏请求
        dict.load();
      })
      .catch(() => {
        // 启动期登录失败不阻塞渲染，页面会再试一次并给出提示
        dict.load();
      });
  },

  /**
   * 确保已登录。并发调用共享同一个 Promise。
   *
   * 只有 wx.login 拿到的 code 能换 openid，没有本地兜底方案，所以失败就是失败。
   * 不能假装已登录——那会让后续所有接口都 401，现象更难定位。
   *
   * @return {Promise<object>} 用户状态；registered=false 表示这个微信还没登记过
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
            reject({ message: LOGIN_FAILED });
            return;
          }
          request({ url: '/auth/login', method: 'POST', data: { code: res.code }, auth: false })
            .then((data) => {
              if (!data.registered) {
                // 没登记过不发令牌：建号只能发生在 joinByInvite / 群卡片登记里
                this.globalData.me = unregistered();
                resolve(this.globalData.me);
                return;
              }
              resolve(this.applyLogin(data));
            })
            .catch(reject);
        },
        fail: () => reject({ message: LOGIN_FAILED }),
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

  /**
   * 把登录 / 登记的响应落到本地：写令牌、拼用户状态。
   *
   * 单独拆出来是因为 /auth/login 与 /auth/join 的响应结构一致，
   * 且 registered 这个字段必须保留——页面靠它区分「该去填邀请码」和「已登记但没准入」。
   */
  applyLogin(data) {
    setToken(data.token);
    this.globalData.me = {
      registered: true,
      userId: data.userId,
      nickName: data.nickName,
      avatarUrl: data.avatarUrl,
      accessGranted: data.accessGranted,
      accessVia: data.accessVia,
      auditStatus: data.auditStatus,
      rejectReason: data.rejectReason,
      phone: data.phone,
    };
    return this.globalData.me;
  },

  /**
   * 凭准入凭证登记并登录（POST /auth/join）。
   *
   * 每次都新取一个 wx.login code：code 只能用一次，用户填邀请码的这段时间足够它过期。
   *
   * @param {object} credential {inviteCode} 或 {encryptedData, iv}，二选一
   */
  joinRequest(credential) {
    return new Promise((resolve, reject) => {
      wx.login({
        success: (res) => {
          if (!res.code) {
            reject({ message: LOGIN_FAILED });
            return;
          }
          request({
            url: '/auth/join',
            method: 'POST',
            auth: false,
            data: Object.assign({ code: res.code }, credential),
          })
            .then((data) => resolve(this.applyLogin(data)))
            .catch(reject);
        },
        fail: () => reject({ message: LOGIN_FAILED }),
      });
    });
  },

  /** 用邀请码登记并进入：未登记用户走这里，登记失败的文案由服务端给（码过期 / 已用过等）。 */
  joinByInvite(rawCode) {
    return this.joinRequest({ inviteCode: rawCode });
  },

  /** 刷新当前用户状态（商家审核后用户无需重登即可看到变化）。 */
  refreshMe() {
    return request({ url: '/auth/me' }).then((data) => {
      // /auth/me 只发给已登记的号，所以这里补上 registered
      this.globalData.me = Object.assign({ registered: true }, data);
      return this.globalData.me;
    });
  },

  /**
   * 待审核用户的统一拦截（需求 3.1 / 4.17）。
   *
   * 准入与审核是两件事：待审核用户能看菜单、能看自己已有的地址，
   * 但选餐、结算、自己填地址都要等商家审核通过 —— 不能等提交订单时才告诉他。
   *
   * 只读 `globalData.me`，拿不到状态时不拦：服务端在 `/address` 与 `/order` 上还有一道兜底，
   * 前端这层只负责「早说一句」。
   *
   * @param {string} message 被拦时提示的文案
   * @return {boolean} true 表示已拦住，调用方直接 return
   */
  blockIfNotApproved(message) {
    const me = this.globalData.me;
    if (!me || me.auditStatus === 'approved') {
      return false;
    }
    // 已驳回的人不是「在等」，说「审核中」会让他一直等下去
    const text = me.auditStatus === 'rejected' ? '账号申请已被驳回，先重新提交申请' : message;
    wx.showToast({ title: text, icon: 'none', duration: 2000 });
    return true;
  },

  /**
   * 进入首页的统一前置：登录 → 刷新状态 → 尝试群绑定。
   *
   * 令牌 7 天到期是必然发生的事，所以这里遇到 401 会清掉本地令牌重新 wx.login 一次，
   * 而不是把「登录已过期」甩给用户让他自己猜怎么办。
   *
   * 未登记的号没有令牌，刷新状态只会 401，所以直接去试群卡片登记：
   * 从群卡片进来的人不必手输邀请码，与商家的群白名单对上就自动建号。
   *
   * @param {object} options 页面 onLoad 的 options（含 shareTicket）
   * @return {Promise<object>} 最新的用户状态
   */
  bootstrap(options) {
    return this.ensureLogin()
      .then((me) => {
        if (me && !me.registered) {
          // 明确知道没登记（没有令牌）：试试这次是不是从群卡片进来的
          return this.tryJoinByGroup(options, me);
        }
        // me 为空说明有令牌但内存里还没状态，照旧拉一次
        return this.refreshMe().catch((err) => {
          if (err && err.code === 40101) {
            setToken('');
            return this.ensureLogin().then((fresh) =>
              fresh && fresh.registered ? this.refreshMe() : this.tryJoinByGroup(options, fresh)
            );
          }
          throw err;
        });
      })
      .then((me) => (me && me.accessGranted ? me : this.tryBindGroup(options, me)));
  },

  /**
   * 尚未登记时，用群卡片带的加密数据直接登记（POST /auth/join）。
   *
   * 失败不弹错：群卡片可能被转发到别的群，也可能本来就拿不到密文，
   * 这两种情况都该安静地落到引导页走邀请码。
   */
  tryJoinByGroup(options, currentMe) {
    return getGroupEncrypted(options).then((encrypted) => {
      if (!encrypted) {
        return currentMe;
      }
      return this.joinRequest({
        encryptedData: encrypted.encryptedData,
        iv: encrypted.iv,
      }).catch(() => currentMe);
    });
  },

  /**
   * 用群卡片带的加密数据绑定 openGId。
   *
   * 失败不弹错：这里是「自动尝试」，拿不到群标识的用户会落到引导页走邀请码，
   * 在这条路径上弹红字只会让用户困惑。
   */
  tryBindGroup(options, currentMe) {
    if (!currentMe || !currentMe.registered) {
      // 未登记的号没有令牌，调不到 /auth/bind-group，该走的是 join
      return Promise.resolve(currentMe);
    }
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
