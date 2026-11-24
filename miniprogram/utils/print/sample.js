/**
 * 样张：测试打印与标尺样张。
 *
 * 为什么要有「标尺样张」而不是在软件里算偏移：
 * SDK 里**没有任何接口能读出「这一张偏了 0.4mm」**（`getPageInfos` 只支持 M2，
 * 内部那个 `setBiaoDing` 是死代码，未对外暴露）。所以只能打一张带刻度的纸让人拿尺子量。
 * 这是唯一能到 0.1mm 精度的办法，也是程序能给的最好工具。
 *
 * 标尺样张支持连打 20 张：连打会不会越打越歪，只能靠首末两张的留白差判断。
 */

const { runJob } = require('./print-runner');
const { JCAPICommon } = require('./jcapi');

const LINE_MODEL = JCAPICommon.SDKDrawTextLineModel;

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
      lineModel: conf.lineModel || LINE_MODEL.singleLine,
    },
  };
}

function line(x, y, w, h) {
  return { id: 2, x, y, w, h, rotation: 0 };
}

function rect(x, y, w, h, filled, linewidth) {
  return { id: 5, x, y, w, h, rotation: 0, filled: !!filled, linewidth: linewidth || 0.3 };
}

function page(elements, pageKey) {
  return {
    pageKey,
    w: 70,
    h: 40,
    rotation: 0,
    count: 1,
    gapType: JCAPICommon.SDKLabelType.gap,
    elements,
  };
}

/**
 * 测试样张：中英文、数字、线条、二维码位。
 *
 * 用途是看三件事：中文在 2.3~2.4mm 下清不清楚、线条连不连续、四边有没有被裁掉。
 * 四角各画一个 2mm 的直角标记，用它判断内容有没有偏移出安全区。
 */
function testModel() {
  const elements = [];
  // 安全边 3mm 的框：打在框外说明画布或间隙设置不对
  elements.push(rect(3, 3, 64, 34, false, 0.3));
  // 四角标记
  [[2, 2], [66, 2], [2, 36], [66, 36]].forEach((pos) => {
    elements.push(rect(pos[0], pos[1], 2, 2, true, 0.2));
  });
  elements.push(text({ x: 6, y: 5, w: 40, h: 6, value: '打印测试', fontSize: 3.6, bold: true }));
  elements.push(text({ x: 46, y: 5.5, w: 20, h: 4, value: '70×40', fontSize: 2.4, align: 'right' }));
  elements.push(text({
    x: 6, y: 12, w: 58, h: 5,
    value: '中文：红烧牛腩饭套餐 微辣 加饭', fontSize: 2.4,
  }));
  elements.push(text({
    x: 6, y: 17.5, w: 58, h: 5,
    value: 'ABC abc 0123456789', fontSize: 2.4,
  }));
  elements.push(line(6, 23, 58, 0.3));
  elements.push(text({ x: 6, y: 24.5, w: 30, h: 4, value: '2.3mm 小字', fontSize: 2.3 }));
  elements.push(text({ x: 38, y: 24.5, w: 26, h: 4, value: '取餐码 8521', fontSize: 2.3, align: 'right' }));
  // 二维码位：正式标签这里是 14×14 的小程序码
  elements.push(rect(50, 28, 14, 8, false, 0.3));
  elements.push(text({ x: 6, y: 31, w: 40, h: 5, value: '二维码位 14×14', fontSize: 2.2 }));
  return page(elements, 'sample-test');
}

/**
 * 标尺样张：量连打偏移用。
 *
 * 每 10mm 一道刻度、顶部 1mm 粗黑线（方便对齐标签上沿）、右下角印「第几张 / 共几张」。
 * 连打 20 张后量第一张与最后一张的上下留白差：一致说明纸张 pitch 准，
 * 差 0.5mm 以上就要去打印机的 App 里做一次纸张校准。
 */
function rulerModel(index, count) {
  const elements = [];
  // 顶部 1mm 粗黑线：与标签上沿的间距就是「上留白」，量它
  elements.push(rect(0, 0, 70, 1, true, 0.2));
  // 每 10mm 一道刻度，从 10 到 60
  for (let x = 10; x <= 60; x += 10) {
    elements.push(line(x, 0, 0.2, 3));
    elements.push(text({ x: x - 3, y: 3.5, w: 8, h: 3, value: String(x), fontSize: 1.8, align: 'center' }));
  }
  // 安全边 3mm：内容不该出这个框
  elements.push(rect(3, 6, 64, 28, false, 0.3));
  // 每 10mm 一道横向刻度
  for (let y = 10; y <= 30; y += 10) {
    elements.push(line(3, y, 3, 0.2));
    elements.push(text({ x: 6.5, y: y - 1.2, w: 8, h: 3, value: String(y), fontSize: 1.6 }));
  }
  elements.push(text({
    x: 20, y: 12, w: 48, h: 5,
    value: '标尺样张 10mm 刻度', fontSize: 2.6, bold: true,
  }));
  elements.push(text({
    x: 20, y: 18, w: 48, h: 4,
    value: '量上下留白是否一致', fontSize: 2.2,
  }));
  elements.push(text({
    x: 20, y: 26, w: 48, h: 5,
    value: '第 ' + index + ' / ' + count + ' 张', fontSize: 3.0, bold: true,
  }));
  return page(elements, 'sample-ruler-' + index);
}

/** 打一张测试样张 */
function printTest(settings, component, canvasId, callbacks) {
  const pages = [{ pageKey: 'sample-test', orderId: 'sample', comboIndex: 1 }];
  return runJob(Object.assign({
    pages,
    settings,
    component,
    canvasId,
    report: false,
    modelOf: () => testModel(),
  }, callbacks));
}

/** 连打 count 张标尺样张，默认 20 张 */
function printRuler(settings, component, canvasId, count, callbacks) {
  const total = Math.max(1, Math.min(30, Number(count) || 20));
  const pages = [];
  // 每张 comboIndex 不同，避免像素缓存把 20 张合成 1 张（那样序号永远是 1）
  for (let i = 1; i <= total; i++) {
    pages.push({ pageKey: 'sample-ruler-' + i, orderId: 'sample-ruler', comboIndex: i });
  }
  return runJob(Object.assign({
    pages,
    settings,
    component,
    canvasId,
    report: false,
    modelOf: (p) => rulerModel(Number(p.comboIndex) || 1, total),
  }, callbacks));
}

module.exports = { printTest, printRuler };
