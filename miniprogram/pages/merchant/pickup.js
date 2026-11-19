const app = getApp();
const { request } = require('../../utils/request');
const { yuan, dateStr } = require('../../utils/format');

Page({
  data: {
    serveDate: '',
    code: '',
    /** 四个格子的显示值。WXML 里对字符串做下标取值不稳，放到 JS 里算好 */
    boxes: ['', '', '', ''],
    /** 当前待输入的格子下标（= 已输入位数），-1 表示输满或未聚焦 */
    active: 0,
    /** 隐藏输入框是否聚焦 */
    focused: true,
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

  /** 已输入几位，光标就停在第几个格子；满 4 位后不再高亮 */
  activeIndex(code) {
    return code.length < 4 ? code.length : -1;
  },

  /** 点格子区域把焦点还给隐藏输入框（键盘收起后再点一次能重新唤起） */
  onBoxesTap() {
    if (!this.data.focused) {
      this.setData({ focused: true });
    }
  },

  onFocus() {
    this.setData({ focused: true, active: this.activeIndex(this.data.code) });
  },

  onBlur() {
    this.setData({ focused: false, active: -1 });
  },

  onCodeInput(e) {
    // 只留数字：手输容易带空格或字母
    const code = (e.detail.value || '').replace(/\D/g, '').slice(0, 4);
    const boxes = ['', '', '', ''];
    for (let i = 0; i < code.length; i++) {
      boxes[i] = code[i];
    }
    this.setData({ code, boxes, active: this.activeIndex(code) });
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
    // 未到供餐日只提醒、不拦截：提前核销常出现在串流程时，弹一次只为防手滑
    if (this.data.serveDate > dateStr()) {
      wx.showModal({
        title: '还没到供餐日',
        content:
          '这单的供餐日是 ' + this.data.serveDate + '，今天 ' + dateStr() +
          '。仍要核销吗？',
        confirmText: '仍要核销',
        cancelText: '先等等',
        success: (res) => {
          if (res.confirm) {
            this.doSubmit();
          }
        },
      });
      return;
    }
    this.doSubmit();
  },

  doSubmit() {
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
          boxes: ['', '', '', ''],
          active: 0,
        });
        wx.vibrateShort({ type: 'medium' });
      })
      .catch((err) => {
        app.toastError(err);
        this.setData({ code: '', boxes: ['', '', '', ''], active: 0 });
      })
      .then(() => this.setData({ submitting: false }));
  },
});
