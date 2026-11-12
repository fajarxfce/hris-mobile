const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { format } = require('node:util');
const { runInNewContext } = require('node:vm');

const script = readFileSync(join(__dirname, '..', 'karma.config.d', 'kotlin-line-reporter.js'), 'utf8');

function configure(reporters = ['karma-kotlin-reporter']) {
    const config = { reporters, plugins: ['existing-plugin'] };
    const received = [];
    let upstreamLoads = 0;
    function Upstream(decorate, configuration, emitter) {
        decorate(this);
        received.push({ reporter: this, configuration, emitter });
    }
    runInNewContext(script, {
        config,
        process: { env: { KOTLIN_TOOLING_DIR: '/tooling/node_modules' } },
        require(name) {
            if (name === 'node:util') return require('node:util');
            if (name === 'node:path') return require('node:path');
            assert.equal(name, join('/tooling/node_modules', 'kotlin-web-helpers/dist/karma-kotlin-reporter.js'));
            upstreamLoads++;
            return { 'reporter:karma-kotlin-reporter': ['type', Upstream] };
        },
    });
    return { config, received, upstreamLoads };
}

function instantiate(setup = configure(), failWrite = false) {
    const writes = [];
    const emitter = {};
    const Factory = setup.config.plugins.at(-1)['reporter:karma-kotlin-lines'][1];
    const instance = new Factory(reporter => {
        reporter.write = function (...args) {
            assert.equal(this, reporter);
            if (failWrite) throw new Error('writer failed');
            writes.push(format(...args));
        };
    }, setup.config, emitter);
    return { instance, writes, emitter, setup, Factory };
}

test('preserves custom reporters, plugins and upstream constructor ownership', () => {
    const setup = configure(['spec', 'karma-kotlin-reporter', 'junit']);
    const { instance, emitter, Factory } = instantiate(setup);
    assert.deepEqual(Array.from(setup.config.reporters), ['spec', 'karma-kotlin-lines', 'junit']);
    assert.equal(setup.config.plugins[0], 'existing-plugin');
    assert.equal(setup.upstreamLoads, 1);
    assert.deepEqual(Array.from(Factory.$inject), ['baseReporterDecorator', 'config', 'emitter']);
    assert.equal(setup.received[0].reporter, instance);
    assert.equal(setup.received[0].configuration, setup.config);
    assert.equal(setup.received[0].emitter, emitter);
});

test('does not alter configurations without the Kotlin reporter', () => {
    const setup = configure(['junit']);
    assert.equal(setup.upstreamLoads, 0);
    assert.deepEqual(setup.config.reporters, ['junit']);
    assert.deepEqual(setup.config.plugins, ['existing-plugin']);
});

test('adds exactly one terminator and retains existing LF or CRLF', () => {
    const { instance, writes } = instantiate();
    for (const value of ['message', 'message\n', 'message\r\n', '']) instance.write(value);
    assert.deepEqual(writes, ['message\n', 'message\n', 'message\r\n', '\n']);
});

test('preserves escaped service content, Unicode and printf arguments', () => {
    const { instance, writes } = instantiate();
    const message = "##teamcity[testStarted name='pesan %s |n |] 日本語']";
    instance.write('%s', message);
    instance.write('value=%s number=%d', '100%', 7);
    assert.deepEqual(writes, [message + '\n', 'value=100% number=7\n']);
});

test('frames a suite larger than the Gradle one-MiB limit without losing messages', () => {
    const { instance, writes } = instantiate();
    const messages = Array.from({ length: 6000 }, (_, i) =>
        "##teamcity[testStarted name='case" + i + '_'.repeat(240) + "']");
    assert.ok(Buffer.byteLength(messages.join('')) > 1024 * 1024);
    for (const message of messages) instance.write(message);
    assert.equal(writes.length, messages.length);
    assert.deepEqual(writes.join('').trimEnd().split('\n'), messages);
    assert.ok(writes.every(line => Buffer.byteLength(line) < 1024 * 1024));
});

test('preserves failure and ignored messages instead of hiding failures', () => {
    const { instance, writes } = instantiate();
    const messages = ["##teamcity[testFailed name='broken' message='error']", "##teamcity[testIgnored name='ignored']"];
    messages.forEach(message => instance.write(message));
    assert.deepEqual(writes, messages.map(message => message + '\n'));
});

test('propagates writer errors', () => {
    const { instance } = instantiate(configure(), true);
    assert.throws(() => instance.write('message'), /writer failed/);
});

test('keeps separate reporter instances independent', () => {
    const setup = configure();
    const first = instantiate(setup);
    const second = instantiate(setup);
    first.instance.write('first');
    second.instance.write('second');
    assert.deepEqual(first.writes, ['first\n']);
    assert.deepEqual(second.writes, ['second\n']);
});
