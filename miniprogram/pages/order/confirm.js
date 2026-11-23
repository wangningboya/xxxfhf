const app = getApp();
const { request } = require('../../utils/request');
const { yuan, parseList } = require('../../utils/format');
const cartUtil = require('../../utils/cart');

const SELECTED_ADDRESS_KEY = 'meal_selected_address';

/**
 * 用户侧订阅消息模板：接单、拒单，第三个按取送方式二选一。
 *
 * 微信一次最多请求 3 个模板，而用户侧一共需要 4 个（自取取餐码 / 配送进度各一个），
 * 所以按这一单的取送方式拆分：自取单授权「取餐提醒」，配送单授权「订单配送通知」。
 * 必须写在前端 —— requestSubscribeMessage 只认 tmplIds 实体值，读不到服务端配置；
 * 与后台 config.subscribeTemplates 里同名项的 templateId 是同一组，换模板要两边一起改。
 */
const SUBSCRIBE_TMPL_IDS = {
  pickup: [
    'b5jJRQwng514myFXOsLuSLDxaVzMIDzbwLUpFKqB5ZU',
    'qk-sIMYmoL0PhyLV-CjVIfHxDd-HmZ5tTgRNO_yeFwY',
    '7Fb9GjfQAgGf-ZmIuLzf7In1qjtasqqZaQaZxLsF-UY',
  ],
  delivery: [
    'b5jJRQwng514myFXOsLuSLDxaVzMIDzbwLUpFKqB5ZU',
    'qk-sIMYmoL0PhyLV-CjVIfHxDd-HmZ5tTgRNO_yeFwY',
    'xeXcnfIwF2fDiFOFcQwgYyHV6QMO-f1RvffX-8QKIog',
  ],
};

/**
 * 订阅消息授权，永不 reject。
 *
 * 微信要求这个接口必须由用户点击同步触发，所以只能放在「提交订单」的 tap 回调里。
 * 用 complete 而不是 success：用户拒绝、基础库太旧、模板 ID 失效，都只是少几条通知，
 * 不能因为拿不到授权就拦住这一单。
 *
 * @param {string} deliveryType 'delivery' 或 'pickup'，决定第三个模板用哪个
 */
function requestSubscribe(deliveryType) {
  const tmplIds = SUBSCRIBE_TMPL_IDS[deliveryType === 'delivery' ? 'delivery' : 'pickup'];
  return new Promise((resolve) => {
    if (!wx.requestSubscribeMessage) {
      resolve();
      return;
    }
    wx.requestSubscribeMessage({
      tmplIds,
      complete: () => resolve(),
    });
  });
}

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
    /** 该菜单已截单：不能提交（需求 4.6） */
    cutoff: false,
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
    // 跨 0 点后车里是昨天的餐，服务端一定拒单，不如当场清掉退回购物车
    if (cartUtil.isStale(cart)) {
      cartUtil.clear();
      wx.showToast({ title: '购物车已过期，请重新选择', icon: 'none' });
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
      this.checkCutoff();
      this.recalc();
    });
  },

  /**
   * 截单检测：过了截单点不能提交（判定以服务端为准，这里只做前置提示）。
   *
   * 无参请求 /menu —— 服务端按自己的「明天」返回。日期对不上说明跨天了，
   * 车内餐已不可能下单：清车、提示、退回购物车。
   */
  checkCutoff() {
    if (!this.data.serveDate) {
      return;
    }
    request({ url: '/menu' })
      .then((menu) => {
        if (menu.serveDate !== this.data.serveDate) {
          cartUtil.clear();
          wx.showToast({ title: '购物车已过期，请重新选择', icon: 'none' });
          wx.navigateBack();
          return;
        }
        this.setData({ cutoff: !!menu.cutoff });
        this.recalc();
      })
      .catch(() => {
        // 拉不到就不提示，提交时服务端仍会拦
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
        const all = list || [];
        // 能不能用由服务端判定（审核状态 × 地址启用 × 区域启用），页面不再自己拼接判断
        const usable = all.filter((a) => a.usable);
        let address = null;
        if (picked) {
          const found = all.find((a) => a.id === picked);
          if (found && found.usable) {
            address = found;
          } else if (found && found.unusableReason) {
            wx.showToast({ title: found.unusableReason, icon: 'none' });
          }
        }
        if (!address && usable.length) {
          address = usable[0];
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
    const canSubmit = !this.data.cutoff && (this.data.deliveryType === 'delivery'
      ? !!this.data.address
      : !!this.data.pickupSlot);
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
    // 兜底：正常路径已在加购 / 结算拦过，这里防的是页面栈里的旧页面
    if (app.blockIfNotApproved('账号审核中，通过后才能下单')) {
      return;
    }
    if (this.data.cutoff) {
      wx.showToast({ title: '已截单，请重新选择', icon: 'none' });
      return;
    }
    if (!this.data.canSubmit) {
      wx.showToast({
        title: this.data.deliveryType === 'delivery' ? '请选择收货地址' : '请选择取餐时段',
        icon: 'none',
      });
      return;
    }
    // 自取单不强制留手机号：到店报 4 位取餐码即可核销，电话不是必要信息。
    // 账号里填过号会带进订单快照，商家仍能在售后窗口内联系到人
    this.setData({ submitting: true });
    // 先弹授权框、再上遮罩：showLoading 的 mask 会盖住页面，
    // 授权框夹在中间会让用户看到一次突兀的闪烁。拿到结果无论如何都继续下单
    requestSubscribe(this.data.deliveryType).then(() => this.submitOrder());
  },

  submitOrder() {
    // loading 遮罩：挡住页面上的其他点击，配合按钮禁用双保险防重复提交
    wx.showLoading({ title: '提交中', mask: true });
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
        wx.hideLoading();
        cartUtil.clear();
        wx.redirectTo({ url: '/pages/order/result?orderId=' + order.orderId });
      })
      .catch((err) => {
        wx.hideLoading();
        app.toastError(err);
        // 份数被抢完这类错误，退回去让用户重新选
        this.setData({ submitting: false });
      });
  },
});
