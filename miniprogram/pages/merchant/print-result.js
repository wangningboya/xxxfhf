const app = getApp();
const { runJob } = require('../../utils/print/print-runner');
const { shortErrorText } = require('../../utils/print/error-text');
const { loadSettings, deviceName } = require('../../utils/print/printer');
const printer = require('../../utils/print/printer');

/**
 * 打印结果页。
 *
 * 这一页的存在理由很直接：批量打到第 12 张缺纸停了，只告诉商家「已打 4 张」没有意义 ——
 * 他面对 20 个袋子，要知道的是**哪一单的哪个套餐没出纸、缺几张**。
 * 没有这一页，商家遇到一次中断就再也不敢用批量，功能等于白做。
 *
 * 数据来自上一页通过 `globalData.printResult` 交过来的本次作业结果（pages + results），
 * 不从服务端拉：服务端记录是给跨手机查询用的，本机刚打完的结果是准的，
 * 拉一次反而可能因为上报延迟而看到旧状态。
 */
Page({
  data: {
    summary: { total: 0, successCount: 0, failCount: 0 },
    doneRows: [],
    failRows: [],
    /** 被 30 张上限截下、本次还没打的张数（需求 7.3 第 6 条） */
    restCount: 0,
    printing: false,
    reprinting: false,
    paused: false,
    printedCount: 0,
    totalCount: 0,
    progressPercent: 0,
    errorText: '',
  },

  onLoad() {
    const payload = app.globalData.printResult;
    // 取完即清：用户从结果页返回再点进来，应该看到「没有打印记录」而不是上一次的旧结果
    app.globalData.printResult = null;
    if (!payload || !payload.results) {
      return;
    }
    this.payload = payload;
    // 截断剩下的那批只在内存里带着：不落 storage、不做持久队列。
    // 商家关掉小程序就该回列表重新勾选，而不是下次打开冒出几十张不明来历的标签。
    this.restPages = payload.restPages || [];
    this.setData({ restCount: this.restPages.length });
    this.render(payload.results);
  },

  onUnload() {
    if (this.runner) {
      this.runner.cancel();
      this.runner = null;
    }
  },

  /**
   * 按「订单 × 套餐」聚合成一行。
   *
   * 统计与重打都按张，只有展示聚合：一单三份全列三行只是噪音，
   * 但缺一张必须补一张，所以「2/2」「1/2」这种分子分母要留着。
   */
  render(results) {
    const map = {};
    const order = [];
    results.forEach((item) => {
      if (!item || !item.page) {
        return;
      }
      const page = item.page;
      const key = page.orderId + ':' + page.comboIndex;
      if (!map[key]) {
        map[key] = {
          key,
          // 验收 21 要求「订单尾号 + 套餐」能定位，标签上是「联系人 + 电话后四位」，
          // 两个都给：尾号对应列表，联系人对应袋子，少一个就要翻手机
          label: '尾号 ' + String(page.orderNo || '').slice(-4) + '  ' + (page.comboName || ''),
          who: (page.contactName || '顾客')
            + (page.phoneTail ? '  ****' + page.phoneTail : ''),
          total: 0,
          success: 0,
          reason: '',
        };
        order.push(key);
      }
      const row = map[key];
      row.total += 1;
      if (item.result === 'success') {
        row.success += 1;
      } else if (!row.reason) {
        // 一行内多张原因不同时取第一张失败的，够用且不必堆文案
        row.reason = shortErrorText(item.errCode);
      }
    });

    const rows = order.map((key) => map[key]);
    const doneRows = rows.filter((row) => row.success >= row.total);
    const failRows = rows.filter((row) => row.success < row.total);
    const successCount = results.filter((item) => item && item.result === 'success').length;
    this.setData({
      summary: {
        total: results.length,
        successCount,
        failCount: results.length - successCount,
      },
      doneRows,
      failRows,
    });
  },

  onClose() {
    wx.navigateBack();
  },

  onReprint() {
    if (this.data.printing || !this.payload) {
      return;
    }
    const failed = (this.payload.results || [])
      .filter((item) => item && item.result === 'fail' && item.page)
      .map((item) => item.page);
    if (!failed.length) {
      return;
    }
    this.runPages(failed, (summary) => {
      // 把补打的结果合并回原结果：重打成功的替换掉原来那条失败记录
      const merged = this.mergeResults(this.payload.results, summary.results);
      this.payload = Object.assign({}, this.payload, { results: merged });
      this.render(merged);
      this.toastResult(summary, '补打完成');
    });
  },

  /**
   * 继续打印被 30 张上限截下的那批（需求 7.3 第 6 条 / 验收 20）。
   *
   * 与「只重打未打的」是两个入口，故意不合并：那个补的是本批**失败的张**，
   * 这个打的是本批**根本没排进队列的张**。混成一个按钮，商家就分不清
   * 「还剩 10 张」是打坏了还是没轮到。
   */
  onContinue() {
    if (this.data.printing || !this.restPages || !this.restPages.length) {
      return;
    }
    const pages = this.restPages.slice();
    this.runPages(pages, (summary) => {
      // 追加而不是覆盖：这批是新张，pageKey 与本批不重叠（截断点落在套餐边界）
      const merged = (this.payload.results || []).concat(summary.results);
      this.payload = Object.assign({}, this.payload, { results: merged });
      this.restPages = [];
      this.setData({ restCount: 0 });
      this.render(merged);
      this.toastResult(summary, '剩余已打完');
    });
  },

  /**
   * 打一队纸并把结果交给调用方合并。
   *
   * 连接检查、设置读取、进度回调三处完全一样，抽出来是因为补打与续打的**唯一**差别
   * 在结果怎么合并 —— 其余各写一遍，迟早有一处漏掉暂停提示。
   */
  runPages(pages, onDone) {
    if (!printer.isConnected()) {
      wx.showModal({
        title: '打印机未连接',
        content: '先连接打印机再打印。',
        confirmText: '去连接',
        success: (res) => {
          if (res.confirm) {
            wx.navigateTo({ url: '/pages/merchant/printer' });
          }
        },
      });
      return;
    }

    const device = printer.loadDevice();
    const settings = Object.assign(loadSettings(device), { deviceName: deviceName(device) });

    this.runner = runJob({
      pages,
      settings,
      component: this,
      canvasId: 'test',
      // 同一个批次号：详情页的打印记录按批聚合，「继续打印」属于同一批，不该记成两次作业
      batchNo: this.payload ? this.payload.batchNo : String(Date.now()),
      onProgress: ({ printed, total }) => {
        this.setData({
          printedCount: printed,
          totalCount: total,
          progressPercent: total ? Math.min(100, Math.round((printed / total) * 100)) : 0,
        });
      },
      onPause: ({ errCode }) => this.setData({
        paused: true,
        errorText: errCode ? shortErrorText(errCode) : '',
      }),
      onResume: () => this.setData({ paused: false, errorText: '' }),
    });

    this.setData({
      printing: true,
      reprinting: true,
      paused: false,
      printedCount: 0,
      totalCount: pages.length,
      progressPercent: 0,
      errorText: '',
    });

    this.runner.promise.then((summary) => {
      this.runner = null;
      this.setData({ printing: false, reprinting: false, paused: false });
      onDone(summary);
    });
  },

  /** 一批结束的统一提示：全成给 success，有失败就如实报剩下几张 */
  toastResult(summary, okText) {
    if (summary.successCount === summary.total) {
      wx.showToast({ title: okText, icon: 'success' });
      return;
    }
    wx.showToast({
      title: '还有 ' + (summary.total - summary.successCount) + ' 张没打出来',
      icon: 'none',
    });
  },

  /**
   * 合并补打结果。
   *
   * 按 pageKey 覆盖而不是追加：补打的是同一张纸，追加会让「已打 2/2」变成 3 张，
   * 统计就错了。这次仍失败的保留失败原因。
   */
  mergeResults(origin, reprint) {
    const byKey = {};
    (origin || []).forEach((item) => {
      if (item && item.page) {
        byKey[item.page.pageKey] = item;
      }
    });
    (reprint || []).forEach((item) => {
      if (item && item.page) {
        byKey[item.page.pageKey] = item;
      }
    });
    return (origin || []).map((item) => (item && item.page ? byKey[item.page.pageKey] : item));
  },

  onResume() {
    if (this.runner) {
      this.setData({ paused: false, errorText: '' });
      this.runner.resume();
    }
  },

  onCancelPrint() {
    if (this.runner) {
      this.runner.cancel();
    }
    this.setData({ printing: false, reprinting: false, paused: false });
  },
});
