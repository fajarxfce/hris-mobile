// Kotlin's Gradle reader requires newline-delimited TeamCity service messages.
// Keep upstream formatting and failures; only frame each reporter write.
(() => {
    const originalName = 'karma-kotlin-reporter';
    if (!config.reporters.includes(originalName)) return;

    const { format } = require('node:util');
    const { join } = require('node:path');
    const upstream = require(join(
        process.env.KOTLIN_TOOLING_DIR,
        'kotlin-web-helpers/dist/karma-kotlin-reporter.js'
    ))['reporter:' + originalName][1];
    const framedName = 'karma-kotlin-lines';

    function FramedKotlinReporter(baseReporterDecorator, configuration, emitter) {
        upstream.call(this, reporter => {
            baseReporterDecorator(reporter);
            const write = reporter.write.bind(reporter);
            reporter.write = (...args) => {
                const message = format(...args);
                write('%s', message.endsWith('\n') ? message : message + '\n');
            };
        }, configuration, emitter);
    }
    FramedKotlinReporter.$inject = ['baseReporterDecorator', 'config', 'emitter'];
    config.plugins.push({ ['reporter:' + framedName]: ['type', FramedKotlinReporter] });
    config.reporters = config.reporters.map(name => name === originalName ? framedName : name);
})();
