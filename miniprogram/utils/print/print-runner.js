/**
 * 打印执行器：把一队标签一张一张推到打印机，并逐张记账。
 *
 * 三条硬约束（都是踩过的坑，不要"优化"掉）：
 *
 * 1. **canvas 是单例**。SDK 内部只认一个画布上下文，上一张 `endDrawLabel` 完成前
 *    不能开始下一张，否则两张的像素会互相覆盖，打出「A 的版面上印着 B 的菜」。
 *    所以所有绘制都进同一个串行队列。
 * 2. **一张一次 `print`，`count` 恒为 1**。用 `count:N` 让打印机连打，
 *    中间卡纸时整个作业只返回一个失败，无从知道出了几张、该补哪张。
 * 3. **同套餐的多份复用像素数据**。拆成一张一张推以后传输量按份数翻倍，
 *    若连 canvas 也重画 N 次，渲染开销也翻倍。缓存 key 用 `orderId:comboIndex`，
 *    **不能带份序号**，带了永远命中不了，等于没缓存。
 */

const { JCAPI, JCAPICommon } = require('./jcapi');
const { buildPageModel } = require('./label-layout');
const { assetUrl } = require('../format');
const { LOCAL_ERROR } = require('./error-text');
const { reportPage, flushPending } = require('./pending');
const preview = require('./preview');
const printer = require('./printer');

/** 缓存上限：一单最多三四个套餐，3 条足够；不做全局 LRU，一批打完直接清空 */
const CACHE_LIMIT = 3;

/** 二维码预取并发数：4 路足够快，又不至于把手机网络打满 */
const PREFETCH_CONCURRENCY = 4;

/* ==================== canvas 串行队列 ==================== */

let canvasQueue = Promise.resolve();

function enqueueCanvas(task) {
  const execution = canvasQueue.catch(() => undefined).then(task);
  canvasQueue = execution.catch(() => undefined);
  return execution;
}

/* ==================== 绘制 ==================== */

function drawText(common, element) {
  const para = new common.SDKDrawTextTemplate(element.x, element.y, element.w, element.h, element.rotation);
  para.content = element.value;
  para.fontSize = element.fontSize;
  para.bold = element.options && element.options.bold ? element.options.bold : null;
  para.italic = null;
  para.family = null;
  para.align = element.options && element.options.align ? element.options.align : null;
  para.lineModel = element.options && element.options.lineModel
    ? parseInt(element.options.lineModel, 10)
    : common.SDKDrawTextLineModel.singleLine;
  return para;
}

/**
 * 绘制单个元素。空内容直接跳过，不交给 SDK —— 空串会让 SDK 画一个不可见的框，
 * 虽然看不出来，但每次多一次蓝牙往返。
 */
function drawElement(element) {
  if (!element) {
    return Promise.resolve();
  }
  if (element.id === 1) {
    if (!element.value) {
      return Promise.resolve();
    }
    return Promise.resolve(JCAPI.drawText(drawText(JCAPICommon, element)));
  }
  if (element.id === 2) {
    return Promise.resolve(JCAPI.drawLine(
      new JCAPICommon.SDKDrawLineTemplate(element.x, element.y, element.w, element.h, element.rotation)
    ));
  }
  if (element.id === 5) {
    // 矩形只有样张用（安全边框、标尺粗线），标签模板不用
    const RectangleTemplate = JCAPICommon.SDKDrawRectangleTemplate || JCAPICommon.SDKDrawRectagleTemplate;
    const para = new RectangleTemplate(element.x, element.y, element.w, element.h, element.rotation);
    para.filled = !!element.filled;
    para.lineWidth = element.linewidth || 0.3;
    return Promise.resolve(JCAPI.drawRectangle(para));
  }
  if (element.id === 6) {
    if (!element.value) {
      return Promise.resolve();
    }
    const para = new JCAPICommon.SDKDrawImageTemplate(element.x, element.y, element.w, element.h, element.rotation);
    para.path = element.value;
    return Promise.resolve(JCAPI.drawImage(para));
  }
  return Promise.resolve();
}

/**
 * 画一页并取回像素数据。
 *
 * @param {object} model     页面模型（毫米坐标）
 * @param {object} component 页面实例，SDK 用它找 canvas
 * @param {string} canvasId  与 wxml 里 canvas 的 canvas-id 一致
 * @param {object} [opts]    `{snapshot: true}` 时在 endDrawLabel 前多截一张预览图
 * @return {Promise<{data: string, width: number, height: number, previewPath: string}>}
 */
function renderPage(model, component, canvasId, opts) {
  return enqueueCanvas(async () => {
    const start = new JCAPICommon.SDKDrawLabelTemplate();
    start.canvasId = canvasId || 'test';
    start.component = component;
    start.width = model.w;
    start.height = model.h;
    start.rotation = model.rotation;
    start.refresh = true;
    await JCAPI.startDrawLabel(start);

    const elements = model.elements || [];
    for (let i = 0; i < elements.length; i++) {
      await drawElement(elements[i]);
    }

    // 截图必须在 endDrawLabel 之前：SDK 取完像素收尾时可能清画布，之后再截容易拿到白图。
    // 截图失败只降级成空路径，绝不阻断后面的打印。
    let previewPath = '';
    if (opts && opts.snapshot) {
      const tempPath = await preview.snapshot(component, canvasId || 'test');
      previewPath = preview.persist(tempPath, model.pageKey);
    }

    const result = await JCAPI.endDrawLabel(model.gapType || 1);
    if (!result || !result.data) {
      throw new Error('标签像素读取失败');
    }
    return Object.assign({}, result, { previewPath });
  });
}

/**
 * 预览一张标签：与打印走完全相同的绘制路径，唯一差别是 endDrawLabel 前多截一张图。
 *
 * 不产生打印记录，也不要求打印机已连接（画布是本地渲染）；二维码仍按打印时的方式
 * 预取一次，否则预览里不会有码，看到的不等于要打的。
 */
function previewPage(page, component, canvasId) {
  return prefetchQr([page]).then((paths) => {
    const qrPath = page.qrImageUrl ? (paths && paths[page.qrImageUrl]) || '' : '';
    const model = buildPageModel(page, { qrPath });
    return renderPage(model, component, canvasId, { snapshot: true });
  });
}

/* ==================== 二维码预取 ==================== */

/**
 * 把二维码 URL 下载成本地临时路径。
 *
 * 必须在 `startJob` 之前做完：打印过程中每张临时等一次网络，30 张就是几十秒，
 * 而且中途失败没有地方报错。同一订单的多份共用一个 URL，这里按 URL 去重。
 */
function prefetchQr(pages) {
  const urls = [];
  const seen = {};
  (pages || []).forEach((page) => {
    const url = page && page.qrImageUrl;
    if (url && !seen[url]) {
      seen[url] = true;
      urls.push(url);
    }
  });
  const paths = {};
  if (!urls.length) {
    return Promise.resolve(paths);
  }

  let cursor = 0;
  function worker() {
    if (cursor >= urls.length) {
      return Promise.resolve();
    }
    const url = urls[cursor++];
    return new Promise((resolve) => {
      wx.getImageInfo({
        // 服务端按项目惯例只存相对路径（/upload/qr/x.png），`<image>` 会把它当包内路径，
        // getImageInfo 则直接失败 —— 不补全域名的话每张码都下不来，所有标签静默降级成无码版
        src: assetUrl(url),
        success: (res) => {
          paths[url] = res.path;
          resolve();
        },
        fail: () => {
          // 下载失败就让这张走无码降级布局，不阻断整批打印
          console.warn('[打印] 二维码下载失败', assetUrl(url));
          resolve();
        },
      });
    }).then(worker);
  }

  const workers = [];
  for (let i = 0; i < Math.min(PREFETCH_CONCURRENCY, urls.length); i++) {
    workers.push(worker());
  }
  return Promise.all(workers).then(() => paths);
}

/* ==================== 作业 ==================== */

/**
 * 执行一次打印作业。
 *
 * @param {object}   options
 * @param {Array}    options.pages      服务端返回的 pages（已展开到张）
 * @param {object}   options.settings   { paper, darkness, deviceName }
 * @param {object}   options.component  页面实例（带 canvas）
 * @param {string}   options.canvasId   canvas-id，默认 test
 * @param {string}   options.batchNo    本批作业号
 * @param {Function} options.onProgress 每张进度：{printed, total, page}
 * @param {Function} options.onPause    打印机需要人工处理：{errCode}
 * @param {Function} options.onResume   恢复
 * @return {{promise: Promise, cancel: Function, resume: Function}}
 */
function runJob(options) {
  const pages = options.pages || [];
  const settings = options.settings || {};
  const component = options.component;
  const canvasId = options.canvasId || 'test';
  const batchNo = options.batchNo || String(Date.now());
  const deviceName = settings.deviceName || '';
  const total = pages.length;

  const state = {
    index: 0,
    current: -1,
    results: new Array(total).fill(null),
    cache: {},
    finished: false,
    cancelled: false,
    paused: false,
    /** 队列空后的收口宽限计时器，见 onSubmit */
    guard: null,
  };
  let settle = null;

  const promise = new Promise((resolve) => {
    settle = resolve;
  });

  // 作业期间不让自动重连来抢 SDK：cancelPrint 与 connectWithDeviceId 同时跑会互相干扰
  printer.setJobActive(true);

  function emitProgress(page) {
    if (typeof options.onProgress === 'function') {
      options.onProgress({ printed: completedCount(), total, page });
    }
  }

  function completedCount() {
    return state.results.filter((item) => item && item.result === 'success').length;
  }

  function record(k, result, errCode) {
    if (k < 0 || k >= total || state.results[k]) {
      return false;
    }
    const page = pages[k];
    state.results[k] = { page, result, errCode: errCode == null ? null : errCode };
    // 打样张时没有对应订单，不能上报（会上报一条 order_id 为空的记录）
    if (options.report !== false) {
      reportPage({
        batchNo,
        orderId: page.orderId,
        pageKey: page.pageKey,
        template: page.template,
        result,
        errCode: errCode == null ? null : errCode,
        deviceName,
      });
    }
    return true;
  }

  function finish() {
    if (state.finished) {
      return;
    }
    state.finished = true;
    // 宽限计时器可能还在跑：不清的话它会在作业结束后再 finish 一次（虽有 finished 挡住，
    // 但定时器会拖住页面销毁时的回收）
    clearTimeout(state.guard);
    wx.setKeepScreenOn({ keepScreenOn: false });
    // 没定结果的张：取消的算「已取消」，其余算「中断」，都记失败，供结果页重打
    const fallback = state.cancelled ? LOCAL_ERROR.cancelled : LOCAL_ERROR.interrupted;
    for (let k = 0; k < total; k++) {
      if (!state.results[k]) {
        record(k, 'fail', fallback);
      }
    }
    const results = state.results.slice();
    const summary = {
      batchNo,
      total,
      successCount: results.filter((item) => item && item.result === 'success').length,
      failCount: results.filter((item) => item && item.result === 'fail').length,
      results,
      cancelled: state.cancelled,
    };
    settle(summary);
    // 作业收口：释放「打印中不抢连接」的闸门，并记下这次结果供工作台卡片展示
    printer.setJobActive(false);
    printer.notePrintResult(summary);
  }

  function failCurrent(errCode) {
    record(state.current, 'fail', errCode);
    try {
      JCAPI.cancelPrint(function noop() {});
    } catch (e) {
      // 作业已经异常，取消失败只影响下一次能否立刻开始，不影响结果记录
    }
    finish();
  }

  /**
   * 把一张的像素推给打印机。
   *
   * `count` 恒为 1：一张一次调用，失败能精确对应到哪一张（需求 4.1）。
   */
  function push(result) {
    JCAPI.print({
      data: result.data,
      width: result.width,
      height: result.height,
      count: 1,
      rotation: 0,
      epc: null,
      hasDash: false,
    });
  }

  function renderAndPrint(k) {
    const page = pages[k];
    const cacheKey = page.orderId + ':' + page.comboIndex;
    const cached = state.cache[cacheKey];
    if (cached) {
      push(cached);
      return;
    }
    const qrPath = page.qrImageUrl ? state.qrPaths[page.qrImageUrl] : '';
    // 样张要自己排版，所以允许调用方替换「页面 → 模型」这一步
    const model = options.modelOf
      ? options.modelOf(page, qrPath)
      : buildPageModel(page, { qrPath: qrPath || '' });
    renderPage(model, component, canvasId)
      .then((result) => {
        if (state.finished || state.cancelled) {
          return;
        }
        // 缓存键忽略份序号，同套餐的多份才能复用
        const keys = Object.keys(state.cache);
        if (keys.length >= CACHE_LIMIT) {
          delete state.cache[keys[0]];
        }
        state.cache[cacheKey] = result;
        push(result);
      })
      .catch((err) => {
        console.error('[打印] 标签生成失败', page.pageKey, err);
        failCurrent(LOCAL_ERROR.render);
      });
  }

  function onSubmit() {
    if (state.finished) {
      return;
    }
    // 队列已空还被要数据：说明 SDK 多要了一次（固件在最后一张之后仍会再请求一次）。
    // 这里**不能**顺手把上一张记成功 —— 那一张此刻可能还在走纸，
    // 记上之后若再收口，就会把一张没出的纸记成已出，结果页永远不补打它。
    // 正常收口交给 printProgressCallback 的累计张数；下面那个 3 秒宽限只兜「进度回调没到齐」。
    if (state.index >= total) {
      if (!state.guard) {
        state.guard = setTimeout(() => {
          if (!state.finished) {
            finish();
          }
        }, 3000);
      }
      return;
    }
    // SDK 又来要下一张，说明上一张已经打完了 —— 这是确认「上一张成功」的唯一信号
    if (state.index > 0) {
      const prev = state.index - 1;
      if (record(prev, 'success', null)) {
        emitProgress(pages[prev]);
      }
    }
    state.current = state.index;
    state.index += 1;
    renderAndPrint(state.current);
  }

  function onProgress(res) {
    if (state.finished) {
      return;
    }
    const printed = Number(res && res.count) || 0;
    if (typeof options.onProgress === 'function') {
      options.onProgress({
        printed,
        total,
        page: pages[Math.min(printed, total) - 1] || null,
      });
    }
    if (printed >= total) {
      // 累计完成数到齐，把最后一张记成成功；前面若有漏记的顺手补齐
      for (let k = 0; k < total; k++) {
        if (!state.results[k]) {
          record(k, 'success', null);
        }
      }
      finish();
    }
  }

  function onError(res) {
    if (state.finished) {
      return;
    }
    const errCode = res && res.errCode;
    if (res && res.errToPause === true) {
      // 缺纸、盒盖这类：打印机自己停了，等待人工处理后继续，不能当成失败张
      state.paused = true;
      if (typeof options.onPause === 'function') {
        options.onPause({ errCode });
      }
      return;
    }
    failCurrent(errCode == null ? LOCAL_ERROR.interrupted : errCode);
  }

  // 二维码必须先下完再开作业，否则打印过程中会卡在网络请求上
  prefetchQr(pages)
    .then((paths) => {
      state.qrPaths = paths || {};
      wx.setKeepScreenOn({ keepScreenOn: true });
      const para = new JCAPICommon.SDKStartJobPara();
      para.paper = settings.paper || 1;
      para.darkness = settings.darkness || 3;
      para.totalCount = total;
      para.printErrorCallback = onError;
      para.printProgressCallback = onProgress;
      para.submitDataCallback = onSubmit;
      para.onPause = function onPause() {
        state.paused = true;
        if (typeof options.onPause === 'function') {
          options.onPause({ errCode: null });
        }
      };
      para.onResume = function onResume() {
        state.paused = false;
        if (typeof options.onResume === 'function') {
          options.onResume();
        }
      };
      JCAPI.startJob(para);
    })
    .catch((err) => {
      console.error('[打印] 作业启动失败', err);
      failCurrent(LOCAL_ERROR.offline);
    });

  return {
    promise,
    /** 停止：已推给打印机的不管，剩下的记「已取消」 */
    cancel() {
      if (state.finished) {
        return;
      }
      state.cancelled = true;
      try {
        JCAPI.cancelPrint(function noop() {});
      } catch (e) {
        // 取消失败也照样收口，否则页面会一直停在「打印中」
      }
      finish();
    },
    /** 继续：打印机因缺纸/盒盖停下后，装好纸由商家点 */
    resume() {
      if (state.finished) {
        return;
      }
      try {
        JCAPI.resumePrint();
      } catch (e) {
        wx.showToast({ title: '打印机未响应，请重试', icon: 'none' });
      }
    },
  };
}

module.exports = { runJob, renderPage, previewPage, prefetchQr, flushPending, buildPageModel };
