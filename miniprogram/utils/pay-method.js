const dict = require('./dict');

/**
 * 收款方式选择（需求 4.10 / 4.20 / D-25）。
 *
 * 选项以字典 `pay_method` 为准：商家在网页后台的「数据字典」里就能加一个
 * 「银行转账」，小程序不该为此发版。停用的项不会出现，排序也跟字典走。
 *
 * 没取到字典时退回内置三项（与服务端 PayMethod 常量一致），
 * 保证弱网 / 首次进入也能选。
 */
const FALLBACK = [
  { code: 'wechat', label: '微信' },
  { code: 'alipay', label: '支付宝' },
  { code: 'cash', label: '现金' },
];

/** 可选方式：字典优先，空则兜底 */
function options() {
  const list = dict.items('pay_method');
  if (!list.length) {
    return FALLBACK;
  }
  return list.map((item) => ({ code: item.itemValue, label: item.itemLabel }));
}

/** 存值 → 显示名；字典里没有该项（已停用 / 已删除）返回空串 */
function label(code) {
  const hit = options().filter((item) => item.code === code)[0];
  return hit ? hit.label : '';
}

/** 弹出三选一（也可能是 N 选一）；取消时 reject 成 { cancelled: true }，调用方据此不提示错误 */
function pick() {
  const list = options();
  return new Promise((resolve, reject) => {
    wx.showActionSheet({
      itemList: list.map((item) => item.label),
      success: (res) => resolve(list[res.tapIndex].code),
      fail: () => reject({ cancelled: true }),
    });
  });
}

/**
 * 选择收款方式。
 *
 * @param {string} currentCode 已知的申报值（商家确认收款时传 order.payMethod）
 * @returns {Promise<string>} 选中的存值
 *
 * 用户申报过就默认走他申报的那个：先问一句「实际收到的也是吗」，
 * 点「是的」一次就提交，要改再弹选择 —— 这是「默认带出、可改」在
 * showActionSheet 不支持预选时的落地方式。没申报过（现金单没点按钮）则必须自己选。
 *
 * 申报的方式若已被商家从字典里停用 / 删除，{@link label} 返回空，
 * 直接落到选择，不拿一个提交必被拒的旧值去问「也是吗」。
 */
function choose(currentCode) {
  if (dict.ready()) {
    return doChoose(currentCode);
  }
  // 字典还没到就先取一次，否则会把「已有申报值」当成没有，白让商家重选一遍
  return dict.load().then(() => doChoose(currentCode));
}

function doChoose(currentCode) {
  const current = label(currentCode);
  if (!current) {
    return pick();
  }
  return new Promise((resolve, reject) => {
    wx.showModal({
      title: '确认收款方式',
      content: '用户申报的付款方式是「' + current + '」，实际收到的也是吗？',
      confirmText: '是的',
      cancelText: '改一下',
      success: (res) => {
        if (res.confirm) {
          resolve(currentCode);
          return;
        }
        pick().then(resolve).catch(reject);
      },
      fail: reject,
    });
  });
}

module.exports = { options, label, choose };
