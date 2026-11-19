const privacy = require('../../utils/privacy');

/**
 * 隐私提示弹窗。
 *
 * 只负责「长什么样」和「把答复交回 privacy.js」，不判断业务、不直接调接口。
 * 由 privacy.ensureAgreed() 唤起，页面里挂一个实例即可（id 随意）。
 */
Component({
  data: {
    visible: false,
  },

  lifetimes: {
    attached() {
      privacy.registerModal(this);
    },
    detached() {
      privacy.unregisterModal(this);
    },
  },

  /**
   * 页面从后台/其它页返回时重新注册。
   * 页面实例在栈里不会销毁，只靠 attached 的话，返回后 modal 仍指向已经卸载的那个实例，
   * 会导致「弹窗永远不出现、收集动作永远被拒」。
   */
  pageLifetimes: {
    show() {
      privacy.registerModal(this);
    },
  },

  methods: {
    open() {
      this.setData({ visible: true });
    },

    close() {
      this.setData({ visible: false });
    },

    onAgree() {
      this.close();
      privacy.agree();
    },

    onDisagree() {
      this.close();
      privacy.disagree();
    },

    onOpenPolicy() {
      privacy.openPolicy();
    },

    /** 挡住蒙层下的滚动穿透 */
    noop() {},
  },
});
