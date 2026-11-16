const app = getApp();
const { request } = require('../../utils/request');
const { yuan, delta } = require('../../utils/format');
const cartUtil = require('../../utils/cart');

Page({
  data: {
    comboId: '',
    serveDate: '',
    loading: true,
    orderable: false,
    item: null,
    priceText: '0.00',
    totalText: '0.00',
    dishesText: '',
    /** 规格组展示数据，options 里带 priceText */
    groups: [],
    /** { [组下标]: optionId } */
    selected: {},
    qty: 1,
    maxQty: 99,
  },

  onLoad(options) {
    this.setData({ comboId: options.comboId || '', serveDate: options.serveDate || '' });
    this.load();
  },

  load() {
    if (!this.data.comboId) {
      this.setData({ loading: false });
      return;
    }
    request({ url: '/menu?serveDate=' + this.data.serveDate })
      .then((menu) => {
        const item = (menu.items || []).find((it) => it.comboId === this.data.comboId);
        if (!item) {
          wx.showToast({ title: '这道菜不在当天菜单里', icon: 'none' });
          this.setData({ loading: false });
          return;
        }
        // 默认选中每个必选组的第一个选项：让用户一进来就能直接下单，
        // 而不是点「加入」时才被拦下来提示「请选择规格」
        const selected = {};
        const groups = (item.specGroups || []).map((group, index) => {
          const options = (group.options || []).map((opt) => ({
            optionId: opt.optionId,
            name: opt.name,
            priceDelta: opt.priceDelta || 0,
            deltaText: delta(opt.priceDelta),
          }));
          if (group.required && options.length) {
            selected[index] = options[0].optionId;
          }
          return { name: group.name, required: !!group.required, options };
        });

        const maxQty = item.remainQty === null || item.remainQty === undefined
          ? 99 : Math.max(item.remainQty, 0);

        this.setData({
          loading: false,
          item,
          orderable: !!menu.orderable && maxQty > 0,
          dishesText: (item.dishes || []).map((d) => d.name + '×' + d.qty).join('、'),
          groups,
          selected,
          maxQty,
        });
        wx.setNavigationBarTitle({ title: item.name || '详情' });
        this.recalc();
      })
      .catch((err) => {
        this.setData({ loading: false });
        app.toastError(err);
      });
  },

  /** 选规格。同一组内互斥，再点一次取消（非必选组允许不选） */
  onSelectOption(e) {
    const groupIndex = Number(e.currentTarget.dataset.group);
    const optionId = e.currentTarget.dataset.id;
    const group = this.data.groups[groupIndex];
    const selected = Object.assign({}, this.data.selected);

    if (selected[groupIndex] === optionId) {
      if (group.required) {
        return;
      }
      delete selected[groupIndex];
    } else {
      selected[groupIndex] = optionId;
    }
    this.setData({ selected });
    this.recalc();
  },

  recalc() {
    const item = this.data.item;
    if (!item) {
      return;
    }
    let unit = Number(item.price || 0);
    this.data.groups.forEach((group, index) => {
      const optionId = this.data.selected[index];
      const opt = group.options.find((o) => o.optionId === optionId);
      if (opt) {
        unit += opt.priceDelta;
      }
    });
    this.setData({
      priceText: yuan(item.price),
      totalText: yuan(unit * this.data.qty),
    });
  },

  onMinus() {
    if (this.data.qty <= 1) {
      return;
    }
    this.setData({ qty: this.data.qty - 1 });
    this.recalc();
  },

  onPlus() {
    if (this.data.qty >= this.data.maxQty) {
      wx.showToast({ title: '最多 ' + this.data.maxQty + ' 份', icon: 'none' });
      return;
    }
    this.setData({ qty: this.data.qty + 1 });
    this.recalc();
  },

  onAdd() {
    if (!this.data.orderable) {
      wx.showToast({ title: '当前不可下单', icon: 'none' });
      return;
    }

    // 必选组没选就不让加：做出来是错的，退单成本比多一次点击高得多
    const missing = [];
    this.data.groups.forEach((group, index) => {
      if (group.required && !this.data.selected[index]) {
        missing.push(group.name);
      }
    });
    if (missing.length) {
      wx.showToast({ title: '请选择' + missing.join('、'), icon: 'none' });
      return;
    }

    const optionIds = [];
    const specDeltas = [];
    const specTexts = [];
    this.data.groups.forEach((group, index) => {
      const optionId = this.data.selected[index];
      if (!optionId) {
        return;
      }
      const opt = group.options.find((o) => o.optionId === optionId);
      if (!opt) {
        return;
      }
      optionIds.push(opt.optionId);
      specDeltas.push(opt.priceDelta);
      specTexts.push(group.name + '：' + opt.name);
    });

    const item = this.data.item;
    let cart = cartUtil.load();
    if (cart.serveDate && cart.serveDate !== this.data.serveDate) {
      cart = cartUtil.empty();
    }
    const next = cartUtil.addLine(cart, {
      comboId: item.comboId,
      name: item.name,
      cover: item.cover,
      price: Number(item.price || 0),
      qty: this.data.qty,
      optionIds,
      specDeltas,
      specText: specTexts.join('　'),
    });
    next.serveDate = this.data.serveDate;
    cartUtil.save(next);

    wx.showToast({ title: '已加入', icon: 'success', duration: 800 });
    setTimeout(() => wx.navigateBack(), 500);
  },
});
