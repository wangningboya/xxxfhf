const app = getApp();
const { request } = require('../../utils/request');
const { yuan, timeLabel } = require('../../utils/format');
const dict = require('../../utils/dict');
const payMethod = require('../../utils/pay-method');

Page({
  data: {
    orderId: '',
    order: null,
    lines: [],
    totalText: '0.00',
    goodsText: '0.00',
    specText: '0.00',
    feeText: '0.00',
    busy: false,
    /** 订单流转节点：只含订单本身的状态变化，不含推送投递记录 */
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
          // 取送方式的中文名也走字典，商家可以把「自取」改成「到店拿」
          deliveryTypeText: dict.label(
            'delivery_type',
            order.deliveryType,
            order.deliveryType === 'delivery' ? '配送' : '自取'
          ),
        });
        this.loadTimeline();
      })
      .catch((err) => {
        app.toastError(err);
      });
  },

  /**
   * 订单进度时间轴。
   *
   * 接口只返回 log_type='order' 的节点，推送投递记录（event.notify / notify_fail）
   * 在服务端就滤掉了 —— 把「通知未送达」摆给用户看，等于系统自认没做到。
   *
   * 失败静默：时间轴是补充信息，订单状态本身已经显示在顶部状态卡片里，
   * 不该因为它拉不到就弹一个错误。
   */
  loadTimeline() {
    request({ url: '/order/' + this.data.orderId + '/timeline' })
      .then((rows) => {
        const timeline = (rows || []).map((row) => ({
          id: row.id,
          // 有前状态就取「变成了什么」（已接单 / 待取餐）；下单这类没有前状态，
          // 用动作名「下单」—— 直接显示 toStatusLabel 会变成「待接单」，看着像还没下单
          label: row.fromStatusLabel ? row.toStatusLabel : row.actionLabel,
          timeText: timeLabel(row.createdAt),
          remark: row.remark || '',
        }));
        this.setData({ timeline });
      })
      .catch(() => {});
  },

  /**
   * 我已完成付款。
   *
   * 先让用户选收款方式（微信 / 支付宝 / 现金），**漏选不许提交** ——
   * 服务端已把方式设为必填，没带值的请求会直接 400。
   * 这里主动拦下来，是为了在弹窗取消时安静返回，而不是让用户看到一个报错。
   */
  onClaimPaid() {
    if (this.data.busy) {
      return;
    }
    payMethod
      .choose()
      .then((method) => {
        this.setData({ busy: true });
        return request({
          url: '/order/claim-paid',
          method: 'POST',
          data: { orderId: this.data.orderId, payMethod: method },
        });
      })
      .then(() => {
        wx.showToast({ title: '已告诉商家', icon: 'success' });
        this.load();
      })
      .catch((err) => {
        // 用户在三选一里点了取消，不是错误
        if (!err || !err.cancelled) {
          app.toastError(err);
        }
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
