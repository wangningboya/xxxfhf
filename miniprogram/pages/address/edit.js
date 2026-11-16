const app = getApp();
const { request } = require('../../utils/request');

Page({
  data: {
    id: '',
    regions: [],
    regionIndex: -1,
    regionName: '',
    contactName: '',
    phone: '',
    detail: '',
    tag: '',
    tags: ['家', '公司'],
    saving: false,
    /** 编辑已有地址时提示「保存后要重新等商家确认」 */
    isEdit: false,
    pendingTip: false,
  },

  onLoad(options) {
    const id = options.id || '';
    this.setData({ id, isEdit: !!id });
    this.loadRegions(id);
  },

  loadRegions(editId) {
    Promise.all([
      request({ url: '/region/list' }),
      editId ? request({ url: '/address/mine' }) : Promise.resolve([]),
    ])
      .then(([regions, list]) => {
        this.setData({ regions: regions || [] });
        if (!editId) {
          return;
        }
        const addr = (list || []).find((a) => a.id === editId);
        if (!addr) {
          wx.showToast({ title: '地址不存在', icon: 'none' });
          return;
        }
        const index = (regions || []).findIndex((r) => r.id === addr.regionId);
        this.setData({
          regionIndex: index,
          regionName: addr.regionName || '',
          contactName: addr.contactName || '',
          phone: addr.phone || '',
          detail: addr.detail || '',
          tag: addr.tag || '',
          pendingTip: addr.status !== 'active',
        });
      })
      .catch((err) => {
        app.toastError(err);
      });
  },

  onRegionChange(e) {
    const index = Number(e.detail.value);
    const region = this.data.regions[index];
    this.setData({
      regionIndex: index,
      regionName: region ? region.name : '',
    });
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field;
    this.setData({ [field]: e.detail.value });
  },

  onPickTag(e) {
    const tag = e.currentTarget.dataset.tag;
    this.setData({ tag: this.data.tag === tag ? '' : tag });
  },

  onSave() {
    if (this.data.saving) {
      return;
    }
    const { regionIndex, contactName, phone, detail, id } = this.data;
    const region = this.data.regions[regionIndex];
    if (!region) {
      wx.showToast({ title: '请选择区域', icon: 'none' });
      return;
    }
    if (!contactName.trim()) {
      wx.showToast({ title: '请填写收货人', icon: 'none' });
      return;
    }
    if (!/^1\d{10}$/.test(phone.trim())) {
      wx.showToast({ title: '请填写正确的手机号', icon: 'none' });
      return;
    }
    if (!detail.trim()) {
      wx.showToast({ title: '请填写详细地址', icon: 'none' });
      return;
    }

    this.setData({ saving: true });
    request({
      url: '/address',
      method: 'POST',
      data: {
        id: id || null,
        regionId: region.id,
        contactName: contactName.trim(),
        phone: phone.trim(),
        detail: detail.trim(),
        tag: this.data.tag || null,
      },
    })
      .then(() => {
        wx.showToast({ title: '已提交，等商家确认', icon: 'none', duration: 2000 });
        setTimeout(() => wx.navigateBack(), 900);
      })
      .catch((err) => {
        app.toastError(err);
        this.setData({ saving: false });
      });
  },
});
