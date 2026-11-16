const { yuan } = require('../../utils/format');
const cartUtil = require('../../utils/cart');

Page({
  data: {
    serveDate: '',
    lines: [],
    count: 0,
    amountText: '0.00',
  },

  onShow() {
    this.refresh();
  },

  refresh() {
    const cart = cartUtil.load();
    const lines = cart.items.map((item) => {
      const specDelta = (item.specDeltas || []).reduce((s, v) => s + Number(v || 0), 0);
      const unit = Number(item.price || 0) + specDelta;
      return {
        key: cartUtil.lineKey(item.comboId, item.optionIds),
        comboId: item.comboId,
        name: item.name,
        cover: item.cover,
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
    wx.navigateTo({ url: '/pages/order/confirm' });
  },
});
