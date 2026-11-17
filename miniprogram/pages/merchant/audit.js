const app = getApp();
const { request } = require('../../utils/request');
const dict = require('../../utils/dict');

/** 兜底文案，字典加载出来后被覆盖（商家可把「已通过」改成「已放行」） */
const FALLBACK_AUDIT = [
  { key: 'pending', label: '待审核' },
  { key: 'approved', label: '已通过' },
  { key: 'rejected', label: '已驳回' },
  { key: 'disabled', label: '已停用' },
];

/** 邀请码状态兜底。作废是商家主动撤销，与「过期」不是一回事，要分开显示 */
const FALLBACK_INVITE = {
  unused: '未使用',
  used: '已使用',
  expired: '已过期',
  revoked: '已作废',
};

/** 审核状态页签：字典里停用的项不出现，顺序按字典 sort */
function buildTabs() {
  const list = dict.tabs('audit_status', null, FALLBACK_AUDIT);
  return list.length ? list : FALLBACK_AUDIT;
}

function inviteStatusText(status) {
  return dict.label('invite_status', status, FALLBACK_INVITE[status] || status);
}

Page({
  data: {
    tabs: buildTabs(),
    auditStatus: 'pending',
    list: [],
    page: 1,
    pageSize: 20,
    finished: false,
    loading: false,
    keyword: '',
    /** 邀请码：新用户没有群卡片时靠它进来 */
    inviteCodes: [],
    showInvite: false,
    creating: false,
  },

  onShow() {
    this.setData({ tabs: buildTabs() });
    this.reload();
    this.loadInvites();
    // 冷启动首进：字典还没回来，等它到了再刷页签与邀请码状态文案
    if (!dict.ready()) {
      dict.load().then(() => {
        this.setData({ tabs: buildTabs() });
        this.loadInvites();
      });
    }
  },

  onReachBottom() {
    if (this.data.finished || this.data.loading) {
      return;
    }
    this.load(this.data.page + 1);
  },

  reload() {
    this.setData({ list: [], page: 1, finished: false });
    return this.load(1);
  },

  load(page) {
    if (this.data.loading) {
      return Promise.resolve();
    }
    this.setData({ loading: true });
    const query = ['auditStatus=' + this.data.auditStatus, 'page=' + page, 'pageSize=' + this.data.pageSize];
    if (this.data.keyword) {
      query.push('keyword=' + encodeURIComponent(this.data.keyword));
    }
    return request({ url: '/admin/user/list?' + query.join('&') })
      .then((result) => {
        const rows = (result.list || []).map((row) => ({
          userId: row.userId,
          nickName: row.nickName || '未填昵称',
          phone: row.phone || '',
          auditStatus: row.auditStatus,
          rejectReason: row.rejectReason || '',
          accessVia: row.accessVia,
          avatarUrl: row.avatarUrl,
        }));
        this.setData({
          list: page === 1 ? rows : this.data.list.concat(rows),
          page,
          finished: rows.length < this.data.pageSize,
        });
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ loading: false }));
  },

  loadInvites() {
    request({ url: '/admin/invite/list?page=1&pageSize=5' })
      .then((result) =>
        this.setData({
          // 状态文案在 js 里算好：wxml 里嵌套三元没法维护，加一个状态就得再嵌一层
          inviteCodes: (result.list || []).map((item) => ({
            ...item,
            statusText: inviteStatusText(item.status),
          })),
        })
      )
      .catch(() => {});
  },

  onRevokeCode(e) {
    const code = e.currentTarget.dataset.code;
    wx.showModal({
      title: '作废邀请码',
      content: '作废后 ' + code + ' 立刻失效，已用它进来的人不受影响。确定吗？',
      confirmText: '作废',
      confirmColor: '#d93026',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        request({ url: '/admin/invite/revoke', method: 'POST', data: { code } })
          .then(() => {
            wx.showToast({ title: '已作废', icon: 'success' });
            this.loadInvites();
          })
          .catch((err) => app.toastError(err));
      },
    });
  },

  onSwitchTab(e) {
    this.setData({ auditStatus: e.currentTarget.dataset.key });
    this.reload();
  },

  onKeywordInput(e) {
    this.setData({ keyword: (e.detail.value || '').trim() });
  },

  onSearch() {
    this.reload();
  },

  onAudit(e) {
    const userId = e.currentTarget.dataset.id;
    const action = e.currentTarget.dataset.action;
    if (action === 'reject') {
      wx.showModal({
        title: '驳回申请',
        editable: true,
        placeholderText: '必填：驳回原因，用户会看到',
        success: (res) => {
          if (!res.confirm) {
            return;
          }
          const reason = (res.content || '').trim();
          if (!reason) {
            wx.showToast({ title: '请填写驳回原因', icon: 'none' });
            return;
          }
          this.post(userId, action, reason);
        },
      });
      return;
    }
    const label = action === 'approve' ? '通过' : action === 'disable' ? '停用' : '恢复';
    wx.showModal({
      title: '确认' + label,
      success: (res) => {
        if (res.confirm) {
          this.post(userId, action);
        }
      },
    });
  },

  post(userId, action, reason) {
    request({ url: '/admin/user/audit', method: 'POST', data: { userId, action, reason } })
      .then(() => {
        wx.showToast({ title: '已处理', icon: 'success' });
        this.reload();
      })
      .catch((err) => app.toastError(err));
  },

  onToggleInvite() {
    this.setData({ showInvite: !this.data.showInvite });
  },

  onCreateInvite() {
    if (this.data.creating) {
      return;
    }
    this.setData({ creating: true });
    // 默认 48 小时：够群主把卡片发出去、对方隔天打开
    request({ url: '/admin/invite/create', method: 'POST', data: { expireHours: 48 } })
      .then((invite) => {
        wx.showModal({
          title: '邀请码已生成',
          content: invite.code + '\n有效期 48 小时，一次性使用',
          showCancel: false,
        });
        this.loadInvites();
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ creating: false }));
  },

  onCopyCode(e) {
    wx.setClipboardData({ data: e.currentTarget.dataset.code });
  },
});
