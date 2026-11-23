/**
 * 证照公示页。
 *
 * 依据：《网络交易监督管理办法》第十二条（公示营业执照信息）、《网络餐饮服务食品安全监督管理办法》
 * 第九条（公示食品经营许可证）。入口在「我的」页「证照公示」一行。
 *
 * 图片放在服务器上，不占小程序包体：`shop-info.js` 里填 `/upload/...` 或完整 https 地址，
 * 这里统一过一遍 assetUrl 拼上 apiBase——不拼的话小程序会当成代码包内文件去找，找不到就白图。
 */

const shop = require('../../shop-info');
const { assetUrl } = require('../../utils/format');

function filled(value) {
  return (value || '').trim();
}

Page({
  data: {
    licenses: [],
  },

  onLoad() {
    const images = shop.licenseImages || {};
    // 只放已配置的图；两个都没填时页面走空态，不给用户看运营的自查清单
    const licenses = [
      { key: 'businessLicense', name: '营业执照', src: assetUrl(filled(images.businessLicense)) },
      { key: 'foodPermit', name: '食品经营许可证', src: assetUrl(filled(images.foodPermit)) },
    ].filter((item) => !!item.src);

    this.setData({ licenses });
  },

  /** 点图全屏看：证照上的小字不放大根本看不清 */
  onPreview(e) {
    const src = e.currentTarget.dataset.src;
    if (!src) {
      return;
    }
    wx.previewImage({
      current: src,
      urls: this.data.licenses.map((item) => item.src),
    });
  },
});
