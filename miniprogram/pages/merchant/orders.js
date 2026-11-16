const app = getApp();
const { request } = require('../../utils/request');
const { yuan, dateStr, addDays } = require('../../utils/format');

const STATUS_TABS = [
  { key: '', label: '全部' },
  { key: 'pending_accept', label: '待接单' },
  { key: 'accepted', label: '已接单' },
  { key: 'making', label: '制作中' },
  { key: 'ready', label: '待取餐' },
  { key: 'completed', label: '已完成' },
  { key: 'cancelled', label: '已取消' },
  { key: 'rejected', label: '已拒单' },
];

Page({
  data: {
    tabs: STATUS_TABS,
    status: '',
    serveDate: '',
    keyword: '',
    list: [],
    selected: {},
    selectedCount: 0,
    page: 1,
    pageSize: 20,
    finished: false,
    loading: false,
    accepting: false,
    acceptText: '接单',
  },

  onLoad(options) {
    this.setData({
      serveDate: options.serveDate || addDays(dateStr(), 1),
      status: options.status || '',
    });
  },

  onShow() {
    this.reload();
  },

  onReachBottom() {
    if (this.data.finished || this.data.loading) {
      return;
    }
    this.load(this.data.page + 1);
  },

  reload() {
    this.setData({ list: [], page: 1, finished: false, selected: {}, selectedCount: 0, acceptText: '接单' });
    return this.load(1);
  },

  load(page) {
    if (this.data.loading) {
      return Promise.resolve();
    }
    this.setData({ loading: true });
    const query = [
      'serveDate=' + this.data.serveDate,
      'page=' + page,
      'pageSize=' + this.data.pageSize,
    ];
    if (this.data.status) {
      query.push('status=' + this.data.status);
    }
    if (this.data.keyword) {
      query.push('keyword=' + encodeURIComponent(this.data.keyword));
    }

    return request({ url: '/admin/order/list?' + query.join('&') })
      .then((result) => {
        const rows = (result.list || []).map((row) => this.toRow(row));
        this.setData({
          list: page === 1 ? rows : this.data.list.concat(rows),
          page,
          finished: rows.length < this.data.pageSize,
        });
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ loading: false }));
  },

  toRow(row) {
    const order = row.order || {};
    return {
      orderId: order.orderId,
      orderNo: order.orderNo,
      status: order.status,
      statusLabel: order.statusLabel,
      payStatusLabel: order.payStatusLabel,
      totalText: yuan(order.totalAmount),
      pickupCode: order.pickupCode,
      pickupSlot: order.pickupSlot,
      deliveryType: order.deliveryType,
      regionName: order.address ? order.address.regionName : '',
      userNickName: row.userNickName || '群成员',
      userPhone: row.userPhone || '',
      summary: (order.items || []).map((it) => it.name + '×' + it.qty).join('、'),
      // 份数被超时释放的单必须顶在最前并标红：这份餐可能已经被别人买走了
      stockReleased: !!order.stockReleased,
      cancelRequested: !!order.cancelRequested,
      selectable: order.status === 'pending_accept' && !order.cancelRequested,
    };
  },

  onDateChange(e) {
    this.setData({ serveDate: e.detail.value });
    this.reload();
  },

  onSwitchTab(e) {
    this.setData({ status: e.currentTarget.dataset.key || '' });
    this.reload();
  },

  onKeywordInput(e) {
    this.setData({ keyword: (e.detail.value || '').trim() });
  },

  onSearch() {
    this.reload();
  },

  onToggle(e) {
    const id = e.currentTarget.dataset.id;
    const selectable = e.currentTarget.dataset.selectable;
    if (!selectable) {
      return;
    }
    const selected = Object.assign({}, this.data.selected);
    if (selected[id]) {
      delete selected[id];
    } else {
      selected[id] = true;
    }
    this.applySelected(selected);
  },

  onSelectAll() {
    const ids = this.data.list.filter((row) => row.selectable).map((row) => row.orderId);
    const allSelected = ids.length > 0 && ids.every((id) => this.data.selected[id]);
    const selected = {};
    if (!allSelected) {
      ids.forEach((id) => {
        selected[id] = true;
      });
    }
    this.applySelected(selected);
  },

  applySelected(selected) {
    const count = Object.keys(selected).length;
    this.setData({
      selected,
      selectedCount: count,
      acceptText: count ? '接单 ' + count + ' 单' : '接单',
    });
  },

  onAccept() {
    const orderIds = Object.keys(this.data.selected);
    if (!orderIds.length) {
      wx.showToast({ title: '请先勾选订单', icon: 'none' });
      return;
    }
    if (this.data.accepting) {
      return;
    }
    this.setData({ accepting: true });
    request({ url: '/admin/order/accept', method: 'POST', data: { orderIds } })
      .then((result) => {
        const warnings = result.warnings || [];
        const skipped = result.skipped || [];
        let msg = '已接 ' + (result.accepted || []).length + ' 单';
        if (skipped.length) {
          msg += '，跳过 ' + skipped.length + ' 单';
        }
        wx.showModal({
          title: '接单结果',
          content: msg + (warnings.length ? '\n\n' + warnings.join('\n') : ''),
          showCancel: false,
        });
        this.reload();
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ accepting: false }));
  },

  onOpen(e) {
    wx.navigateTo({ url: '/pages/merchant/order-detail?orderId=' + e.currentTarget.dataset.id });
  },
});
