const app = getApp();
const { request } = require('../../utils/request');
const { yuan, dateStr } = require('../../utils/format');

Page({
  data: {
    serveDate: '',
    code: '',
    /** 四个格子的显示值。WXML 里对字符串做下标取值不稳，放到 JS 里算好 */
    boxes: ['', '', '', ''],
    result: null,
    resultTotalText: '',
    submitting: false,
  },

  onLoad() {
    this.setData({ serveDate: dateStr() });
  },

  onDateChange(e) {
    this.setData({ serveDate: e.detail.value, result: null });
  },

  onCodeInput(e) {
    // 只留数字：手输容易带空格或字母
    const code = (e.detail.value || '').replace(/\D/g, '').slice(0, 4);
    const boxes = ['', '', '', ''];
    for (let i = 0; i < code.length; i++) {
      boxes[i] = code[i];
    }
    this.setData({ code, boxes });
    if (code.length === 4) {
      this.submit();
    }
  },

  submit() {
    if (this.data.submitting) {
      return;
    }
    if (this.data.code.length !== 4) {
      wx.showToast({ title: '请输入 4 位取餐码', icon: 'none' });
      return;
    }
    this.setData({ submitting: true });
    request({
      url: '/admin/order/pickup',
      method: 'POST',
      data: { serveDate: this.data.serveDate, code: this.data.code },
    })
      .then((order) => {
        this.setData({
          result: order,
          resultTotalText: yuan(order.totalAmount),
          code: '',
        });
        wx.vibrateShort({ type: 'medium' });
      })
      .catch((err) => {
        app.toastError(err);
        this.setData({ code: '' });
      })
      .then(() => this.setData({ submitting: false }));
  },
});
