const app = getApp();
const { request } = require('../../utils/request');
const { dateStr, addDays, yuan, assetUrl, soldBadge } = require('../../utils/format');
const dict = require('../../utils/dict');
const cartUtil = require('../../utils/cart');
const share = require('../../utils/share');

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
    /** 明日菜单预览：横向滑动卡片，数据直接来自已请求的次日菜单（需求 4.5 / F-D11） */
    previewItems: [],
    /** 明日进行中的订单（待接单 / 已接单 / 待取餐）取最新一笔，没有则为 null 不渲染 */
    orderBar: null,
    /** 购物车份数：车里有货才显示悬浮球（与点餐页同款，需求 4.5 / D-15） */
    cartCount: 0,
  },

  onLoad(options) {
    this.shareOptions = options || {};
  },

  onShow() {
    // 每次显示都要重开：分享菜单不跨页面状态，从别的页返回后不调会退回默认（不带 shareTicket）
    share.enableShareMenu();
    if (this.loadingNow) {
      return;
    }
    this.loadingNow = true;
    // 热启动从群卡片进来时 onLoad 不会重跑，用 App.onShow 接住的那份参数覆盖 onLoad 留下的旧参数
    const shareEntry = app.takeShareEntryOptions();
    if (shareEntry) {
      this.shareOptions = shareEntry;
    }
    // 每次显示都重读购物车：从盒饭详情加完货返回首页时正好落在这里，球数才是新的
    this.refreshCartBar();
    this.init();
  },

  /**
   * 转发到群。商家把这张卡片发到服务群，群成员点进来才带得到群信息（群准入的主路径）；
   * 没有这个方法，右上角连「转发」按钮都不会出现。
   */
  onShareAppMessage() {
    return share.buildShareCard(this.data.shopName, '/pages/home/index');
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
          this.applyMenuPreview(menu);
        }
      })
      .catch((err) => {
        app.toastError(err);
      })
      .then(() => {
        this.setData({ loading: false });
        this.loadingNow = false;
      });

    // 消息与订单状态条并行：它们慢或失败都不该拖住「开始点餐」按钮
    this.loadMessages();
    this.loadOrderBar();
  },

  /**
   * 明日菜单预览：与点餐页完全同口径地渲染次日菜单（封面 / 价格 / 剩余份数）。
   * 数据就是上面那次 /menu 请求的返回，不再单独发请求。
   */
  applyMenuPreview(menu) {
    // 首页是「先看盒饭」的入口：盒饭（bento）永远排在前面，自制菜等其它分类跟在后面。
    // 用分组再拼接而不是 sort 比较器 —— 组内要保持服务端给的 sort 顺序，靠比较器得额外记下标
    const bento = [];
    const others = [];
    (menu.items || []).forEach((item) => {
      (item.category === 'bento' ? bento : others).push(item);
    });

    const previewItems = bento.concat(others).map((item) => {
      // 口径与点餐页一致：limitQty 为 null / undefined 表示不限量，只报已订份数、不画进度条
      const limit = item.limitQty === null || item.limitQty === undefined ? null : Number(item.limitQty);
      const sold = Number(item.soldQty || 0);
      const unlimited = limit === null;
      return {
        comboId: item.comboId,
        name: item.name,
        coverUrl: assetUrl(item.cover),
        priceText: yuan(item.price),
        stockText: unlimited ? '已订 ' + sold + ' 份 / 不限量' : '已订 ' + sold + ' / 共 ' + limit,
        stockPercent: unlimited ? 0 : (limit > 0 ? Math.min(100, Math.floor((sold / limit) * 100)) : 100),
        unlimited,
        soldOut: !unlimited && sold >= limit,
        monthSoldText: soldBadge(item.monthSoldQty),
      };
    });
    this.setData({ previewItems });
  },

  /**
   * 订单状态条：明天那笔还在进行中的单（待接单 / 已接单 / 待取餐）。
   *
   * 复用订单列表的 /order/mine 取第一页再前端过滤，不给后端加接口；
   * 多笔命中取列表里最新的一条；失败静默 —— 首页的第一件事是点餐，
   * 订单接口挂了不该顶一个红色提示出来。
   */
  loadOrderBar() {
    const serveDate = this.data.serveDate;
    request({ url: '/order/mine?page=1&pageSize=20' })
      .then((result) => {
        const ACTIVE = ['pending_accept', 'accepted', 'ready'];
        const hit = (result.list || []).find(
          (o) => o.serveDate === serveDate && ACTIVE.indexOf(o.status) !== -1
        );
        if (!hit) {
          this.setData({ orderBar: null });
          return;
        }
        this.setData({
          orderBar: {
            orderId: hit.orderId,
            statusLabel: hit.statusLabel || dict.label('order_status', hit.status),
            // 配色优先用服务端下发的 statusTag，本地字典没加载时也能正确上色
            tagClass: 'tag-' + (hit.statusTag || dict.tagType('order_status', hit.status)),
            qty: (hit.items || []).reduce((s, it) => s + (it.qty || 0), 0),
            summary: (hit.items || []).map((it) => it.name + '×' + it.qty).join('、'),
          },
        });
      })
      .catch(() => {});
  },

  onOpenOrder() {
    if (!this.data.orderBar) {
      return;
    }
    wx.navigateTo({ url: '/pages/order/detail?orderId=' + this.data.orderBar.orderId });
  },

  /** 点预览卡片进盒饭详情，与点餐页行为一致（需求 4.5） */
  onOpenCombo(e) {
    const comboId = e.currentTarget.dataset.id;
    if (!comboId) {
      return;
    }
    wx.navigateTo({
      url: '/pages/combo/detail?comboId=' + comboId + '&serveDate=' + this.data.serveDate,
    });
  },

  /**
   * 悬浮购物车球：口径与点餐页完全一致 —— 先作废跨 0 点的过期车，再读份数。
   * 车里有货才渲染，空车不占位置（需求 4.5 / D-15）。
   */
  refreshCartBar() {
    cartUtil.expireIfStale();
    const cart = cartUtil.load();
    this.setData({ cartCount: cartUtil.count(cart) });
  },

  onOpenCart() {
    if (!this.data.cartCount) {
      wx.showToast({ title: '还没有选餐', icon: 'none' });
      return;
    }
    wx.navigateTo({ url: '/pages/cart/index' });
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
