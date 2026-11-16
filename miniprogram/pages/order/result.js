const app = getApp();
const { request } = require('../../utils/request');
const { yuan } = require('../../utils/format');

Page({
  data: {
    orderId: '',
    order: null,
    totalText: '0.00',
    payQrCode: '',
    claiming: false,
    claimed: false,
  },

  onLoad(options) {
    this.setData({ orderId: options.orderId || '' });
  },

  onShow() {
    this.load();
  },

  load() {
    if (!this.data.orderId) {
      return;
    }
    app.loadConfig().then((config) => {
      this.setData({ payQrCode: config.payQrCode || '' });
    });
    request({ url: '/order/detail?orderId=' + this.data.orderId })
      .then((order) => {
        this.setData({
          order,
          totalText: yuan(order.totalAmount),
          claimed: order.payStatus === 'claimed' || order.payStatus === 'paid',
        });
      })
      .catch((err) => {
        app.toastError(err);
      });
  },

  onPreviewQr() {
    if (!this.data.payQrCode) {
      return;
    }
    wx.previewImage({ urls: [this.data.payQrCode] });
  },

  /**
   * 我已完成转账。
   *
   * 只是「声称」，不是「已收款」—— 钱到没到只有商家看得到，
   * 所以文案要写清楚「等商家核对」，不能让用户以为已经付成功了。
   */
  onClaimPaid() {
    if (this.data.claiming || this.data.claimed) {
      return;
    }
    this.setData({ claiming: true });
    request({ url: '/order/claim-paid', method: 'POST', data: { orderId: this.data.orderId } })
      .then((order) => {
        this.setData({ order, claimed: true });
        wx.showToast({ title: '已告诉商家', icon: 'success' });
      })
      .catch((err) => {
        app.toastError(err);
      })
      .then(() => {
        this.setData({ claiming: false });
      });
  },

  onOpenOrder() {
    wx.redirectTo({ url: '/pages/order/detail?orderId=' + this.data.orderId });
  },

  onBackToMenu() {
    wx.reLaunch({ url: '/pages/index/index' });
  },
});
