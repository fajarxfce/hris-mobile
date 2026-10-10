// Kotlin's Karma server needs an explicit mapping for non-JavaScript resources.
// basePath is <workspace>/build/wasm/packages/<test-package> in this workspace.
const path = require('path');
const resourceDirectory = path.resolve(
    config.basePath,
    '../../../../core/localization/build/processedResources/wasmJs/main/composeResources'
).replace(/\\/g, '/');
config.files.push({
    pattern: resourceDirectory + '/**/*',
    included: false,
    served: true,
    watched: false
});
config.proxies = {
    ...config.proxies,
    '/composeResources/': '/absolute' + resourceDirectory + '/'
};
