const app = getApp();
const { request } = require('../../utils/request');
const { yuan, dateStr, addDays } = require('../../utils/format');
const dict = require('../../utils/dict');

/** 单批打印上限（张）。30 张按单张 1.5~2.5 秒已是商家愿意站着等的上限 */
const MAX_BATCH_PRINT = 30;

/**
 * 按套餐边界截断到 max 张。
 *
 * 不能简单地 `slice(0, 30)`：那会把「A 套餐 ×3」切成 2 张，剩下 1 张跑到下一批，
 * 结果页按「订单 × 套餐」聚合时就会显示成 2/3，商家分不清是漏打还是被截断。
 */
function limitPages(pages, max) {
  const kept = [];
  let i = 0;
  while (i < pages.length) {
    const first = pages[i];
    const group = [];
    while (i < pages.length
      && pages[i].orderId === first.orderId
      && pages[i].comboIndex === first.comboIndex) {
      group.push(pages[i]);
      i += 1;
    }
    if (kept.length + group.length > max) {
      break;
    }
    group.forEach((item) => kept.push(item));
  }
  // 第一组就超上限时至少打一组，否则会出现「点了打印什么都不打」
  if (!kept.length && pages.length) {
    pages.filter((item) => item.orderId === pages[0].orderId
      && item.comboIndex === pages[0].comboIndex).forEach((item) => kept.push(item));
  }
  return { kept, rest: pages.length - kept.length };
}

/** 兜底顺序与文案，字典加载出来后以字典为准（商家可改显示名与顺序） */
const FALLBACK_STATUS = [
  { key: 'pending_accept', label: '待接单' },
  { key: 'accepted', label: '已接单' },
  { key: 'ready', label: '待取餐' },
  { key: 'completed', label: '已完成' },
  { key: 'cancelled', label: '已取消' },
  { key: 'rejected', label: '已拒单' },
];

function buildTabs() {
  return dict.tabs('order_status', '全部', FALLBACK_STATUS);
}

Page({
  data: {
    tabs: buildTabs(),
    status: '',
    serveDate: '',
    keyword: '',
    list: [],
    selected: {},
    selectedCount: 0,
    page: 1,
    pageSize: 20,
    finished: false,
    loading: false,
    accepting: false,
    acceptText: '接单',
    /** 批量拒单（v1.53 / F-H12）：整批共用一条原因 */
    rejecting: false,
    rejectText: '拒单',
    /** 只看有退订申请的单。开启时请求不带供餐日，往期漏单也能捞到 */
    cancelOnly: false,
    /** 收款状态筛选（v1.53）：工作台「待核对收款 / 待退款」点进来时带上 */
    payStatus: '',
    /**
     * 打印模式。
     *
     * 与接单模式是两套可选规则，不能混：接单只对「待接单」有意义（服务端也会跳过其余状态），
     * 而补打标签在已接单、已出餐的订单上照样要做。所以勾选框的可选性跟着这个开关走。
     */
    printMode: false,
    printLoading: false,
    /** 打印进度层 */
    printing: false,
    paused: false,
    printedCount: 0,
    totalCount: 0,
    progressPercent: 0,
    printError: '',
    printText: '打印标签',
    /** 批量首张预览（需求 14.6）：出 30 张纸之前先看一眼版式 */
    previewVisible: false,
    previewSrc: '',
    previewLoading: false,
    previewLines: [],
    previewTotal: 0,
    previewRest: 0,
    previewPages: [],
    previewRestPages: [],
  },

  onLoad(options) {
    this.setData({
      serveDate: options.serveDate || addDays(dateStr(), 1),
      status: options.status || '',
      cancelOnly: options.cancelOnly === '1',
      payStatus: options.payStatus || '',
    });
  },

  onShow() {
    this.setData({ tabs: buildTabs() });
    dict.load().then(() => this.setData({ tabs: buildTabs() }));
    this.reload();
  },

  /** 页面销毁时终止打印作业：作业还在跑但页面没了，回调会打到已销毁的页面上 */
  onUnload() {
    if (this.runner) {
      this.runner.cancel();
      this.runner = null;
    }
  },

  onReachBottom() {
    if (this.data.finished || this.data.loading) {
      return;
    }
    this.load(this.data.page + 1);
  },

  reload() {
    this.setData({
      list: [],
      page: 1,
      finished: false,
      selected: {},
      selectedCount: 0,
      acceptText: '接单',
      rejectText: '拒单',
    });
    return this.load(1);
  },

  load(page) {
    if (this.data.loading) {
      return Promise.resolve();
    }
    this.setData({ loading: true });
    const query = [
      'page=' + page,
      'pageSize=' + this.data.pageSize,
    ];
    // 只看退订、或按收款状态筛时都不带供餐日：要捞的是漏处理的单 / 没核对的钱，
    // 往期的也得算进来（带上「明天」等于先把要找的那批排除掉）
    if (!this.data.cancelOnly && !this.data.payStatus) {
      query.unshift('serveDate=' + this.data.serveDate);
    }
    if (this.data.cancelOnly) {
      query.push('cancelRequested=true');
    }
    if (this.data.payStatus) {
      query.push('payStatus=' + this.data.payStatus);
    }
    if (this.data.status) {
      query.push('status=' + this.data.status);
    }
    if (this.data.keyword) {
      query.push('keyword=' + encodeURIComponent(this.data.keyword));
    }

    return request({ url: '/admin/order/list?' + query.join('&') })
      .then((result) => {
        const rows = (result.list || []).map((row) => this.toRow(row));
        this.setData({
          list: page === 1 ? rows : this.data.list.concat(rows),
          page,
          finished: rows.length < this.data.pageSize,
        });
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ loading: false }));
  },

  toRow(row) {
    const order = row.order || {};
    const print = row.print || { printedPages: 0, totalPages: 0, printState: 'none', lastPrintedAt: '' };
    const payStatus = order.payStatus;
    /**
     * 能不能打标签（服务端还会再判一次）：
     * 取消、退款中、待退款、用户申请取消的单打出来就是废纸。
     */
    const printable = order.status !== 'cancelled'
      && payStatus !== 'refunded'
      && payStatus !== 'refunding'
      && !order.cancelRequested;
    return {
      orderId: order.orderId,
      orderNo: order.orderNo,
      status: order.status,
      statusLabel: order.statusLabel,
      tagClass: 'tag-' + (order.statusTag || dict.tagType('order_status', order.status)),
      payStatus,
      payStatusLabel: order.payStatusLabel,
      totalText: yuan(order.totalAmount),
      pickupCode: order.pickupCode,
      pickupSlot: order.pickupSlot,
      deliveryType: order.deliveryType,
      regionName: order.address ? order.address.regionName : '',
      userNickName: row.userNickName || '群成员',
      userPhone: row.userPhone || '',
      summary: (order.items || []).map((it) => it.name + '×' + it.qty).join('、'),
      // 整单备注必须传到后厨与配送手上，列表里就摆出来（需求 4.11 / 4.14）
      remark: order.remark || '',
      // 份数被超时释放的单必须顶在最前并标红：这份餐可能已经被别人买走了
      stockReleased: !!order.stockReleased,
      cancelRequested: !!order.cancelRequested,
      selectable: order.status === 'pending_accept' && !order.cancelRequested,
      printable,
      // 三态角标：按张算，A×2 只出 1 张显示「部分 1/2」而不是「已打」
      printState: print.printState || 'none',
      printText: print.printState === 'partial'
        ? '部分 ' + print.printedPages + '/' + print.totalPages
        : (print.printState === 'done' ? '已打' : ''),
    };
  },

  onDateChange(e) {
    this.setData({ serveDate: e.detail.value });
    this.reload();
  },

  onSwitchTab(e) {
    this.setData({ status: e.currentTarget.dataset.key || '' });
    this.reload();
  },

  onKeywordInput(e) {
    this.setData({ keyword: (e.detail.value || '').trim() });
  },

  onSearch() {
    this.reload();
  },

  onToggleCancelOnly() {
    this.setData({ cancelOnly: !this.data.cancelOnly });
    this.reload();
  },

  /**
   * 切换打印模式。
   *
   * 关闭时保留勾选：商家可能先勾了待接单的单准备接单，点错了开关不该把选择清掉；
   * 反过来从打印模式切回来，那些已接单的单在新模式下不可选，下次 applySelected 会剔除。
   */
  onTogglePrintMode() {
    const next = !this.data.printMode;
    const selected = Object.assign({}, this.data.selected);
    if (!next) {
      // 回到接单模式：把不属于「待接单」的勾去掉，否则底部会显示「接单 5 单」实际只能接 2 单
      this.data.list.forEach((row) => {
        if (!row.selectable) {
          delete selected[row.orderId];
        }
      });
    }
    this.setData({ printMode: next, selected: {}, selectedCount: 0 });
    this.applySelected(selected);
  },

  onToggle(e) {
    const id = e.currentTarget.dataset.id;
    const selectable = e.currentTarget.dataset.selectable;
    if (!selectable) {
      return;
    }
    const selected = Object.assign({}, this.data.selected);
    if (selected[id]) {
      delete selected[id];
    } else {
      selected[id] = true;
    }
    this.applySelected(selected);
  },

  onSelectAll() {
    // 全选的规则跟着模式走：接单模式只选待接单，打印模式选所有能打的
    const field = this.data.printMode ? 'printable' : 'selectable';
    const ids = this.data.list.filter((row) => row[field]).map((row) => row.orderId);
    const allSelected = ids.length > 0 && ids.every((id) => this.data.selected[id]);
    const selected = {};
    if (!allSelected) {
      ids.forEach((id) => {
        selected[id] = true;
      });
    }
    this.applySelected(selected);
  },

  applySelected(selected) {
    const count = Object.keys(selected).length;
    // 按钮上只报单数：张数要服务端按明细展开后才知道（一单可能有几份），
    // 在端上估算一遍迟早和实际出纸数对不上，那个数字放在确认弹窗里给
    this.setData({
      selected,
      selectedCount: count,
      acceptText: count ? '接单 ' + count + ' 单' : '接单',
      rejectText: count ? '拒单 ' + count + ' 单' : '拒单',
      printText: count ? '打印 ' + count + ' 单' : '打印标签',
    });
  },

  onAccept() {
    const orderIds = Object.keys(this.data.selected);
    if (!orderIds.length) {
      wx.showToast({ title: '请先勾选订单', icon: 'none' });
      return;
    }
    if (this.data.accepting) {
      return;
    }
    this.setData({ accepting: true });
    request({ url: '/admin/order/accept', method: 'POST', data: { orderIds } })
      .then((result) => {
        const warnings = result.warnings || [];
        const skipped = result.skipped || [];
        let msg = '已接 ' + (result.accepted || []).length + ' 单';
        if (skipped.length) {
          msg += '，跳过 ' + skipped.length + ' 单';
        }
        wx.showModal({
          title: '接单结果',
          content: msg + (warnings.length ? '\n\n' + warnings.join('\n') : ''),
          showCancel: false,
        });
        this.reload();
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ accepting: false }));
  },

  /**
   * 批量拒单：整批共用一条原因。
   *
   * 用 wx.showModal 的 editable 输入而不是跳新页面：拒单是个「点了就要马上给结果」的动作，
   * 多跳一页反而慢。原因必填 —— 顾客会在订单详情里看到这句话。
   */
  onBatchReject() {
    const orderIds = Object.keys(this.data.selected);
    if (!orderIds.length) {
      wx.showToast({ title: '请先勾选订单', icon: 'none' });
      return;
    }
    if (this.data.rejecting) {
      return;
    }
    wx.showModal({
      title: '批量拒单（' + orderIds.length + ' 单）',
      editable: true,
      placeholderText: '拒单原因必填，顾客会看到',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        const reason = (res.content || '').trim();
        if (!reason) {
          wx.showToast({ title: '请填写拒单原因', icon: 'none' });
          return;
        }
        this.submitBatchReject(orderIds, reason);
      },
    });
  },

  submitBatchReject(orderIds, reason) {
    this.setData({ rejecting: true });
    request({
      url: '/admin/order/reject-batch',
      method: 'POST',
      data: { orderIds, reason },
    })
      .then((result) => {
        const skipped = result.skipped || [];
        let msg = '已拒 ' + (result.rejected || []).length + ' 单';
        if (skipped.length) {
          msg += '，跳过 ' + skipped.length + ' 单：\n' + skipped.join('\n');
        }
        wx.showModal({ title: '拒单结果', content: msg, showCancel: false });
        this.reload();
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ rejecting: false }));
  },

  /* ==================== 批量打印标签 ==================== */

  /**
   * 点「打印标签」：先向服务端要标签数据，再弹确认。
   *
   * 为什么先取数据再确认、而不是先确认再取：张数与构成（配送/自取各几张、跳过几单、
   * 其中几单已经打过）只有服务端算得准。端上按「份数之和」估一遍，
   * 一旦和实际出纸数不一致，商家就会怀疑这个功能靠不靠谱。
   */
  onBatchPrint() {
    const orderIds = Object.keys(this.data.selected).map(Number);
    if (!orderIds.length) {
      wx.showToast({ title: '请先勾选订单', icon: 'none' });
      return;
    }
    if (this.data.printLoading || this.data.printing) {
      return;
    }
    this.setData({ printLoading: true });
    request({ url: '/admin/print/labels', method: 'POST', data: { orderIds } })
      .then((data) => {
        this.setData({ printLoading: false });
        this.confirmPrint(data || {});
      })
      .catch((err) => {
        this.setData({ printLoading: false });
        app.toastError(err);
      });
  },

  confirmPrint(data) {
    const { skipText } = require('../../utils/print/error-text');
    const allPages = data.pages || [];
    const skipped = data.skipped || [];

    if (!allPages.length) {
      const reasons = [];
      skipped.forEach((item) => {
        const text = skipText(item.reason);
        if (reasons.indexOf(text) < 0) {
          reasons.push(text);
        }
      });
      wx.showModal({
        title: '没有可打印的标签',
        content: reasons.length ? '所选订单都不可打印：' + reasons.join('、') : '所选订单没有可打印的内容',
        showCancel: false,
      });
      return;
    }

    // 单批上限 30 张，按套餐边界截断（不会把一个套餐的几份切到两批）
    const { kept, rest } = limitPages(allPages, MAX_BATCH_PRINT);
    const delivery = kept.filter((item) => item.template === 'delivery').length;
    const pickup = kept.length - delivery;

    // 需求 7.3 第 2 条的文案：已选 N 单、共 M 张、模板构成，三件事一次说清。
    // 单数取勾选数（不是能打的单数）—— 商家勾了 10 单，就该看到「已选 10 单」，
    // 跳过的几单在下一行单独说明，否则他会以为自己选漏了。
    const orderCount = Object.keys(this.data.selected).length;
    let content = '已选 ' + orderCount + ' 单，共 ' + kept.length + ' 张';
    if (delivery && pickup) {
      content += '（配送 ' + delivery + ' 张 / 自取 ' + pickup + ' 张）';
    } else {
      content += delivery ? '，全部为配送单' : '，全部为自取单';
    }

    // 「打过了」只提示不拦截：补打是常态，拦下来反而要去别的页面绕一圈
    const printed = data.printed || {};
    const printedIds = Object.keys(printed).filter((id) => (printed[id].printedPages || 0) > 0);
    if (printedIds.length) {
      const lastAt = printedIds
        .map((id) => printed[id].lastAt)
        .filter(Boolean)
        .sort()
        .pop();
      content += '\n其中 ' + printedIds.length + ' 单打印过' + (lastAt ? '（最近 ' + lastAt + '）' : '');
    }

    if (skipped.length) {
      const reasons = [];
      skipped.forEach((item) => {
        const text = skipText(item.reason);
        if (reasons.indexOf(text) < 0) {
          reasons.push(text);
        }
      });
      content += '\n' + skipped.length + ' 单已跳过（' + reasons.join('、') + '）';
    }
    if (rest > 0) {
      content += '\n本次先打前 ' + kept.length + ' 张，还剩 ' + rest + ' 张下次再打';
    }

    // 先出首张预览，再把「开始打印」交给商家点（需求 14.6）：
    // 30 张纸打出去之前，至少让他在本机看一眼版式是不是这单的。
    this.openPreview(kept, allPages.slice(kept.length), content);
  },

  /**
   * 批量首张预览。只渲染第一张并截图，不上报打印记录。
   * 预览失败不阻断打印：弹层里退化成纯文案，按钮照常可点。
   */
  openPreview(kept, restPages, content) {
    const lines = String(content || '').split('\n').filter((line) => line);
    this.setData({
      previewVisible: true,
      previewSrc: '',
      previewLoading: true,
      previewLines: lines,
      previewTotal: kept.length,
      previewRest: restPages.length,
      previewPages: kept,
      previewRestPages: restPages,
    });
    const { previewPage } = require('../../utils/print/print-runner');
    previewPage(kept[0], this, 'test')
      .then((result) => {
        this.setData({ previewLoading: false, previewSrc: (result && result.previewPath) || '' });
      })
      .catch((err) => {
        console.warn('[打印] 首张预览失败', err);
        this.setData({ previewLoading: false });
      });
  },

  onClosePreview() {
    this.setData({ previewVisible: false, previewSrc: '', previewPages: [], previewRestPages: [] });
  },

  onConfirmPreview() {
    const pages = this.data.previewPages || [];
    const restPages = this.data.previewRestPages || [];
    this.setData({ previewVisible: false, previewSrc: '' });
    if (pages.length) {
      this.startPrint(pages, restPages);
    }
  },

  showConnectModal() {
    wx.showModal({
      title: '打印机未连接',
      content: '先连接打印机，再回来打印。',
      confirmText: '去连接',
      success: (res) => {
        if (res.confirm) {
          wx.navigateTo({ url: '/pages/merchant/printer' });
        }
      },
    });
  },

  /**
   * 开始打印。
   *
   * 打印相关依赖在这里才 require：订单列表是最高频页面，不该为了一个可能不点的按钮
   * 在首屏同步加载三百多 KB 的蓝牙 SDK。
   */
  async startPrint(pages, restPages) {
    const printer = require('../../utils/print/printer');
    const { runJob } = require('../../utils/print/print-runner');
    const { errorText } = require('../../utils/print/error-text');

    // 先静默试连一次（需求 14.4）：断线后紧接着点打印很常见，直接弹「去连接」会让人多跑一趟
    const ready = await printer.ensureConnected();
    if (!ready) {
      this.showConnectModal();
      return;
    }

    const device = printer.loadDevice() || printer.loadLastDevice();
    const settings = Object.assign(printer.loadSettings(device), {
      deviceName: printer.deviceName(device),
    });
    const batchNo = 'b' + Date.now().toString(36);

    this.runner = runJob({
      pages,
      settings,
      component: this,
      canvasId: 'test',
      batchNo,
      onProgress: ({ printed, total }) => {
        this.setData({
          printedCount: printed,
          totalCount: total,
          progressPercent: total ? Math.min(100, Math.round((printed / total) * 100)) : 0,
        });
      },
      onPause: ({ errCode }) => {
        this.setData({ paused: true, printError: errCode ? errorText(errCode) : '' });
      },
      onResume: () => this.setData({ paused: false, printError: '' }),
    });

    this.setData({
      printing: true,
      paused: false,
      printedCount: 0,
      totalCount: pages.length,
      progressPercent: 0,
      printError: '',
    });

    this.runner.promise.then((summary) => {
      this.runner = null;
      this.setData({ printing: false, paused: false });
      // 结果交给结果页：本机刚打完的数据是准的，不必再从服务端拉一遍
      app.globalData.printResult = {
        pages,
        results: summary.results,
        batchNo,
        restPages: restPages || [],
      };
      // 先刷列表再看结果：关掉结果页回来时角标已经是新状态
      this.reload();
      wx.navigateTo({ url: '/pages/merchant/print-result' });
    });
  },

  onCancelPrint() {
    if (this.runner) {
      this.runner.cancel();
    }
    this.setData({ printing: false, paused: false });
  },

  onResumePrint() {
    if (this.runner) {
      this.setData({ paused: false, printError: '' });
      this.runner.resume();
    }
  },

  onOpen(e) {
    wx.navigateTo({ url: '/pages/merchant/order-detail?orderId=' + e.currentTarget.dataset.id });
  },
});
