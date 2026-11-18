const app = getApp();
const { request } = require('../../utils/request');
const dict = require('../../utils/dict');

/**
 * 地址三态：待确认→通过 / 驳回，驳回必须给原因（用户端会原样显示）。
 * 这里是兜底文案，字典加载出来后以字典为准。
 */
const ADDRESS_STATUS_TEXT = {
  active: '可用',
  pending: '待确认',
  rejected: '已驳回',
};

function addressStatusText(status) {
  return dict.label('address_status', status, ADDRESS_STATUS_TEXT[status] || status);
}

/** 用户列表顶部的审核状态页签；「全部」由页面自己加 */
const FALLBACK_AUDIT = [
  { key: 'approved', label: '已通过' },
  { key: 'pending', label: '待审核' },
  { key: 'rejected', label: '已驳回' },
  { key: 'disabled', label: '已停用' },
];

function auditTabs() {
  const list = dict.tabs('audit_status', null, FALLBACK_AUDIT);
  return [{ key: '', label: '全部' }].concat(list);
}

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
    auditTabs: auditTabs(),
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
    // 冷启动首进时字典可能还没回来，到了再重渲染一次状态文案
    if (!dict.ready()) {
      dict.load().then(() => {
        this.setData({ auditTabs: auditTabs() });
        this.loadUsers();
      });
    }
  },

  /**
   * 代填 / 修改用户手机号。
   *
   * 用系统弹窗而不是自建浮层：地址表单已经在卡片里展开，再叠一层容易点错。
   * 校验规则与服务端一致（11 位大陆手机号），前端先挡一次省一个来回。
   */
  onEditPhone(e) {
    const user = this.data.users[e.currentTarget.dataset.index];
    if (!user) {
      return;
    }
    wx.showModal({
      title: '修改手机号',
      editable: true,
      // 列表里给的是掩码（138****1234），预填会把星号当成号码存回去，所以留空重填
      placeholderText: '11 位大陆手机号',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        const phone = (res.content || '').trim();
        if (!/^1[3-9]\d{9}$/.test(phone)) {
          wx.showToast({ title: '手机号应为 11 位（1[3-9] 开头）', icon: 'none' });
          return;
        }
        request({ url: '/admin/user/phone', method: 'POST', data: { userId: user.userId, phone } })
          .then(() => {
            wx.showToast({ title: '已保存', icon: 'success' });
            this.loadUsers();
          })
          .catch((err) => app.toastError(err));
      },
    });
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
        const users = ((page && page.list) || []).map((u) =>
          Object.assign({}, u, {
            auditText: dict.label('audit_status', u.auditStatus, u.auditStatus),
          })
        );
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
      .then((list) =>
        this.setData({
          addresses: (list || []).map((item) => ({
            ...item,
            statusText: addressStatusText(item.status),
          })),
        })
      )
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
        // 手机号必填（需求 4.8）。列表里的账号手机号是掩码，这里留空，
        // 再单独取一次明文回填；取不到就让商家自己填，绝不把 138****1234 存进去
        phone: '',
        detail: '',
        tag: '',
      },
      formRegionIndex: 0,
    });
    const userId = this.data.selectedUser.userId;
    request({ url: `/admin/user/${userId}/phone` })
      .then((phone) => {
        const form = this.data.form;
        if (phone && form && form.userId === userId && !form.id) {
          this.setData({ form: Object.assign({}, form, { phone }) });
        }
      })
      .catch(() => {
        // 无权限或还没填号：保持留空
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
    if (!form.phone || !/^1[3-9]\d{9}$/.test(form.phone.trim())) {
      wx.showToast({ title: '手机号必填，11 位（1[3-9] 开头）', icon: 'none' });
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

  onSetAddressStatus(e) {
    const index = e.currentTarget.dataset.index;
    const status = e.currentTarget.dataset.status;
    const address = this.data.addresses[index];
    if (!address) {
      return;
    }
    this.postAddressStatus(address.id, status);
  },

  /**
   * 驳回地址：必须填原因。
   *
   * 以前只有「可用 / 待确认」两态，商家想否掉一个错地址只能删掉，
   * 用户端表现为地址凭空消失、不知道为什么，于是再填一遍同样的错地址。
   */
  onRejectAddress(e) {
    const index = e.currentTarget.dataset.index;
    const address = this.data.addresses[index];
    if (!address) {
      return;
    }
    wx.showModal({
      title: '驳回地址',
      editable: true,
      placeholderText: '必填：哪里不对，用户会看到',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        const reason = (res.content || '').trim();
        if (!reason) {
          wx.showToast({ title: '请填写驳回原因', icon: 'none' });
          return;
        }
        this.postAddressStatus(address.id, 'rejected', reason);
      },
    });
  },

  postAddressStatus(addressId, status, reason) {
    request({
      url: '/admin/address/status',
      method: 'POST',
      data: { addressId, status, reason: reason || null },
    })
      .then(() => {
        wx.showToast({ title: status === 'active' ? '已通过' : '已更新', icon: 'success' });
        this.loadAddresses(this.data.selectedUser.userId);
      })
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
