const { request, setToken, getToken, toastError } = require('./utils/request');
const { getGroupEncrypted } = require('./utils/group-entry');
const dict = require('./utils/dict');
const privacy = require('./utils/privacy');
// 只读纯 storage 的链路状态，不含蓝牙 SDK
const linkState = require('./utils/print/link-state');

/** 微信登录拿不到 code 时的统一文案：两种情况对用户是同一件事 */
const LOGIN_FAILED = '微信登录失败，请重新进入小程序';

/**
 * 店铺配置的本地缓存时长。
 *
 * 配置以前是「成功一次就永不重拉」，后台改了份数上限/配送费，小程序不杀进程就一直按旧值算。
 * 60 秒是折中：商家改完最多等一分钟，顾客端也不必每进一个页面就打一次 /config。
 */
const CONFIG_TTL_MS = 60 * 1000;

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

/**
 * 群链路观测：打印本次进入的群相关参数（v3.53）。
 *
 * 为什么不复用 group-entry.js 里那行「进入参数」：那行只在真正去取群密文时才打，
 * 而老号（已登记且已准入）在 bootstrap 末尾是直接返回的，取密文那步根本走不到。
 * 群里有几百个已登记用户，他们才是「从群卡片点进来」的主要人群 ——
 * 日志挂在取密文的分支上，等于把这批人全漏掉。
 *
 * 所以提到 bootstrap 入口无条件打：只要打开小程序就有一行，与登录状态、审核状态无关。
 * shareTicket 只打长度不打内容，与 group-entry.js 的约定一致。
 *
 * @param {object} options 页面 onLoad 的 options（含 scene / shareTicket）
 */
function logEnter(options) {
  const opts = options || {};
  const scene = opts.scene === undefined ? '-' : opts.scene;
  const ticket = opts.shareTicket ? '有(长度 ' + opts.shareTicket.length + ')' : '无';
  // 与 group-entry.js 的「进入参数」刻意区分文案：那两个在群卡片建号/绑定时会各打一次，
  // 文案一样的话，看日志分不清是页面进入时打的还是取密文时打的
  console.log('[group] 进入小程序 scene=' + scene + ' shareTicket=' + ticket);
}

App({
  globalData: {
    /** 当前用户的准入与审核状态，结构同服务端 /auth/me */
    me: null,
    /** 店铺配置：名称、公告、截单时间等 */
    config: {},
    configLoaded: false,
    /** 上次成功拉到配置的时刻，配合 CONFIG_TTL_MS 判过期 */
    configLoadedAt: 0,
    /** 登录中的 Promise，避免多个页面同时触发重复 wx.login */
    loginPromise: null,
    /**
     * 热启动时 App.onShow 接住的分享进入参数（含 scene / shareTicket）。
     * 只放「确实带 shareTicket 的那一次」，页面 onShow 用 takeShareEntryOptions() 取走，取完即清。
     */
    shareEntryOptions: null,
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
    this.registerPrivacyListener();
    // 冷启动时蓝牙连接一定已经断了（进程被杀），把展示用的「已连接」复位：
    // 不复位的话工作台会拿 10 分钟内的旧状态显示绿灯，点打印才发现打不出来
    linkState.write({ connected: false, reconnecting: 0, lost: false });
    // 每天首次冷启动清一次过期预览截图：小程序没有常驻定时器，只能惰性清理（见需求 14.7）
    try {
      require('./utils/print/preview').cleanDaily();
    } catch (e) {
      console.warn('[打印] 预览清理失败', e);
    }
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
   * 热启动时接住分享卡片的参数（v3.53）。
   *
   * 冷启动从群卡片进来：App.onShow → Page.onLoad(options) → Page.onShow，页面在 onLoad 里就拿到了参数。
   * 热启动（小程序还在后台，用户先逛过小程序、再回群里点卡片）：首页与点餐页都是 tabBar 页，
   * 微信用 switchTab 回到已有页面实例，onLoad 不重跑、Page.onShow 又不带参数 ——
   * 到页面这一层时参数已经没有了，只剩这里一份。
   *
   * 只认带 shareTicket 的调用：普通「从后台切回前台」也会触发 onShow，但那不是一次新的分享进入；
   * 把它当成新参数，页面会拿着一份过期 shareTicket 反复去换群标识。
   */
  onShow(options) {
    const opts = options || {};
    if (!opts.shareTicket) {
      return;
    }
    this.globalData.shareEntryOptions = opts;
    console.log('[group] App.onShow 接住分享进入 scene='
      + (opts.scene === undefined ? '-' : opts.scene)
      + ' shareTicket=有(长度 ' + opts.shareTicket.length + ')');
  },

  /**
   * 取走 App.onShow 接住的分享参数，取完即清。
   *
   * 页面在 onShow 里调：拿到说明这次是热启动从分享卡片进来的，要用它覆盖 onLoad 留下的旧参数；
   * 拿到 null 就保持原样。清空是为了让同一份参数只被消费一次 ——
   * 否则用户在几个 tab 之间来回切，每次 onShow 都会拿同一份旧密文再试一遍绑定。
   *
   * @return {object|null} 本次新的分享进入参数
   */
  takeShareEntryOptions() {
    const opts = this.globalData.shareEntryOptions;
    this.globalData.shareEntryOptions = null;
    return opts;
  },

  /**
   * 注册微信侧隐私授权监听（整改 P0-06）。
   *
   * 微信的授权状态和本地的 `meal_privacy_consent` 是两套东西：
   * 只存本地 → 用户点了「同意」，微信仍然认为没同意，
   * 于是 `type="nickname"` 拿不到昵称、`wx.chooseMedia` 直接 fail。
   *
   * 全局只能注册一次，且必须在 onLaunch 里 —— 注册得晚，用户这一下点下去就没接住了。
   * 回调里不自己弹 UI，统一交给 utils/privacy.js（它知道当前哪个页面的弹窗还活着）。
   */
  registerPrivacyListener() {
    if (typeof wx.onNeedPrivacyAuthorization !== 'function') {
      // 老版本基础库没有这个接口：微信会走自己的默认交互，不算致命
      console.warn('[隐私] 当前基础库不支持 onNeedPrivacyAuthorization');
      return;
    }
    wx.onNeedPrivacyAuthorization((resolve) => {
      privacy.onWechatAuthNeeded(resolve);
    });
    this.logPrivacyState();
  },

  /**
   * 开发版/体验版启动时打印一次微信侧的隐私配置（正式版不打，避免污染日志）。
   *
   * 用途：判断「弹窗没出现」到底是哪一种情况——
   * - privacyContractName 为空：后台隐私指引没生效，微信不会发起任何授权，怎么点都不会弹；
   * - needAuthorization=false 且合同名有值：用户已授权过，或该接口本来不设门槛；
   * - needAuthorization=true：微信确实要授权，此时点相册/位置类接口应能触发弹窗。
   */
  logPrivacyState() {
    if (typeof wx.getPrivacySetting !== 'function') {
      return;
    }
    let envVersion = 'unknown';
    try {
      const info = wx.getAccountInfoSync();
      envVersion = (info && info.miniProgram && info.miniProgram.envVersion) || 'unknown';
    } catch (e) {
      // 拿不到版本就当未知，继续打日志：宁可多打一行，也别把排查信息吞掉
    }
    if (envVersion !== 'develop' && envVersion !== 'trial') {
      return;
    }
    wx.getPrivacySetting({
      success: (res) => {
        console.info('[隐私诊断] 微信侧配置', {
          是否还需授权: res.needAuthorization,
          隐私合同名: res.privacyContractName || '(空)',
          环境: envVersion,
        });
      },
      fail: (err) => console.warn('[隐私诊断] getPrivacySetting 调用失败', err),
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
   * 进入首页的统一前置：登录 → 刷新状态 → 未登记时试群卡片建号。
   *
   * 令牌 7 天到期是必然发生的事，所以这里遇到 401 会清掉本地令牌重新 wx.login 一次，
   * 而不是把「登录已过期」甩给用户让他自己猜怎么办。
   *
   * 未登记的号没有令牌，刷新状态只会 401，所以直接去试群卡片登记：
   * 从群卡片进来的人不必手输邀请码，与商家的群白名单对上就自动建号。
   *
   * 准入只有一个入口（/auth/join）：已登记就必然已准入，没有「补绑群」这一步可做。
   *
   * @param {object} options 页面 onLoad 的 options（含 shareTicket）
   * @return {Promise<object>} 最新的用户状态
   */
  bootstrap(options) {
    // 参数日志必须在入口、与身份无关：不在这里打就没有任何 [group] 输出
    logEnter(options);
    return this.ensureLogin().then((me) => {
      if (me && !me.registered) {
        // 明确知道没登记（没有令牌）：试试这次是不是从群卡片进来的
        console.log('[group] 身份判定：未登记 → 走群卡片建号');
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
    });
  },

  /**
   * 尚未登记时，用群卡片带的加密数据直接登记（POST /auth/join）。
   *
   * 失败不弹错：群卡片可能被转发到别的群，也可能本来就拿不到密文，
   * 这两种情况都该安静地落到引导页走邀请码。
   */
  tryJoinByGroup(options, currentMe) {
    console.log('[group] 尚未登记，尝试用群卡片直接建号');
    return getGroupEncrypted(options).then((encrypted) => {
      if (!encrypted) {
        console.log('[group] 这次没有群密文，跳过群卡片建号');
        return currentMe;
      }
      return this.joinRequest({
        encryptedData: encrypted.encryptedData,
        iv: encrypted.iv,
      })
        .then((me) => {
          console.log('[group] 群卡片建号成功');
          return me;
        })
        .catch((err) => {
          // 群不在白名单、白名单未配置、密文解不开都到这里；静默放行到邀请码，
          // 但必须留日志：服务端那条 WARN 带 openGId，客户端这条带错误码，两头对得上才查得动
          console.warn('[group] 群卡片建号被拒', err && err.code, err && err.message);
          return currentMe;
        });
    });
  },

  /* 已登记的用户不需要「补绑群」：有账号就意味着已准入，/auth/bind-group 已随
     /auth/invite 一起删除（两者的前置状态都是「已登记但未准入」，而建号必然带准入）。 */

  /** 加载店铺配置：60 秒内复用，超过就重拉。 */
  loadConfig() {
    const fresh = this.globalData.configLoaded
      && Date.now() - this.globalData.configLoadedAt < CONFIG_TTL_MS;
    if (fresh) {
      return Promise.resolve(this.globalData.config);
    }
    return request({ url: '/config' })
      .then((data) => {
        this.globalData.config = data || {};
        this.globalData.configLoaded = true;
        this.globalData.configLoadedAt = Date.now();
        return this.globalData.config;
      })
      .catch(() => this.globalData.config);
  },

  toastError,
});
