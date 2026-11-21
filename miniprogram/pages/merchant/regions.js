const app = getApp();
const { request } = require('../../utils/request');
const { unusableText } = require('../../utils/address');

const MERCHANT_KEY = 'meal_merchant';

/**
 * 区域管理（F-G01 的手机入口）。
 *
 * 区域是备菜与配送的归集维度，删不得随意：有地址或已被订单引用时接口返回 409，
 * 这里只做「提示 + 引导改成停用」，不在前端假装能删。
 *
 * 区域停用不改地址数据：下面所有地址在判定时乘上区域状态就都不可用了（N-44）。
 * 抽屉里的「地址启停」是给商家做个别处置用的，归 user.address 权限管。
 */
Page({
  data: {
    regions: [],
    loading: false,
    form: null,
    saving: false,
    /** 展开地址清单的区域对象；null 表示抽屉收起 */
    drawer: null,
    drawerRows: [],
    drawerLoading: false,
    canToggleAddress: false,
  },

  onShow() {
    const saved = wx.getStorageSync(MERCHANT_KEY);
    const permissions = saved && saved.permissions;
    // undefined 是旧版本登录缓存（当时还没存权限），先放出来交给服务端挡
    const canToggleAddress = permissions === undefined
      || (permissions || []).indexOf('user.address') >= 0;
    this.setData({ canToggleAddress });
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
    if (next === 'enabled') {
      this.postStatus(region, next);
      return;
    }
    const count = region.addressCount || 0;
    const tail = count > 0 ? '该区域下有 ' + count + ' 个地址，停用后它们都不能用于新配送单（已下的订单不受影响）。' : '';
    wx.showModal({
      title: '停用区域',
      content: tail + '地址数据不会被修改，区域恢复启用后自动恢复。确定停用「' + region.name + '」？',
      success: (res) => {
        if (res.confirm) {
          this.postStatus(region, next);
        }
      },
    });
  },

  postStatus(region, next) {
    request({
      url: '/admin/region/status',
      method: 'POST',
      data: { regionId: region.id, status: next },
    })
      .then(() => this.load())
      .catch((err) => app.toastError(err));
  },

  /* ==================== 区域下的地址清单 ==================== */

  onOpenAddresses(e) {
    const region = this.data.regions[e.currentTarget.dataset.index];
    if (!region) {
      return;
    }
    this.setData({ drawer: region, drawerRows: [] });
    this.loadAddresses(region.id);
  },

  onCloseDrawer() {
    this.setData({ drawer: null, drawerRows: [] });
  },

  loadAddresses(regionId) {
    this.setData({ drawerLoading: true });
    // 复用跨用户地址队列，只加 regionId 条件
    request({ url: '/admin/address/list', data: { regionId, page: 1, pageSize: 100 } })
      .then((result) => {
        const rows = ((result && result.list) || []).map((row) => Object.assign({}, row, {
          statusText: { active: '可用', pending: '待确认', rejected: '已驳回' }[row.status] || row.status,
          unusableText: row.usable ? '' : unusableText(row),
        }));
        this.setData({ drawerRows: rows, drawerLoading: false });
      })
      .catch((err) => {
        this.setData({ drawerLoading: false });
        app.toastError(err);
      });
  },

  onToggleAddress(e) {
    const row = this.data.drawerRows[e.currentTarget.dataset.index];
    if (!row) {
      return;
    }
    if (!row.enabled) {
      wx.showModal({
        title: '启用地址',
        content: '启用后该地址恢复可用。如果它所属的区域仍是停用状态，则依然不能下单。确定启用？',
        success: (res) => {
          if (res.confirm) {
            this.postAddressEnabled(row, true);
          }
        },
      });
      return;
    }
    wx.showModal({
      title: '停用地址',
      content: '停用后该地址不能再用于新配送单（已下的订单不受影响）。停用原因可填，用户端会显示。',
      editable: true,
      placeholderText: '如：地址已拆迁 / 用户要求暂停配送（可不填）',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        const reason = (res.content || '').trim();
        if (reason.length > 200) {
          wx.showToast({ title: '停用原因最多 200 字', icon: 'none' });
          return;
        }
        this.postAddressEnabled(row, false, reason);
      },
    });
  },

  postAddressEnabled(row, enabled, reason) {
    request({
      url: '/admin/address/enabled',
      method: 'POST',
      data: { addressId: row.id, enabled, reason: reason || null },
    })
      .then(() => {
        wx.showToast({ title: enabled ? '已启用' : '已停用', icon: 'success' });
        if (this.data.drawer) {
          this.loadAddresses(this.data.drawer.id);
        }
        this.load();
      })
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
