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
      cancelRequestTimeoutHours: '2',
    },
    /** 自取时段用多行文本编辑，比让商家写 JSON 靠谱 */
    slotsText: '',
    /** 配送费按元编辑，保存时换回分 */
    deliveryFeeYuan: '0.00',
    /** 群白名单同样一行一个，存的时候拼 JSON 数组 */
    groupIdsText: '',
    /** 订阅模板是嵌套对象，只能让商家贴 JSON；保存前先 parse 校验 */
    templatesText: '{}',
    saving: false,
  },

  onShow() {
    this.load();
  },

  load() {
    request({ url: '/admin/config' })
      .then((all) => {
        // 0 是有效值（关闭自动同意），不能用 `|| 2` 兜底
        const timeout = all.cancelRequestTimeoutHours;
        const config = Object.assign({}, this.data.config, {
          shopName: all.shopName || '',
          shopNotice: all.shopNotice || '',
          serveTimeText: all.serveTimeText || '',
          cutoffTime: all.cutoffTime || '',
          pickupSlots: all.pickupSlots || '[]',
          deliveryFee: all.deliveryFee || '0',
          prepayReleaseMinutes: all.prepayReleaseMinutes || '30',
          cancelRequestTimeoutHours:
            timeout === undefined || timeout === null || timeout === '' ? '2' : String(timeout),
        });
        this.setData({
          config,
          slotsText: this.parseSlots(config.pickupSlots),
          deliveryFeeYuan: (Number(config.deliveryFee || 0) / 100).toFixed(2),
          groupIdsText: this.parseList(all.groupOpenIds).join('\n'),
          templatesText: this.prettyJson(all.subscribeTemplates),
        });
      })
      .catch((err) => app.toastError(err));
  },

  parseSlots(raw) {
    return this.parseList(raw).join('\n');
  },

  /** JSON 字符串数组 → 数组；解析不了就给空数组，绝不把坏值带上界面 */
  parseList(raw) {
    try {
      const arr = JSON.parse(raw || '[]');
      return Array.isArray(arr) ? arr : [];
    } catch (e) {
      return [];
    }
  },

  /** 配置里存的是压缩过的 JSON，缩进后才看得懂 */
  prettyJson(raw) {
    const text = (raw || '').trim();
    if (!text) {
      return '{}';
    }
    try {
      return JSON.stringify(JSON.parse(text), null, 2);
    } catch (e) {
      return text;
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

  /** 群白名单：一行一个，空行丢掉 */
  onGroupsInput(e) {
    this.setData({ groupIdsText: e.detail.value || '' });
  },

  onTemplatesInput(e) {
    this.setData({ templatesText: e.detail.value || '' });
  },

  /** 群白名单行 → JSON 数组字符串；空列表也要给 `[]`，不能给空串 */
  groupIdsJson() {
    const lines = (this.data.groupIdsText || '')
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean);
    return JSON.stringify(lines);
  },

  /** 订阅模板必须是合法 JSON 对象；解析失败返回 null 由调用方拦截 */
  templatesJson() {
    const text = (this.data.templatesText || '').trim();
    if (!text) {
      return '{}';
    }
    try {
      const parsed = JSON.parse(text);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return null;
      }
      return JSON.stringify(parsed);
    } catch (e) {
      return null;
    }
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
    const timeoutHours = Number(c.cancelRequestTimeoutHours);
    if (isNaN(timeoutHours) || timeoutHours < 0 || timeoutHours > 72) {
      wx.showToast({ title: '取消超时填 0~72 小时', icon: 'none' });
      return;
    }
    const templates = this.templatesJson();
    if (templates === null) {
      wx.showToast({ title: '消息模板不是合法 JSON', icon: 'none' });
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
        cancelRequestTimeoutHours: String(timeoutHours),
        groupOpenIds: this.groupIdsJson(),
        subscribeTemplates: templates,
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
