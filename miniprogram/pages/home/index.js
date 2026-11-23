const app = getApp();
const { request } = require('../../utils/request');
const { dateStr, addDays } = require('../../utils/format');

Page({
  data: {
    loading: true,
    shopName: '本店',
    shopNotice: '',
    /** 自取点与商家电话：都是可选配置，留空时前端不渲染对应行 */
    shopAddress: '',
    shopPhone: '',
    serveTimeText: '',
    cutoffTime: '',
    /** 首页只看次日菜单的营业状态，与点餐页同源（需求 4.5） */
    serveDate: '',
    statusText: '',
    statusWarn: false,
    /** 消息：首页只显示「发给我本人」的那一侧，与商家工作台各算各的 */
    unreadCount: 0,
    unreadBadge: '',
    noticeItems: [],
  },

  onLoad(options) {
    this.shareOptions = options || {};
  },

  onShow() {
    if (this.loadingNow) {
      return;
    }
    this.loadingNow = true;
    this.init();
  },

  init() {
    const serveDate = addDays(dateStr(), 1);
    this.setData({ serveDate });

    app
      .bootstrap(this.shareOptions)
      .then((me) => {
        if (!me || !me.accessGranted) {
          // 与点餐页一致：不是本店群里的人一律拦到引导页
          wx.reLaunch({ url: '/pages/no-access/index' });
          return null;
        }
        return app.loadConfig();
      })
      .then((config) => {
        if (!config) {
          return null;
        }
        this.setData({
          shopName: config.shopName || '本店',
          shopNotice: config.shopNotice || '',
          shopAddress: config.shopAddress || '',
          shopPhone: config.shopPhone || '',
          serveTimeText: config.serveTimeText || '',
          cutoffTime: config.cutoffTime || '',
        });
        return request({ url: '/menu?serveDate=' + serveDate });
      })
      .then((menu) => {
        if (menu) {
          this.applyMenuStatus(menu);
        }
      })
      .catch((err) => {
        app.toastError(err);
      })
      .then(() => {
        this.setData({ loading: false });
        this.loadingNow = false;
      });

    // 消息与菜单并行：它慢或失败都不该拖住「开始点餐」按钮
    this.loadMessages();
  },

  /**
   * 未读数与最近 3 条未读。
   *
   * 失败静默，不弹错误：首页的第一件事是点餐，消息接口挂了不该顶一个红色提示出来。
   * 未登录时这里必然 401，同样什么都不做 —— 引导用户登录是 bootstrap 的职责。
   */
  loadMessages() {
    request({ url: '/message/summary?side=user' })
      .then((data) => {
        const count = (data && data.unreadCount) || 0;
        this.setData({
          unreadCount: count,
          unreadBadge: count > 99 ? '99+' : String(count),
          noticeItems: (data && data.items) || [],
        });
      })
      .catch(() => {});
  },

  onOpenMessages() {
    wx.navigateTo({ url: '/pages/message/index' });
  },

  /** 营业状态口径与点餐页完全同源，不另算一套 */
  applyMenuStatus(menu) {
    const count = (menu.items || []).length;
    let statusText = '明日可下单';
    let statusWarn = false;

    if (menu.closed) {
      statusText = '明日门店暂停供应';
      statusWarn = true;
    } else if (!count) {
      statusText = '明日暂无供应';
      statusWarn = true;
    } else if (menu.cutoff) {
      statusText = '今日已截单，0 点后可继续下单';
      statusWarn = true;
    } else if (!menu.orderable) {
      statusText = '明日菜单仅供浏览，明天开放下单';
      statusWarn = true;
    }

    this.setData({ statusText, statusWarn });
  },

  /** 下单统一在点餐 Tab，首页不加购 */
  onStartOrder() {
    wx.switchTab({ url: '/pages/index/index' });
  },

  /** 拨打电话：转账要收款账号、地址与取消都要找商家，小程序里必须有这个入口 */
  onCallShop() {
    const phone = this.data.shopPhone;
    if (!phone) {
      return;
    }
    wx.makePhoneCall({
      phoneNumber: phone,
      fail: () => {
        // 用户取消拨号也会走 fail，不打扰
      },
    });
  },

  /** 长按复制取餐点地址，方便粘到导航或发给家人 */
  onCopyAddress() {
    const address = this.data.shopAddress;
    if (!address) {
      return;
    }
    wx.setClipboardData({ data: address });
  },
});
