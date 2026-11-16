const app = getApp();
const { request } = require('../../utils/request');
const { yuan, dateStr, addDays } = require('../../utils/format');

Page({
  data: {
    serveDate: '',
    combos: [],
    /** comboId -> { selected, limitQty } */
    picked: {},
    pickedCount: 0,
    cutoff: false,
    closed: false,
    loading: true,
    saving: false,
  },

  onLoad(options) {
    this.setData({ serveDate: options.serveDate || addDays(dateStr(), 1) });
  },

  onShow() {
    this.load();
  },

  load() {
    this.setData({ loading: true });
    Promise.all([
      request({ url: '/admin/combo/list?status=ON' }),
      request({ url: '/admin/menu/view?serveDate=' + this.data.serveDate }),
    ])
      .then(([combos, view]) => {
        const picked = {};
        (view.items || []).forEach((item) => {
          picked[item.comboId] = {
            selected: true,
            limitQty: item.limitQty === null || item.limitQty === undefined ? '' : item.limitQty,
          };
        });
        const rows = (combos || []).map((combo) => ({
          comboId: combo.id,
          name: combo.name,
          cover: combo.cover,
          priceText: yuan(combo.price),
          dishesText: (combo.dishes || []).map((d) => d.name + '×' + d.qty).join('、'),
        }));
        this.setData({
          combos: rows,
          picked,
          pickedCount: Object.keys(picked).length,
          cutoff: !!view.cutoff,
          closed: !!view.closed,
          loading: false,
        });
      })
      .catch((err) => {
        this.setData({ loading: false });
        app.toastError(err);
      });
  },

  onDateChange(e) {
    this.setData({ serveDate: e.detail.value });
    this.load();
  },

  /** 份数输入框在自己的区域内点击，不该触发整行的勾选切换 */
  noop() {},

  onToggle(e) {
    const comboId = e.currentTarget.dataset.id;
    const picked = Object.assign({}, this.data.picked);
    if (picked[comboId]) {
      delete picked[comboId];
    } else {
      picked[comboId] = { selected: true, limitQty: '' };
    }
    this.setData({ picked, pickedCount: Object.keys(picked).length });
  },

  onLimitInput(e) {
    const comboId = e.currentTarget.dataset.id;
    const value = (e.detail.value || '').replace(/\D/g, '');
    const picked = Object.assign({}, this.data.picked);
    if (!picked[comboId]) {
      return;
    }
    picked[comboId] = { selected: true, limitQty: value };
    this.setData({ picked });
  },

  buildItems() {
    return Object.keys(this.data.picked).map((comboId) => {
      const limit = this.data.picked[comboId].limitQty;
      return {
        comboId,
        // 空 = 不限量。传 0 会被当成「一份都不做」，语义完全不同
        limitQty: limit === '' ? null : Number(limit),
      };
    });
  },

  onSave() {
    if (this.data.saving) {
      return;
    }
    if (!this.data.pickedCount) {
      wx.showToast({ title: '至少选一个盒饭', icon: 'none' });
      return;
    }
    this.setData({ saving: true });
    request({
      url: '/admin/menu/save',
      method: 'POST',
      data: { serveDate: this.data.serveDate, items: this.buildItems() },
    })
      .then(() => {
        wx.showToast({ title: '已保存', icon: 'success' });
        this.load();
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ saving: false }));
  },

  /**
   * 发布。
   *
   * 发布时会把盒饭的价格、配菜、规格拍成快照写进 daily_menu，
   * 之后再改盒饭也不影响这一天已经卖出去的餐 —— 所以发布前必须确认菜单是对的。
   */
  onPublish() {
    wx.showModal({
      title: '发布菜单',
      content: '发布后这一天的价格与配菜会被锁定，用户可下单。确定吗？',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        request({
          url: '/admin/menu/publish',
          method: 'POST',
          data: { serveDate: this.data.serveDate, published: true },
        })
          .then(() => {
            wx.showToast({ title: '已发布', icon: 'success' });
            this.load();
          })
          .catch((err) => app.toastError(err));
      },
    });
  },

  onUnpublish() {
    wx.showModal({
      title: '撤下菜单',
      content: '撤下后用户看不到这一天，已下的单不受影响。',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        request({
          url: '/admin/menu/publish',
          method: 'POST',
          data: { serveDate: this.data.serveDate, published: false },
        })
          .then(() => {
            wx.showToast({ title: '已撤下', icon: 'success' });
            this.load();
          })
          .catch((err) => app.toastError(err));
      },
    });
  },

  /** 复制前一天的菜单，省得每天重新勾一遍 */
  onCopy() {
    wx.showModal({
      title: '复制上一份菜单',
      content: '把前一个供餐日的盒饭与份数复制到这一天，已有内容会被覆盖。',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        request({ url: '/admin/menu/copy?serveDate=' + this.data.serveDate })
          .then((items) => {
            const picked = {};
            (items || []).forEach((item) => {
              picked[item.comboId] = {
                selected: true,
                limitQty: item.limitQty === null || item.limitQty === undefined ? '' : item.limitQty,
              };
            });
            if (!Object.keys(picked).length) {
              wx.showToast({ title: '没有可复制的菜单', icon: 'none' });
              return;
            }
            this.setData({ picked, pickedCount: Object.keys(picked).length });
            return request({
              url: '/admin/menu/save',
              method: 'POST',
              data: { serveDate: this.data.serveDate, items: this.buildItems() },
            }).then(() => {
              wx.showToast({ title: '已复制', icon: 'success' });
              this.load();
            });
          })
          .catch((err) => app.toastError(err));
      },
    });
  },

  onToggleClosed() {
    const next = !this.data.closed;
    request({
      url: '/admin/menu/close',
      method: 'POST',
      data: { serveDate: this.data.serveDate, closed: next },
    })
      .then(() => {
        this.setData({ closed: next });
        wx.showToast({ title: next ? '已暂停' : '已恢复', icon: 'success' });
      })
      .catch((err) => app.toastError(err));
  },
});
