/**
 * 隐私政策全文页（P0-01）。
 *
 * 内容与根目录 `隐私政策.md` 同源，版本号必须一起改：
 * 正文有实质改动 → 这里的 VERSION、`utils/privacy.js` 的 POLICY_VERSION、`隐私政策.md` 的版本三处同步。
 *
 * 页面是纯静态文本，不发任何请求、不读写本地存储：政策本身不能因为接口挂了打不开。
 * 正文用数据描述而不是写死在 wxml 里，改条款只动这一处。
 */

const shop = require('../../shop-info');
const { assetUrl } = require('../../utils/format');

const VERSION = 'v1.1';

/** 收集清单的每一张卡片：tag 是「必要性」，rows 是「收集方式 / 用途」 */
const COLLECT_ITEMS = [
  {
    name: '微信昵称',
    tag: '审核前需填写',
    rows: [
      { k: '收集方式', v: '您点击昵称输入框时一键填入微信昵称，或自行填写' },
      {
        k: '用途',
        v: '账号审核时认出您是谁（核对您是否为群内获准进入的成员），并在订单、备菜与配送单上称呼、区分不同顾客',
      },
    ],
  },
  {
    name: '收货人姓名、详细地址、所属区域、地址联系电话',
    tag: '配送单必需',
    rows: [
      { k: '收集方式', v: '您在地址表单填写，或商家代客填写' },
      { k: '用途', v: '按地址准确送达；按所属区域归集备菜与配送顺序；送达时联系收货人' },
      { k: '必要性', v: '配送单必需（地址联系电话必填）；自取单不需要' },
    ],
  },
  {
    name: '账号手机号',
    tag: '选填',
    rows: [
      { k: '收集方式', v: '您在"我的"页自行填写，或商家代为填写、修正' },
      { k: '用途', v: '送餐到达后联系您（确认收餐位置、找不到人时致电）；到店取餐时核对身份' },
      { k: '必要性', v: '选填；我们不在非必要场景强制收集' },
    ],
  },
  {
    name: '订单信息（盒饭与菜品、规格、份数、金额、取餐码、状态、收款方式、备注、取消原因、下单时间）',
    tag: '下单后产生',
    rows: [
      { k: '收集方式', v: '您下单及后续操作时产生' },
      {
        k: '用途',
        v: '订单在各状态间流转（待接单 → 备菜 → 配送 → 完成 / 取消）；对账结算；售后与食品安全追溯',
      },
    ],
  },
  {
    name: '微信身份标识（openid）',
    tag: '必需',
    rows: [
      { k: '收集方式', v: '您首次进入小程序时，由微信平台提供' },
      { k: '用途', v: '识别您的账号、完成登录与身份绑定' },
    ],
  },
  {
    name: '微信群标识（openGId）或邀请码',
    tag: '二者具备其一',
    rows: [
      { k: '收集方式', v: '您通过群卡片进入时由微信提供，或您自行输入邀请码' },
      { k: '用途', v: '判断您是否来自获准进入的微信群' },
    ],
  },
  {
    name: '菜品图片、盒饭封面图片',
    tag: '仅商家功能',
    rows: [
      { k: '收集方式', v: '仅当您使用商家功能上传菜品图片或盒饭封面时，从手机相册或相机选取' },
      { k: '用途', v: '在菜单、菜品与盒饭详情中展示对应的菜品与盒饭' },
      { k: '必要性', v: '仅商家功能需要' },
    ],
  },
];

/** 待填项标黄提示：提审前必须替换成真实信息，否则属于「未告知处理者身份」 */
const TODO = '【待补充】';

function filled(value) {
  return (value || '').trim();
}

/** 有值就用值；没值渲染成黄色占位，不让空白混进正文 */
function orTodo(value, hint) {
  const text = filled(value);
  return text || TODO + hint;
}

/** 客服电话与邮箱合成一行；两个都没填时给占位 */
function contactLine() {
  const parts = [filled(shop.servicePhone), filled(shop.serviceEmail)].filter(Boolean);
  return parts.length ? parts.join(' / ') : TODO + '客服电话或邮箱';
}

/** 投诉渠道留空时，按政策正文的说法回落到客服联系方式 */
function complaintLine() {
  const own = filled(shop.complaintChannel);
  if (own) {
    return own;
  }
  if (filled(shop.servicePhone) || filled(shop.serviceEmail)) {
    return '与上述客服电话 / 邮箱相同';
  }
  return TODO + '小程序内客服入口或上述电话、邮箱';
}

const OPERATOR_NAME = orTodo(shop.legalName, '营业执照登记全称');
const OPERATOR_CODE = orTodo(shop.creditCode, '营业执照上的 18 位代码');
/** 正文首段是连读的句子，占位不能带说明文字，否则句子读不通 */
const INLINE_NAME = filled(shop.legalName) || TODO;
const INLINE_CODE = filled(shop.creditCode) || TODO;

/**
 * 证照公示：只放已配置的图，两个都没填时整块不渲染。
 * 不在这里给「待补充」占位——用户看的是政策，不是运营的自查清单。
 *
 * 图片在服务器上（`/upload/...`），必须过 assetUrl 拼上 apiBase；
 * 直接绑 `/upload/...` 会被当成代码包内文件，找不到就白图。
 */
function licenseList() {
  const images = shop.licenseImages || {};
  return [
    { name: '营业执照', src: assetUrl(filled(images.businessLicense)) },
    { name: '食品经营许可证', src: assetUrl(filled(images.foodPermit)) },
  ].filter(function (item) {
    return item.src;
  });
}

const sections = [
  {
    title: '',
    blocks: [
      {
        type: 'p',
        text:
          INLINE_NAME +
          '（以下简称"我们"或"本店"）是"群内点餐"微信小程序（以下简称"本小程序"）的运营者，统一社会信用代码：' +
          INLINE_CODE +
          '。本政策说明我们在您使用本小程序时如何收集、使用、存储和保护您的个人信息。我们的营业执照与食品经营许可证在小程序内公示。',
        todo: !filled(shop.legalName) || !filled(shop.creditCode),
      },
      {
        type: 'p',
        text:
          '我们是一家店自营的点餐服务，不是为用户提供商家入驻的平台。请在使用前仔细阅读本政策；当您点击隐私弹窗中的"同意并继续"，或使用需要授权的功能时，即表示您已知悉并同意本政策。',
      },
    ],
  },
  {
    title: '一、我们收集的信息及用途',
    blocks: [
      { type: 'cards', items: COLLECT_ITEMS },
      {
        type: 'ul',
        items: [
          '微信昵称只用于认出与称呼，不用于任何对外展示或营销：账号审核通过与否，仅由商家在后台核对。',
          '账号手机号与地址联系电话是两个独立字段，用途不同、互不覆盖。新增地址时会预填账号手机号，您可以修改；自取单不需要填写地址与地址联系电话。',
          '我们不收集您的位置信息、通讯录、通话记录、身份证件信息、银行卡信息，也不读取您设备上的其他文件；除商家上传菜品图片或盒饭封面这一场景外，不访问您的相册。',
          '您填写的昵称、订单备注、收货人姓名与取消原因，会由微信平台提供的内容安全能力进行校验，以降低违规内容随备菜单、配送单传播的风险。',
          '我们不会在您仅浏览菜单时收集上述信息。',
        ],
      },
    ],
  },
  {
    title: '二、信息的存储与保留',
    blocks: [
      {
        type: 'dl',
        rows: [
          {
            k: '存储位置',
            v: '我们的服务器与数据库均部署在腾讯云中国大陆地域，数据库仅在内网使用，不对外开放端口。本小程序不涉及个人信息出境。',
          },
          {
            k: '处理方式',
            v: '上述信息以数据库记录的形式存储，用于展示、比对与业务流转（下单、备菜、配送、对账）；我们不进行用户画像、不开展自动化决策，也不会据此向您推送营销内容。',
          },
          {
            k: '账号信息',
            v: '昵称、账号手机号等保留至您注销账号。注销时，若您没有任何订单，我们会物理删除您的账号与地址信息；若您有订单，会对账号做匿名化处理（昵称统一置为"已注销"、手机号清空、地址删除），并掩码历史订单中的联系电话。',
          },
          {
            k: '订单与交易记录',
            v: '自交易完成之日起保留不少于 3 年（《中华人民共和国电子商务法》第三十一条），到期后清理或脱敏。保留期间仅用于履约、对账与售后，不做物理删除。',
          },
          { k: '运行日志', v: '保留 30 天，用于故障排查与安全风控。' },
        ],
      },
      {
        type: 'p',
        text: '我们不会将您的个人信息用于本政策未载明的其他目的；如确需变更用途，会另行征得您的同意。',
      },
    ],
  },
  {
    title: '三、信息的共享、转让与公开披露',
    blocks: [
      {
        type: 'ul',
        items: [
          '本小程序不接入任何第三方 SDK，不向第三方共享、转让或公开披露您的个人信息。',
          '微信平台为本小程序提供登录身份识别、内容安全检测等基础能力，相关必要信息会在该能力范围内由微信平台处理。',
          '除上述情形外，我们仅在法律法规明确规定或政府主管部门依法调取时，按要求提供相关信息。',
          '我们不出售您的个人信息。',
        ],
      },
    ],
  },
  {
    title: '四、我们如何保护您的信息',
    blocks: [
      {
        type: 'ul',
        items: [
          '全站使用 HTTPS 传输；数据库位于内网，不对外开放端口，并定期备份。',
          '手机号在服务端按场景脱敏：业务核对场景显示前 3 位、后 4 位（如 138****1234），订单、配送及其他对外场景显示前 3 位、后 2 位（如 138******34）。列表、详情、下拉选项、导出物与异常信息中均不出现完整号码；前端不参与截断，避免抓包获取明文。',
          '完整号码仅通过两个受控动作获取：商家"查看完整号码"（需专门权限）与订单"联系顾客"拨号（校验订单归属、订单状态与售后窗口，并设有频次限制）。两者均记录操作审计日志，且号码不落页面、不写本地缓存。',
          '备菜环节不展示联系电话，导出的备菜与对账表格不含电话号码。',
          '按角色分配数据访问权限，只读角色无法查看完整号码；本地缓存中不保存明文号码。',
          '生产环境关闭 SQL 全文日志，并对日志中的手机号做统一掩码处理。',
        ],
      },
    ],
  },
  {
    title: '五、您的权利',
    blocks: [
      {
        type: 'dl',
        rows: [
          {
            k: '查询与更正',
            v: '您可以在"我的"页查看并修改昵称、账号手机号，在"收货地址"页管理地址；订单信息可在订单详情中查看。需要商家协助修改的，可通过客服渠道联系我们。',
          },
          {
            k: '删除与注销',
            v: '点击"我的"→"注销账号"，阅读后果说明后二次确认即可提交，我们将在 15 天内处理。注销不可恢复；依法需保留的订单交易记录会以匿名化形式继续保存（见第二节）。商家账号不提供自助注销，请联系我们处理。',
          },
          {
            k: '撤回同意',
            v: '您可以不再使用需要相应授权的功能；涉及微信隐私授权的，也可以通过微信小程序的隐私设置进行管理。撤回同意不影响撤回前基于您的同意已经进行的处理。',
          },
          {
            k: '投诉与反馈',
            v: '如您认为我们的信息处理行为损害了您的合法权益，可通过第八节的联系方式向我们投诉，我们会在收到后 15 天内处理并答复。',
          },
        ],
      },
    ],
  },
  {
    title: '六、未成年人保护',
    blocks: [
      {
        type: 'p',
        text:
          '本小程序面向微信群内的成年用户，不面向不满十四周岁的儿童。若我们发现在未事先取得监护人同意的情况下收集了儿童个人信息，会尽快删除。监护人可通过第八节的联系方式提出删除要求。',
      },
    ],
  },
  {
    title: '七、政策更新',
    blocks: [
      {
        type: 'p',
        text:
          '本政策可能因业务调整或法律法规变化而更新。更新后我们会在小程序内发布新版本，并标注版本号与生效日期；涉及收集用途、共享对象等重大变更时，会再次弹窗征求您的同意。更新后的政策自其标注的生效日期起生效。',
      },
    ],
  },
  {
    title: '八、联系我们',
    blocks: [
      {
        type: 'dl',
        rows: [
          { k: '个人信息处理者', v: OPERATOR_NAME, todo: !filled(shop.legalName) },
          { k: '统一社会信用代码', v: OPERATOR_CODE, todo: !filled(shop.creditCode) },
          {
            k: '联系地址',
            v: orTodo(shop.address, '营业执照登记地址'),
            todo: !filled(shop.address),
          },
          {
            k: '客服电话 / 邮箱',
            v: contactLine(),
            todo: !filled(shop.servicePhone) && !filled(shop.serviceEmail),
          },
          {
            k: '投诉渠道',
            v: complaintLine(),
            todo:
              !filled(shop.complaintChannel) &&
              !filled(shop.servicePhone) &&
              !filled(shop.serviceEmail),
          },
        ],
      },
    ],
  },
];

/**
 * 给列表补一个稳定的 key：正文是静态数据，永远不会重排，
 * 但 wxml 的 wx:for 需要唯一 key，否则控制台会刷警告。
 */
function keyed(list, prefix) {
  return (list || []).map(function (item, i) {
    const copy = typeof item === 'object' && item !== null ? Object.assign({}, item) : { text: item };
    copy._k = prefix + i;
    return copy;
  });
}

const viewSections = keyed(sections, 's').map(function (s) {
  const blockList = keyed(s.blocks, s._k + '-b').map(function (b) {
    const nb = Object.assign({}, b);
    if (b.items) {
      nb.items = keyed(b.items, b._k + '-i').map(function (it) {
        if (!it.rows) {
          return it;
        }
        const ni = Object.assign({}, it);
        ni.rows = keyed(it.rows, it._k + '-r');
        return ni;
      });
    }
    if (b.rows) {
      nb.rows = keyed(b.rows, b._k + '-r');
    }
    return nb;
  });
  const ns = Object.assign({}, s);
  ns.blocks = blockList;
  return ns;
});

Page({
  data: {
    version: VERSION,
    effectiveText: filled(shop.effectiveDate) || TODO,
    licenses: licenseList(),
    sections: viewSections,
  },

  /** 证照缩略图看不全，点一下全屏放大 */
  onPreviewLicense(e) {
    const src = e.currentTarget.dataset.src;
    if (!src) {
      return;
    }
    wx.previewImage({
      current: src,
      urls: this.data.licenses.map(function (item) {
        return item.src;
      }),
    });
  },
});
