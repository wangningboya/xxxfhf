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
    this.refresh();
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

  onPlus(e) {
    const key = e.currentTarget.dataset.key;
    const line = this.data.lines.find((l) => l.key === key);
    if (!line) {
      return;
    }
    const cart = cartUtil.load();
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
    wx.navigateTo({ url: '/pages/order/confirm' });
  },
});
