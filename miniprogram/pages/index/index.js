const app = getApp();
const { request } = require('../../utils/request');
const { yuan, dateStr, addDays, dayLabel, assetUrl } = require('../../utils/format');
const cartUtil = require('../../utils/cart');

Page({
  data: {
    loading: true,
    shopName: '本店',
    shopNotice: '',
    serveTimeText: '',
    cutoffTime: '',
    /** pending / approved / rejected */
    auditStatus: 'pending',
    rejectReason: '',
    auditTitle: '',
    auditDesc: '',
    showAuditCard: false,
    reapplyLoading: false,
    /** 还没填昵称时弹填写浮层：商家审核列表靠昵称认人 */
    needNickName: false,
    nickNameInput: '',
    savingNickName: false,

    /* ---------------- 菜单 ---------------- */
    /** 两个供餐日：明天可下单、后天只读（需求 4.5） */
    dates: [],
    activeDate: '',
    menu: { orderable: false, cutoff: false, closed: false, items: [] },
    menuLoading: false,
    /** 菜单项展示用（价格已转成元，避免 wxml 里做除法） */
    viewItems: [],
    /** 第三个 Tab「全部菜单」：盒饭库全量，只浏览不直接加购 */
    allActive: false,
    allItems: [],
    allLoading: false,
    cartCount: 0,
  },

  onLoad(options) {
    // 群卡片的 shareTicket 在 onLoad 的 options 里，留给 bootstrap 尝试绑定群
    this.shareOptions = options || {};
  },

  onShow() {
    this.refreshCartBar();
    if (this.loadingNow) {
      return;
    }
    this.loadingNow = true;
    this.init();
  },

  init() {
    const today = dateStr();
    const tomorrow = addDays(today, 1);
    const dayAfter = addDays(today, 2);
    // 后天 Tab 仅在商家已发布后天菜单时出现（需求 4.5）：
    // 先只放明天，探到后天有菜单再追加，没发布就不占位不置灰
    this.setData({
      dates: [{ date: tomorrow, label: dayLabel(tomorrow, today, tomorrow) }],
    });

    app
      .bootstrap(this.shareOptions)
      .then((me) => {
        if (!me || !me.accessGranted) {
          // 不是本店群里的人：直接进引导页，首页不渲染内容
          wx.reLaunch({ url: '/pages/no-access/index' });
          return null;
        }
        this.applyMe(me);
        return app.loadConfig();
      })
      .then((config) => {
        if (config) {
          this.applyConfig(config);
        }
        const active = this.data.activeDate || (this.data.dates[0] && this.data.dates[0].date) || '';
        this.setData({ loading: false, activeDate: active });
        this.loadMenu(active);
        this.probeDayAfter(dayAfter, today, tomorrow);
      })
      .catch((err) => {
        this.setData({ loading: false });
        app.toastError(err);
      })
      .then(() => {
        this.loadingNow = false;
      });
  },

  /** 探测后天菜单是否已发布，是才追加「后天」Tab（不占位不置灰，需求 4.5） */
  probeDayAfter(dayAfter, today, tomorrow) {
    request({ url: '/menu?serveDate=' + dayAfter })
      .then((menu) => {
        if ((menu.items || []).length && this.data.dates.length === 1) {
          this.setData({
            dates: this.data.dates.concat([
              { date: dayAfter, label: dayLabel(dayAfter, today, tomorrow) },
            ]),
          });
        }
      })
      .catch(() => {
        // 后天菜单拉不到（还没排 / 已停业）就不显示这个 Tab，不弹错
      });
  },

  applyMe(me) {
    const status = me.auditStatus || 'pending';
    let title = '';
    let desc = '';

    if (status === 'pending') {
      title = '已提交申请，等待商家审核';
      desc = '审核通过后即可下单。商家审核后回到本页会自动更新。';
    } else if (status === 'rejected') {
      title = '审核未通过';
      desc = me.rejectReason || '请联系群管理员了解原因。';
    }

    this.setData({
      auditStatus: status,
      rejectReason: me.rejectReason || '',
      auditTitle: title,
      auditDesc: desc,
      showAuditCard: status !== 'approved',
      // 微信已不返回真实昵称，只能靠用户自己填一次；没填就弹浮层
      needNickName: !me.nickName,
    });
  },

  applyConfig(config) {
    this.setData({
      shopName: config.shopName || '本店',
      shopNotice: config.shopNotice || '',
      serveTimeText: config.serveTimeText || '',
      cutoffTime: config.cutoffTime || '',
    });
  },

  /* ==================== 菜单 ==================== */

  loadMenu(date) {
    if (!date) {
      return Promise.resolve();
    }
    this.setData({ menuLoading: true });
    return request({ url: '/menu?serveDate=' + date })
      .then((menu) => {
        const viewItems = (menu.items || []).map((item) => ({
          comboId: item.comboId,
          name: item.name,
          cover: item.cover,
          coverUrl: assetUrl(item.cover),
          price: Number(item.price || 0),
          priceText: yuan(item.price),
          dishesText: (item.dishes || []).map((d) => d.name + '×' + d.qty).join('、'),
          hasSpec: (item.specGroups || []).length > 0,
          // 不限量时 remainQty 为 null，文案要区分「不限量」和「还剩 N 份」
          remainText: item.remainQty === null || item.remainQty === undefined
            ? '不限量'
            : (item.remainQty > 0 ? '剩 ' + item.remainQty + ' 份' : '已售完'),
          soldOut: item.remainQty !== null && item.remainQty !== undefined && item.remainQty <= 0,
        }));
        this.setData({ menu, viewItems });
      })
      .catch((err) => {
        app.toastError(err);
      })
      .then(() => {
        this.setData({ menuLoading: false });
      });
  },

  /** 切换供餐日。后天只能看不能买，但仍要能看。 */
  onSwitchDate(e) {
    const date = e.currentTarget.dataset.date;
    if (!date) {
      return;
    }
    // 从「全部菜单」切回来时，即使还是同一个供餐日也要生效
    if (!this.data.allActive && date === this.data.activeDate) {
      return;
    }
    const cart = cartUtil.load();
    if (cart.items.length && cart.serveDate && cart.serveDate !== date) {
      wx.showModal({
        title: '切换供餐日',
        content: '购物车里是另一个供餐日的餐，切换后需要重新选，继续吗？',
        success: (res) => {
          if (!res.confirm) {
            return;
          }
          cartUtil.clear();
          this.refreshCartBar();
          this.setData({ activeDate: date, allActive: false });
          this.loadMenu(date);
        },
      });
      return;
    }
    this.setData({ activeDate: date, allActive: false });
    this.loadMenu(date);
  },

  /** 切到「全部菜单」Tab。全部菜单是浏览入口，点卡片进详情再按供餐日下单。 */
  onSwitchAll() {
    if (this.data.allActive) {
      return;
    }
    this.setData({ allActive: true });
    this.loadAllCombos();
  },

  loadAllCombos() {
    this.setData({ allLoading: true });
    return request({ url: '/combo/all' })
      .then((list) => {
        const allItems = (list || []).map((item) => ({
          comboId: item.comboId,
          name: item.name,
          cover: item.cover,
          coverUrl: assetUrl(item.cover),
          desc: item.desc || '',
          priceText: yuan(item.price),
          dishesText: (item.dishes || []).map((d) => d.name + '×' + d.qty).join('、'),
          tomorrowText: item.tomorrowSupplied ? '明日供应' : '暂不供应',
        }));
        this.setData({ allItems });
      })
      .catch((err) => {
        app.toastError(err);
      })
      .then(() => {
        this.setData({ allLoading: false });
      });
  },

  /** 点卡片进详情：规格只能在那里选 */
  onOpenCombo(e) {
    const comboId = e.currentTarget.dataset.id;
    wx.navigateTo({
      url: '/pages/combo/detail?comboId=' + comboId + '&serveDate=' + this.data.activeDate,
    });
  },

  /**
   * 快速加购。
   *
   * 有规格组的盒饭不允许直接加 —— 规格必须让用户看到并选，否则做出来是错的。
   * 没有规格组的就直接 +1，省一次跳转。
   */
  onQuickAdd(e) {
    const comboId = e.currentTarget.dataset.id;
    const item = this.data.viewItems.find((v) => v.comboId === comboId);
    if (!item) {
      return;
    }
    // 待审核用户只能看，不能选：拦住要在这里，不能等他填完地址、提交订单才说
    if (app.blockIfNotApproved('账号审核中，通过后才能选餐')) {
      return;
    }
    if (!this.data.menu.orderable) {
      wx.showToast({
        title: this.data.menu.closed ? '该供餐日暂停供应' : '当前不可下单',
        icon: 'none',
      });
      return;
    }
    if (item.soldOut) {
      wx.showToast({ title: '已售完', icon: 'none' });
      return;
    }
    if (item.hasSpec) {
      this.onOpenCombo(e);
      return;
    }
    this.addToCart({
      comboId,
      name: item.name,
      cover: item.cover,
      price: item.price,
      qty: 1,
      optionIds: [],
      specDeltas: [],
      specText: '',
    });
  },

  addToCart(line) {
    let cart = cartUtil.load();
    if (cart.serveDate && cart.serveDate !== this.data.activeDate) {
      cart = cartUtil.empty();
    }
    const next = cartUtil.addLine(cart, line);
    next.serveDate = this.data.activeDate;
    cartUtil.save(next);
    this.refreshCartBar();
    wx.showToast({ title: '已加入', icon: 'success', duration: 800 });
  },

  refreshCartBar() {
    const cart = cartUtil.load();
    this.setData({ cartCount: cartUtil.count(cart) });
  },

  /* ==================== 导航 ==================== */

  onOpenCart() {
    if (!this.data.cartCount) {
      wx.showToast({ title: '还没有选餐', icon: 'none' });
      return;
    }
    wx.navigateTo({ url: '/pages/cart/index' });
  },

  onOpenMine() {
    // 「我的」已是 tab 页，navigateTo 跳 tab 页会静默失败
    wx.switchTab({ url: '/pages/mine/index' });
  },

  onOpenOrders() {
    wx.navigateTo({ url: '/pages/order/list' });
  },

  /* ==================== 审核 / 昵称 ==================== */

  /** 被驳回后重新提交申请 */
  onReapply() {
    if (this.data.reapplyLoading) {
      return;
    }
    this.setData({ reapplyLoading: true });

    request({ url: '/auth/reapply', method: 'POST' })
      .then(() => {
        wx.showToast({ title: '已重新提交', icon: 'success' });
        return app.refreshMe();
      })
      .then((me) => {
        this.applyMe(me);
      })
      .catch((err) => {
        app.toastError(err);
      })
      .then(() => {
        this.setData({ reapplyLoading: false });
      });
  },

  /**
   * 昵称输入。
   *
   * `type="nickname"` 的输入框，用户点键盘上方「使用微信昵称」时只保证触发 blur / change，
   * 手打时才有 input，所以三种事件都接同一个处理函数，删掉任何一种都会漏值。
   */
  onNickNameInput(e) {
    this.setData({ nickNameInput: (e.detail && e.detail.value) || '' });
  },

  /** 保存昵称。成功即关浮层（applyMe 会重新判断是否需要填）。 */
  onSaveNickName() {
    if (this.data.savingNickName) {
      return;
    }
    const nickName = (this.data.nickNameInput || '').trim();
    if (!nickName) {
      wx.showToast({ title: '请填写昵称', icon: 'none' });
      return;
    }

    this.setData({ savingNickName: true });
    request({ url: '/auth/nickname', method: 'POST', data: { nickName } })
      .then(() => app.refreshMe())
      .then((me) => {
        this.applyMe(me);
        wx.showToast({ title: '已保存', icon: 'success' });
      })
      .catch((err) => {
        app.toastError(err);
      })
      .then(() => {
        this.setData({ savingNickName: false });
      });
  },
});
