const app = getApp();
const { request } = require('../../utils/request');
const privacy = require('../../utils/privacy');
const { yuan, dateStr, addDays, dayLabel, assetUrl, soldBadge } = require('../../utils/format');
const cartUtil = require('../../utils/cart');
const share = require('../../utils/share');
const dict = require('../../utils/dict');

Page({
  data: {
    loading: true,
    shopName: '本店',
    shopNotice: '',
    serveTimeText: '',
    cutoffTime: '',
    /** pending / approved / rejected */
    auditStatus: 'pending',
    rejectReason: '',
    auditTitle: '',
    auditDesc: '',
    showAuditCard: false,
    reapplyLoading: false,
    /** 还没填昵称时弹填写浮层：商家审核列表靠昵称认人 */
    needNickName: false,
    nickNameInput: '',
    /** 微信审核判过违规的那段昵称：保存时比对，同一段不再提交 */
    nickRejectedText: '',
    savingNickName: false,

    /* ---------------- 菜单 ---------------- */
    /** 两个供餐日：明天可下单、后天只读（需求 4.5） */
    dates: [],
    activeDate: '',
    menu: { orderable: false, cutoff: false, closed: false, items: [] },
    menuLoading: false,
    /** 菜单项展示用（价格已转成元，避免 wxml 里做除法） */
    viewItems: [],
    /** 未按分类过滤的整份列表，切分类时不再重复算 */
    menuItems: [],
    /** 分类导航（左栏）：[{ key, label }]，首项是「全部」 */
    categoryTabs: [],
    /** 左栏只在「有 2 个及以上分类」时才渲染，只有一类等于没得筛 */
    showCategoryTabs: false,
    activeCategory: '',
    /** 第三个 Tab「全部菜单」：盒饭库全量，只浏览不直接加购 */
    allActive: false,
    allItems: [],
    allLoading: false,
    cartCount: 0,
  },

  onLoad(options) {
    // 群卡片的 shareTicket 在 onLoad 的 options 里，留给 bootstrap 尝试绑定群
    this.shareOptions = options || {};
  },

  onShow() {
    // 与首页一致：分享菜单要在每次显示时重开，否则 shareTicket 拿不到
    share.enableShareMenu();
    this.refreshCartBar();
    this.scheduleMidnight();
    if (this.loadingNow) {
      return;
    }
    this.loadingNow = true;
    // 热启动从群卡片进来时 onLoad 不会重跑，用 App.onShow 接住的那份参数覆盖 onLoad 留下的旧参数
    const shareEntry = app.takeShareEntryOptions();
    if (shareEntry) {
      this.shareOptions = shareEntry;
    }
    this.init();
  },

  /**
   * 从点餐页转发：落地页也用点餐页，群里的人点开直接看菜，少一次跳转。
   */
  onShareAppMessage() {
    return share.buildShareCard(this.data.shopName, '/pages/index/index');
  },

  onHide() {
    this.clearMidnightTimer();
  },

  onUnload() {
    this.clearMidnightTimer();
  },

  /**
   * 跨 0 点的定时器：页面开着也要当场作废购物车、把日期推进到新的一天。
   *
   * 只挂在点餐页（用户停留最久的入口页）；切走就清掉，回来由 onShow 重挂。
   * 后台挂起时定时器不一定准点触发，所以 onShow 的 refreshCartBar 还要再判一次。
   */
  scheduleMidnight() {
    this.clearMidnightTimer();
    const now = new Date();
    const next = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 1);
    this.midnightTimer = setTimeout(() => {
      this.midnightTimer = null;
      // refreshCartBar 里会作废过期购物车；init 会把日期推进到新的一天
      this.refreshCartBar();
      this.init();
      this.scheduleMidnight();
    }, next.getTime() - now.getTime());
  },

  clearMidnightTimer() {
    if (this.midnightTimer) {
      clearTimeout(this.midnightTimer);
      this.midnightTimer = null;
    }
  },

  init() {
    const today = dateStr();
    const tomorrow = addDays(today, 1);
    const dayAfter = addDays(today, 2);
    // 后天 Tab 仅在商家已发布后天菜单时出现（需求 4.5）：
    // 先只放明天，探到后天有菜单再追加，没发布就不占位不置灰
    this.setData({
      dates: [{ date: tomorrow, label: dayLabel(tomorrow, today, tomorrow) }],
    });

    app
      .bootstrap(this.shareOptions)
      .then((me) => {
        if (!me || !me.accessGranted) {
          // 不是本店群里的人：直接进引导页，首页不渲染内容
          wx.reLaunch({ url: '/pages/no-access/index' });
          return null;
        }
        this.applyMe(me);
        return app.loadConfig();
      })
      .then((config) => {
        if (config) {
          this.applyConfig(config);
        }
        // 保留用户当前选中的供餐日；跨过 0 点后它已经变成「今天」，
        // 不再是可看的日期，落回「明天」（小程序后台挂起时定时器不一定准点，兜在这里）
        const active = this.data.activeDate && this.data.activeDate > today
          ? this.data.activeDate
          : ((this.data.dates[0] && this.data.dates[0].date) || '');
        this.setData({ loading: false, activeDate: active });
        this.loadMenu(active);
        this.probeDayAfter(dayAfter, today, tomorrow);
      })
      .catch((err) => {
        this.setData({ loading: false });
        app.toastError(err);
      })
      .then(() => {
        this.loadingNow = false;
      });
  },

  /** 探测后天菜单是否已发布，是才追加「后天」Tab（不占位不置灰，需求 4.5） */
  probeDayAfter(dayAfter, today, tomorrow) {
    request({ url: '/menu?serveDate=' + dayAfter })
      .then((menu) => {
        if ((menu.items || []).length && this.data.dates.length === 1) {
          this.setData({
            dates: this.data.dates.concat([
              { date: dayAfter, label: dayLabel(dayAfter, today, tomorrow) },
            ]),
          });
        }
      })
      .catch(() => {
        // 后天菜单拉不到（还没排 / 已停业）就不显示这个 Tab，不弹错
      });
  },

  applyMe(me) {
    const status = me.auditStatus || 'pending';
    let title = '';
    let desc = '';

    if (status === 'pending') {
      title = '已提交申请，等待商家审核';
      desc = '审核通过后即可下单。商家审核后回到本页会自动更新。';
    } else if (status === 'rejected') {
      title = '审核未通过';
      desc = me.rejectReason || '请联系群管理员了解原因。';
    }

    // 浮层从「不显示」变成「显示」的那一次，才算首次露头
    const firstShow = !this.data.needNickName && !me.nickName;

    this.setData({
      auditStatus: status,
      rejectReason: me.rejectReason || '',
      auditTitle: title,
      auditDesc: desc,
      showAuditCard: status !== 'approved',
      // 微信已不返回真实昵称，只能靠用户自己填一次；没填就弹浮层
      needNickName: !me.nickName,
    });

    if (firstShow) {
      this.promptNickPrivacy();
    }
  },

  /**
   * 昵称浮层露出时同步给出隐私提示（P0-04 v1.10，方案 A）。
   *
   * 为什么提前到这一刻：原来的顺序是「点输入框 → 点选微信昵称 → 点保存 → 才被告知要同意」，
   * 用户会觉得填完才被拦，而且告知晚于收集。挪到浮层出现时，先告知、后填写。
   *
   * 只主动弹一次：`nickPrivacyPrompted` 是页面实例级标记，用户点过「暂不」后不再反复打扰
   * （P0-05 的口径）。保存时 `onSaveNickName` 里那道闸门保留，负责「不漏收集」。
   */
  promptNickPrivacy() {
    if (this.nickPrivacyPrompted) {
      return;
    }
    this.nickPrivacyPrompted = true;
    if (privacy.hasAgreed()) {
      return;
    }
    // P0-05：拒绝后 7 天内不在「浮层露出」这个时机主动弹，避免反复打扰。
    // 用户自己点「保存」时 onSaveNickName() 另有闸门，漏不掉告知，只是不在人家没动作时凑上去。
    if (privacy.declinedRecently()) {
      return;
    }
    // 不需要 then：这里没有后续动作，用户答完该填还是填，保存时会再校验一次
    privacy.ensureAgreed();
  },

  applyConfig(config) {
    this.setData({
      shopName: config.shopName || '本店',
      shopNotice: config.shopNotice || '',
      serveTimeText: config.serveTimeText || '',
      cutoffTime: config.cutoffTime || '',
    });
  },

  /* ==================== 菜单 ==================== */

  loadMenu(date) {
    if (!date) {
      return Promise.resolve();
    }
    this.setData({ menuLoading: true });
    return request({ url: '/menu?serveDate=' + date })
      .then((menu) => {
        const menuItems = (menu.items || []).map((item) => {
          // 库存展示：限量显示「已订 M / 共 N」并按 M/N 画进度条；
          // 不限量时 limitQty 为 null，没有 N，只报已订份数、不画进度条
          const limit = item.limitQty === null || item.limitQty === undefined ? null : Number(item.limitQty);
          const sold = Number(item.soldQty || 0);
          const unlimited = limit === null;
          return {
            comboId: item.comboId,
            name: item.name,
            cover: item.cover,
            coverUrl: assetUrl(item.cover),
            price: Number(item.price || 0),
            priceText: yuan(item.price),
            // 分类存值（bento / homemade …），只用来筛列表；显示名由字典解析，改显示名不用发版
            category: item.category || '',
            dishesText: (item.dishes || []).map((d) => d.name + '×' + d.qty).join('、'),
            hasSpec: (item.specGroups || []).length > 0,
            stockText: unlimited ? '已订 ' + sold + ' 份 / 不限量' : '已订 ' + sold + ' / 共 ' + limit,
            stockPercent: unlimited ? 0 : (limit > 0 ? Math.min(100, Math.floor((sold / limit) * 100)) : 100),
            unlimited,
            soldOut: !unlimited && sold >= limit,
            // 本月已售（按供餐日自然月、已接单及之后），0 份时为空串即不渲染角标
            monthSoldText: soldBadge(item.monthSoldQty),
          };
        });
        this.setData({ menu, menuItems });
        this.applyCategoryFilter();
        this.loadCategoryTabs();
      })
      .catch((err) => {
        app.toastError(err);
      })
      .then(() => {
        this.setData({ menuLoading: false });
      });
  },

  /* ==================== 商品分类筛选 ==================== */

  /**
   * 这份菜单里出现、但本地字典还不认识的分类存值。
   *
   * 菜单里已经有 `homemade`、字典里还没有这一项，只有两种可能：小程序本地缓存陈旧
   * （`/dict` 有 12 小时 TTL），或者服务端确实还没种这个类型。前者强制刷一次就好，
   * 后者只能先按存值显示 —— 但两种情况下左栏都不该整块消失。
   */
  unknownCategories() {
    const known = dict.items('combo_category').map((d) => d.itemValue);
    const unknown = [];
    this.data.menuItems.forEach((item) => {
      if (item.category && known.indexOf(item.category) === -1 && unknown.indexOf(item.category) === -1) {
        unknown.push(item.category);
      }
    });
    return unknown;
  },

  /**
   * 左栏分类：**以这份菜单里真实的商品为准**，字典只负责顺序与显示名（需求 4.22）。
   *
   * 之前这里直接拿 dict.tabs()，缓存里没有 combo_category 时左栏会整块消失 ——
   * 菜单里明明有商品。现在先统计菜单里出现的分类，再按字典顺序排列、用字典补显示名；
   * 字典没有的存值先按出现顺序排在后面、标签暂用存值，同时强制刷一次字典。
   * 只有 1 个分类时不渲染：一个页签的「筛选」只是占地方。
   */
  loadCategoryTabs() {
    const unknown = this.unknownCategories();
    if (unknown.length && !this.categoryDictRefreshed) {
      // 先别出左栏：直接渲染会闪一下「bento」这种存值，等字典回来一次成型
      this.categoryDictRefreshed = true;
      dict.load(true).then(() => this.renderCategoryTabs());
      return;
    }
    this.renderCategoryTabs();
    // 陈旧缓存先渲染，再静默刷新一次（不阻塞、不闪白）
    dict.load().then(() => this.renderCategoryTabs());
  },

  /** 按「菜单里出现的分类 ∩ 字典」算出左栏；字典缺项时用存值兜底，绝不把整栏吞掉 */
  renderCategoryTabs() {
    const present = [];
    this.data.menuItems.forEach((item) => {
      if (item.category && present.indexOf(item.category) === -1) {
        present.push(item.category);
      }
    });

    const dictItems = dict.items('combo_category');
    const known = dictItems.map((d) => d.itemValue);
    // 字典里有的按字典顺序排前面；字典里没有的先按出现顺序排在后面
    const tabs = [{ key: '', label: '全部' }]
      .concat(dictItems
        .filter((d) => present.indexOf(d.itemValue) !== -1)
        .map((d) => ({ key: d.itemValue, label: d.itemLabel })))
      .concat(present
        .filter((code) => known.indexOf(code) === -1)
        .map((code) => ({ key: code, label: code })));

    const show = tabs.length > 2;
    const active = this.data.activeCategory;
    // 当前选中的分类在这份菜单里没有商品时落回「全部」，否则会筛出一份永远空着的列表
    const keep = show && tabs.some((tab) => tab.key === active) ? active : '';
    this.setData({ categoryTabs: show ? tabs : [], showCategoryTabs: show, activeCategory: keep });
    this.applyCategoryFilter();
  },

  onSwitchCategory(e) {
    const key = e.currentTarget.dataset.key || '';
    if (key === this.data.activeCategory) {
      return;
    }
    this.setData({ activeCategory: key });
    this.applyCategoryFilter();
    // 切分类后把列表拉回顶部：左栏是 sticky 的，用户很可能已经滚到列表深处，
    // 停在原位会先看到一片空白（新分类比上一份短时尤其明显）
    wx.pageScrollTo({ selector: '.menu-split', duration: 200 });
  },

  /** 按当前分类过滤出要渲染的列表；切换供餐日不重置选择（需求 4.22） */
  applyCategoryFilter() {
    const all = this.data.menuItems;
    const active = this.data.activeCategory;
    this.setData({ viewItems: active ? all.filter((item) => item.category === active) : all });
  },

  /**
   * 切换供餐日。
   *
   * 后天只能看不能买，但仍要能看；切换日期只是换一份菜单看，
   * 与车里的餐无关 —— 购物车只在跨 0 点过期（需求 4.6、D-36）。
   */
  onSwitchDate(e) {
    const date = e.currentTarget.dataset.date;
    if (!date) {
      return;
    }
    // 从「全部菜单」切回来时，即使还是同一个供餐日也要生效
    if (!this.data.allActive && date === this.data.activeDate) {
      return;
    }
    this.setData({ activeDate: date, allActive: false });
    this.loadMenu(date);
  },

  /** 切到「全部菜单」Tab。全部菜单是浏览入口，点卡片进详情再按供餐日下单。 */
  onSwitchAll() {
    if (this.data.allActive) {
      return;
    }
    this.setData({ allActive: true });
    this.loadAllCombos();
  },

  loadAllCombos() {
    this.setData({ allLoading: true });
    return request({ url: '/combo/all' })
      .then((list) => {
        const allItems = (list || []).map((item) => ({
          comboId: item.comboId,
          name: item.name,
          cover: item.cover,
          coverUrl: assetUrl(item.cover),
          desc: item.desc || '',
          priceText: yuan(item.price),
          dishesText: (item.dishes || []).map((d) => d.name + '×' + d.qty).join('、'),
          tomorrowText: item.tomorrowSupplied ? '明日供应' : '暂不供应',
          monthSoldText: soldBadge(item.monthSoldQty),
        }));
        this.setData({ allItems });
      })
      .catch((err) => {
        app.toastError(err);
      })
      .then(() => {
        this.setData({ allLoading: false });
      });
  },

  /** 点卡片进详情：规格只能在那里选 */
  onOpenCombo(e) {
    const comboId = e.currentTarget.dataset.id;
    wx.navigateTo({
      url: '/pages/combo/detail?comboId=' + comboId + '&serveDate=' + this.data.activeDate,
    });
  },

  /**
   * 快速加购。
   *
   * 有规格组的盒饭不允许直接加 —— 规格必须让用户看到并选，否则做出来是错的。
   * 没有规格组的就直接 +1，省一次跳转。
   */
  onQuickAdd(e) {
    const comboId = e.currentTarget.dataset.id;
    const item = this.data.viewItems.find((v) => v.comboId === comboId);
    if (!item) {
      return;
    }
    // 待审核用户只能看，不能选：拦住要在这里，不能等他填完地址、提交订单才说
    if (app.blockIfNotApproved('账号审核中，通过后才能选餐')) {
      return;
    }
    if (!this.data.menu.orderable) {
      // 三种不可下单的原因要分别说清楚，否则用户只知道点不动、不知道为什么：
      // 停业 > 已截单（今天来不及了）> 其它（如后天的菜单，明天才开放下单）
      const menu = this.data.menu;
      wx.showToast({
        title: menu.closed
          ? '该供餐日暂停供应'
          : (menu.cutoff ? '当日已截单' : '当前不可下单'),
        icon: 'none',
      });
      return;
    }
    if (item.soldOut) {
      wx.showToast({ title: '当前菜品已售罄', icon: 'none' });
      return;
    }
    if (item.hasSpec) {
      this.onOpenCombo(e);
      return;
    }
    this.addToCart({
      comboId,
      name: item.name,
      cover: item.cover,
      price: item.price,
      qty: 1,
      optionIds: [],
      specDeltas: [],
      specText: '',
    });
  },

  addToCart(line) {
    let cart = cartUtil.load();
    // 跨 0 点后旧车已不可能提交，静默换成新车；切换日期不用清
    if (cartUtil.isStale(cart)) {
      cart = cartUtil.empty();
    }
    const next = cartUtil.addLine(cart, line);
    next.serveDate = this.data.activeDate;
    cartUtil.save(next);
    this.refreshCartBar();
    wx.showToast({ title: '已加入', icon: 'success', duration: 800 });
  },

  refreshCartBar() {
    cartUtil.expireIfStale();
    const cart = cartUtil.load();
    this.setData({ cartCount: cartUtil.count(cart) });
  },

  /* ==================== 导航 ==================== */

  onOpenCart() {
    if (!this.data.cartCount) {
      wx.showToast({ title: '还没有选餐', icon: 'none' });
      return;
    }
    wx.navigateTo({ url: '/pages/cart/index' });
  },

  onOpenMine() {
    // 「我的」已是 tab 页，navigateTo 跳 tab 页会静默失败
    wx.switchTab({ url: '/pages/mine/index' });
  },

  onOpenOrders() {
    wx.navigateTo({ url: '/pages/order/list' });
  },

  /* ==================== 审核 / 昵称 ==================== */

  /** 被驳回后重新提交申请 */
  onReapply() {
    if (this.data.reapplyLoading) {
      return;
    }
    this.setData({ reapplyLoading: true });

    request({ url: '/auth/reapply', method: 'POST' })
      .then(() => {
        wx.showToast({ title: '已重新提交', icon: 'success' });
        return app.refreshMe();
      })
      .then((me) => {
        this.applyMe(me);
      })
      .catch((err) => {
        app.toastError(err);
      })
      .then(() => {
        this.setData({ reapplyLoading: false });
      });
  },

  /**
   * 昵称输入。
   *
   * `type="nickname"` 的输入框，用户点键盘上方「使用微信昵称」时只保证触发 blur / change，
   * 手打时才有 input，所以三种事件都接同一个处理函数，删掉任何一种都会漏值。
   */
  onNickNameInput(e) {
    this.setData({ nickNameInput: (e.detail && e.detail.value) || '' });
  },

  /**
   * 微信侧的昵称审核结果（基础库 2.29.1+，`detail = { pass, timeout }`）。
   *
   * `pass === false` 时微信自己会弹「无法使用该昵称」，这里只记住被拒的那段文本，保存时比对。
   * 不记状态位：`blur` 与本次回调的先后顺序不保证，状态位会被 bindblur 冲掉；比对文本不受时序影响。
   * `timeout === true` 表示检测没跑完（官方未定义此时 pass 的取值），按放行处理，不记也不清。
   */
  onNickNameReview(e) {
    const detail = e.detail || {};
    if (detail.timeout) {
      return;
    }
    if (detail.pass === false) {
      this.setData({ nickRejectedText: (this.data.nickNameInput || '').trim() });
      return;
    }
    if (this.data.nickRejectedText) {
      this.setData({ nickRejectedText: '' });
    }
  },

  /** 保存昵称。成功即关浮层（applyMe 会重新判断是否需要填）。 */
  onSaveNickName() {
    if (this.data.savingNickName) {
      return;
    }
    const nickName = (this.data.nickNameInput || '').trim();
    if (!nickName) {
      wx.showToast({ title: '请填写昵称', icon: 'none' });
      return;
    }
    // 微信审核判过违规的同一段文本不再提交；改过字就放行，由服务端兜底
    if (nickName === this.data.nickRejectedText) {
      wx.showToast({ title: '该昵称无法使用，请换一个', icon: 'none' });
      return;
    }

    // 昵称是个人信息，首次保存前先告知用途；同意后接着走原来的保存流程
    privacy.ensureAgreed().then((ok) => {
      if (!ok) {
        wx.showToast({ title: '同意后才能保存昵称', icon: 'none' });
        return;
      }
      this.setData({ savingNickName: true });
      request({ url: '/auth/nickname', method: 'POST', data: { nickName } })
        .then(() => app.refreshMe())
        .then((me) => {
          this.applyMe(me);
          wx.showToast({ title: '已保存', icon: 'success' });
        })
        .catch((err) => {
          app.toastError(err);
        })
        .then(() => {
          this.setData({ savingNickName: false });
        });
    });
  },
});
