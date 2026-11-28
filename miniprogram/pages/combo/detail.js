const app = getApp();
const { request } = require('../../utils/request');
const { yuan, delta, assetUrl, soldBadge } = require('../../utils/format');
const cartUtil = require('../../utils/cart');

Page({
  data: {
    comboId: '',
    serveDate: '',
    loading: true,
    orderable: false,
    /** 整份菜单的状态：与点餐页同口径，用来把「为什么不能下单」说清楚，而不是笼统一句「当前不可下单」 */
    cutoff: false,
    closed: false,
    /** 浏览态：从「全部菜单」进来的、不在当日菜单里的盒饭，只看不能买（需求 4.5） */
    browse: false,
    desc: '',
    item: null,
    priceText: '0.00',
    totalText: '0.00',
    dishesText: '',
    /** 规格组展示数据，options 里带 priceText */
    groups: [],
    /** { [组下标]: optionId } */
    selected: {},
    qty: 1,
    /** 加号上限：加载前先按 1，避免配置还没到手时能一路加到 99 */
    maxQty: 1,
    /** 审核已通过：待审核用户只能看，按钮置灰（需求 3.1 / 4.17） */
    approved: true,
  },

  onLoad(options) {
    const me = app.globalData.me;
    this.setData({
      comboId: options.comboId || '',
      serveDate: options.serveDate || '',
      approved: !me || me.auditStatus === 'approved',
    });
    this.load();
  },

  load() {
    if (!this.data.comboId) {
      this.setData({ loading: false });
      return;
    }
    // 配置与菜单并行拉，但必须等配置到手再渲染：加号上限来自「单个盒饭最多份数」，
    // 冷启动直接进这页（分享卡片、扫码、消息跳转、购物车跳详情）时 globalData 里还是空的，
    // 只读缓存会按 99 兜底，用户能一路加到 99，提交时才被服务端打回。
    Promise.all([
      app.loadConfig(),
      request({ url: '/menu?serveDate=' + this.data.serveDate }),
    ])
      .then(([, menu]) => {
        const item = (menu.items || []).find((it) => it.comboId === this.data.comboId);
        if (item) {
          // cutoff / closed 一起传下去：只传 orderable 的话，页面上说不出「已截单」还是「停业」
          this.renderItem(item, !!menu.orderable, false, !!menu.cutoff, !!menu.closed);
          return;
        }
        // 不在当日菜单：从「全部菜单」点进来的，退到盒饭库浏览（需求 4.5：进详情可看图文）
        this.loadFromAll();
      })
      .catch(() => {
        // 当日菜单拉不到（日期越界等）：同样退到盒饭库浏览
        this.loadFromAll();
      });
  },

  /** 从盒饭库加载：只渲染，不给下单 */
  loadFromAll() {
    return request({ url: '/combo/all' })
      .then((list) => {
        const item = (list || []).find((it) => it.comboId === this.data.comboId);
        if (!item) {
          wx.showToast({ title: '盒饭不存在或已下架', icon: 'none' });
          this.setData({ loading: false });
          return;
        }
        this.renderItem(item, false, true);
      })
      .catch((err) => {
        this.setData({ loading: false });
        app.toastError(err);
      });
  },

  /** 渲染一份数据。browse = 浏览态（隐藏份数与加购，展示简介） */
  renderItem(item, orderable, browse, cutoff, closed) {
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

    // 加号上限 = min(后台配置的「单个盒饭最多份数」, 当日剩余份数) - 车里已有的该盒饭份数。
    // 配置口径是「单个盒饭合计」：车里已有 4 份，这次最多只能再加 1 份，
    // 否则两次加购能凑出 2N 份（换主食就是两行，逐行都合规但合计超了）。
    // 配置请求失败时按 99 兜底：前端只是提前拦，真正的判据在服务端，
    // 宁可让用户点到提交被拒，也不能让页面连一份都加不了。
    const configLimit = Number((app.globalData.config || {}).orderMaxQtyPerItem) || 99;
    const stockLimit = item.remainQty === null || item.remainQty === undefined
      ? configLimit
      : Math.max(item.remainQty, 0);
    const cart = cartUtil.load();
    // 过期车等于空车：onAdd 里也是这么处理的，两处口径要一致
    const inCart = cartUtil.isStale(cart) ? 0 : cartUtil.qtyOfCombo(cart, item.comboId);
    const remain = Math.max(Math.min(configLimit, stockLimit) - inCart, 0);
    const maxQty = browse ? configLimit : remain;

    // 库存展示口径与点餐页一致：限量「已订 M / 共 N」+ 进度条，不限量只报已订份数
    const limit = item.limitQty === null || item.limitQty === undefined ? null : Number(item.limitQty);
    const sold = Number(item.soldQty || 0);
    const unlimited = limit === null;

    this.setData({
      loading: false,
      item,
      /** 封面单独算一份完整地址：item.cover 是相对路径，还要用于加购，不能就地改 */
      coverUrl: assetUrl(item.cover),
      orderable: orderable && maxQty > 0,
      // 从「全部菜单」进来时没有菜单级状态，两个都落 false，按钮文案仍由售罄决定
      cutoff: !!cutoff,
      closed: !!closed,
      browse: !!browse,
      desc: item.desc || '',
      dishesText: (item.dishes || []).map((d) => d.name + '×' + d.qty).join('、'),
      groups,
      selected,
      maxQty,
      stockText: unlimited ? '已订 ' + sold + ' 份 / 不限量' : '已订 ' + sold + ' / 共 ' + limit,
      stockPercent: unlimited ? 0 : (limit > 0 ? Math.min(100, Math.floor((sold / limit) * 100)) : 100),
      unlimited,
      soldOut: !unlimited && sold >= limit,
      /** 车里这个盒饭已经攒到上限，本次一份都加不了（与售罄区分开：这不是没货） */
      cartFull: !browse && remain <= 0,
      monthSoldText: soldBadge(item.monthSoldQty),
    });
    wx.setNavigationBarTitle({ title: item.name || '详情' });
    this.recalc();
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
      wx.showToast({
        title: this.data.cartFull ? '这个盒饭已加到上限' : '最多 ' + this.data.maxQty + ' 份',
        icon: 'none',
      });
      return;
    }
    this.setData({ qty: this.data.qty + 1 });
    this.recalc();
  },

  onAdd() {
    if (app.blockIfNotApproved('账号审核中，通过后才能选餐')) {
      return;
    }
    if (!this.data.orderable) {
      // 与点餐页 isOnQuickAdd 同口径同顺序：整份菜单的原因（停业 > 截单）先说，
      // 再退到「这款没了」，然后才是「车里这个盒饭已经加满」，最后才是兜底的「当前不可下单」
      const title = this.data.closed
        ? '该供餐日暂停供应'
        : (this.data.cutoff
          ? '当日已截单'
          : (this.data.soldOut
            ? '当前菜品已售罄'
            : (this.data.cartFull ? '这个盒饭已加到上限' : '当前不可下单')));
      wx.showToast({ title, icon: 'none' });
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
    // 只有跨 0 点才算过期；本页的 serveDate 只是这盒饭属于哪天，不用来清车
    if (cartUtil.isStale(cart)) {
      cart = cartUtil.empty();
    }
    // 一单最多几种盒饭：按盒饭去重，与服务端同一口径（同盒饭换主食只算一种）。
    // 读不到配置就不拦（服务端仍会判定），避免配置请求失败时连菜都加不了。
    const maxKinds = Number((app.globalData.config || {}).orderMaxItemKinds) || 0;
    if (maxKinds > 0) {
      const kinds = [];
      cart.items.forEach((line) => {
        if (kinds.indexOf(line.comboId) < 0) {
          kinds.push(line.comboId);
        }
      });
      // 车里已有这个盒饭（换个主食再加一份）不算新增一种
      if (kinds.indexOf(item.comboId) < 0 && kinds.length >= maxKinds) {
        wx.showToast({ title: '一单最多 ' + maxKinds + ' 种盒饭', icon: 'none' });
        return;
      }
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
