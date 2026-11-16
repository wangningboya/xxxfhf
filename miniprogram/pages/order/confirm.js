const app = getApp();
const { request } = require('../../utils/request');
const { yuan, parseList } = require('../../utils/format');
const cartUtil = require('../../utils/cart');

const SELECTED_ADDRESS_KEY = 'meal_selected_address';

Page({
  data: {
    serveDate: '',
    lines: [],
    count: 0,
    goodsAmountText: '0.00',
    deliveryFee: 0,
    deliveryFeeText: '0.00',
    totalAmountText: '0.00',

    deliveryType: 'pickup',
    pickupSlots: [],
    pickupSlot: '',
    address: null,
    remark: '',
    submitting: false,
    /** 单次提交的幂等号：点两次「提交」只建一单 */
    requestId: '',
    canSubmit: false,
  },

  onLoad() {
    // 进入页面就定下幂等号。同一页面重复提交只会产生一单，
    // 用户退回去改了再进来的算新的一单 —— 那是他的真实意图
    this.setData({ requestId: 'r' + Date.now() + Math.floor(Math.random() * 100000) });
  },

  onShow() {
    this.load();
  },

  load() {
    const cart = cartUtil.load();
    if (!cart.items.length) {
      wx.showToast({ title: '购物车是空的', icon: 'none' });
      wx.navigateBack();
      return;
    }

    const lines = cart.items.map((item) => {
      const specDelta = (item.specDeltas || []).reduce((s, v) => s + Number(v || 0), 0);
      const unit = Number(item.price || 0) + specDelta;
      return {
        key: cartUtil.lineKey(item.comboId, item.optionIds),
        name: item.name,
        specText: item.specText,
        qty: item.qty,
        optionIds: item.optionIds || [],
        comboId: item.comboId,
        lineText: yuan(unit * item.qty),
      };
    });

    app.loadConfig().then((config) => {
      const slots = parseList(config.pickupSlots);
      const deliveryFee = Number(config.deliveryFee || 0);
      this.setData({
        serveDate: cart.serveDate,
        lines,
        count: cartUtil.count(cart),
        goodsAmountText: yuan(cartUtil.goodsAmount(cart)),
        deliveryFee,
        deliveryFeeText: yuan(deliveryFee),
        pickupSlots: slots,
        // 只有一个时段就直接选上，省一次点击
        pickupSlot: this.data.pickupSlot || (slots.length === 1 ? slots[0] : ''),
      });
      this.loadAddress();
      this.recalc();
    });
  },

  /** 默认地址：配送单必须有一个，否则提交时会被服务端打回 */
  loadAddress() {
    const picked = wx.getStorageSync(SELECTED_ADDRESS_KEY);
    if (picked) {
      wx.removeStorageSync(SELECTED_ADDRESS_KEY);
    }
    return request({ url: '/address/mine' })
      .then((list) => {
        const active = (list || []).filter((a) => a.status === 'active');
        let address = null;
        if (picked) {
          address = (list || []).find((a) => a.id === picked) || null;
        }
        if (!address && active.length) {
          address = active[0];
        }
        this.setData({ address });
        this.recalc();
      })
      .catch(() => {
        // 地址拉不到不影响自取单提交，不弹错
      });
  },

  recalc() {
    const deliveryFee = this.data.deliveryType === 'delivery' ? this.data.deliveryFee : 0;
    const cart = cartUtil.load();
    const total = cartUtil.goodsAmount(cart) + deliveryFee;
    const canSubmit = this.data.deliveryType === 'delivery'
      ? !!this.data.address
      : !!this.data.pickupSlot;
    this.setData({
      deliveryFeeText: yuan(deliveryFee),
      totalAmountText: yuan(total),
      canSubmit,
    });
  },

  onSwitchType(e) {
    this.setData({ deliveryType: e.currentTarget.dataset.type });
    this.recalc();
  },

  onPickSlot(e) {
    this.setData({ pickupSlot: e.currentTarget.dataset.slot });
    this.recalc();
  },

  onOpenAddress() {
    wx.navigateTo({ url: '/pages/address/list?mode=select' });
  },

  onRemarkInput(e) {
    this.setData({ remark: (e.detail.value || '').slice(0, 100) });
  },

  onSubmit() {
    if (this.data.submitting) {
      return;
    }
    if (!this.data.canSubmit) {
      wx.showToast({
        title: this.data.deliveryType === 'delivery' ? '请选择收货地址' : '请选择取餐时段',
        icon: 'none',
      });
      return;
    }

    this.setData({ submitting: true });
    const data = {
      serveDate: this.data.serveDate,
      deliveryType: this.data.deliveryType,
      addressId: this.data.deliveryType === 'delivery' ? this.data.address.id : null,
      pickupSlot: this.data.deliveryType === 'pickup' ? this.data.pickupSlot : null,
      remark: this.data.remark,
      requestId: this.data.requestId,
      items: this.data.lines.map((line) => ({
        comboId: line.comboId,
        qty: line.qty,
        optionIds: line.optionIds,
      })),
    };

    request({ url: '/order', method: 'POST', data })
      .then((order) => {
        cartUtil.clear();
        wx.redirectTo({ url: '/pages/order/result?orderId=' + order.orderId });
      })
      .catch((err) => {
        app.toastError(err);
        // 份数被抢完这类错误，退回去让用户重新选
        this.setData({ submitting: false });
      });
  },
});
