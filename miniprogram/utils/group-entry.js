/**
 * 群卡片进入时拿加密的群标识。
 *
 * 微信这块能力调整过几次，所以按「新接口优先、老接口兜底、都拿不到就返回 null」处理：
 * - wx.getGroupEnterInfo：当前推荐，不需要 shareTicket
 * - wx.getShareInfo：老接口，需要 withShareTicket 派生的 shareTicket
 *
 * 拿不到不算错误——用户改用邀请码进入即可，这本来就是设计好的第二条路。
 */
function getGroupEncrypted(options) {
  return new Promise((resolve) => {
    const viaShareInfo = () => {
      const shareTicket = options && options.shareTicket;
      if (!shareTicket || !wx.getShareInfo) {
        resolve(null);
        return;
      }
      wx.getShareInfo({
        shareTicket,
        success: (res) => resolve(res && res.encryptedData ? res : null),
        fail: () => resolve(null),
      });
    };

    if (!wx.getGroupEnterInfo) {
      viaShareInfo();
      return;
    }

    wx.getGroupEnterInfo({
      success: (res) => {
        if (res && res.encryptedData && res.iv) {
          resolve(res);
        } else {
          viaShareInfo();
        }
      },
      fail: () => viaShareInfo(),
    });
  });
}

module.exports = { getGroupEncrypted };
