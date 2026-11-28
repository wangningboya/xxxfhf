const app = getApp();
const { request } = require('../../utils/request');
const { yuan, assetUrl } = require('../../utils/format');
const cartUtil = require('../../utils/cart');

Page({
  data: {
    serveDate: '',
    lines: [],
    count: 0,
    amountText: '0.00',
    /** 该菜单已截单：车内餐品失效，不能再结算（需求 4.6） */
    cutoff: false,
  },

  onShow() {
    // 一单总份数上限来自 /config（后台「系统配置 → 业务限制」）。冷启动直接进本页
    // （底栏 tab 点进来）时 globalData 里还是空的，不等它就会按 20 兜底；
    // 缓存未过期时 loadConfig 立即返回，不会多等一次网络。
    app.loadConfig().then(() => this.refresh());
  },

  refresh() {
    const expired = cartUtil.expireIfStale();
    const cart = cartUtil.load();
    const lines = cart.items.map((item) => {
      const specDelta = (item.specDeltas || []).reduce((s, v) => s + Number(v || 0), 0);
      const unit = Number(item.price || 0) + specDelta;
      return {
        key: cartUtil.lineKey(item.comboId, item.optionIds),
        comboId: item.comboId,
        name: item.name,
        cover: item.cover,
        coverUrl: assetUrl(item.cover),
        specText: item.specText,
        qty: item.qty,
        unitText: yuan(unit),
        lineText: yuan(unit * item.qty),
      };
    });
    this.setData({
      serveDate: cart.serveDate,
      lines,
      count: cartUtil.count(cart),
      amountText: yuan(cartUtil.goodsAmount(cart)),
    });
    if (expired) {
      wx.showToast({ title: '购物车已过期，请重新选择', icon: 'none' });
    }
    this.checkCutoff();
  },

  /**
   * 截单检测：过了截单点车内餐品自动失效（判定以服务端为准，这里只做提示）。
   *
   * 无参请求 /menu —— 服务端按自己的「明天」返回。顺带复核车里的日期：
   * 对不上说明已经跨天，这车不可能下单了，当场清掉。
   */
  checkCutoff() {
    if (!this.data.serveDate || !this.data.lines.length) {
      if (this.data.cutoff) {
        this.setData({ cutoff: false });
      }
      return;
    }
    request({ url: '/menu' })
      .then((menu) => {
        if (menu.serveDate !== this.data.serveDate) {
          cartUtil.clear();
          this.refresh();
          wx.showToast({ title: '购物车已过期，请重新选择', icon: 'none' });
          return;
        }
        this.setData({ cutoff: !!menu.cutoff });
      })
      .catch(() => {
        // 拉不到就不提示，提交时服务端仍会拦
      });
  },

  onMinus(e) {
    const key = e.currentTarget.dataset.key;
    const line = this.data.lines.find((l) => l.key === key);
    if (!line) {
      return;
    }
    const cart = cartUtil.load();
    cartUtil.save(cartUtil.setQty(cart, key, line.qty - 1));
    this.refresh();
  },

  /**
   * 一单总份数上限：后台「系统配置 → 业务限制」下发，缺失时按 20 兜底。
   *
   * 前端拦只是为了少一次「点了提交才被打回」，判据始终在服务端。
   */
  totalLimit() {
    return Number((app.globalData.config || {}).orderMaxQtyTotal) || 20;
  },

  onPlus(e) {
    const key = e.currentTarget.dataset.key;
    const line = this.data.lines.find((l) => l.key === key);
    if (!line) {
      return;
    }
    const cart = cartUtil.load();
    const limit = this.totalLimit();
    if (cartUtil.count(cart) >= limit) {
      wx.showToast({ title: '一单最多 ' + limit + ' 份', icon: 'none' });
      return;
    }
    // 单个盒饭合计：同一盒饭换规格是两行，两份各加也能凑出 2N，这里按盒饭汇总判
    const perItem = Number((app.globalData.config || {}).orderMaxQtyPerItem) || 0;
    if (perItem > 0 && cartUtil.qtyOfCombo(cart, line.comboId) >= perItem) {
      wx.showToast({ title: '单个盒饭最多 ' + perItem + ' 份', icon: 'none' });
      return;
    }
    cartUtil.save(cartUtil.setQty(cart, key, line.qty + 1));
    this.refresh();
  },

  onRemove(e) {
    const key = e.currentTarget.dataset.key;
    wx.showModal({
      title: '删掉这一项？',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        const cart = cartUtil.load();
        cartUtil.save(cartUtil.setQty(cart, key, 0));
        this.refresh();
      },
    });
  },

  onClear() {
    wx.showModal({
      title: '清空购物车？',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        cartUtil.clear();
        this.refresh();
      },
    });
  },

  onBackToMenu() {
    wx.navigateBack();
  },

  onCheckout() {
    if (!this.data.count) {
      wx.showToast({ title: '购物车是空的', icon: 'none' });
      return;
    }
    // 待审核用户：车里可能有拦截之前加的东西，走到结算前就要说清
    if (app.blockIfNotApproved('账号审核中，通过后才能下单')) {
      return;
    }
    if (this.data.cutoff) {
      wx.showToast({ title: '已截单，请重新选择', icon: 'none' });
      return;
    }
    // 结算前再核一次总份数：商家可能刚把上限调小，车里还是按旧上限攒的
    const limit = this.totalLimit();
    if (this.data.count > limit) {
      wx.showToast({ title: '一单最多 ' + limit + ' 份，请减少份数', icon: 'none' });
      return;
    }
    wx.navigateTo({ url: '/pages/order/confirm' });
  },
});
