const app = getApp();
const { request } = require('../../utils/request');
const { yuan, dateStr, addDays } = require('../../utils/format');
const dict = require('../../utils/dict');

/** 兜底顺序与文案，字典加载出来后以字典为准（商家可改显示名与顺序） */
const FALLBACK_STATUS = [
  { key: 'pending_accept', label: '待接单' },
  { key: 'accepted', label: '已接单' },
  { key: 'ready', label: '待取餐' },
  { key: 'completed', label: '已完成' },
  { key: 'cancelled', label: '已取消' },
  { key: 'rejected', label: '已拒单' },
];

function buildTabs() {
  return dict.tabs('order_status', '全部', FALLBACK_STATUS);
}

Page({
  data: {
    tabs: buildTabs(),
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
    /** 批量拒单（v1.53 / F-H12）：整批共用一条原因 */
    rejecting: false,
    rejectText: '拒单',
    /** 只看有退订申请的单。开启时请求不带供餐日，往期漏单也能捞到 */
    cancelOnly: false,
    /** 收款状态筛选（v1.53）：工作台「待核对收款 / 待退款」点进来时带上 */
    payStatus: '',
  },

  onLoad(options) {
    this.setData({
      serveDate: options.serveDate || addDays(dateStr(), 1),
      status: options.status || '',
      cancelOnly: options.cancelOnly === '1',
      payStatus: options.payStatus || '',
    });
  },

  onShow() {
    this.setData({ tabs: buildTabs() });
    dict.load().then(() => this.setData({ tabs: buildTabs() }));
    this.reload();
  },

  onReachBottom() {
    if (this.data.finished || this.data.loading) {
      return;
    }
    this.load(this.data.page + 1);
  },

  reload() {
    this.setData({
      list: [],
      page: 1,
      finished: false,
      selected: {},
      selectedCount: 0,
      acceptText: '接单',
      rejectText: '拒单',
    });
    return this.load(1);
  },

  load(page) {
    if (this.data.loading) {
      return Promise.resolve();
    }
    this.setData({ loading: true });
    const query = [
      'page=' + page,
      'pageSize=' + this.data.pageSize,
    ];
    // 只看退订、或按收款状态筛时都不带供餐日：要捞的是漏处理的单 / 没核对的钱，
    // 往期的也得算进来（带上「明天」等于先把要找的那批排除掉）
    if (!this.data.cancelOnly && !this.data.payStatus) {
      query.unshift('serveDate=' + this.data.serveDate);
    }
    if (this.data.cancelOnly) {
      query.push('cancelRequested=true');
    }
    if (this.data.payStatus) {
      query.push('payStatus=' + this.data.payStatus);
    }
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
      tagClass: 'tag-' + (order.statusTag || dict.tagType('order_status', order.status)),
      payStatusLabel: order.payStatusLabel,
      totalText: yuan(order.totalAmount),
      pickupCode: order.pickupCode,
      pickupSlot: order.pickupSlot,
      deliveryType: order.deliveryType,
      regionName: order.address ? order.address.regionName : '',
      userNickName: row.userNickName || '群成员',
      userPhone: row.userPhone || '',
      summary: (order.items || []).map((it) => it.name + '×' + it.qty).join('、'),
      // 整单备注必须传到后厨与配送手上，列表里就摆出来（需求 4.11 / 4.14）
      remark: order.remark || '',
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

  onToggleCancelOnly() {
    this.setData({ cancelOnly: !this.data.cancelOnly });
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
      rejectText: count ? '拒单 ' + count + ' 单' : '拒单',
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

  /**
   * 批量拒单：整批共用一条原因。
   *
   * 用 wx.showModal 的 editable 输入而不是跳新页面：拒单是个「点了就要马上给结果」的动作，
   * 多跳一页反而慢。原因必填 —— 顾客会在订单详情里看到这句话。
   */
  onBatchReject() {
    const orderIds = Object.keys(this.data.selected);
    if (!orderIds.length) {
      wx.showToast({ title: '请先勾选订单', icon: 'none' });
      return;
    }
    if (this.data.rejecting) {
      return;
    }
    wx.showModal({
      title: '批量拒单（' + orderIds.length + ' 单）',
      editable: true,
      placeholderText: '拒单原因必填，顾客会看到',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        const reason = (res.content || '').trim();
        if (!reason) {
          wx.showToast({ title: '请填写拒单原因', icon: 'none' });
          return;
        }
        this.submitBatchReject(orderIds, reason);
      },
    });
  },

  submitBatchReject(orderIds, reason) {
    this.setData({ rejecting: true });
    request({
      url: '/admin/order/reject-batch',
      method: 'POST',
      data: { orderIds, reason },
    })
      .then((result) => {
        const skipped = result.skipped || [];
        let msg = '已拒 ' + (result.rejected || []).length + ' 单';
        if (skipped.length) {
          msg += '，跳过 ' + skipped.length + ' 单：\n' + skipped.join('\n');
        }
        wx.showModal({ title: '拒单结果', content: msg, showCancel: false });
        this.reload();
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ rejecting: false }));
  },

  onOpen(e) {
    wx.navigateTo({ url: '/pages/merchant/order-detail?orderId=' + e.currentTarget.dataset.id });
  },
});
