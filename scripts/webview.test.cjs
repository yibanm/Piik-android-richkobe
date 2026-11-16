const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const root = path.resolve(__dirname, '..');
const shim = fs.readFileSync(path.join(root, 'app/src/main/assets/old-webview.js'), 'utf8');
const activity = fs.readFileSync(path.join(root, 'app/src/main/java/com/richkobe/piikandroid/WebActivity.kt'), 'utf8');

function browser(major) {
  const context = vm.createContext({ setTimeout() {}, console });
  vm.runInContext(`
    window = this;
    addEventListener = function () {};
    document = { getElementById: function () { return null; }, body: null };
    __piikCompat = ${major < 84};
    function DOMException(message, name) { this.message = message; this.name = name; }
    function AbortSignal() { this.aborted = false; this.listeners = []; }
    AbortSignal.prototype.addEventListener = function (name, listener) { this.listeners.push(listener); };
    AbortSignal.prototype.removeEventListener = function (name, listener) {
      this.listeners = this.listeners.filter(function (item) { return item !== listener; });
    };
    function AbortController() { this.signal = new AbortSignal(); }
    AbortController.prototype.abort = function (reason) {
      if (this.signal.aborted) return;
      this.signal.aborted = true;
      ${major >= 98 ? "this.signal.reason = reason === undefined ? new DOMException('Aborted', 'AbortError') : reason;" : ''}
      this.signal.listeners.slice().forEach(function (listener) { listener(); });
    };
    ${major >= 98 ? 'AbortSignal.prototype.reason = undefined;' : ''}
    ${major >= 100 ? 'AbortSignal.prototype.throwIfAborted = function () { if (this.aborted) throw this.reason; };' : ''}
    ${major < 85 ? 'delete String.prototype.replaceAll;' : ''}
    ${major < 93 ? 'delete Object.hasOwn;' : ''}
    ${major < 92 ? 'delete Array.prototype.at; delete String.prototype.at;' : ''}
  `, context);
  return context;
}

for (const major of [74, 84, 92, 98, 100, 156]) {
  test(`WebView ${major}：补齐实际缺失接口，不受旧版本门槛影响`, () => {
    const context = browser(major);
    vm.runInContext(shim, context);
    assert.equal(vm.runInContext("typeof String.prototype.replaceAll", context), 'function');
    assert.equal(vm.runInContext("typeof Object.hasOwn", context), 'function');
    assert.equal(vm.runInContext("typeof Array.prototype.at", context), 'function');
    assert.equal(vm.runInContext("typeof AbortSignal.prototype.throwIfAborted", context), 'function');
    assert.equal(vm.runInContext("new AbortController().signal.reason", context), undefined);
    vm.runInContext('new AbortController().signal.throwIfAborted()', context);
  });
}

test('取消原因在同步事件中可见，重复取消不覆盖第一次的原因', () => {
  const context = browser(74);
  vm.runInContext(shim, context);
  const result = vm.runInContext(`
    (function () {
      var c = new AbortController(), reason = { message: 'first' }, seen;
      c.signal.addEventListener('abort', function () { seen = c.signal.reason; });
      c.abort(reason);
      c.abort('second');
      var thrown;
      try { c.signal.throwIfAborted(); } catch (error) { thrown = error; }
      return seen === reason && c.signal.reason === reason && thrown === reason;
    })()
  `, context);
  assert.equal(result, true);
});

test('默认取消抛 AbortError，显式 null 原因原样保留', () => {
  const context = browser(74);
  vm.runInContext(shim, context);
  assert.equal(vm.runInContext(`
    (function () {
      var a = new AbortController(), b = new AbortController(), c = new AbortController();
      a.abort(); b.abort(undefined); c.abort(null);
      var caught = false;
      try { c.signal.throwIfAborted(); } catch (error) { caught = error === null; }
      return a.signal.reason.name === 'AbortError' && b.signal.reason.name === 'AbortError' && caught;
    })()
  `, context), true);
});

test('现代原生实现不被覆盖，重复注入不重复包装', () => {
  const context = browser(156);
  const originals = vm.runInContext('[AbortController.prototype.abort, AbortSignal.prototype.throwIfAborted, String.prototype.replaceAll, Object.hasOwn, Array.prototype.at]', context);
  vm.runInContext(shim, context);
  vm.runInContext(shim, context);
  const current = vm.runInContext('[AbortController.prototype.abort, AbortSignal.prototype.throwIfAborted, String.prototype.replaceAll, Object.hasOwn, Array.prototype.at]', context);
  originals.forEach((fn, index) => assert.equal(current[index], fn));
  const oldContext = browser(74);
  vm.runInContext(shim, oldContext);
  const abort = vm.runInContext('AbortController.prototype.abort', oldContext);
  vm.runInContext(shim, oldContext);
  assert.equal(vm.runInContext('AbortController.prototype.abort', oldContext), abort);
});

const popoverSection = activity.slice(activity.indexOf('private const val POPOVER_OPEN_JS'), activity.indexOf('private const val POPOVER_CLOSE_JS'));
const popoverScript = JSON.parse(popoverSection.match(/=\s*("(?:\\.|[^"\\])*")/)[1]);
for (const open of [true, false]) {
  test(`返回键菜单检测返回布尔值：${open}`, () => {
    const result = vm.runInNewContext(popoverScript, { document: { querySelector() { return open ? {} : null; } } });
    assert.equal(typeof result, 'boolean');
    assert.equal(JSON.stringify(result), String(open));
  });
}

test('旧内核不认识 popover 选择器时返回 false', () => {
  const result = vm.runInNewContext(popoverScript, { document: { querySelector() { throw new SyntaxError('unsupported'); } } });
  assert.equal(result, false);
});
