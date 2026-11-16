const app = getApp();
const { request } = require('../../utils/request');
const { yuan } = require('../../utils/format');

Page({
  data: {
    orderId: '',
    order: null,
    lines: [],
    totalText: '0.00',
    goodsText: '0.00',
    specText: '0.00',
    feeText: '0.00',
    payQrCode: '',
    busy: false,
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
        const lines = (order.items || []).map((item) => ({
          name: item.name,
          qty: item.qty,
          priceText: yuan(item.price),
          dishesText: (item.dishes || []).map((d) => d.name + '×' + d.qty).join('、'),
          specText: (item.specs || []).map((s) => s.groupName + '：' + s.optionName).join('　'),
        }));
        this.setData({
          order,
          lines,
          totalText: yuan(order.totalAmount),
          goodsText: yuan(order.goodsAmount),
          specText: yuan(order.specAmount),
          feeText: yuan(order.deliveryFee),
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

  onClaimPaid() {
    if (this.data.busy) {
      return;
    }
    this.setData({ busy: true });
    request({ url: '/order/claim-paid', method: 'POST', data: { orderId: this.data.orderId } })
      .then(() => {
        wx.showToast({ title: '已告诉商家', icon: 'success' });
        this.load();
      })
      .catch((err) => {
        app.toastError(err);
      })
      .then(() => {
        this.setData({ busy: false });
      });
  },

  /**
   * 取消。
   *
   * 截单前是「直接取消」，截单后只是「提交申请」—— 文案必须区分，
   * 否则用户以为取消了，商家那边其实还在备菜。
   */
  onCancel() {
    wx.showModal({
      title: '取消订单',
      editable: true,
      placeholderText: '取消原因（可不填）',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        if (this.data.busy) {
          return;
        }
        this.setData({ busy: true });
        request({
          url: '/order/cancel',
          method: 'POST',
          data: { orderId: this.data.orderId, reason: res.content || '' },
        })
          .then((order) => {
            const requested = order.cancelRequested;
            wx.showToast({
              title: requested ? '已提交申请，等商家处理' : '已取消',
              icon: 'none',
            });
            this.load();
          })
          .catch((err) => {
            app.toastError(err);
          })
          .then(() => {
            this.setData({ busy: false });
          });
      },
    });
  },

  onCopyOrderNo() {
    wx.setClipboardData({ data: this.data.order.orderNo });
  },
});
