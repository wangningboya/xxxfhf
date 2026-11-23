const app = getApp();
const { request, getToken } = require('../../utils/request');
const { apiBase } = require('../../config');
const { yuan, assetUrl } = require('../../utils/format');

/**
 * 盒饭编辑（F-H09 编辑部分）。
 *
 * 组成菜品只传 dishId 与份数，菜名由服务端按菜品库回填 —— 前端传名字的话，
 * 菜品改名后盒饭里会留着旧名字，备菜统计就对不上了。
 *
 * 价格在界面上按「元」输入，提交前换成「分」。计算只在分上做。
 */
Page({
  data: {
    id: '',
    name: '',
    priceYuan: '',
    cover: '',
    /** 预览用的完整地址；cover 存的是相对路径，提交时用它，别混成一个字段 */
    coverPreview: '',
    desc: '',
    sort: '',
    dishes: [],
    dishOptions: [],
    specGroups: [],
    selectedSpecs: [],
    /* 规格组：id -> 是否启用，WXML 里直接读 */
    specChecked: {},
    uploading: false,
    saving: false,
  },

  onLoad(options) {
    const id = (options && options.id) || '';
    this.setData({ id });
    this.loadDishes();
    this.loadSpecs();
    if (id) {
      this.loadCombo(id);
    }
  },

  loadCombo(id) {
    request({ url: '/admin/combo/detail?comboId=' + id })
      .then((combo) => {
        const selectedSpecs = combo.specGroupIds || [];
        const specChecked = {};
        selectedSpecs.forEach((specId) => {
          specChecked[specId] = true;
        });
        this.setData({
          name: combo.name || '',
          priceYuan: yuan(combo.price),
          cover: combo.cover || '',
          coverPreview: assetUrl(combo.cover),
          desc: combo.desc || '',
          sort: combo.sort === null || combo.sort === undefined ? '' : String(combo.sort),
          dishes: (combo.dishes || []).map((d) => ({
            dishId: d.dishId,
            name: d.name,
            qty: d.qty || 1,
          })),
          selectedSpecs,
          specChecked,
        }, () => this.syncSpecChecked());
      })
      .catch((err) => app.toastError(err));
  },

  loadDishes() {
    request({ url: '/admin/dish/list?status=enabled' })
      .then((list) => {
        const dishOptions = (list || []).map((dish) => ({
          dishId: dish.id,
          name: dish.name,
        }));
        this.setData({ dishOptions }, () => this.syncDishChecked());
      })
      .catch((err) => app.toastError(err));
  },

  loadSpecs() {
    request({ url: '/admin/spec/list-enabled' })
      .then((list) => {
        this.setData({ specGroups: list || [] }, () => this.syncSpecChecked());
      })
      .catch(() => this.setData({ specGroups: [] }));
  },

  /** 规格组的勾选态也算进数组里，WXML 不写 specChecked[item.id] 这种动态取值 */
  syncSpecChecked() {
    const specGroups = this.data.specGroups.map((group) =>
      Object.assign({}, group, { checked: !!this.data.specChecked[group.id] })
    );
    this.setData({ specGroups });
  },

  /** 已选菜品的勾选态算进 dishOptions，WXML 不写动态 key */
  syncDishChecked() {
    const picked = {};
    this.data.dishes.forEach((d) => {
      picked[d.dishId] = true;
    });
    const dishOptions = this.data.dishOptions.map((dish) =>
      Object.assign({}, dish, {
        checked: !!picked[dish.dishId],
        qty: (this.data.dishes.filter((d) => d.dishId === dish.dishId)[0] || {}).qty || 1,
      })
    );
    this.setData({ dishOptions });
  },

  /* ---------------- 基本信息 ---------------- */

  onInput(e) {
    const key = e.currentTarget.dataset.key;
    this.setData({ [key]: e.detail.value });
  },

  onChooseCover() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sizeType: ['compressed'],
      success: (res) => {
        const filePath = res.tempFiles[0].tempFilePath;
        this.setData({ uploading: true });
        wx.uploadFile({
          // 上传接口挂在 /admin/file 下（与后台网页同一入口），不是根路径的 /upload
          url: apiBase + '/admin/file/upload',
          filePath,
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
              this.setData({
                cover: body.data.url,
                coverPreview: assetUrl(body.data.url),
                uploading: false,
              });
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
      /**
       * 相册是微信的隐私接口：用户没同意隐私政策、或后台没声明「相册」时，
       * 这里会失败。原来没有 fail 回调，用户点「上传封面」界面毫无反应，
       * 看起来像按钮坏了 —— 必须给一句明确的提示。
       */
      fail: (err) => {
        const msg = (err && err.errMsg) || '';
        if (msg.indexOf('cancel') > -1) {
          // 用户自己关掉了选择器，不是错误，不打扰
          return;
        }
        console.warn('[上传封面] chooseMedia 失败', err);
        wx.showToast({
          title: '无法打开相册，请先同意隐私授权后重试',
          icon: 'none',
          duration: 2500,
        });
      },
    });
  },

  onClearCover() {
    this.setData({ cover: '', coverPreview: '' });
  },

  /* ---------------- 组成菜品 ---------------- */

  onToggleDish(e) {
    const index = Number(e.currentTarget.dataset.index);
    const dish = this.data.dishOptions[index];
    if (!dish) {
      return;
    }
    let dishes = this.data.dishes.slice();
    if (dish.checked) {
      dishes = dishes.filter((d) => d.dishId !== dish.dishId);
    } else {
      dishes.push({ dishId: dish.dishId, name: dish.name, qty: 1 });
    }
    this.setData({ dishes }, () => this.syncDishChecked());
  },

  onDishQty(e) {
    const dishId = e.currentTarget.dataset.id;
    const step = Number(e.currentTarget.dataset.step);
    const dishes = this.data.dishes.map((d) => {
      if (d.dishId !== dishId) {
        return d;
      }
      const qty = Math.max(1, (d.qty || 1) + step);
      return Object.assign({}, d, { qty });
    });
    this.setData({ dishes }, () => this.syncDishChecked());
  },

  /* ---------------- 规格组 ---------------- */

  onToggleSpec(e) {
    const specId = e.currentTarget.dataset.id;
    const specChecked = Object.assign({}, this.data.specChecked);
    if (specChecked[specId]) {
      delete specChecked[specId];
    } else {
      specChecked[specId] = true;
    }
    this.setData({
      specChecked,
      selectedSpecs: Object.keys(specChecked),
    }, () => this.syncSpecChecked());
  },

  /* ---------------- 保存 ---------------- */

  onSave() {
    const { id, name, priceYuan, desc, dishes, selectedSpecs, cover, sort } = this.data;
    if (!name || !name.trim()) {
      wx.showToast({ title: '请填写盒饭名称', icon: 'none' });
      return;
    }
    const price = Math.round(Number(priceYuan || 0) * 100);
    if (!price || price <= 0 || isNaN(price)) {
      wx.showToast({ title: '请填写正确的价格', icon: 'none' });
      return;
    }
    if (dishes.length === 0) {
      wx.showToast({ title: '至少选择一道菜', icon: 'none' });
      return;
    }

    this.setData({ saving: true });
    request({
      url: '/admin/combo/save',
      method: 'POST',
      data: {
        id: id || null,
        name: name.trim(),
        cover: cover || null,
        desc: desc || null,
        price,
        dishes: dishes.map((d) => ({ dishId: d.dishId, name: d.name, qty: d.qty || 1 })),
        specGroupIds: selectedSpecs,
        sort: sort === '' ? null : Number(sort),
      },
    })
      .then(() => {
        this.setData({ saving: false });
        wx.showToast({ title: '已保存', icon: 'success' });
        setTimeout(() => wx.navigateBack(), 600);
      })
      .catch((err) => {
        this.setData({ saving: false });
        app.toastError(err);
      });
  },
});
