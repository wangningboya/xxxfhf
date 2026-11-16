const app = getApp();
const { request } = require('../../utils/request');
const { yuan } = require('../../utils/format');

/** 状态页签。不做「进行中」这种合并状态：服务端按单状态分页，合并会让分页数不准。 */
const TABS = [
  { key: '', label: '全部' },
  { key: 'pending_accept', label: '待接单' },
  { key: 'accepted', label: '已接单' },
  { key: 'making', label: '制作中' },
  { key: 'ready', label: '待取餐' },
  { key: 'completed', label: '已完成' },
  { key: 'rejected', label: '已拒单' },
  { key: 'cancelled', label: '已取消' },
];

Page({
  data: {
    tabs: TABS,
    status: '',
    list: [],
    page: 1,
    pageSize: 10,
    total: 0,
    loading: false,
    finished: false,
  },

  onShow() {
    // 从详情页回来要看到最新状态（比如刚取消过）
    this.reload();
  },

  onReachBottom() {
    if (this.data.finished || this.data.loading) {
      return;
    }
    this.load(this.data.page + 1);
  },

  onPullDownRefresh() {
    this.reload(() => wx.stopPullDownRefresh());
  },

  onSwitchTab(e) {
    const status = e.currentTarget.dataset.key || '';
    if (status === this.data.status) {
      return;
    }
    this.setData({ status });
    this.reload();
  },

  reload(done) {
    this.setData({ list: [], page: 1, finished: false, total: 0 });
    this.load(1).then(() => {
      if (done) {
        done();
      }
    });
  },

  load(page) {
    if (this.data.loading) {
      return Promise.resolve();
    }
    this.setData({ loading: true });
    const query = ['page=' + page, 'pageSize=' + this.data.pageSize];
    if (this.data.status) {
      query.push('status=' + this.data.status);
    }
    return request({ url: '/order/mine?' + query.join('&') })
      .then((result) => {
        const rows = (result.list || []).map((order) => ({
          orderId: order.orderId,
          orderNo: order.orderNo,
          serveDate: order.serveDate,
          status: order.status,
          statusLabel: order.statusLabel,
          totalText: yuan(order.totalAmount),
          qty: (order.items || []).reduce((s, it) => s + (it.qty || 0), 0),
          summary: (order.items || []).map((it) => it.name + '×' + it.qty).join('、'),
          pickupCode: order.pickupCode,
          rejectReason: order.rejectReason,
          cancelRequested: order.cancelRequested,
        }));
        this.setData({
          list: page === 1 ? rows : this.data.list.concat(rows),
          page,
          total: result.total || 0,
          finished: rows.length < this.data.pageSize,
        });
      })
      .catch((err) => {
        app.toastError(err);
      })
      .then(() => {
        this.setData({ loading: false });
      });
  },

  onOpen(e) {
    wx.navigateTo({ url: '/pages/order/detail?orderId=' + e.currentTarget.dataset.id });
  },
});
