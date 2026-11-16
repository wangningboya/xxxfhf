const app = getApp();
const { request } = require('../../utils/request');
const { yuan } = require('../../utils/format');

/** 盒饭管理（F-H09 列表部分）：上下架、排序、进编辑页。 */
Page({
  data: {
    keyword: '',
    status: '',
    combos: [],
    loading: false,
  },

  onShow() {
    this.load();
  },

  onPullDownRefresh() {
    this.load(() => wx.stopPullDownRefresh());
  },

  onKeyword(e) {
    this.setData({ keyword: e.detail.value });
    clearTimeout(this.kwTimer);
    this.kwTimer = setTimeout(() => this.load(), 350);
  },

  onStatus(e) {
    this.setData({ status: e.currentTarget.dataset.status });
    this.load();
  },

  load(done) {
    this.setData({ loading: true });
    const kw = encodeURIComponent(this.data.keyword || '');
    const status = this.data.status ? '&status=' + this.data.status : '';
    request({ url: '/admin/combo/list?keyword=' + kw + status })
      .then((list) => {
        const combos = (list || []).map((combo) => ({
          id: combo.id,
          name: combo.name,
          cover: combo.cover,
          priceText: yuan(combo.price),
          desc: combo.desc || '',
          status: combo.status,
          sort: combo.sort || 0,
          dishesText: (combo.dishes || [])
            .map((d) => (d.qty > 1 ? d.name + '×' + d.qty : d.name))
            .join('、'),
          specCount: (combo.specGroupIds || []).length,
        }));
        this.setData({ combos, loading: false });
      })
      .catch((err) => {
        this.setData({ loading: false });
        app.toastError(err);
      })
      .then(() => {
        if (done) {
          done();
        }
      });
  },

  onEdit(e) {
    const combo = this.data.combos[e.currentTarget.dataset.index];
    if (combo) {
      wx.navigateTo({ url: '/pages/merchant/combo-edit?id=' + combo.id });
    }
  },

  onNew() {
    wx.navigateTo({ url: '/pages/merchant/combo-edit' });
  },

  onToggle(e) {
    const combo = this.data.combos[e.currentTarget.dataset.index];
    if (!combo) {
      return;
    }
    const next = combo.status === 'on' ? 'off' : 'on';
    request({ url: '/admin/combo/status', method: 'POST', data: { comboId: combo.id, status: next } })
      .then(() => {
        wx.showToast({ title: next === 'on' ? '已上架' : '已下架', icon: 'success' });
        this.load();
      })
      .catch((err) => app.toastError(err));
  },

  onDelete(e) {
    const combo = this.data.combos[e.currentTarget.dataset.index];
    if (!combo) {
      return;
    }
    wx.showModal({
      title: '删除盒饭',
      content: '删除「' + combo.name + '」？历史订单不受影响，但明天的菜单里会少这一项。',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        request({ url: '/admin/combo/delete', method: 'POST', data: { comboId: combo.id } })
          .then(() => {
            wx.showToast({ title: '已删除', icon: 'success' });
            this.load();
          })
          .catch((err) => app.toastError(err));
      },
    });
  },
});
