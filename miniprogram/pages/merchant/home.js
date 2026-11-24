const app = getApp();
const { request } = require('../../utils/request');
const { dateStr, addDays } = require('../../utils/format');
const dict = require('../../utils/dict');

const MERCHANT_KEY = 'meal_merchant';

/**
 * 待办卡片的标题要走字典。
 *
 * 卡片的数字来自 `/admin/dashboard` 的 `pendingAccept` / `pendingAudit`，
 * 标题跟着「待接单」「待审核」这两个状态的显示名走 —— 商家在后台把「待接单」
 * 改成「待处理」，这里必须一起变，否则点进去看到的页签名字和卡片对不上。
 */
function buildStatLabels() {
  return {
    acceptLabel: dict.label('order_status', 'pending_accept', '待接单'),
    auditLabel: dict.label('audit_status', 'pending', '待审核'),
  };
}

/**
 * 按权限决定显示哪些入口。
 *
 * 这只是体验优化 —— 真正的边界在服务端拦截器上，隐藏按钮不等于安全。
 * permissions 为 undefined 说明是旧版本登录缓存（当时还没存权限），一律放出来，
 * 让服务端去挡；空数组则确实是「一个权限都没有」，那就该什么都不显示。
 */
function buildCan(permissions) {
  const has = (perm) => permissions === undefined || (permissions || []).indexOf(perm) >= 0;
  return {
    order: has('order.accept') || has('order.view'),
    pickup: has('order.accept'),
    prep: has('prep.view'),
    audit: has('user.audit'),
    users: has('user.address') || has('user.view'),
    regions: has('region.manage'),
    combos: has('combo.manage'),
    dishes: has('dish.manage'),
    menu: has('menu.manage'),
    finance: has('finance.view'),
    config: has('config.manage'),
    // 打印不新增权限项：打印只是把屏幕上已有的信息落到纸上，权限口径与看单一致
    printTag: has('order.view') || has('order.accept'),
  };
}

Page({
  data: {
    displayName: '',
    serveDate: '',
    pendingAccept: 0,
    pendingCancel: 0,
    pendingAudit: 0,
    pendingAddress: 0,
    /** 资金类待办（v1.53 / F-J09）：不限供餐日，与其余待办同口径 */
    pendingCheck: 0,
    pendingRefund: 0,
    /** 已出餐/已完成但钱没到（v3.50 / F-F07）：与上面两项同为不限供餐日的资金待办 */
    pendingCollect: 0,
    comboCount: 0,
    acceptLabel: '待接单',
    auditLabel: '待审核',
    closed: false,
    cutoff: false,
    cutoffText: '',
    loading: true,
    can: {},
    /** 本机记住的打印机名，空串表示没连过 */
    printerName: '',
    /** 消息：只统计「发给商家」的那一侧，与用户首页各算各的 */
    unreadCount: 0,
    unreadBadge: '',
    noticeItems: [],
  },

  onShow() {
    const saved = wx.getStorageSync(MERCHANT_KEY);
    if (!saved || !saved.userId) {
      wx.redirectTo({ url: '/pages/merchant/login' });
      return;
    }
    this.setData({ displayName: saved.displayName || saved.username || '商家' });
    this.setData({ can: buildCan(saved.permissions) });
    this.setData({ serveDate: addDays(dateStr(), 1) });
    // 只读本地记住的设备名，不 require 打印 SDK：这里显示的是「连过哪台」，
    // 真实连接状态要问蓝牙，问一次就得把整套蓝牙栈加载起来，不值得
    this.refreshPrinterName();
    // 先用当前字典（可能还没加载好，退回兜底文案）渲染，取回来再校正一次
    this.setData(buildStatLabels());
    dict.load().then(() => this.setData(buildStatLabels()));
    this.load();
    this.loadMessages();
    this.startTimer();
  },

  onHide() {
    this.stopTimer();
  },

  /**
   * 读本机记住的打印机名。
   *
   * 直接用 storage 而不是 utils/print/printer.js：那个模块会连带 require 整套 JCAPI，
   * 工作台首屏不该为一行卡片付这个代价。storage 键名与 printer.js 的 DEVICE_KEY 保持一致。
   */
  refreshPrinterName() {
    const device = wx.getStorageSync('print_device');
    const name = device && (device.name || device.localName || device.deviceId);
    this.setData({ printerName: name || '' });
  },

  onUnload() {
    this.stopTimer();
  },

  load() {
    const serveDate = this.data.serveDate;
    const tasks = [
      // 待办统一走聚合接口：原来分散调 2 个列表接口只取 total，
      // 现在要看的待办变多（待接单/待取消/待审核/待确认地址），合并成一次请求
      request({ url: '/admin/dashboard?serveDate=' + serveDate })
        .then((d) =>
          this.setData({
            pendingAccept: d.pendingAccept || 0,
            pendingCancel: d.pendingCancel || 0,
            pendingAudit: d.pendingAudit || 0,
            pendingAddress: d.pendingAddress || 0,
            pendingCheck: d.pendingCheck || 0,
            pendingRefund: d.pendingRefund || 0,
            pendingCollect: d.pendingCollect || 0,
          })
        )
        // 只有备菜 / 对账权限的角色拿不到工作台数字，不该因此整页报错
        .catch(() => {}),
      request({ url: '/admin/menu/view?serveDate=' + serveDate }).then((view) =>
        this.setData({
          comboCount: (view.items || []).length,
          closed: !!view.closed,
          cutoff: !!view.cutoff,
          cutoffAt: view.cutoffAt,
        })
      ),
    ];

    return Promise.all(tasks)
      .then(() => {
        this.updateCutoffText();
        this.setData({ loading: false });
      })
      .catch((err) => {
        this.setData({ loading: false });
        // 权限不足会被拦截器挡住，这时应该回登录页而不是干瞪眼
        if (err && (err.code === 40101 || err.code === 40301)) {
          wx.removeStorageSync(MERCHANT_KEY);
          wx.redirectTo({ url: '/pages/merchant/login' });
          return;
        }
        app.toastError(err);
      });
  },

  /**
   * 未读消息（商家侧）。
   *
   * 单独发请求、不进下面的 Promise.all：消息接口失败不该让整个工作台报错或跳登录页。
   * 新订单、取消申请这两类推送商家基本收不到（订阅消息要授权），这里才是主要提醒途径。
   */
  loadMessages() {
    request({ url: '/message/summary?side=merchant' })
      .then((data) => {
        const count = (data && data.unreadCount) || 0;
        this.setData({
          unreadCount: count,
          unreadBadge: count > 99 ? '99+' : String(count),
          noticeItems: (data && data.items) || [],
        });
      })
      .catch(() => {});
  },

  onOpenMessages() {
    wx.navigateTo({ url: '/pages/message/index?role=merchant' });
  },

  startTimer() {
    this.stopTimer();
    this.timer = setInterval(() => this.updateCutoffText(), 30000);
  },

  stopTimer() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  },

  /**
   * 截单倒计时。
   *
   * 只用于给商家一个「还剩多久」的直觉，**不做任何判定**：
   * 能不能下单由服务端按自己的时钟算，手机时间调过也不影响业务。
   */
  updateCutoffText() {
    const cutoffAt = this.data.cutoffAt;
    if (this.data.cutoff) {
      this.setData({ cutoffText: '已截单' });
      return;
    }
    if (!cutoffAt) {
      this.setData({ cutoffText: '' });
      return;
    }
    // 服务端给的是 2026-11-17T21:00，iOS 对带 T 的字符串解析不稳，换成空格
    const at = new Date(String(cutoffAt).replace('T', ' ').replace(/-/g, '/'));
    const diff = at.getTime() - Date.now();
    if (isNaN(diff)) {
      this.setData({ cutoffText: '' });
      return;
    }
    if (diff <= 0) {
      this.setData({ cutoffText: '已截单' });
      return;
    }
    const hours = Math.floor(diff / 3600000);
    const minutes = Math.floor((diff % 3600000) / 60000);
    this.setData({
      cutoffText: hours > 0 ? '距截单 ' + hours + ' 小时 ' + minutes + ' 分' : '距截单 ' + minutes + ' 分',
    });
  },

  onToggleClosed() {
    const next = !this.data.closed;
    wx.showModal({
      title: next ? '暂停供应' : '恢复供应',
      content: next
        ? '暂停后该供餐日不能下单，已下的单不受影响。确定吗？'
        : '恢复后该供餐日可以正常下单。',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        request({
          url: '/admin/menu/close',
          method: 'POST',
          data: { serveDate: this.data.serveDate, closed: next },
        })
          .then(() => {
            this.setData({ closed: next });
            wx.showToast({ title: next ? '已暂停' : '已恢复', icon: 'success' });
          })
          .catch((err) => app.toastError(err));
      },
    });
  },

  onGo(e) {
    const url = e.currentTarget.dataset.url;
    if (url) {
      wx.navigateTo({ url });
    }
  },

  onLogout() {
    wx.showModal({
      title: '退出商家端',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        wx.removeStorageSync(MERCHANT_KEY);
        wx.reLaunch({ url: '/pages/index/index' });
      },
    });
  },
});
