/**
 * 转发入口（v3.52）。
 *
 * 微信的两条硬规则，缺一条群卡片这条路就走不通：
 * 1. 页面必须定义 onShareAppMessage，右上角菜单里才会出现「转发」——不定义连按钮都没有；
 * 2. 转发前必须 showShareMenu({ withShareTicket: true })，否则从群里点开时
 *    onLoad 的 options 里没有 shareTicket，wx.getShareInfo 这条兜底也就拿不到密文。
 *
 * wx.getGroupEnterInfo 本身不需要 shareTicket，但它是「群场景才给数据」的接口，
 * 所以分享入口仍然是整条链路的前提：没有卡片被转进群，就没有群场景。
 *
 * ⚠️ 不要给 showShareMenu 传 menus：官方文档标注该参数为 **Beta，暂只在 Android 平台支持**。
 * 它的默认值就是 ['shareAppMessage']（正好是我们要的），显式传只会在 iOS 上多担一份平台差异风险。
 */

/** 转发前调一次，让本次分享的卡片带上 shareTicket。 */
function enableShareMenu() {
  if (!wx.showShareMenu) {
    console.warn('[share] 当前基础库不支持 showShareMenu，转发入口不可用');
    return;
  }
  wx.showShareMenu({
    withShareTicket: true,
    success: () => console.log('[share] 转发菜单已开启（withShareTicket=true）'),
    fail: (err) => console.warn('[share] 开启转发菜单失败', err && err.errMsg, err && err.errCode),
  });
}

/**
 * 分享卡片内容。
 *
 * path 固定落在 tabBar 页：群成员点进来要能立刻看到菜单，而不是落在一个只讲活动的中间页。
 *
 * @param {string} title 卡片标题，一般用店铺名
 * @param {string} page  落地页，默认首页
 */
function buildShareCard(title, page) {
  const path = (page || '/pages/home/index') + '?from=share';
  // 打一行再返回：真机上「点了转发没反应」和「按钮根本没出现」是两种完全不同的故障，
  // 有这行日志才能一眼分清
  console.log('[share] onShareAppMessage 已触发 path=' + path);
  return {
    title: title ? title + '｜今天吃什么' : '今天吃什么',
    path,
  };
}

module.exports = { enableShareMenu, buildShareCard };
