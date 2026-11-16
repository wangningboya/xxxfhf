const app = getApp();
const { request, getToken } = require('../../utils/request');
const { apiBase } = require('../../config');

/**
 * 菜品库（F-H10）：原料菜维护。
 *
 * 菜品没有价格 —— 价格只在盒饭上，散菜不单独售卖。这一点决定了本页只有名称与图片。
 */
Page({
  data: {
    keyword: '',
    status: '',
    dishes: [],
    loading: false,
    form: null,
    uploading: false,
    saving: false,
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
    request({ url: '/admin/dish/list?keyword=' + kw + status })
      .then((list) => this.setData({ dishes: list || [], loading: false }))
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
    this.setData({ form: { id: '', name: '', image: '', sort: this.data.dishes.length + 1 } });
  },

  onEdit(e) {
    const dish = this.data.dishes[e.currentTarget.dataset.index];
    if (!dish) {
      return;
    }
    this.setData({
      form: {
        id: dish.id,
        name: dish.name,
        image: dish.image || '',
        sort: dish.sort || 0,
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

  onChooseImage() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sizeType: ['compressed'],
      success: (res) => {
        this.setData({ uploading: true });
        wx.uploadFile({
          url: apiBase + '/upload',
          filePath: res.tempFiles[0].tempFilePath,
          name: 'file',
          header: { Authorization: 'Bearer ' + getToken() },
          success: (uploadRes) => {
            let body = {};
            try {
              body = JSON.parse(uploadRes.data || '{}');
            } catch (e) {
              body = {};
            }
            if (uploadRes.statusCode === 200 && body.code === 0 && body.data) {
              const form = Object.assign({}, this.data.form, { image: body.data.url });
              this.setData({ form, uploading: false });
            } else {
              this.setData({ uploading: false });
              app.toastError({ message: body.message || '上传失败' });
            }
          },
          fail: () => {
            this.setData({ uploading: false });
            app.toastError({ message: '上传失败，请检查网络' });
          },
        });
      },
    });
  },

  onSave() {
    const form = this.data.form;
    if (!form) {
      return;
    }
    if (!form.name || !form.name.trim()) {
      wx.showToast({ title: '请填写菜品名称', icon: 'none' });
      return;
    }
    this.setData({ saving: true });
    request({
      url: '/admin/dish/save',
      method: 'POST',
      data: {
        id: form.id || null,
        name: form.name.trim(),
        image: form.image || null,
        sort: form.sort === '' ? null : Number(form.sort),
      },
    })
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
    const dish = this.data.dishes[e.currentTarget.dataset.index];
    if (!dish) {
      return;
    }
    const next = dish.status === 'enabled' ? 'disabled' : 'enabled';
    request({ url: '/admin/dish/status', method: 'POST', data: { dishId: dish.id, status: next } })
      .then(() => this.load())
      .catch((err) => app.toastError(err));
  },

  onDelete(e) {
    const dish = this.data.dishes[e.currentTarget.dataset.index];
    if (!dish) {
      return;
    }
    wx.showModal({
      title: '删除菜品',
      content: '删除「' + dish.name + '」？被盒饭引用时不允许删除，只能停用。',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        request({ url: '/admin/dish/delete', method: 'POST', data: { dishId: dish.id } })
          .then(() => {
            wx.showToast({ title: '已删除', icon: 'success' });
            this.load();
          })
          .catch((err) => app.toastError(err));
      },
    });
  },
});
