// Resource files load asynchronously; keep a finite budget on shared CI runners.
config.client = config.client || {};
config.client.mocha = { ...config.client.mocha, timeout: 20000 };
