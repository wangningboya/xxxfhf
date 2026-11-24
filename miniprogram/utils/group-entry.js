/**
 * 群卡片进入时拿加密的群标识。
 *
 * 微信这块能力调整过几次，所以按「新接口优先、老接口兜底、都拿不到就返回 null」处理：
 * - wx.getGroupEnterInfo：当前推荐，不需要 shareTicket
 * - wx.getShareInfo：老接口，需要 withShareTicket 派生的 shareTicket
 *
 * 拿不到不算错误——用户改用邀请码进入即可，这本来就是设计好的第二条路。
 *
 * 日志（v3.52）：这条链路几乎每一步失败都是静默的（拿不到 → 回 null → 跳引导页），
 * 真机上出了事只能靠日志倒推，所以每一步都打一行。只打长度与错误码，**不打密文本身**：
 * 密文对排查没有意义，落到日志里反而是敏感数据。
 */
function log(stage, detail) {
  console.log('[group] ' + stage + (detail === undefined ? '' : ' ' + detail));
}

function warn(stage, detail) {
  console.warn('[group] ' + stage + (detail === undefined ? '' : ' ' + detail));
}

function getGroupEncrypted(options) {
  const opts = options || {};
  const shareTicket = opts.shareTicket;
  // scene 1044 = 群聊会话中的小程序卡片，是唯一能稳定拿到群信息的入口
  log('进入参数', 'scene=' + (opts.scene === undefined ? '-' : opts.scene));
  log('shareTicket', shareTicket ? '有' : '无（非群会话进入，或未开启 withShareTicket）');

  return new Promise((resolve) => {
    const viaShareInfo = (why) => {
      if (!shareTicket || !wx.getShareInfo) {
        warn('兜底不可用：' + (!shareTicket ? '没有 shareTicket' : '基础库不支持 getShareInfo'),
          (why ? '；上游原因：' + why : '') + '（不影响使用，走邀请码即可）');
        resolve(null);
        return;
      }
      log('改用 getShareInfo 兜底，shareTicket 长度=' + shareTicket.length);
      wx.getShareInfo({
        shareTicket,
        success: (res) => {
          if (res && res.encryptedData && res.iv) {
            log('getShareInfo 成功，密文长度=' + res.encryptedData.length);
            resolve(res);
          } else {
            warn('getShareInfo 返回不完整', res);
            resolve(null);
          }
        },
        fail: (err) => {
          warn('getShareInfo 失败',
            'errCode=' + (err && err.errCode) + ' errMsg=' + (err && err.errMsg));
          resolve(null);
        },
      });
    };

    if (!wx.getGroupEnterInfo) {
      viaShareInfo('基础库不支持 getGroupEnterInfo');
      return;
    }

    wx.getGroupEnterInfo({
      success: (res) => {
        if (res && res.encryptedData && res.iv) {
          log('getGroupEnterInfo 成功，密文长度=' + res.encryptedData.length);
          resolve(res);
        } else {
          viaShareInfo('getGroupEnterInfo 返回里没有 encryptedData/iv');
        }
      },
      fail: (err) => {
        // 常见 65206 = 用户已不在该群；非群场景进入也会失败，属正常情况
        warn('getGroupEnterInfo 失败',
          'errCode=' + (err && err.errCode) + ' errMsg=' + (err && err.errMsg));
        viaShareInfo('getGroupEnterInfo 失败');
      },
    });
  });
}

module.exports = { getGroupEncrypted };
