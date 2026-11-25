const app = getApp();
const printer = require('../../utils/print/printer');
const capability = require('../../utils/print/capability');
const sample = require('../../utils/print/sample');
const linkState = require('../../utils/print/link-state');
const preview = require('../../utils/print/preview');
const { flushPending } = require('../../utils/print/print-runner');
const { errorText } = require('../../utils/print/error-text');

/**
 * 打印机设置页。
 *
 * 这一页的存在理由是「把不确定性收在一个地方」：连接、浓度、纸型、样张校验全在这里做完，
 * 订单页打印时只剩「能不能打」一个判断，不再让商家在要出餐的时候调参数。
 *
 * 页面自带一个隐藏 canvas：SDK 必须先画到真实画布上才能取像素，
 * 不能离屏，也不能用别的页面的 canvas（canvas 是单例共享资源）。
 */
Page({
  data: {
    connected: false,
    /** 断线后正在第几次自动重连（1/3、2/3、3/3），0 表示没在重连 */
    reconnecting: 0,
    /** 三次都失败：保留设备名，等用户点「重连」或直接点打印由 ensureConnected 兜底 */
    lost: false,
    deviceName: '',
    devices: [],
    scanning: false,
    scanTip: '',
    settings: { paper: 1, darkness: 3 },
    densityOptions: [1, 2, 3, 4, 5],
    printing: false,
    paused: false,
    printedCount: 0,
    totalCount: 0,
    progressPercent: 0,
    errorText: '',
  },

  onLoad() {
    const device = printer.loadDevice();
    const settings = printer.loadSettings(device);
    this.setData({
      deviceName: printer.deviceName(device),
      settings,
      densityOptions: capability.densityOptions(capability.capabilityOf(printer.deviceName(device))),
    });
    // 断开要即时反映到页面：打印中走远了、打印机没电了都会走到这里
    this.offDisconnect = printer.onDisconnect(() => {
      this.setData({ connected: false });
      if (this.data.printing) {
        this.stopJob();
        wx.showToast({ title: '打印机断开了', icon: 'none' });
      }
    });
    // 连接状态由 printer 统一广播：断开 → 正在重连(n/3) → 失败停止，都在这里刷新
    this.offState = printer.onStateChange((state) => this.applyLinkState(state));
  },

  applyLinkState(state) {
    // 名字取「当前设备」或「上次的设备」：扫描会清当前设备，但页面不该因此变空白
    const device = printer.loadDevice() || printer.loadLastDevice();
    this.setData({
      connected: !!state.connected,
      reconnecting: state.reconnecting || 0,
      lost: !!state.lost,
      deviceName: printer.deviceName(device),
    });
  },

  onShow() {
    // 每次进来都问一次 SDK，不用本地标志：本地标志很容易停在「已连接」
    const state = linkState.read();
    const device = printer.loadDevice() || printer.loadLastDevice();
    this.setData({
      connected: printer.isConnected(),
      reconnecting: state.reconnecting || 0,
      lost: !!state.lost,
      deviceName: printer.deviceName(device),
    });
    // 补传上次没报成功的打印记录（最多 50 条、7 天）
    flushPending();
    // 每天首次进设置页也清一次过期预览图（另一个入口是 App.onLaunch）
    preview.cleanDaily();
  },

  onUnload() {
    if (this.offDisconnect) {
      this.offDisconnect();
    }
    if (this.offState) {
      this.offState();
    }
    this.stopJob();
  },

  /* ==================== 连接 ==================== */

  onScan() {
    if (this.data.scanning) {
      return;
    }
    this.setData({ scanning: true, scanTip: '' });
    printer
      .scan()
      .then((result) => {
        if (result.timedOut) {
          this.setData({
            devices: result.devices,
            scanTip: '没找到打印机，确认已开机、在 3 米内，并且没有被手机系统配对过。',
          });
          return;
        }
        // 已连接的那台不必出现在候选里，点它没有意义。
        // 但 storage 里有记录不等于现在连着：断链没触发 SDK 回调时记录会留着，
        // 拿它当过滤依据，这台打印机就会从之后每一轮扫描里消失，只能删小程序才能恢复
        const current = printer.isConnected() ? printer.loadDevice() : null;
        const devices = current
          ? result.devices.filter((item) => item.deviceId !== current.deviceId)
          : result.devices;
        this.setData({
          devices,
          scanTip: devices.length ? '' : '附近没有搜到打印机，确认已开机、在 3 米内，并且没有被手机系统配对过。',
        });
      })
      .catch((err) => {
        console.error('[打印机] 搜索失败', err);
        this.setData({ devices: [], scanTip: printer.scanErrorText(err) });
      })
      .then(() => this.setData({ scanning: false }));
  },

  onConnect(e) {
    const index = Number(e.currentTarget.dataset.index);
    const device = this.data.devices[index];
    if (!device) {
      return;
    }
    wx.showLoading({ title: '连接中', mask: true });
    printer
      .connect(device)
      .then((connected) => {
        wx.hideLoading();
        this.setData({
          connected: true,
          deviceName: printer.deviceName(connected),
          devices: [],
          // 换机型要跟着换浓度范围，否则拿 B32 的 1~15 画给 B3S
          settings: printer.loadSettings(connected),
          densityOptions: capability.densityOptions(capability.capabilityOf(printer.deviceName(connected))),
        });
        wx.showToast({ title: '已连接', icon: 'success' });
      })
      .catch((err) => {
        wx.hideLoading();
        console.error('[打印机] 连接失败', err);
        wx.showModal({
          title: '连接失败',
          content: (err && err.message) || '连接失败，请重试。若系统蓝牙里已配对过这台打印机，先取消配对再试。',
          showCancel: false,
        });
      });
  },

  onDisconnect() {
    wx.showLoading({ title: '断开中', mask: true });
    printer.disconnect().then(() => {
      wx.hideLoading();
      this.setData({ connected: false, reconnecting: 0, lost: false, deviceName: '' });
    });
  },

  /** 三次自动重连都失败后，让商家自己再试一次（用记住的设备，不必重新搜索） */
  onRetryConnect() {
    if (this.data.reconnecting) {
      return;
    }
    wx.showLoading({ title: '重连中', mask: true });
    printer.ensureConnected(5000).then((ok) => {
      wx.hideLoading();
      if (ok) {
        wx.showToast({ title: '已连接', icon: 'success' });
        return;
      }
      wx.showModal({
        title: '还是连不上',
        content: '确认打印机已开机、在 3 米内；也可以在「附近设备」里重新搜索一次。',
        showCancel: false,
      });
    });
  },

  /* ==================== 设置 ==================== */

  onDensity(e) {
    const darkness = Number(e.currentTarget.dataset.value);
    const settings = Object.assign({}, this.data.settings, { darkness });
    this.setData({ settings });
    // 只存本机：浓度调准一次就不用再动，为它做服务端同步不值得
    printer.saveSettings(settings);
  },

  /* ==================== 样张 ==================== */

  onTestPrint() {
    this.startSample('test', 1);
  },

  onRulerPrint() {
    this.startSample('ruler', 1);
  },

  /** 长按连打 20 张：判断连续走纸会不会累积偏移 */
  onRulerLongPress() {
    this.startSample('ruler', 20);
  },

  /**
   * 打样张。
   *
   * 样张不上报（没有对应订单），所以走 report:false 的作业；
   * 其余流程与正式标签完全一致，包括进度和暂停处理 —— 打样能打出问题，
   * 正式打印就会是同样的问题。
   */
  async startSample(mode, count) {
    if (this.data.printing) {
      return;
    }
    // 与正式打印一致：先静默试连一次，别让「刚断线」变成「先搜索再连接」两步
    const ready = await printer.ensureConnected();
    if (!ready) {
      wx.showModal({
        title: '打印机未连接',
        content: '先搜索并连接打印机。',
        showCancel: false,
      });
      return;
    }
    const settings = Object.assign({}, this.data.settings, {
      deviceName: this.data.deviceName,
    });
    const callbacks = {
      onProgress: ({ printed, total }) => {
        this.setData({
          printedCount: printed,
          totalCount: total,
          progressPercent: total ? Math.min(100, Math.round((printed / total) * 100)) : 0,
        });
      },
      onPause: ({ errCode }) => {
        this.setData({
          paused: true,
          errorText: errCode ? errorText(errCode) : '',
        });
      },
      onResume: () => this.setData({ paused: false, errorText: '' }),
    };
    const runner = mode === 'ruler'
      ? sample.printRuler(settings, this, 'test', count, callbacks)
      : sample.printTest(settings, this, 'test', callbacks);

    this.runner = runner;
    this.setData({
      printing: true,
      paused: false,
      printedCount: 0,
      totalCount: mode === 'ruler' ? Math.max(1, Math.min(30, count)) : 1,
      progressPercent: 0,
      errorText: '',
    });

    runner.promise.then((summary) => {
      this.runner = null;
      this.setData({
        printing: false,
        paused: false,
        printedCount: summary.successCount,
        progressPercent: 100,
      });
      if (summary.successCount === summary.total) {
        wx.showToast({ title: count > 1 ? '已打 ' + count + ' 张' : '样张已打印', icon: 'success' });
      } else {
        const firstFail = (summary.results || []).find((item) => item && item.result === 'fail');
        wx.showModal({
          title: '打印未完成',
          content: '成功 ' + summary.successCount + ' 张，失败 ' + summary.failCount + ' 张'
            + (firstFail ? '：' + errorText(firstFail.errCode) : ''),
          showCancel: false,
        });
      }
    });
  },

  onResume() {
    if (this.runner) {
      this.setData({ paused: false });
      this.runner.resume();
    }
  },

  onCancelPrint() {
    if (this.runner) {
      this.runner.cancel();
    }
    this.stopJob();
  },

  /** 停止并复位进度层。断开、页面卸载、用户取消都走这里，避免三处各写一遍 */
  stopJob() {
    this.runner = null;
    this.setData({
      printing: false,
      paused: false,
      progressPercent: 0,
      errorText: '',
    });
  },
});
