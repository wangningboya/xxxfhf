const app = getApp();
const { request } = require('../../utils/request');
const { dateStr, addDays } = require('../../utils/format');

Page({
  data: {
    loading: true,
    shopName: '本店',
    shopNotice: '',
    serveTimeText: '',
    cutoffTime: '',
    /** 首页只看次日菜单的营业状态，与点餐页同源（需求 4.5） */
    serveDate: '',
    statusText: '',
    statusWarn: false,
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
});
