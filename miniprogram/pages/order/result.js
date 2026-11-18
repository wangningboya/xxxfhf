const app = getApp();
const { request } = require('../../utils/request');
const { yuan } = require('../../utils/format');
const payMethod = require('../../utils/pay-method');

Page({
  data: {
    orderId: '',
    order: null,
    totalText: '0.00',
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

  /**
   * 我已完成付款。
   *
   * 只是「声称」，不是「已收款」—— 钱到没到只有商家看得到，
   * 所以文案要写清楚「等商家核对」，不能让用户以为已经付成功了。
   *
   * 先选收款方式，漏选不提交：服务端方式是必填，没带值会 400。
   */
  onClaimPaid() {
    if (this.data.claiming || this.data.claimed) {
      return;
    }
    payMethod
      .choose()
      .then((method) => {
        this.setData({ claiming: true });
        return request({
          url: '/order/claim-paid',
          method: 'POST',
          data: { orderId: this.data.orderId, payMethod: method },
        });
      })
      .then((order) => {
        this.setData({ order, claimed: true });
        wx.showToast({ title: '已告诉商家', icon: 'success' });
      })
      .catch((err) => {
        // 三选一里点取消不算错误
        if (!err || !err.cancelled) {
          app.toastError(err);
        }
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
