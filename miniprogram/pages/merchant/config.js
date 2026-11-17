const app = getApp();
const { request } = require('../../utils/request');

Page({
  data: {
    config: {
      shopName: '',
      shopNotice: '',
      serveTimeText: '',
      cutoffTime: '',
      pickupSlots: '',
      deliveryFee: '0',
      prepayReleaseMinutes: '30',
    },
    /** 自取时段用多行文本编辑，比让商家写 JSON 靠谱 */
    slotsText: '',
    /** 配送费按元编辑，保存时换回分 */
    deliveryFeeYuan: '0.00',
    saving: false,
  },

  onShow() {
    this.load();
  },

  load() {
    request({ url: '/admin/config' })
      .then((all) => {
        const config = Object.assign({}, this.data.config, {
          shopName: all.shopName || '',
          shopNotice: all.shopNotice || '',
          serveTimeText: all.serveTimeText || '',
          cutoffTime: all.cutoffTime || '',
          pickupSlots: all.pickupSlots || '[]',
          deliveryFee: all.deliveryFee || '0',
          prepayReleaseMinutes: all.prepayReleaseMinutes || '30',
        });
        this.setData({
          config,
          slotsText: this.parseSlots(config.pickupSlots),
          deliveryFeeYuan: (Number(config.deliveryFee || 0) / 100).toFixed(2),
        });
      })
      .catch((err) => app.toastError(err));
  },

  parseSlots(raw) {
    try {
      const arr = JSON.parse(raw || '[]');
      return Array.isArray(arr) ? arr.join('\n') : '';
    } catch (e) {
      return '';
    }
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field;
    this.setData({ ['config.' + field]: e.detail.value });
  },

  onFeeInput(e) {
    const value = (e.detail.value || '').replace(/[^\d.]/g, '');
    this.setData({ deliveryFeeYuan: value });
  },

  onTimeChange(e) {
    this.setData({ 'config.cutoffTime': e.detail.value });
  },

  /** 自取时段按「一行一个」编辑 */
  onSlotsInput(e) {
    const text = e.detail.value || '';
    const lines = text
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean);
    this.setData({
      slotsText: text,
      'config.pickupSlots': JSON.stringify(lines),
    });
  },

  onSave() {
    if (this.data.saving) {
      return;
    }
    const c = this.data.config;
    const feeYuan = Number(this.data.deliveryFeeYuan || 0);
    if (isNaN(feeYuan) || feeYuan < 0) {
      wx.showToast({ title: '配送费填错了', icon: 'none' });
      return;
    }
    if (!/^\d{1,2}:\d{2}$/.test(c.cutoffTime)) {
      wx.showToast({ title: '截单时间格式应为 HH:mm', icon: 'none' });
      return;
    }

    this.setData({ saving: true });
    request({
      url: '/admin/config',
      method: 'POST',
      data: {
        shopName: c.shopName,
        shopNotice: c.shopNotice,
        serveTimeText: c.serveTimeText,
        cutoffTime: c.cutoffTime,
        pickupSlots: c.pickupSlots,
        // 金额存分，避免 0.1 这类小数进数据库
        deliveryFee: String(Math.round(feeYuan * 100)),
        prepayReleaseMinutes: c.prepayReleaseMinutes,
      },
    })
      .then(() => {
        app.globalData.configLoaded = false;
        wx.showToast({ title: '已保存', icon: 'success' });
        this.load();
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ saving: false }));
  },
});
