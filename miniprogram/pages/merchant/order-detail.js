const app = getApp();
const { request } = require('../../utils/request');
const { yuan, dateStr, timeLabel, durationText } = require('../../utils/format');
const dict = require('../../utils/dict');
const payMethod = require('../../utils/pay-method');

Page({
  data: {
    orderId: '',
    row: null,
    order: null,
    lines: [],
    totalText: '0.00',
    /** 实际退款额（元），只在「已退款」时有值 */
    refundText: '',
    busy: false,
    /** 可推进到的下一个状态 */
    nextStatus: '',
    nextLabel: '',
    /** 全部流转记录（含推送投递成败），节点耗时只算订单线 */
    timeline: [],
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
    request({ url: '/admin/order/detail?orderId=' + this.data.orderId })
      .then((row) => {
        const order = row.order || {};
        const lines = (order.items || []).map((item) => ({
          name: item.name,
          qty: item.qty,
          priceText: yuan(item.price),
          dishesText: (item.dishes || []).map((d) => d.name + '×' + d.qty).join('、'),
          specText: (item.specs || []).map((s) => s.groupName + '：' + s.optionName).join('　'),
        }));
        const next = this.nextOf(order.status, order.deliveryType);
        this.setData({
          row,
          order,
          lines,
          totalText: yuan(order.totalAmount),
          // 历史单可能没有 refundAmount（v3.49 之前），回退订单合计
          refundText:
            order.payStatus === 'refunded'
              ? yuan(order.refundAmount == null ? order.totalAmount : order.refundAmount)
              : '',
          nextStatus: next.status,
          nextLabel: next.label,
          deliveryTypeText: dict.label(
            'delivery_type',
            order.deliveryType,
            order.deliveryType === 'delivery' ? '配送' : '自取'
          ),
        });
        this.loadTimeline();
      })
      .catch((err) => app.toastError(err));
  },

  /**
   * 状态流转时间轴（商家工作台入口）。
   *
   * 与用户端不同，这里要**全部记录**，含 `event.notify` / `event.notify_fail` ——
   * 商家需要判断「系统到底发没发出去」，用户说没收到时这是唯一的依据。
   * 直接复用网页后台在用的 `/admin/order/status-log`，不新增后端接口。
   *
   * 耗时只在订单线节点之间算：中间夹着的「通知已发送」与状态变化只差一两秒，
   * 若按相邻两条记录相减，「下单 → 接单」会被算成 2 秒，完全失真。
   */
  loadTimeline() {
    request({ url: '/admin/order/status-log?orderId=' + this.data.orderId })
      .then((rows) => {
        let lastOrderAt = null;
        const timeline = (rows || []).map((row) => {
          const isOrderLine = row.logType === 'order';
          let gapText = '';
          if (isOrderLine) {
            if (lastOrderAt) {
              gapText = durationText(lastOrderAt, row.createdAt);
            }
            lastOrderAt = row.createdAt;
          }
          return {
            id: row.id,
            // 有前状态就取「变成了什么」，没有前状态（下单、事件）用动作名：
            // 下单直接显示 toStatusLabel 会变成「待接单」，看着像还没下单
            label: row.fromStatusLabel ? row.toStatusLabel : row.actionLabel,
            timeText: timeLabel(row.createdAt),
            remark: row.remark || '',
            gapText,
            tone: row.logType,
          };
        });
        this.setData({ timeline });
      })
      .catch(() => {});
  },

  /**
   * 状态机在服务端也有校验，这里只是决定按钮显示什么。
   * 没有「制作中」，自取与配送也不分叉：接单后都进 ready，
   * 只是自取说「取餐」、配送说「送达」。
   */
  nextOf(status, deliveryType) {
    const delivery = deliveryType === 'delivery';
    if (status === 'accepted') {
      return delivery
        ? { status: 'ready', label: '开始配送' }
        : { status: 'ready', label: '做好了，可取餐' };
    }
    if (status === 'ready') {
      return delivery
        ? { status: 'completed', label: '确认已送达' }
        : { status: 'completed', label: '确认已完成' };
    }
    return { status: '', label: '' };
  },

  /**
   * 联系顾客：号码平时是掩码，真要打电话才向服务端取一次全号。
   * 不 setData、不缓存 —— 页面上任何能反查的地方都不该出现 11 位号。
   * 这一次调取会记进订单时间轴，且同单 24 小时最多 5 次。
   */
  onCallCustomer() {
    if (!this.data.orderId) {
      return;
    }
    request({ url: '/admin/order/' + this.data.orderId + '/contact-phone' })
      .then((phone) => {
        if (!phone) {
          wx.showToast({ title: '这单没有留电话', icon: 'none' });
          return;
        }
        wx.makePhoneCall({ phoneNumber: phone, fail: () => {} });
      })
      .catch((err) => app.toastError(err));
  },

  onAccept() {
    this.post('/admin/order/accept', { orderIds: [this.data.orderId] }, '已接单');
  },

  onReject() {
    wx.showModal({
      title: '拒单',
      editable: true,
      placeholderText: '必填：拒单原因，用户会看到',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        const reason = (res.content || '').trim();
        if (!reason) {
          wx.showToast({ title: '拒单必须填写原因', icon: 'none' });
          return;
        }
        this.post('/admin/order/reject', { orderId: this.data.orderId, reason }, '已拒单，份数已释放');
      },
    });
  },

  /** 申请中点了「先处理取消申请」：不静默，明确告诉商家先批或驳 */
  onAdvanceWhenRequested() {
    wx.showModal({
      title: '先处理取消申请',
      content: '用户已申请取消，请先同意或驳回，再推进出餐。',
      showCancel: false,
    });
  },

  onAdvance() {
    if (!this.data.nextStatus) {
      return;
    }
    const go = () => {
      this.post(
        '/admin/order/advance',
        { orderId: this.data.orderId, status: this.data.nextStatus },
        '已推进'
      );
    };
    const order = this.data.order || {};
    // 未到供餐日只提醒、不拦截：商家要能提前把流程点一遍验证；弹一次只为防手滑
    if (order.serveDate && order.serveDate > dateStr()) {
      wx.showModal({
        title: '还没到供餐日',
        content:
          '这单的供餐日是 ' + order.serveDate + '，今天 ' + dateStr() +
          '。仍要「' + this.data.nextLabel + '」吗？',
        confirmText: '仍要操作',
        cancelText: '先等等',
        success: (res) => {
          if (res.confirm) {
            go();
          }
        },
      });
      return;
    }
    go();
  },

  /**
   * 确认收款。
   *
   * 必须再选一次收款方式：现金单用户根本不点【我已完成付款】，确认时不再选一次，
   * 这类单永远没有方式值。用户申报过就默认带出他那次的值，可改 ——
   * 两条记录值不同就是「用户说微信、实际收现金」的差异依据。
   *
   * 注意与网页后台共用同一个接口：服务端已把 confirm 的 payMethod 设为必填，
   * 不带值会 400，所以两端弹窗必须同时存在。
   */
  onConfirmPay() {
    const current = (this.data.order || {}).payMethod || '';
    payMethod
      .choose(current)
      .then((method) => {
        this.post(
          '/admin/order/payment',
          { orderId: this.data.orderId, action: 'confirm', payMethod: method },
          '已确认收款'
        );
      })
      .catch((err) => {
        // 选择弹窗里点取消不提示错误
        if (!err || !err.cancelled) {
          app.toastError(err);
        }
      });
  },

  onRevertPay() {
    wx.showModal({
      title: '回退为未收款',
      content: '用在「用户说转了但查不到」的情况，确定吗？',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        this.post('/admin/order/payment', { orderId: this.data.orderId, action: 'revert' }, '已回退');
      },
    });
  },

  /**
   * 标记已退款，先填金额再填备注（P1-7 / `F-F06`）。
   *
   * 两步 showModal 而不是新开一页：这是「点了就要给结果」的动作，多跳一页反而慢。
   * 金额默认带出订单合计、可改成实际退的 —— 线下退款天然会有部分退（少送一份、
   * 扣掉已发生的配送费）。第二步点「不填」表示不带备注，仍然会提交。
   */
  onRefund() {
    const order = this.data.order || {};
    const total = Number(order.totalAmount || 0);
    const defaultYuan = (total / 100).toFixed(2);
    wx.showModal({
      title: '退款金额（元）',
      editable: true,
      content: defaultYuan,
      placeholderText: '实际退给顾客的金额',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        const raw = (res.content || '').trim() || defaultYuan;
        const yuanValue = Number(raw);
        if (!isFinite(yuanValue) || yuanValue <= 0) {
          wx.showToast({ title: '退款金额必须大于 0', icon: 'none' });
          return;
        }
        const cent = Math.round(yuanValue * 100);
        if (total > 0 && cent > total) {
          wx.showToast({ title: '不能超过订单合计', icon: 'none' });
          return;
        }
        wx.showModal({
          title: '退款备注（选填）',
          editable: true,
          placeholderText: '退到哪个账户、为什么少退',
          cancelText: '不填',
          confirmText: '确认退款',
          success: (res2) => {
            const remark = res2.confirm ? (res2.content || '').trim() : '';
            this.post(
              '/admin/order/refund',
              { orderId: this.data.orderId, refundAmount: cent, refundRemark: remark || null },
              '已标记退款'
            );
          },
        });
      },
    });
  },

  onHandleCancel(e) {
    const approve = e.currentTarget.dataset.approve === 'true';
    wx.showModal({
      title: approve ? '同意取消' : '驳回取消申请',
      editable: true,
      // 驳回的原因会原样显示在用户端，提示写清楚；同意时只是个备注
      placeholderText: approve ? '备注（可不填）' : '驳回原因（用户会看到，建议填写）',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        this.post(
          '/admin/order/cancel-request',
          { orderId: this.data.orderId, approve, remark: (res.content || '').trim() },
          approve ? '已同意取消' : '已驳回申请'
        );
      },
    });
  },

  post(url, data, successText) {
    if (this.data.busy) {
      return;
    }
    this.setData({ busy: true });
    request({ url, method: 'POST', data })
      .then(() => {
        wx.showToast({ title: successText, icon: 'success' });
        this.load();
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ busy: false }));
  },
});
