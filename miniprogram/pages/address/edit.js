const app = getApp();
const { request } = require('../../utils/request');
const privacy = require('../../utils/privacy');

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
    /** 地址或所属区域已停用：只读，不给保存（入口在列表页已经藏了，这里是兜底） */
    readonly: false,
  },

  onLoad(options) {
    const id = options.id || '';
    this.setData({ id, isEdit: !!id });
    if (!id) {
      // 新增时用账号手机号预填，可改（需求 4.8）。
      // me.phone 已是掩码，回填明文必须单独取，否则用户「什么都没改」一保存就成了非法号码
      request({ url: '/auth/my-phone' })
        .then((phone) => {
          if (phone && !this.data.isEdit) {
            this.setData({ phone });
          }
        })
        .catch(() => {
          // 取不到就不预填，用户手填即可
        });
    }
    this.loadRegions(id);
  },

  loadRegions(editId) {
    Promise.all([
      request({ url: '/region/list' }),
      editId ? request({ url: '/address/mine' }) : Promise.resolve([]),
    ])
      .then(([regions, list]) => {
        const enabledRegions = regions || [];
        if (!editId) {
          this.setData({ regions: enabledRegions });
          return;
        }
        const addr = (list || []).find((a) => a.id === editId);
        if (!addr) {
          wx.showToast({ title: '地址不存在', icon: 'none' });
          return;
        }
        // /region/list 只给启用中的区域。地址所属区域被停用时必须把它补进选择器，
        // 否则 index 是 -1，保存直接报「请选择区域」——这是既有缺陷，本轮一并修
        const options = enabledRegions.slice();
        if (!options.some((r) => r.id === addr.regionId)) {
          options.unshift({ id: addr.regionId, name: (addr.regionName || '原区域') + '（已停用）' });
        }
        const index = options.findIndex((r) => r.id === addr.regionId);
        this.setData({
          regions: options,
          regionIndex: index,
          regionName: index >= 0 ? options[index].name : addr.regionName || '',
          contactName: addr.contactName || '',
          phone: addr.phone || '',
          detail: addr.detail || '',
          tag: addr.tag || '',
          pendingTip: addr.status !== 'active',
          // 服务端在 AddressView 里已算好：两侧都启用才可编辑
          readonly: addr.editable === false,
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

  /** 用途告知里的政策入口：跳 pages/privacy/index；页面为静态文本，兜底提示只在跳转失败时出现 */
  onOpenPrivacy() {
    wx.navigateTo({
      url: '/pages/privacy/index',
      fail: () => {
        wx.showToast({ title: '政策页打开失败，请稍后再试', icon: 'none' });
      },
    });
  },

  onSave() {
    if (this.data.saving) {
      return;
    }
    // 兜底：停用地址 / 停用区域下的地址，服务端也会拒；这里先给出人话提示
    if (this.data.readonly) {
      wx.showToast({ title: '该地址已停用，请联系商家处理', icon: 'none' });
      return;
    }
    // 兜底：从缓存页或历史页面栈进来时，仍然不让待审核用户写入
    if (app.blockIfNotApproved('账号审核中，通过后才能填写地址')) {
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
    if (!/^1[3-9]\d{9}$/.test(phone.trim())) {
      wx.showToast({ title: '手机号必填，11 位（1[3-9] 开头）', icon: 'none' });
      return;
    }
    if (!detail.trim()) {
      wx.showToast({ title: '请填写详细地址', icon: 'none' });
      return;
    }

    // 地址里含收货人姓名与电话，保存即收集：首次保存前告知用途，同意后继续提交。
    // 只拦「保存」，不拦 onLoad 的预填——那只是把用户自己的号码回填到表单里。
    privacy.ensureAgreed().then((ok) => {
      if (!ok) {
        wx.showToast({ title: '同意后才能保存地址，你可以先逛逛', icon: 'none' });
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
    });
  },
});
