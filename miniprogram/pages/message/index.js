const app = getApp();
const { request } = require('../../utils/request');
const { relativeTime } = require('../../utils/format');

const PAGE_SIZE = 20;

/**
 * 消息类型 → 图标与配色。
 *
 * 用 emoji 而不是图标字体：项目里已经在用（商家详情「📞 未留电话」），
 * 不必为一个页面引入一套图标资源。
 */
const STYLES = {
  accepted: { icon: '✅', tone: 'ok' },
  ready: { icon: '🔔', tone: 'ok' },
  delivered: { icon: '🛵', tone: 'ok' },
  rejected: { icon: '❌', tone: 'danger' },
  cancelled: { icon: '🚫', tone: 'muted' },
  newOrder: { icon: '📦', tone: 'warn' },
  cancelRequest: { icon: '↩️', tone: 'warn' },
};

function decorate(item) {
  const style = STYLES[item.msgType] || { icon: '🔔', tone: 'muted' };
  return Object.assign({}, item, {
    icon: style.icon,
    tone: style.tone,
    timeText: relativeTime(item.createdAt),
  });
}

Page({
  data: {
    /** user / merchant，决定查哪一侧的消息 */
    side: 'user',
    items: [],
    page: 1,
    hasMore: true,
    loading: true,
    loadingMore: false,
  },

  onLoad(options) {
    const role = (options && options.role) === 'merchant' ? 'merchant' : '';
    this.setData({ side: role ? 'merchant' : 'user' });
    wx.setNavigationBarTitle({ title: role ? '商家消息' : '消息中心' });
  },

  onShow() {
    this.refresh();
  },

  onPullDownRefresh() {
    this.refresh().then(() => wx.stopPullDownRefresh());
  },

  onReachBottom() {
    if (this.data.hasMore && !this.data.loadingMore) {
      this.load(this.data.page + 1);
    }
  },

  refresh() {
    return this.load(1);
  },

  /**
   * 取一页。第 1 页替换列表，其余追加。
   *
   * hasMore 用 items.length < total 判断而不是「本页不足 20 条」：
   * 90 天清理任务会删掉旧消息，总数在翻页途中可能变小，用总数判断更稳。
   */
  load(page) {
    const first = page === 1;
    this.setData(first ? { loading: true } : { loadingMore: true });

    return request({
      url: '/message/list?side=' + this.data.side + '&page=' + page + '&pageSize=' + PAGE_SIZE,
    })
      .then((res) => {
        const list = (res.list || []).map(decorate);
        const items = first ? list : this.data.items.concat(list);
        this.setData({
          items,
          page,
          hasMore: items.length < (res.total || 0),
        });
      })
      .catch((err) => {
        // 401 时 refresh 失败，页面停在空态；提示交给 toastError，不静默
        app.toastError(err);
        this.setData({ hasMore: false });
      })
      .then(() => {
        this.setData({ loading: false, loadingMore: false });
      });
  },

  /**
   * 点一条：标已读，然后跳对应订单详情。
   *
   * 跳哪一边看**这条消息属于哪一侧**（userType），不看当前账号是不是商家：
   * 商家账号买的单，收到的「已接单」属于用户侧，该跳用户端详情。
   *
   * 标已读失败不拦跳转 —— 用户要看的是订单，不是那一行红点。
   */
  onTapItem(e) {
    const index = e.currentTarget.dataset.index;
    const item = this.data.items[index];
    if (!item) {
      return;
    }

    const mark = item.unread
      ? request({ url: '/message/' + item.id + '/read', method: 'POST' }).catch(() => {})
      : Promise.resolve();

    mark.then(() => {
      if (item.unread) {
        this.setData({ ['items[' + index + '].unread']: false });
      }
      if (!item.orderId) {
        wx.showToast({ title: '这条消息没有关联订单', icon: 'none' });
        return;
      }
      const url =
        item.userType === 'merchant'
          ? '/pages/merchant/order-detail?orderId=' + item.orderId
          : '/pages/order/detail?orderId=' + item.orderId;
      wx.navigateTo({ url });
    });
  },

  /**
   * 扫把：全部已读。
   *
   * 不弹二次确认：误触的代价只是红点消失，不动任何业务数据，而这个按钮
   * 就是给「积了一堆未读、懒得一条条点」用的，多一步确认等于多一道麻烦。
   */
  onReadAll() {
    request({ url: '/message/read-all?side=' + this.data.side, method: 'POST' })
      .then((count) => {
        if (!count) {
          wx.showToast({ title: '没有未读消息', icon: 'none' });
          return;
        }
        const items = this.data.items.map((item) => Object.assign({}, item, { unread: false }));
        this.setData({ items });
        wx.showToast({ title: '已全部标为已读', icon: 'success' });
      })
      .catch((err) => app.toastError(err));
  },
});
