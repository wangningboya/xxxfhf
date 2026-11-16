const app = getApp();
const { request } = require('../../utils/request');

/**
 * 用户与地址（F-H08）。
 *
 * 商家能看到群成员以及他们名下的地址，并可直接代维护。
 * 商家录的地址 status 直接是 active（可下单用）；用户自己提交的要在这里确认才生效。
 *
 * 地址必须挂区域 —— 备菜统计按区域汇总，没有区域的地址会让配送单落不进任何一格。
 */
Page({
  data: {
    keyword: '',
    auditStatus: '',
    users: [],
    loading: false,
    selectedUser: null,
    addresses: [],
    regions: [],
    /* 地址表单 */
    form: null,
    formRegionIndex: 0,
    saving: false,
  },

  onLoad(options) {
    this.focusUserId = options && options.userId ? options.userId : '';
    this.loadUsers();
    this.loadRegions();
  },

  onPullDownRefresh() {
    this.loadUsers(() => wx.stopPullDownRefresh());
  },

  loadRegions() {
    request({ url: '/region/list' })
      .then((list) => this.setData({ regions: list || [] }))
      .catch(() => this.setData({ regions: [] }));
  },

  onKeyword(e) {
    this.setData({ keyword: e.detail.value });
    clearTimeout(this.kwTimer);
    this.kwTimer = setTimeout(() => this.loadUsers(), 350);
  },

  onStatus(e) {
    this.setData({ auditStatus: e.currentTarget.dataset.status });
    this.loadUsers();
  },

  loadUsers(done) {
    this.setData({ loading: true });
    const kw = encodeURIComponent(this.data.keyword || '');
    const status = this.data.auditStatus ? '&auditStatus=' + this.data.auditStatus : '';
    request({ url: '/admin/user/list?page=1&pageSize=50&keyword=' + kw + status })
      .then((page) => {
        const users = (page && page.list) || [];
        this.setData({ users, loading: false });
        // 从代客下单页跳过来时，直接展开那位用户
        if (this.focusUserId) {
          const target = users.filter((u) => u.userId === this.focusUserId)[0];
          this.focusUserId = '';
          if (target) {
            this.openUser(target);
          }
        }
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

  openUser(user) {
    this.setData({ selectedUser: user, addresses: [], form: null });
    this.loadAddresses(user.userId);
  },

  onPickUser(e) {
    const user = this.data.users[e.currentTarget.dataset.index];
    if (!user) {
      return;
    }
    if (this.data.selectedUser && this.data.selectedUser.userId === user.userId) {
      this.setData({ selectedUser: null, addresses: [], form: null });
      return;
    }
    this.openUser(user);
  },

  loadAddresses(userId) {
    request({ url: '/admin/address/list?userId=' + userId })
      .then((list) => this.setData({ addresses: list || [] }))
      .catch((err) => app.toastError(err));
  },

  /* ---------------- 地址表单 ---------------- */

  onNewAddress() {
    const regions = this.data.regions;
    if (regions.length === 0) {
      wx.showToast({ title: '请先在区域管理里添加区域', icon: 'none' });
      return;
    }
    this.setData({
      form: {
        id: '',
        userId: this.data.selectedUser.userId,
        regionId: regions[0].id,
        contactName: '',
        phone: '',
        detail: '',
        tag: '',
      },
      formRegionIndex: 0,
    });
  },

  onEditAddress(e) {
    const address = this.data.addresses[e.currentTarget.dataset.index];
    if (!address) {
      return;
    }
    let index = this.data.regions.findIndex((r) => r.id === address.regionId);
    if (index < 0) {
      index = 0;
    }
    this.setData({
      form: {
        id: address.id,
        userId: address.userId,
        regionId: address.regionId,
        contactName: address.contactName,
        phone: address.phone,
        detail: address.detail,
        tag: address.tag || '',
      },
      formRegionIndex: index,
    });
  },

  onCancelForm() {
    this.setData({ form: null });
  },

  onRegionChange(e) {
    const index = Number(e.detail.value);
    const region = this.data.regions[index];
    const form = Object.assign({}, this.data.form, { regionId: region.id });
    this.setData({ form, formRegionIndex: index });
  },

  onFormInput(e) {
    const key = e.currentTarget.dataset.key;
    const form = Object.assign({}, this.data.form);
    form[key] = e.detail.value;
    this.setData({ form });
  },

  onSaveAddress() {
    const form = this.data.form;
    if (!form) {
      return;
    }
    if (!form.regionId) {
      wx.showToast({ title: '请选择区域', icon: 'none' });
      return;
    }
    if (!form.contactName || !form.contactName.trim()) {
      wx.showToast({ title: '请填写联系人', icon: 'none' });
      return;
    }
    if (!form.phone || !/^1\d{10}$/.test(form.phone.trim())) {
      wx.showToast({ title: '请填写 11 位手机号', icon: 'none' });
      return;
    }
    if (!form.detail || !form.detail.trim()) {
      wx.showToast({ title: '请填写详细地址', icon: 'none' });
      return;
    }

    this.setData({ saving: true });
    request({ url: '/admin/address/save', method: 'POST', data: form })
      .then(() => {
        wx.showToast({ title: '已保存', icon: 'success' });
        this.setData({ saving: false, form: null });
        this.loadAddresses(form.userId);
      })
      .catch((err) => {
        this.setData({ saving: false });
        app.toastError(err);
      });
  },

  onToggleAddress(e) {
    const address = this.data.addresses[e.currentTarget.dataset.index];
    if (!address) {
      return;
    }
    const next = address.status === 'active' ? 'pending' : 'active';
    request({
      url: '/admin/address/status',
      method: 'POST',
      data: { addressId: address.id, status: next },
    })
      .then(() => this.loadAddresses(this.data.selectedUser.userId))
      .catch((err) => app.toastError(err));
  },

  onDeleteAddress(e) {
    const address = this.data.addresses[e.currentTarget.dataset.index];
    if (!address) {
      return;
    }
    wx.showModal({
      title: '删除地址',
      content: '删除后顾客需要重新填写，确定吗？',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        request({ url: '/admin/address/delete', method: 'POST', data: { addressId: address.id } })
          .then(() => this.loadAddresses(this.data.selectedUser.userId))
          .catch((err) => app.toastError(err));
      },
    });
  },
});
