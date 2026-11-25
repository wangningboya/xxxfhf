/**
 * 打印预览截图：截当前标签画布 → 复制到本地用户目录 → 每天首次启动清 3 天前。
 *
 * 为什么截图要落盘：
 * - `wx.canvasToTempFilePath` 给的是临时文件，微信随时可能回收，不能当「留 3 天」的东西；
 * - 复制到 `wx.env.USER_DATA_PATH/print-preview/YYYYMMDD/` 才是归我们管的文件，
 *   也才能按日期目录整体删掉。
 *
 * 为什么清理是「惰性」的：小程序没有常驻后台定时器，`setInterval` 切后台会被冻结，
 * 也没有「每天几点执行」的能力。所以做成「冷启动 + 进打印设置页」时检查，一天只清一次。
 */

const { CLEAN_KEY } = require('./link-state');

const DIR_PREFIX = 'print-preview';
const KEEP_DAYS = 3;
const DAY_MS = 24 * 60 * 60 * 1000;

let fileSystem = null;

function fs() {
  if (!fileSystem) {
    fileSystem = wx.getFileSystemManager();
  }
  return fileSystem;
}

function root() {
  return wx.env.USER_DATA_PATH + '/' + DIR_PREFIX;
}

function pad2(value) {
  return value < 10 ? '0' + String(value) : String(value);
}

/** 目录名与日期戳统一用 YYYYMMDD：同格式才能直接按字符串比较新旧 */
function ymd(date) {
  return String(date.getFullYear()) + pad2(date.getMonth() + 1) + pad2(date.getDate());
}

/**
 * 把页面上的标签画布截成图片。必须在 `JCAPI.endDrawLabel()` 之前调用：
 * SDK 取完像素收尾时可能清画布，之后再截容易拿到白图。
 *
 * 失败一律 resolve 空串：预览是辅助功能，绝不能因为它阻断打印。
 */
function snapshot(component, canvasId) {
  return new Promise((resolve) => {
    wx.canvasToTempFilePath(
      {
        canvasId: canvasId || 'test',
        fileType: 'png',
        destWidth: 560,
        destHeight: 320,
        success: (res) => resolve((res && res.tempFilePath) || ''),
        fail: () => resolve(''),
      },
      component
    );
  });
}

/** 临时文件 → 持久文件。落盘失败退回临时路径：预览照常，只是不跨天 */
function persist(tempPath, pageKey) {
  if (!tempPath) {
    return '';
  }
  try {
    const dir = root() + '/' + ymd(new Date());
    try {
      fs().accessSync(dir);
    } catch (e) {
      fs().mkdirSync(dir, true);
    }
    const name = Date.now() + '-' + String(pageKey || 'p').replace(/[^\w.-]/g, '_') + '.png';
    const file = dir + '/' + name;
    fs().copyFileSync(tempPath, file);
    return file;
  } catch (e) {
    console.warn('[打印] 预览落盘失败', e);
    return tempPath;
  }
}

/** 删掉 KEEP_DAYS 天前的日期目录；只看自己建的 8 位日期目录，其余一概不碰 */
function cleanPreviews() {
  let names = [];
  try {
    names = fs().readdirSync(root());
  } catch (e) {
    return 0; // 目录还没建，等于没有可清的
  }
  const cutoff = ymd(new Date(Date.now() - KEEP_DAYS * DAY_MS));
  let removed = 0;
  names.forEach((name) => {
    if (!/^\d{8}$/.test(name) || name >= cutoff) {
      return;
    }
    try {
      fs().rmdirSync(root() + '/' + name, true);
      removed += 1;
    } catch (e) {
      // 文件被占用或已删，下次再试
    }
  });
  return removed;
}

/**
 * 一天只清一次。先写日期戳再清：万一清理过程中异常，也不会在当天反复扫目录。
 * 日期戳写失败时退化成「每次启动都清」——删的只是过期目录，不会误删新图。
 */
function cleanDaily() {
  const today = ymd(new Date());
  try {
    if (wx.getStorageSync(CLEAN_KEY) === today) {
      return;
    }
    wx.setStorageSync(CLEAN_KEY, today);
  } catch (e) {
    // 存不进去就继续清
  }
  cleanPreviews();
}

module.exports = {
  KEEP_DAYS,
  ymd,
  snapshot,
  persist,
  cleanPreviews,
  cleanDaily,
};
