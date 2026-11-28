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
  partial: '部分使用',
  used: '已用完',
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
    /** 批量通过：只在待审核页签、只手动勾选（不做全选） */
    batchMode: false,
    batchCount: 0,
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
    // 列表重来时勾选一并清掉：留着上次的勾，商家会以为已经勾好了
    this.setData({ list: [], page: 1, finished: false, batchMode: false, batchCount: 0 });
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
          // 没填昵称的人列表照常显示，但通过/驳回置灰：认不出是谁，点了也只能靠猜
          canAudit: !!row.canAudit,
          checked: false,
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
            // 还有剩余次数的码才能作废；服务端同样按这个条件兜底
            canRevoke: item.status === 'unused' || item.status === 'partial',
          })),
        })
      )
      .catch(() => {});
  },

  onRevokeCode(e) {
    const code = e.currentTarget.dataset.code;
    const item = (this.data.inviteCodes || []).find((row) => row.code === code);
    const left = item ? Math.max((item.maxUses || 0) - (item.usedCount || 0), 0) : 0;
    wx.showModal({
      title: '作废邀请码',
      content: '作废后 ' + code + ' 剩余 ' + left + ' 次立即失效，已凭它进来的人不受影响。确定吗？',
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

  /* ---------------- 批量通过 ---------------- */

  onToggleBatch() {
    if (this.data.batchMode) {
      this.clearChecked();
      this.setData({ batchMode: false, batchCount: 0 });
      return;
    }
    this.setData({ batchMode: true, batchCount: 0 });
  },

  /** 批量模式下点卡片任意位置即勾选 / 取消；非批量模式什么都不做 */
  onCardTap(e) {
    if (!this.data.batchMode) {
      return;
    }
    const index = Number(e.currentTarget.dataset.index);
    const row = this.data.list[index];
    if (!row) {
      return;
    }
    if (!row.canAudit) {
      wx.showToast({ title: '对方还没填昵称，暂时不能审核', icon: 'none' });
      return;
    }
    this.setData({
      ['list[' + index + '].checked']: !row.checked,
      batchCount: this.countChecked() + (row.checked ? -1 : 1),
    });
  },

  countChecked() {
    return (this.data.list || []).filter((row) => row.checked).length;
  },

  clearChecked() {
    const patch = {};
    (this.data.list || []).forEach((row, index) => {
      if (row.checked) {
        patch['list[' + index + '].checked'] = false;
      }
    });
    if (Object.keys(patch).length) {
      this.setData(patch);
    }
  },

  onBatchApprove() {
    const picked = (this.data.list || []).filter((row) => row.checked);
    if (!picked.length) {
      wx.showToast({ title: '请先勾选要放行的人', icon: 'none' });
      return;
    }
    if (this.approving) {
      return;
    }
    wx.showModal({
      title: '批量通过',
      content: '确定通过选中的 ' + picked.length + ' 位用户？',
      success: (res) => {
        if (res.confirm) {
          this.runBatchApprove(picked);
        }
      },
    });
  },

  /**
   * 逐条发请求：与后台「用户管理」的批量通过同一做法 ——
   * 单条失败（比如对方刚自己改了昵称、或状态已被别处改掉）不该拖住其余人，
   * 最后汇总一次结果，失败的把人名列出来让商家自己去补。
   */
  runBatchApprove(picked) {
    this.approving = true;
    wx.showLoading({ title: '处理中…', mask: true });
    let ok = 0;
    const failed = [];

    const one = (i) => {
      if (i >= picked.length) {
        return Promise.resolve();
      }
      return request({
        url: '/admin/user/audit',
        method: 'POST',
        data: { userId: picked[i].userId, action: 'approve' },
      })
        .then(() => {
          ok += 1;
        })
        .catch(() => {
          failed.push(picked[i].nickName);
        })
        .then(() => one(i + 1));
    };

    one(0).then(() => {
      wx.hideLoading();
      this.approving = false;
      this.reload();
      if (!failed.length) {
        wx.showToast({ title: '已通过 ' + ok + ' 位', icon: 'none' });
        return;
      }
      wx.showModal({
        title: '部分未通过',
        content: '已通过 ' + ok + ' 位；以下未通过：' + failed.join('、'),
        showCancel: false,
      });
    });
  },

  onAudit(e) {
    const { id: userId, action, canAudit } = e.currentTarget.dataset;
    // 置灰的按钮也走这里：给一句人话，别让商家以为是网络问题
    if ((action === 'approve' || action === 'reject') && !canAudit) {
      wx.showToast({ title: '对方还没填昵称，暂时不能审核', icon: 'none' });
      return;
    }
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
    // 有效期上限读后台配置（系统配置 → 业务限制），不再写死 48：
    // 后台把上限调到 24 时，固定发 48 会被服务端直接拒掉。
    // 取不到就按「不显示上限、本地不拦」处理，交给服务端判定。
    request({ url: '/admin/config' })
      .then((cfg) => {
        const max = Number((cfg || {}).inviteMaxExpireHours);
        this.askUses(max >= 1 ? max : 0);
      })
      .catch(() => this.askUses(0));
  },

  /** 第一步：可用次数。一个码能被多少人各用一次，边界与服务端一致（≥1，不设上限） */
  askUses(maxHours) {
    wx.showModal({
      title: '生成邀请码',
      editable: true,
      placeholderText: '这张码可被多少人使用？',
      content: '1',
      confirmText: '下一步',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        const uses = parseInt((res.content || '1').trim(), 10);
        if (!(uses >= 1)) {
          wx.showToast({ title: '次数需为 1 以上的整数', icon: 'none' });
          return;
        }
        this.askExpire(uses, maxHours);
      },
    });
  },

  /** 第二步：有效期。不预填数字，商家自己填 */
  askExpire(uses, maxHours) {
    wx.showModal({
      title: '有效期（小时）',
      editable: true,
      placeholderText: maxHours > 0
        ? '有效期内可用，不超过 ' + maxHours
        : '有效期内可用',
      content: '',
      confirmText: '生成',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        const hours = parseInt((res.content || '').trim(), 10);
        if (!(hours >= 1)) {
          wx.showToast({ title: '有效期需为 1 以上的整数小时', icon: 'none' });
          return;
        }
        if (maxHours > 0 && hours > maxHours) {
          wx.showToast({ title: '有效期不能超过 ' + maxHours + ' 小时', icon: 'none' });
          return;
        }
        this.createInvite(uses, hours);
      },
    });
  },

  createInvite(uses, expireHours) {
    this.setData({ creating: true });
    request({ url: '/admin/invite/create', method: 'POST', data: { uses, expireHours } })
      .then((code) => {
        if (!code || !code.code) {
          return;
        }
        this.showCreatedInvite(code);
        this.loadInvites();
      })
      .catch((err) => app.toastError(err))
      .then(() => this.setData({ creating: false }));
  },

  /** 生成完的下一步就是发给对方，所以确认键直接当「复制」用 */
  showCreatedInvite(code) {
    const expireAt = (code.expireAt || '').replace('T', ' ').slice(0, 16);
    wx.showModal({
      title: '邀请码已生成',
      content: code.code + '\n可用 ' + code.maxUses + ' 次，有效期至 ' + expireAt,
      confirmText: '复制',
      cancelText: '关闭',
      success: (res) => {
        if (res.confirm) {
          this.copyCode(code.code);
        }
      },
    });
  },

  /** 列表里的「全部复制」：只复制还有剩余次数的，已用完/已作废的发给别人也没用 */
  onCopyAllCodes() {
    const codes = (this.data.inviteCodes || [])
      .filter((item) => item.canRevoke)
      .map((item) => item.code);
    if (!codes.length) {
      wx.showToast({ title: '没有还能用的邀请码', icon: 'none' });
      return;
    }
    this.copyCode(codes.join('\n'));
  },

  copyCode(code) {
    if (!code) {
      return;
    }
    wx.setClipboardData({ data: code });
  },

  onCopyCode(e) {
    this.copyCode(e.currentTarget.dataset.code);
  },
});
