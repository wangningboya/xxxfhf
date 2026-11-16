const app = getApp();
const { request } = require('../../utils/request');
const { dateStr, addDays } = require('../../utils/format');

const MERCHANT_KEY = 'meal_merchant';

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
  };
}

Page({
  data: {
    displayName: '',
    serveDate: '',
    pendingAccept: 0,
    pendingAudit: 0,
    comboCount: 0,
    closed: false,
    cutoff: false,
    cutoffText: '',
    loading: true,
    can: {},
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
    this.load();
    this.startTimer();
  },

  onHide() {
    this.stopTimer();
  },

  onUnload() {
    this.stopTimer();
  },

  load() {
    const serveDate = this.data.serveDate;
    const tasks = [
      // 待接单数：分页接口只取 total，pageSize 给 1 就够
      request({
        url: '/admin/order/list?serveDate=' + serveDate + '&status=pending_accept&page=1&pageSize=1',
      }).then((r) => this.setData({ pendingAccept: r.total || 0 })),
      request({ url: '/admin/user/list?auditStatus=pending&page=1&pageSize=1' }).then((r) =>
        this.setData({ pendingAudit: r.total || 0 })
      ),
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
