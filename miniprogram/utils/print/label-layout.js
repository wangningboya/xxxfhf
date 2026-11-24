/**
 * 标签版面：把服务端给的标签数据排成 SDK 能画的页面模型。
 *
 * 两套模板（配送 / 自取）分别一张坐标表，单位是**毫米**，与需求文档 3.3 一一对应。
 * 坐标系原点在标签左上角，标签 70×40mm。
 *
 * 为什么坐标写死在端上、服务端只给字段：
 * 改版式不该发后端。服务端负责「内容」（截断到几个字、电话后四位、模板裁剪），
 * 端上负责「位置」。两边各管一件事，不会出现服务端改了文案端上排版错位。
 *
 * 元素 id 是 SDK 的绘制类型，不是自定义枚举：
 * 1/7 文字、2 直线、4 二维码、6 图片。这里只用到 1、2、6。
 */

const { JCAPICommon } = require('./jcapi');

const LINE_MODEL = JCAPICommon.SDKDrawTextLineModel;

/** 标签物理尺寸（mm）。写死 70×40，换纸要同时改这里和 3.3 两张坐标表 */
const LABEL_W = 70;
const LABEL_H = 40;

/** 一份一张时打不打 `×1`：单份没有区分意义，只在多份时画 */
function copyBadgeText(page) {
  const total = Number(page.copyTotal) || 1;
  return total > 1 ? '×' + total : '';
}

/** 联系人行：`张先生  ****5678`；没留电话时给一句人话，不能出现 `****null` */
function contactText(page) {
  const name = page.contactName || '顾客';
  const tail = page.phoneTail ? '****' + page.phoneTail : '未留电话';
  return name + '  ' + tail;
}

/** 标题行：套餐名与供餐日拼成一串，交给同一个文字元素，日期天然紧跟（需求 3.4） */
function titleText(page) {
  const name = page.comboName || '';
  const date = page.serveDate || '';
  return date ? name + ' ' + date : name;
}

function text(conf) {
  return {
    id: 1,
    x: conf.x,
    y: conf.y,
    w: conf.w,
    h: conf.h,
    rotation: 0,
    value: conf.value == null ? '' : String(conf.value),
    fontSize: conf.fontSize,
    options: {
      bold: !!conf.bold,
      align: conf.align || 'left',
      // 1 单行；4 换行并按框高截断（地址、小菜两行靠它）
      lineModel: conf.lineModel || LINE_MODEL.singleLine,
    },
  };
}

function line(x, y, w, h) {
  return { id: 2, x, y, w, h, rotation: 0 };
}

function image(x, y, w, h, path) {
  return { id: 6, x, y, w, h, rotation: 0, value: path };
}

/**
 * 右栏（取餐码 / 二维码 / 份数）—— 两套模板共用一套坐标，只有取餐码字号不同。
 *
 * 配送单不打取餐码（`opts.showCode === false`）：餐送到门口，顾客不在店里等，
 * 喊 4 位号没人应；右栏只留二维码和份数。
 *
 * 无二维码时走降级（需求 3.5）：取餐码块整体下移并放大到 7.0mm，垂直上占据右栏主体。
 * 不降级的话右栏会空出 14mm，打出来像是漏印了。
 */
function rightColumn(page, opts) {
  const elements = [];
  const hasQr = !!opts.qrPath;
  const showCode = opts.showCode !== false;

  if (showCode) {
    if (hasQr) {
      elements.push(text({
        x: 50, y: 2.0, w: 17.5, h: 3.0, value: '取餐码',
        fontSize: 2.0, align: 'right',
      }));
      elements.push(text({
        x: 50, y: 5.0, w: 17.5, h: 6.5, value: page.pickupCode || '',
        fontSize: opts.pickupCodeFont, bold: true, align: 'right',
      }));
    } else {
      elements.push(text({
        x: 50, y: 7.0, w: 17.5, h: 3.2, value: '取餐码',
        fontSize: 2.2, align: 'right',
      }));
      elements.push(text({
        x: 50, y: 12.0, w: 17.5, h: 8.0, value: page.pickupCode || '',
        fontSize: 7.0, bold: true, align: 'right',
      }));
    }
  }
  if (hasQr) {
    elements.push(image(51.5, opts.qrY, 14, 14, opts.qrPath));
  }

  const badge = copyBadgeText(page);
  if (badge) {
    elements.push(text({
      x: 50, y: 27.0, w: 17.5, h: 5.0, value: badge,
      fontSize: 3.0, bold: true, align: 'right',
    }));
  }
  return elements;
}

/**
 * 配送单：突出「送到哪」。
 *
 * 区域单独一行放大到 3.0mm 粗体，因为分拣时是**按区域分堆**的，
 * 把它和地址挤在一行会让分堆这一步失去视觉锚点。
 *
 * 不再打印取餐码（送到门口喊号没人应），右栏只留二维码与份数。
 * 小菜+规格按**单行**留高 4.4mm：服务端上限 19 个汉字（PrintService.DISH_SPEC_UNITS=38，
 * 中文按 2 个半角单位算），45.5mm ÷ 2.3mm 正好一行，按两行留 8.4mm 会空出约 4mm 的死白。
 */
function deliveryPage(page, opts) {
  const elements = [];

  elements.push(text({
    x: 2.5, y: 2.0, w: 45.5, h: 6.0, value: titleText(page),
    fontSize: 3.4, bold: true,
  }));
  elements.push(text({
    x: 2.5, y: 8.2, w: 45.5, h: 4.4, value: page.dishSpecText || '',
    fontSize: 2.3, lineModel: LINE_MODEL.wrapAndTruncate,
  }));
  elements.push(line(2.5, 12.8, 45.5, 0.3));

  // 区域缺失时只跳过这一行，地址仍在固定 y 上，不会往上叠（验收第 16 条）
  if (page.regionName) {
    elements.push(text({
      x: 2.5, y: 13.4, w: 45.5, h: 5.4, value: page.regionName,
      fontSize: 3.0, bold: true,
    }));
  }
  elements.push(text({
    x: 2.5, y: 19.0, w: 45.5, h: 8.0, value: page.addressText || '',
    fontSize: 2.5, lineModel: LINE_MODEL.wrapAndTruncate,
  }));
  elements.push(text({
    x: 2.5, y: 27.0, w: 45.5, h: 4.2, value: contactText(page),
    fontSize: 2.4,
  }));
  if (page.remark) {
    elements.push(text({
      x: 2.5, y: 31.4, w: 65.0, h: 4.0, value: page.remark,
      fontSize: 2.3, lineModel: LINE_MODEL.wrapWithEllipsis,
    }));
  }

  return elements.concat(rightColumn(page, Object.assign({ showCode: false, qrY: 12.0 }, opts)));
}

/**
 * 自取单：突出「几点来取」。
 *
 * 不打区域、地址、店铺名——餐盒就在店里，这些字段对经手人零信息量。
 * 省下的空间把时段放大到 3.6mm 粗体：用户在等这个时间，店员按这个时间分装。
 */
function pickupPage(page, opts) {
  const elements = [];

  elements.push(text({
    x: 2.5, y: 2.0, w: 45.5, h: 6.5, value: titleText(page),
    fontSize: 3.6, bold: true,
  }));
  elements.push(text({
    x: 2.5, y: 8.6, w: 45.5, h: 8.4, value: page.dishSpecText || '',
    fontSize: 2.4, lineModel: LINE_MODEL.wrapAndTruncate,
  }));
  elements.push(line(2.5, 17.2, 45.5, 0.3));

  elements.push(text({
    x: 2.5, y: 17.8, w: 45.5, h: 5.0, value: contactText(page),
    fontSize: 2.8,
  }));
  if (page.pickupSlot) {
    elements.push(text({
      x: 2.5, y: 23.0, w: 45.5, h: 6.5, value: page.pickupSlot,
      fontSize: 3.6, bold: true,
    }));
  }
  elements.push(text({
    x: 2.5, y: 29.8, w: 45.5, h: 4.0, value: '到店自取',
    fontSize: 2.6,
  }));
  if (page.remark) {
    elements.push(text({
      x: 2.5, y: 34.5, w: 65.0, h: 4.5, value: page.remark,
      fontSize: 2.3, lineModel: LINE_MODEL.wrapWithEllipsis,
    }));
  }

  return elements.concat(rightColumn(page, Object.assign({ pickupCodeFont: 6.0, qrY: 11.8 }, opts)));
}

/**
 * 生成一页的页面模型。
 *
 * @param {object} page  服务端返回的一张（已展开到张）
 * @param {object} opts  { qrPath } 二维码本地路径（未下载成功传空串，走降级布局）
 * @return {object} renderPrintPage 直接可用的页面模型
 */
function buildPageModel(page, opts) {
  const options = opts || {};
  const isPickup = page.template === 'pickup';
  const elements = isPickup ? pickupPage(page, options) : deliveryPage(page, options);
  return {
    pageKey: page.pageKey,
    w: LABEL_W,
    h: LABEL_H,
    rotation: 0,
    // 一张一次 print 调用，count 恒为 1（需求 4.1）
    count: 1,
    gapType: JCAPICommon.SDKLabelType.gap,
    elements,
  };
}

module.exports = { buildPageModel, LABEL_W, LABEL_H, LINE_MODEL };
