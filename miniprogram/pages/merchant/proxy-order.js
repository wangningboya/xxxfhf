const app = getApp();
const { request } = require('../../utils/request');
const { yuan, parseList, dateStr, addDays } = require('../../utils/format');

/**
 * 代客下单（F-H06）。
 *
 * 供餐日只允许明天 —— 与用户自助下单一致，菜单也只发布明天与后天。
 * 截单时间对商家不设限：顾客临时微信说要一份，往往已经过了截单点，
 * 这正是代客下单的常用场景；停业与限量仍由服务端把关。
 */
Page({
  data: {
    serveDate: '',
    /* ---- 用户 ---- */
    userKeyword: '',
    users: [],
    userLoading: false,
    selectedUser: null,
    /* ---- 菜单 ---- */
    menuItems: [],
    menuLoading: false,
    /* ---- 规格弹层 ---- */
    editing: null,
    editQty: 1,
    editOptions: {},
    editPriceText: '0.00',
    /* ---- 已选 ---- */
    lines: [],
    totalText: '0.00',
    /* ---- 交付 ---- */
    deliveryType: 'pickup',
    slots: [],
    pickupSlot: '',
    addresses: [],
    addressId: '',
    remark: '',
    submitting: false,
  },

  onLoad() {
    this.setData({ serveDate: addDays(dateStr(), 1) });
    this.loadMenu();
    this.searchUsers();
    // 默认是自取，先把时段取回来，商家点开就能选
    this.loadSlots();
  },

  /* ---------------- 用户 ---------------- */

  onKeyword(e) {
    this.setData({ userKeyword: e.detail.value });
    clearTimeout(this.kwTimer);
    this.kwTimer = setTimeout(() => this.searchUsers(), 350);
  },

  searchUsers() {
    this.setData({ userLoading: true });
    const kw = encodeURIComponent(this.data.userKeyword || '');
    request({ url: '/admin/user/list?auditStatus=approved&page=1&pageSize=20&keyword=' + kw })
      // 分页响应的字段是 list / total / page / pageSize，不是 MyBatis-Plus 的 records
      .then((page) => this.setData({ users: (page && page.list) || [], userLoading: false }))
      .catch((err) => {
        this.setData({ userLoading: false });
        app.toastError(err);
      });
  },

  onPickUser(e) {
    const user = this.data.users[e.currentTarget.dataset.index];
    if (!user) {
      return;
    }
    this.setData({ selectedUser: user, addressId: '', addresses: [] });
    this.loadAddresses(user.userId);
  },

  clearUser() {
    this.setData({ selectedUser: null, addresses: [], addressId: '' });
  },

  loadAddresses(userId) {
    request({ url: '/admin/address/list?userId=' + userId })
      .then((list) => {
        const addresses = (list || []).filter((a) => a.status === 'active');
        this.setData({
          addresses,
          addressId: addresses.length > 0 ? addresses[0].id : '',
        });
      })
      .catch(() => this.setData({ addresses: [], addressId: '' }));
  },

  /* ---------------- 菜单 ---------------- */

  loadMenu() {
    this.setData({ menuLoading: true });
    request({ url: '/menu?serveDate=' + this.data.serveDate })
      .then((menu) => {
        const items = ((menu && menu.items) || []).map((item) => ({
          comboId: item.comboId,
          name: item.name,
          cover: item.cover,
          price: item.price,
          priceText: yuan(item.price),
          dishesText: (item.dishes || [])
            .map((d) => (d.qty > 1 ? d.name + '×' + d.qty : d.name))
            .join('、'),
          specGroups: item.specGroups || [],
          remainQty: item.remainQty,
          soldOut: item.remainQty !== null && item.remainQty <= 0,
        }));
        this.setData({ menuItems: items, menuLoading: false });
      })
      .catch((err) => {
        this.setData({ menuLoading: false });
        app.toastError(err);
      });
  },

  /* ---------------- 规格弹层 ---------------- */

  onOpenItem(e) {
    const item = this.data.menuItems[e.currentTarget.dataset.index];
    if (!item || item.soldOut) {
      wx.showToast({ title: '这份盒饭今天已经卖完了', icon: 'none' });
      return;
    }
    // 单选组默认选中第一项，避免商家漏选后提交被服务端驳回
    const editOptions = {};
    (item.specGroups || []).forEach((group) => {
      if (group.required && group.options && group.options.length > 0) {
        editOptions[group.name] = group.options[0].optionId;
      }
    });
    const editing = Object.assign({}, item, {
      groups: (item.specGroups || []).map((group) => ({
        name: group.name,
        required: !!group.required,
        options: (group.options || []).map((opt) => ({
          optionId: opt.optionId,
          name: opt.name,
          priceDelta: opt.priceDelta || 0,
          deltaText: opt.priceDelta ? (opt.priceDelta > 0 ? '+' : '') + yuan(opt.priceDelta) : '',
          checked: editOptions[group.name] === opt.optionId,
        })),
      })),
    });
    this.setData({ editing, editQty: 1, editOptions }, () => this.recalcEditPrice());
  },

  onCloseEdit() {
    this.setData({ editing: null });
  },

  onQty(e) {
    const step = Number(e.currentTarget.dataset.step);
    const qty = this.data.editQty + step;
    if (qty < 1) {
      return;
    }
    if (this.data.editing.remainQty !== null && qty > this.data.editing.remainQty) {
      wx.showToast({ title: '最多还能点 ' + this.data.editing.remainQty + ' 份', icon: 'none' });
      return;
    }
    this.setData({ editQty: qty }, () => this.recalcEditPrice());
  },

  onPickOption(e) {
    const { group, option } = e.currentTarget.dataset;
    const editOptions = Object.assign({}, this.data.editOptions);
    editOptions[group] = option;
    // 勾选态直接算进 groups，WXML 里不用写 editOptions[group.name] 这种动态取值
    const editing = Object.assign({}, this.data.editing);
    editing.groups = (editing.groups || []).map((g) =>
      Object.assign({}, g, {
        options: (g.options || []).map((o) =>
          Object.assign({}, o, { checked: g.name === group ? o.optionId === option : o.checked })
        ),
      })
    );
    this.setData({ editOptions, editing }, () => this.recalcEditPrice());
  },

  /** 单价 = 盒饭价 + 所选规格差价之和；总价再乘份数 */
  recalcEditPrice() {
    const editing = this.data.editing;
    if (!editing) {
      return;
    }
    let unit = editing.price || 0;
    (editing.groups || []).forEach((group) => {
      const picked = this.data.editOptions[group.name];
      const opt = (group.options || []).filter((o) => o.optionId === picked)[0];
      if (opt) {
        unit += opt.priceDelta || 0;
      }
    });
    this.setData({ editPriceText: yuan(unit * this.data.editQty) });
  },

  onConfirmEdit() {
    const editing = this.data.editing;
    if (!editing) {
      return;
    }
    // 必选组没选中的，直接拦住 —— 服务端也会校验，但没必要让商家等到报错
    const missing = (editing.groups || []).filter(
      (group) => group.required && !this.data.editOptions[group.name]
    );
    if (missing.length > 0) {
      wx.showToast({ title: '请选择' + missing[0].name, icon: 'none' });
      return;
    }
    const optionIds = [];
    const specText = [];
    (editing.groups || []).forEach((group) => {
      const picked = this.data.editOptions[group.name];
      const opt = (group.options || []).filter((o) => o.optionId === picked)[0];
      if (opt) {
        optionIds.push(opt.optionId);
        specText.push(opt.name);
      }
    });

    let unit = editing.price || 0;
    (editing.groups || []).forEach((group) => {
      const opt = (group.options || []).filter((o) => o.optionId === this.data.editOptions[group.name])[0];
      if (opt) {
        unit += opt.priceDelta || 0;
      }
    });

    const lines = this.data.lines.slice();
    lines.push({
      key: editing.comboId + '|' + optionIds.join(','),
      comboId: editing.comboId,
      name: editing.name,
      optionIds,
      specText: specText.join(' / '),
      qty: this.data.editQty,
      unit,
      amount: unit * this.data.editQty,
      unitText: yuan(unit),
      amountText: yuan(unit * this.data.editQty),
    });
    this.setData({ lines, editing: null }, () => this.recalcTotal());
  },

  onRemoveLine(e) {
    const index = Number(e.currentTarget.dataset.index);
    const lines = this.data.lines.slice();
    lines.splice(index, 1);
    this.setData({ lines }, () => this.recalcTotal());
  },

  recalcTotal() {
    const total = this.data.lines.reduce((sum, line) => sum + line.amount, 0);
    this.setData({ totalText: yuan(total) });
  },

  /* ---------------- 交付 ---------------- */

  onDeliveryType(e) {
    this.setData({ deliveryType: e.currentTarget.dataset.type });
    if (this.data.deliveryType === 'pickup' && this.data.slots.length === 0) {
      this.loadSlots();
    }
  },

  loadSlots() {
    request({ url: '/config' })
      .then((cfg) => {
        const slots = parseList((cfg && cfg.pickupSlots) || '[]');
        this.setData({ slots, pickupSlot: slots.length > 0 ? slots[0] : '' });
      })
      .catch(() => this.setData({ slots: [] }));
  },

  onPickSlot(e) {
    this.setData({ pickupSlot: this.data.slots[e.currentTarget.dataset.index] });
  },

  onPickAddress(e) {
    this.setData({ addressId: this.data.addresses[e.currentTarget.dataset.index].id });
  },

  onRemark(e) {
    this.setData({ remark: e.detail.value });
  },

  onGoAddress() {
    if (!this.data.selectedUser) {
      return;
    }
    wx.navigateTo({ url: '/pages/merchant/users?userId=' + this.data.selectedUser.userId });
  },

  /* ---------------- 提交 ---------------- */

  onSubmit() {
    const { selectedUser, lines, deliveryType, addressId, pickupSlot, serveDate } = this.data;
    if (!selectedUser) {
      wx.showToast({ title: '请先选择下单用户', icon: 'none' });
      return;
    }
    if (lines.length === 0) {
      wx.showToast({ title: '请先添加盒饭', icon: 'none' });
      return;
    }
    if (deliveryType === 'delivery' && !addressId) {
      wx.showToast({ title: '该用户还没有可用地址，请先维护', icon: 'none' });
      return;
    }

    this.setData({ submitting: true });
    request({
      url: '/admin/order/proxy',
      method: 'POST',
      data: {
        userId: selectedUser.userId,
        serveDate,
        deliveryType,
        addressId: deliveryType === 'delivery' ? addressId : null,
        pickupSlot: deliveryType === 'pickup' ? pickupSlot : null,
        remark: this.data.remark,
        items: lines.map((line) => ({
          comboId: line.comboId,
          qty: line.qty,
          optionIds: line.optionIds,
        })),
      },
    })
      .then((order) => {
        this.setData({ submitting: false, lines: [], totalText: '0.00' });
        wx.showModal({
          title: '已代下单',
          content:
            '订单号 ' + order.orderNo + '\n取餐码 ' + (order.pickupCode || '—') + '\n请提醒顾客付款',
          showCancel: false,
          success: () => this.loadMenu(),
        });
      })
      .catch((err) => {
        this.setData({ submitting: false });
        app.toastError(err);
      });
  },
});
