const app = getApp();
const { request } = require('../../utils/request');

/**
 * 区域管理（F-G01 的手机入口）。
 *
 * 区域是备菜与配送的归集维度，删不得随意：有地址或已被订单引用时接口返回 409，
 * 这里只做「提示 + 引导改成停用」，不在前端假装能删。
 */
Page({
  data: {
    regions: [],
    loading: false,
    form: null,
    saving: false,
  },

  onShow() {
    this.load();
  },

  onPullDownRefresh() {
    this.load(() => wx.stopPullDownRefresh());
  },

  load(done) {
    this.setData({ loading: true });
    request({ url: '/admin/region/list' })
      .then((list) => this.setData({ regions: list || [], loading: false }))
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

  onNew() {
    this.setData({ form: { id: '', name: '', remark: '', sort: this.data.regions.length + 1 } });
  },

  onEdit(e) {
    const region = this.data.regions[e.currentTarget.dataset.index];
    if (!region) {
      return;
    }
    this.setData({
      form: {
        id: region.id,
        name: region.name,
        remark: region.remark || '',
        sort: region.sort || 0,
      },
    });
  },

  onCancel() {
    this.setData({ form: null });
  },

  onInput(e) {
    const key = e.currentTarget.dataset.key;
    const form = Object.assign({}, this.data.form);
    form[key] = e.detail.value;
    this.setData({ form });
  },

  onSave() {
    const form = this.data.form;
    if (!form) {
      return;
    }
    if (!form.name || !form.name.trim()) {
      wx.showToast({ title: '请填写区域名称', icon: 'none' });
      return;
    }
    const payload = {
      id: form.id || null,
      name: form.name.trim(),
      remark: form.remark,
      sort: Number(form.sort) || 0,
    };
    this.setData({ saving: true });
    request({ url: '/admin/region/save', method: 'POST', data: payload })
      .then(() => {
        this.setData({ saving: false, form: null });
        wx.showToast({ title: '已保存', icon: 'success' });
        this.load();
      })
      .catch((err) => {
        this.setData({ saving: false });
        app.toastError(err);
      });
  },

  onToggle(e) {
    const region = this.data.regions[e.currentTarget.dataset.index];
    if (!region) {
      return;
    }
    const next = region.status === 'enabled' ? 'disabled' : 'enabled';
    request({
      url: '/admin/region/status',
      method: 'POST',
      data: { regionId: region.id, status: next },
    })
      .then(() => this.load())
      .catch((err) => app.toastError(err));
  },

  onDelete(e) {
    const region = this.data.regions[e.currentTarget.dataset.index];
    if (!region) {
      return;
    }
    wx.showModal({
      title: '删除区域',
      content: '有地址或历史订单引用的区域不能删除，只能停用。确定删除「' + region.name + '」？',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        request({ url: '/admin/region/delete', method: 'POST', data: { regionId: region.id } })
          .then(() => {
            wx.showToast({ title: '已删除', icon: 'success' });
            this.load();
          })
          .catch((err) => app.toastError(err));
      },
    });
  },
});
