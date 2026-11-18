const app = getApp();
const { request } = require('../../utils/request');

const SELECTED_ADDRESS_KEY = 'meal_selected_address';

Page({
  data: {
    /** select：从确认订单页进来，点了就带回；manage：从「我的」进来，只维护 */
    mode: 'manage',
    groups: [],
    loading: true,
  },

  onLoad(options) {
    this.setData({ mode: options.mode === 'select' ? 'select' : 'manage' });
  },

  onShow() {
    this.load();
  },

  load() {
    this.setData({ loading: true });
    request({ url: '/address/mine' })
      .then((list) => {
        // 按区域分组：同一片区的地址放一起，配送范围一眼能看清
        const map = {};
        (list || []).forEach((item) => {
          const key = item.regionName || '未分区域';
          if (!map[key]) {
            map[key] = [];
          }
          map[key].push(item);
        });
        const groups = Object.keys(map).map((name) => ({ name, items: map[name] }));
        this.setData({ groups });
      })
      .catch((err) => {
        app.toastError(err);
      })
      .then(() => {
        this.setData({ loading: false });
      });
  },

  onPick(e) {
    if (this.data.mode !== 'select') {
      return;
    }
    const id = e.currentTarget.dataset.id;
    const status = e.currentTarget.dataset.status;
    if (status !== 'active') {
      wx.showToast({ title: '该地址还在等商家确认', icon: 'none' });
      return;
    }
    wx.setStorageSync(SELECTED_ADDRESS_KEY, id);
    wx.navigateBack();
  },

  onAdd() {
    if (app.blockIfNotApproved('账号审核中，通过后才能填写地址')) {
      return;
    }
    wx.navigateTo({ url: '/pages/address/edit' });
  },

  onEdit(e) {
    if (app.blockIfNotApproved('账号审核中，通过后才能修改地址')) {
      return;
    }
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: '/pages/address/edit?id=' + id });
  },
});
