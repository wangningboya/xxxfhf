const app = getApp();
const { request } = require('../../utils/request');
const { yuan, dateStr, addDays } = require('../../utils/format');
const dict = require('../../utils/dict');

/**
 * 对账视图（F-G07 之外的第四块运营视图）。
 *
 * 手机端只看数字与下钻明细，导出 Excel 只在网页后台（prep.export）。
 * 口径由服务端算，前端不做任何加减，避免两边算出两个数。
 */
const BUCKETS = [
  { key: 'receivable', label: '应收', hint: '全部有效订单' },
  { key: 'paid', label: '已收', hint: '已确认收款' },
  { key: 'unpaid', label: '未收', hint: '顾客还没点「我已付款」' },
  { key: 'claimed', label: '待确认', hint: '顾客说已付款，待核对' },
  { key: 'refunding', label: '待退款', hint: '已取消或拒单，需退钱' },
  { key: 'refunded', label: '已退款', hint: '退款已完成' },
];

Page({
  data: {
    serveDate: '',
    buckets: BUCKETS,
    summary: {},
    orderCount: 0,
    detail: null,
    detailBucket: '',
    detailTitle: '',
    detailLoading: false,
  },

  onLoad() {
    this.setData({ serveDate: addDays(dateStr(), 1) });
  },

  onShow() {
    this.load();
  },

  onPullDownRefresh() {
    this.load(() => wx.stopPullDownRefresh());
  },

  onDateChange(e) {
    this.setData({ serveDate: e.detail.value, detail: null, detailBucket: '' });
    this.load();
  },

  load(done) {
    request({ url: '/admin/finance?serveDate=' + this.data.serveDate })
      .then((summary) => {
        const buckets = BUCKETS.map((bucket) => {
          const bucketData = summary[bucket.key] || { amount: 0, count: 0 };
          return Object.assign({}, bucket, {
            amountText: yuan(bucketData.amount),
            count: bucketData.count,
          });
        });
        this.setData({ summary, buckets, orderCount: summary.orderCount || 0 });
      })
      .catch((err) => app.toastError(err))
      .then(() => {
        if (done) {
          done();
        }
      });
  },

  onBucket(e) {
    const bucket = e.currentTarget.dataset.bucket;
    if (this.data.detailBucket === bucket) {
      this.setData({ detail: null, detailBucket: '' });
      return;
    }
    this.setData({ detailBucket: bucket, detailLoading: true, detail: null });
    const label = (BUCKETS.filter((b) => b.key === bucket)[0] || {}).label || '';
    this.setData({ detailTitle: label + '明细' });
    request({
      url: '/admin/finance/detail?serveDate=' + this.data.serveDate + '&bucket=' + bucket,
    })
      .then((rows) => {
        const detail = (rows || []).map((row) => ({
          orderNo: row.orderNo,
          contactName: row.contactName || '自取顾客',
          deliveryType: dict.label(
            'delivery_type',
            row.deliveryType,
            row.deliveryType === 'delivery' ? '配送' : '自取'
          ),
          statusLabel: row.statusLabel,
          payStatusLabel: row.payStatusLabel,
          totalText: yuan(row.totalAmount),
          items: row.items,
        }));
        this.setData({ detail, detailLoading: false });
      })
      .catch((err) => {
        this.setData({ detailLoading: false });
        app.toastError(err);
      });
  },
});
