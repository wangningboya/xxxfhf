const app = getApp();
const { request } = require('../../utils/request');
const { yuan } = require('../../utils/format');
const payMethod = require('../../utils/pay-method');

/**
 * 催收清单（v3.50 / 需求 4.21 / `F-F07`）：餐已经给了、钱还没到的单。
 *
 * 与「对账视图」分开：对账是某一天的账，催收是跨天的活。混在一起，
 * 六个数会被这张跨供餐日的清单带得对不上，两头都不好用。
 *
 * 口径由服务端定：`ready/completed` + `unpaid/claimed`，不限供餐日、按供餐日升序。
 * 前端只负责算「逾期几天」与发起确认收款，不做任何金额加减。
 */
Page({
  data: {
    list: [],
    total: 0,
    page: 1,
    pageSize: 20,
    loading: true,
    hasMore: true,
  },

  onShow() {
    this.reload();
  },

  onPullDownRefresh() {
    this.reload(() => wx.stopPullDownRefresh());
  },

  onReachBottom() {
    if (!this.data.hasMore || this.data.loading) {
      return;
    }
    this.load(this.data.page + 1);
  },

  reload(done) {
    this.setData({ page: 1, hasMore: true });
    this.load(1, done);
  },

  load(page, done) {
    this.setData({ loading: true });
    const url = '/admin/order/collect?page=' + page + '&pageSize=' + this.data.pageSize;
    request({ url })
      .then((result) => {
        const rows = ((result && result.list) || []).map((row) => {
          const order = row.order || {};
          const days = this.overdueDays(order.serveDate);
          return Object.assign({}, row, {
            order,
            amountText: yuan(order.totalAmount),
            overdueText: days === 0 ? '当天' : days + ' 天',
            overdueClass: days >= 3 ? 'late-red' : days >= 1 ? 'late-warn' : 'late-ok',
            // 用户在「我已完成付款」里申报过方式；未申报（现金单）为空
            claimed: order.payStatus === 'claimed',
          });
        });
        const list = page === 1 ? rows : this.data.list.concat(rows);
        this.setData({
          list,
          page,
          total: (result && result.total) || 0,
          hasMore: list.length < ((result && result.total) || 0),
        });
      })
      .catch((err) => app.toastError(err))
      .then(() => {
        this.setData({ loading: false });
        if (done) {
          done();
        }
      });
  },

  /** 逾期天数：供餐日到今天，当天算 0 天。用日期串比较，避免时区把「今天」算成昨天 */
  overdueDays(serveDate) {
    if (!serveDate) {
      return 0;
    }
    const one = 24 * 60 * 60 * 1000;
    const a = new Date(serveDate + 'T00:00:00');
    const b = new Date();
    b.setHours(0, 0, 0, 0);
    return Math.max(0, Math.round((b.getTime() - a.getTime()) / one));
  },

  onOpen(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: '/pages/merchant/order-detail?id=' + id });
  },

  /**
   * 行内确认收款。
   *
   * 必须再选一次方式（需求 4.10）：现金单顾客不会点「我已完成付款」，
   * 这里不补选，这单就永远没有方式值，事后对账无法判断钱走的哪条路。
   */
  onConfirmPay(e) {
    const id = e.currentTarget.dataset.id;
    const row = this.data.list.filter((item) => item.order.orderId === id)[0] || {};
    const order = row.order || {};
    payMethod
      .choose(order.payMethod || '')
      .then((method) => {
        request({
          url: '/admin/order/payment',
          method: 'POST',
          data: { orderId: id, action: 'confirm', payMethod: method },
        })
          .then(() => {
            wx.showToast({ title: '已确认收款', icon: 'success' });
            this.reload();
          })
          .catch((err) => app.toastError(err));
      })
      .catch((err) => {
        // 选择弹窗里点取消不提示错误
        if (!err || !err.cancelled) {
          app.toastError(err);
        }
      });
  },
});
